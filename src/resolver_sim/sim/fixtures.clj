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
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.sim.minimizer :as minimizer]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.scenario.theory :as theory]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.normalize :as scenario-norm]
            [resolver-sim.sim.reporter :as reporter]
            [clojure.pprint :as pp]))

(def ^:private golden-schema-version "2.0")

(def golden-verify-modes
  "How fixture :verify compares saved golden reports.
   :replay-only           — metrics, outcome, projection hash only
   :replay-and-theory     — also compare :theory snapshot when present in golden"
  #{:replay-only :replay-and-theory})

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

(defn normalize-scenario
  "Delegate to `resolver-sim.scenario.normalize/normalize-scenario`."
  [x]
  (scenario-norm/normalize-scenario x))

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
                                                 "threshold" "actor" "token" "minimize"} ns-str)))
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
                               (Monte Carlo metric; skipped in replay context)

   When :expected-outcome is :fail, strict solvency is not applied (invariant
   violation is an expected trace outcome)."
  [result thresholds & {:keys [expected-outcome]}]
  (let [violations (atom [])
        metrics    (:metrics result {})]
    (when (and (= :strict (:solvency thresholds))
               (not= expected-outcome :fail)
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

(defn replay-golden-snapshot
  "Replay fields compared in every golden verify mode."
  [report]
  (select-keys report [:suite-id :trace-id :final-state-hash :metrics :outcome]))

(defn generate-golden-report
  "Build a golden report map for one trace replay (+ optional theory snapshot)."
  [suite-id trace-id replay-result & [{:keys [theory-res theory-decl]}]]
  (let [last-entry (last (:trace replay-result))
        final-hash (get-in last-entry [:projection-hash])]
    (cond-> {:golden-schema-version golden-schema-version
             :suite-id suite-id
             :trace-id trace-id
             :final-state-hash final-hash
             :metrics (:metrics replay-result)
             :outcome (:outcome replay-result)}
      theory-res (assoc :theory (theory-result/golden-snapshot theory-res theory-decl)))))

(defn- save-golden-report
  [suite-key result]
  (let [path (str "data/fixtures/golden/" (name (:trace-id result)) ".report.edn")]
    (with-open [w (io/writer path)]
      (pp/pprint (:golden-report result) w))))

(defn compare-golden-reports
  "Compare actual report to on-disk golden.

   opts:
     :golden-verify-mode — :replay-only | :replay-and-theory (default)

   Legacy goldens without :theory or :golden-schema-version compare replay fields only."
  [golden actual {:keys [golden-verify-mode]
                  :or {golden-verify-mode :replay-and-theory}}]
  (when-not (golden-verify-modes golden-verify-mode)
    (throw (ex-info "Invalid golden-verify-mode" {:mode golden-verify-mode
                                                  :allowed golden-verify-modes})))
  (let [replay-ok? (= (replay-golden-snapshot golden) (replay-golden-snapshot actual))
        legacy?    (nil? (:golden-schema-version golden))
        theory-ok? (case golden-verify-mode
                     :replay-only true
                     (cond
                       legacy? true
                       (nil? (:theory golden)) true
                       (nil? (:theory actual)) false
                       :else (= (:theory golden) (:theory actual))))]
    (if (and replay-ok? theory-ok?)
      {:ok? true :golden-verify-mode golden-verify-mode}
      {:ok? false
       :golden-verify-mode golden-verify-mode
       :replay-ok? replay-ok?
       :theory-ok? theory-ok?
       :expected golden
       :actual actual})))

(defn- compare-golden-report
  [suite-key result opts]
  (let [path (str "data/fixtures/golden/" (name (:trace-id result)) ".report.edn")
        golden (edn/read-string (slurp path))
        report (:golden-report result)]
    (compare-golden-reports golden report opts)))

(defn- resolve-golden-verify-mode
  [suite mode opts]
  (when (= mode :verify)
    (let [mode* (or (:golden-verify-mode opts)
                    (:suite/golden-verify-mode suite)
                    :replay-and-theory)]
      (when-not (golden-verify-modes mode*)
        (throw (ex-info "Invalid golden-verify-mode" {:mode mode*})))
      mode*)))

(defn run-suite
  "Execute a suite fixture: compose, replay traces, validate thresholds, and optionally save or verify golden reports.
   An optional protocol can be supplied; defaults to the registry default protocol.

   opts (4th arg or via metadata on 3-arg call when protocol is a map — prefer 4-arg):
     :golden-verify-mode — :replay-only | :replay-and-theory (default in :verify mode)
     :result-display-level — :summary | :failures | :standard | :verbose | :audit (stdout only; default :summary)
     :verbose? / :show-failures? — legacy aliases for display level (stdout only)"
  ([suite-key] (run-suite suite-key nil nil {}))
  ([suite-key mode] (run-suite suite-key mode nil {}))
  ([suite-key mode protocol] (run-suite suite-key mode protocol {}))
  ([suite-key mode protocol opts]
   (let [t0               (System/currentTimeMillis)
         effective-protocol (or protocol
                                (preg/get-protocol preg/default-protocol-id))
         suite (compose-suite (load-fixture suite-key))
         golden-verify-mode (resolve-golden-verify-mode suite mode opts)
         trace-entries (:traces suite [])
         traces (mapv (fn [entry]
                        (if (and (map? entry) (contains? entry :trace))
                          (let [trace-ref (:trace entry)
                                trace (if (fixture-ref? trace-ref)
                                        (compose-suite trace-ref)
                                        trace-ref)]
                            {:trace trace
                             :expected-outcome (:expected-outcome entry)
                             :expected-halt-reason (:expected-halt-reason entry)})
                          {:trace (if (fixture-ref? entry)
                                    (compose-suite entry)
                                    entry)}))
                      trace-entries)
         thresholds (:thresholds suite {})
         proto (:protocol suite)
         state (:state suite)
         authority (:authority suite)
         actors (:actors suite)
         token (:token suite)
         results (mapv (fn [{:keys [trace expected-outcome expected-halt-reason]}]
                         (let [merged-proto (when proto
                                              (merge (dissoc proto :protocol/id)
                                                     (:protocol-params trace)))
                               effective-trace (cond-> trace
                                                 merged-proto (assoc :protocol-params merged-proto)
                                                 state (assoc :initial-block-time (:block-time state 1000))
                                                 authority (assoc :authority-params authority)
                                                 actors (assoc :agents (vec (concat (:agents trace []) actors)))
                                                 token (assoc :token-params token))
                               res (replay/replay-with-protocol effective-protocol effective-trace)
                               expect-res (or (:expectations res)
                                              (when (:expectations trace)
                                                (expectations/evaluate-expectations res (:expectations trace))))
                               theory-res (when (:theory trace)
                                            (let [theory    (:theory trace)
                                                  profile   (or (:theory-eval-profile theory)
                                                                (when (true? (:require-conclusive? theory)) :strict)
                                                                :regression)
                                                  strict-profile? (#{:strict :public-evidence} profile)]
                                              (theory/evaluate-theory res theory
                                                                      {:theory-eval-profile profile})))
                               trace-id (:scenario-id trace)
                               report (generate-golden-report suite-key trace-id res
                                                              {:theory-res theory-res
                                                               :theory-decl (:theory trace)})
                               comparison (when (= mode :verify)
                                            (compare-golden-report suite-key
                                                                   {:trace-id trace-id :golden-report report}
                                                                   {:golden-verify-mode golden-verify-mode}))]
                           (when (= mode :save) (save-golden-report suite-key {:trace-id (:scenario-id trace) :golden-report report}))
                           {:trace-id (:scenario-id trace)
                            :scenario-author (:scenario-author trace)
                            :purpose  (:purpose trace)
                             :theory-source (:theory trace)
                            :outcome (:outcome res)
                             :halt-reason (:halt-reason res)
                             :expected-outcome expected-outcome
                             :expected-halt-reason expected-halt-reason
                            :metrics (:metrics res)
                            :threshold-validation (validate-thresholds res thresholds
                                                                    {:expected-outcome expected-outcome})
                            :golden-report report
                            :golden-comparison comparison
                            :expectations expect-res
                            :theory theory-res}))
                       traces)
         theory-ok? (fn [r]
                      (let [purpose       (:purpose r)
                            mech-status   (get-in r [:theory :mechanism-status] :not-checked)
                            eq-status     (get-in r [:theory :equilibrium-status] :not-checked)
                            mech-results  (vals (get-in r [:theory :mechanism-results] {}))
                            eq-results    (vals (get-in r [:theory :equilibrium-results] {}))
                            profile       (get-in r [:theory :diagnostics :theory-eval-profile])
                            strict?       (or (true? (get-in r [:theory-source :require-conclusive?]))
                                              (#{:strict :public-evidence} profile))
                            opts          {:require-conclusive? strict?}
                            falsify-ok?   (ose/theory-result-ok? (:theory r) purpose opts)
                            mech-ok?      (ose/domain-results-ok? purpose mech-status mech-results opts)
                            eq-ok?        (ose/domain-results-ok? purpose eq-status eq-results opts)]
                        (and falsify-ok? mech-ok? eq-ok?)))
         all-ok? (every? (fn [r] (and (= (or (:expected-outcome r) :pass) (:outcome r))
                                      (or (nil? (:expected-halt-reason r))
                                          (= (:expected-halt-reason r) (:halt-reason r)))
                                      (:ok? (:threshold-validation r))
                                      (or (nil? (:expectations r)) (:ok? (:expectations r)))
                                      (theory-ok? r)
                                      (if (= mode :verify) (:ok? (:golden-comparison r)) true)))
                         results)
         expectations-by-trace-id (into {}
                                        (keep (fn [{:keys [trace]}]
                                                (when-let [id (:scenario-id trace)]
                                                  (when (:expectations trace)
                                                    [id (:expectations trace)]))))
                                      traces)
         suite-result {:suite-id suite-key
                       :golden-verify-mode golden-verify-mode
                       :ok? all-ok?
                       :results results}
         display-opts (merge opts
                             {:elapsed-ms (- (System/currentTimeMillis) t0)
                              :expectations-by-trace-id expectations-by-trace-id
                              :result-display-level (or (:result-display-level opts) :summary)})]
     (reporter/print-suite-results suite-result display-opts)
     suite-result)))

;; ---------------------------------------------------------------------------
;; Trace Minimisation Interface
;; ---------------------------------------------------------------------------

(defn minimise-suite
  "Minimize all failing traces in a suite to their smallest subset that still
   triggers target-invariant.  Only traces that fail with :invariant-violation
   are minimized; passing traces and structural failures are skipped.

   Returns {:suite-id kw :target-invariant kw :minimized-count int :results [...]}"
  ([suite-key target-invariant] (minimise-suite suite-key target-invariant nil))
  ([suite-key target-invariant protocol]
   (let [effective-protocol (or protocol
                                (preg/get-protocol preg/default-protocol-id))
         suite (compose-suite (load-fixture suite-key))
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
             replay-result (replay/replay-with-protocol effective-protocol effective-trace)]
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
      :results          @results})))

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
