(ns resolver-sim.sim.fixtures
  "Recursive fixture loader and composer for deterministic simulation suites.

   Handles loading EDN/JSON files from data/fixtures/ based on keyword namespaces.
   Implements canonical action mapping, golden report generation, and
   trace minimisation integration."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.diff :as diff]
            [resolver-sim.canonical.actions :as canon]
            [resolver-sim.sim.minimizer :as minimizer]
            [resolver-sim.scenario.theory :as theory]
            [resolver-sim.scenario.expectations :as expectations]
            [clojure.pprint :as pp]))

;; ---------------------------------------------------------------------------
;; Canonical Action Mapping
;; ---------------------------------------------------------------------------

(def impl->canonical
  "Reverse mapping for trace reporting."
  (into {} (map (fn [[k v]] [v k]) resolver-sim.canonical.actions/action-map)))

;; ---------------------------------------------------------------------------
;; Fixture Loading
;; ---------------------------------------------------------------------------

(defn- fixture-key->path
  [k]
  (let [ns (namespace k)
        nm (name k)
        ext (if (= ns "traces") ".trace.json" ".edn")]
    (if ns
      (str "data/fixtures/" ns "/" nm ext)
      (str "data/fixtures/" nm ext))))

(defn load-fixture
  [k]
  (let [path (fixture-key->path k)
        resource (io/file path)]
    (if (.exists resource)
      (with-open [r (io/reader resource)]
        (if (.endsWith path ".json")
          (json/read r :key-fn keyword)
          (edn/read (java.io.PushbackReader. r))))
      (throw (ex-info "Fixture not found" {:key k :path path})))))

;; ---------------------------------------------------------------------------
;; JSON Normalization: Handle EDN/JSON type mismatches
;; ---------------------------------------------------------------------------

(defn- normalize-keyword-strings
  "Convert string values starting with ':' to proper Clojure keywords.
   This fixes keyword corruption from JSON deserialization."
  [v]
  (cond
    (string? v)
    (if (and (.startsWith v ":") (> (count v) 1))
      (keyword (subs v 1))  ; Remove leading colon and convert to keyword
      v)
    (keyword? v) v
    :else v))

(defn- normalize-map-keys
  "Recursively convert numeric string keys in maps to Integer keys."
  [m]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                  (let [normalized-k (if (string? k)
                                       (try (Integer/parseInt k)
                                            (catch Exception _ k))
                                       k)]
                    (assoc acc normalized-k v)))
               {} m)
    m))

(defn normalize-scenario
  "Recursively normalize a loaded scenario to fix JSON deserialization issues:
   1. Convert string keywords (e.g. ':released') to proper keywords
   2. Convert numeric string keys to Integer keys in all maps"
  [x]
  (walk/postwalk
    (fn [v]
      (cond
        ;; First handle maps - normalize keys
        (map? v)
        (let [key-normalized (normalize-map-keys v)]
          ;; Then normalize all values in the map
          (reduce-kv (fn [m k kv]
                       (assoc m k (normalize-keyword-strings kv)))
                     key-normalized key-normalized))
        
        ;; Then handle keyword string values
        (string? v)
        (normalize-keyword-strings v)
        
        ;; Everything else stays as-is
        :else v))
    x))

(defn- fixture-ref? [x]
  (and (keyword? x) (namespace x)
       (contains? #{"protocol" "states" "actors" "authority" "tokens" "thresholds" "suites" "traces"} (namespace x))))

(defn compose-suite
  ([x] (compose-suite x #{}))
  ([x seen]
   (cond
     (fixture-ref? x)
     (if (contains? seen x)
       (throw (ex-info "Circular fixture reference" {:key x :seen seen}))
       ;; Normalize JSON-loaded fixtures to fix type mismatches
       (let [loaded (load-fixture x)
             normalized (if (.endsWith (fixture-key->path x) ".json")
                          (normalize-scenario loaded)
                          loaded)]
         (compose-suite normalized (conj seen x))))

     (map? x)
     (reduce-kv (fn [m k v]
                  (let [ns-str (when (keyword? k) (namespace k))]
                    (when (and ns-str
                               (not (contains? #{nil "suite" "protocol" "state" "authority"
                                                 "threshold" "actor" "token"} ns-str)))
                      (throw (ex-info "Unrecognized fixture namespace keyword"
                                      {:key k :namespace ns-str})))
                    (assoc m k (if (contains? #{:suite/id :protocol/id :state/id :authority/id :threshold/id :actor/id :token/id} k)
                                 v
                                 (compose-suite v seen)))))
                {} x)

     (vector? x)
     (mapv #(compose-suite % seen) x)

     :else x)))

;; ---------------------------------------------------------------------------
;; Validation & Golden Reports
;; ---------------------------------------------------------------------------

(defn- validate-thresholds
  "Check replay metrics against a thresholds map.

   Supported keys:
     :solvency :strict  — fails if any invariant-violations > 0
     :max-resolver-profit-ev N  — fails if resolver-profit-ev exceeds N
                                   (Monte Carlo metric; skipped in replay context)
     :min-detection-rate N  — fails if detection-rate falls below N
                               (Monte Carlo metric; skipped in replay context)"
  [result thresholds]
  (let [violations (atom [])
        metrics    (:metrics result {})]
    (when (and (= :strict (:solvency thresholds))
               (pos? (get metrics :invariant-violations 0)))
      (swap! violations conj {:type :solvency-violation :detail "Strict solvency check failed"}))
    (when-let [max-profit (:max-resolver-profit-ev thresholds)]
      (when-let [profit (get metrics :resolver-profit-ev)]
        (when (> profit max-profit)
          (swap! violations conj {:type :profit-ev-exceeded
                                  :detail (str "resolver-profit-ev " profit " > " max-profit)}))))
    (when-let [min-detection (:min-detection-rate thresholds)]
      (when-let [rate (get metrics :detection-rate)]
        (when (< rate min-detection)
          (swap! violations conj {:type :detection-rate-below-minimum
                                  :detail (str "detection-rate " rate " < " min-detection)}))))
    {:ok? (empty? @violations)
     :violations @violations}))

(defn- generate-golden-report
  [suite-id result]
  (let [last-entry (last (:trace result))
        final-hash (get-in last-entry [:projection-hash])]
    {:suite-id suite-id
     :trace-id (:scenario-id result)
     :final-state-hash final-hash
     :metrics (:metrics result)
     :outcome (:outcome result)}))

(defn- save-golden-report
  [suite-key result]
  (let [path (str "data/fixtures/golden/" (name (:trace-id result)) ".report.edn")]
    (with-open [w (io/writer path)]
      (pp/pprint (:golden-report result) w))))

(defn- compare-golden-report
  [suite-key result]
  (let [path (str "data/fixtures/golden/" (name (:trace-id result)) ".report.edn")
        golden (edn/read-string (slurp path))
        report (:golden-report result)]
    (if (= golden report)
      {:ok? true}
      {:ok? false :expected golden :actual report})))

(defn run-suite
  "Execute a suite fixture: compose, replay traces, validate thresholds, and optionally save or verify golden reports."
  ([suite-key] (run-suite suite-key nil))
  ([suite-key mode]
   (let [suite (compose-suite (load-fixture suite-key))
         traces (:traces suite [])
         thresholds (:thresholds suite {})
         proto (:protocol suite)
         state (:state suite)
         authority (:authority suite)
         actors (:actors suite)
         token (:token suite)
         results (mapv (fn [trace]
                         (let [;; Merge protocol-params: suite provides baseline defaults,
                               ;; trace-level params take priority so per-trace overrides
                               ;; (e.g. resolution-module, max-dispute-duration, escalation-resolvers)
                               ;; are preserved rather than silently discarded.
                               merged-proto (when proto
                                              (merge (dissoc proto :protocol/id)
                                                     (:protocol-params trace)))
                               effective-trace (cond-> trace
                                                 merged-proto (assoc :protocol-params merged-proto)
                                                 state (assoc :initial-block-time (:block-time state 1000))
                                                 authority (assoc :authority-params authority)
                                                 actors (assoc :agents (vec (concat (:agents trace []) actors)))
                                                 token (assoc :token-params token))
                               res (replay/replay-scenario effective-trace)
                               report (generate-golden-report suite-key res)
                               comparison (when (= mode :verify) (compare-golden-report suite-key {:trace-id (:scenario-id trace) :golden-report report}))
                               
                               ;; CDRS v1.1 Analysis
                               expect-res (when (:expectations trace)
                                            (expectations/evaluate-expectations res (:expectations trace)))
                               theory-res (when (:theory trace)
                                            (theory/evaluate-theory res (:theory trace)))]
                           
                           (when (not= :pass (:outcome res))
                             (println (str "DEBUG: Trace " (:scenario-id trace) " failed with " (:outcome res) " reason " (:halt-reason res)))
                             (when (= (:outcome res) :fail)
                               (let [last-entry (last (:trace res))
                                     violations (:violations last-entry)]
                                 (println (str "       Halted at seq " (:seq last-entry) " action " (:action last-entry)))
                                 (doseq [[inv-kw res-map] violations]
                                   (when-not (:holds? res-map)
                                     (println (str "       VIOLATION: " inv-kw " details: " (:violations res-map))))))))


                           (when (= mode :save) (save-golden-report suite-key {:trace-id (:scenario-id trace) :golden-report report}))
                           {:trace-id (:scenario-id trace)
                            :purpose  (:purpose trace)
                             :theory-source (:theory trace)
                            :outcome (:outcome res)
                            :metrics (:metrics res)
                            :threshold-validation (validate-thresholds res thresholds)
                            :golden-report report
                            :golden-comparison comparison
                            :expectations expect-res
                            :theory theory-res}))
                       traces)
         theory-ok? (fn [r]
                      ;; Theory result is acceptable when:
                      ;; - no theory block (:not-evaluated)
                      ;; - claim was not falsified (:not-falsified)
                      ;; - claim was falsified AND the scenario is explicitly a theory-falsification exercise
                      ;; :inconclusive is treated as a soft warning, not a hard failure
                      ;;
                       ;; Mechanism-property and equilibrium-concept results (CDRS v1.1):
                       ;; - hard :fail → hard failure UNLESS purpose = :theory-falsification
                       ;; - soft :fail  → warning only (treated as inconclusive)
                      ;; - :inconclusive / :not-applicable → soft warning (suite still passes)
                      ;; - :not-checked → no properties declared; passes
                      ;;
                      ;; Negative test cases (purpose = :theory-falsification) are expected to
                      ;; produce :fail — including mechanism and equilibrium :fail results.
                       (let [status       (get-in r [:theory :status])
                            purpose      (keyword (or (:purpose r) ""))
                            neg-test?    (= purpose :theory-falsification)
                            mech-status  (get-in r [:theory :mechanism-status] :not-checked)
                            eq-status    (get-in r [:theory :equilibrium-status] :not-checked)
                             mech-results (vals (get-in r [:theory :mechanism-results] {}))
                             eq-results   (vals (get-in r [:theory :equilibrium-results] {}))
                             strict?      (true? (get-in r [:theory-source :require-conclusive?]))
                             hard-fail?   (fn [results]
                                            (some #(and (= :fail (:status %))
                                                        (= :hard (:severity %)))
                                                  results))
                             inconclusive? (fn [status-kw]
                                             (contains? #{:inconclusive :not-applicable} status-kw))
                            falsify-ok?  (case status
                                           nil            true
                                           :not-evaluated true
                                           :not-falsified true
                                           :falsified     neg-test?
                                           :inconclusive  true
                                           true)
                             mech-ok?     (and
                                           (or (not (hard-fail? mech-results)) neg-test?)
                                           (or (not strict?) (not (inconclusive? mech-status))))
                             eq-ok?       (and
                                           (or (not (hard-fail? eq-results)) neg-test?)
                                           (or (not strict?) (not (inconclusive? eq-status))))]
                        (and falsify-ok? mech-ok? eq-ok?)))
         all-ok? (every? (fn [r] (and (= :pass (:outcome r))
                                      (:ok? (:threshold-validation r))
                                      (or (nil? (:expectations r)) (:ok? (:expectations r)))
                                      (theory-ok? r)
                                      (if (= mode :verify) (:ok? (:golden-comparison r)) true)))
                         results)]
     {:suite-id suite-key
      :ok? all-ok?
      :results results})))

;; ---------------------------------------------------------------------------
;; Trace Minimisation Interface
;; ---------------------------------------------------------------------------

(defn minimise-suite
  "Minimize all failing traces in a suite to their smallest subset that still
   triggers target-invariant.  Only traces that fail with :invariant-violation
   are minimized; passing traces and structural failures are skipped.

   Returns {:suite-id kw :target-invariant kw :minimized-count int :results [...]}"
  [suite-key target-invariant]
  (let [suite (compose-suite (load-fixture suite-key))
        traces (:traces suite [])
        proto (:protocol suite)
        state (:state suite)
        authority (:authority suite)
        actors (:actors suite)
        results (atom [])]
    (doseq [trace traces]
      (let [merged-proto (when proto
                           (merge (dissoc proto :protocol/id)
                                  (:protocol-params trace)))
            effective-trace (cond-> trace
                              merged-proto (assoc :protocol-params merged-proto)
                              state (assoc :initial-block-time (:block-time state 1000))
                              authority (assoc :authority-params authority)
                              actors (assoc :agents (vec (concat (:agents trace []) actors))))
            replay-result (replay/replay-scenario effective-trace)]
        (when (and (= :fail (:outcome replay-result))
                   (= :invariant-violation (:halt-reason replay-result)))
          (let [minimized (minimizer/minimize effective-trace target-invariant)]
            (swap! results conj
                   {:trace-id             (:scenario-id effective-trace)
                    :target-invariant     target-invariant
                    :event-count          (count (:events minimized))
                    :original-event-count (count (:events effective-trace))
                    :reduction            (- (count (:events effective-trace))
                                             (count (:events minimized)))
                    :minimized-trace      minimized})))))
    {:suite-id         suite-key
     :target-invariant target-invariant
     :minimized-count  (count @results)
     :results          @results}))

;; ---------------------------------------------------------------------------
;; Suite Discovery
;; ---------------------------------------------------------------------------

(defn list-suites
  "Read the suite registry from data/fixtures/suites/manifest.edn and return a map of suite-key → metadata."
  []
  (let [manifest-path "data/fixtures/suites/manifest.edn"
        manifest (edn/read-string (slurp manifest-path))]
    (reduce-kv (fn [m k v]
                 (let [suite-path (str "data/fixtures/suites/" (:file v))
                       suite (edn/read-string (slurp suite-path))]
                   (assoc m k (select-keys suite [:suite/id :suite/title :suite/purpose
                                                 :suite/class :suite/criticality
                                                 :suite/prevents]))))
               {}
               manifest)))
