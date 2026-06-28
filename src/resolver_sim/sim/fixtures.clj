(ns resolver-sim.sim.fixtures
  "Recursive fixture loader and composer for deterministic simulation suites.

   Handles loading EDN/JSON files from data/fixtures/ based on keyword namespaces.
   Implements canonical action mapping, golden report generation, and
   trace minimisation integration."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.validation.suite-result :as suite]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.sim.minimizer :as minimizer]
            [resolver-sim.scenario.normalize :as scenario-norm]
            [resolver-sim.scenario.runner :as scenario-runner]
            [resolver-sim.scenario.golden :as golden]
            [resolver-sim.scenario.summary :as scenario-summary]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.sim.reporter :as reporter]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.params :as stoch-params]
            [resolver-sim.stochastic.types :as stoch-types]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.protocols.sew.invariant-scenarios :as sew-scenarios]
            [resolver-sim.governance.rules :as gov-rules]
            [clojure.pprint :as pp]))

(defn- result->checks
  [entry]
  (map (fn [[k v]]
         {:check/id k
          :status (if (:ok? v true) :passed :failed)
          :message (str (:violations v) (:expected v) (:actual v))})
       (:checks entry)))

(defn emit-suite-result
  [suite-id result]
  (let [all-checks (mapcat result->checks (:results result))
        suite-res (suite/suite-result suite-id :protocol all-checks)
        out-path  (str (evcfg/artifact-dir) "/suite-" (name suite-id) ".json")]
    (io/make-parents out-path)
    (spit out-path (json/write-str suite-res))))

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
             normalized (normalize-scenario loaded)]
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
     :max-held-delta N  — fails if per-step held-delta exceeds N
                           (reserved for token-pathology scenarios;
                            requires metric implementation)"
  [result thresholds & {:keys [expected-outcome]}]
  (let [violations (atom [])
        metrics    (:metrics result {})]
    (when (and (= :strict (:solvency thresholds))
               (not= expected-outcome :fail)
               (pos? (get metrics :invariant-violations 0)))
      (swap! violations conj {:type :solvency-violation :detail "Strict solvency check failed"}))
    (let [max-profit (:max-resolver-profit-ev thresholds)
          profit (get metrics :resolver-profit-ev)]
      (when (and max-profit profit (> profit max-profit))
        (swap! violations conj {:type :profit-ev-exceeded
                                :detail (str "resolver-profit-ev " profit " > " max-profit)})))
    (let [min-detection (:min-detection-rate thresholds)
          rate (get metrics :detection-rate)]
      (cond
        (and min-detection (nil? rate))
        ;; Skip validation if detection-rate is missing (stochastic-only metric).
        nil
        (and min-detection rate (< rate min-detection))
        (swap! violations conj {:type :detection-rate-below-minimum
                                :detail (str "detection-rate " rate " < " min-detection)})))
    (when-let [max-delta (:max-held-delta thresholds)]
      (let [held-before (get metrics :total-held-before 0)
            held-after  (get metrics :total-held-after 0)
            delta       (Math/abs (- held-after held-before))]
        (when (> delta max-delta)
          (swap! violations conj {:type :held-delta-exceeded
                                  :detail (str "|held-after - held-before| = " delta " > " max-delta)}))))
    {:ok? (empty? @violations)
     :violations @violations}))

(defn replay-golden-snapshot
  "Replay fields compared in every golden verify mode."
  [report]
  (golden/replay-golden-snapshot report))

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

   Returns structured `:checks :golden` data including :summary and :mismatches.
   Legacy keys :expected, :actual, :replay-ok?, :theory-ok? retained on mismatch."
  [golden actual opts]
  (let [mode (or (:golden-verify-mode opts) :replay-and-theory)]
    (when-not (golden-verify-modes mode)
      (throw (ex-info "Invalid golden-verify-mode"
                      {:mode mode :allowed golden-verify-modes})))
    (golden/compare-reports golden actual (assoc opts :golden-verify-mode mode))))

(defn- compare-golden-report
  [suite-key result opts]
  (let [trace-id (:trace-id result)
        path     (str "data/fixtures/golden/" (name trace-id) ".report.edn")
        report   (:golden-report result)
        mode     (or (:golden-verify-mode opts) :replay-and-theory)]
    (if (.exists (java.io.File. path))
      (compare-golden-reports (edn/read-string (slurp path)) report opts)
      (golden/missing-golden-check trace-id path mode))))

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
  "Data-first fixture suite runner: compose traces, replay, judge, return summary.

   Returns `scenario.summary/build-summary` shape:
     :passed :total :elapsed-ms :ok? :results :suite-id

   Each result entry includes canonical `:pass?` and `:checks` plus legacy
   fixture keys (:trace-id, :golden-report, …).

   opts:
     :silent? — suppress automatic legacy fixture printing (default false).
                Prefer `:report? false` in a future option shape; `:silent?` is
                the current name.
     :golden-verify-mode — :replay-only | :replay-and-theory (in :verify mode)
     :result-display-level — legacy fixture display when not silent

   Reporting boundaries:
     - Judgement: `scenario.runner` (`:pass?`)
     - Table report: `scenario.report/print-report`
     - Legacy detail: `sim.reporter` / `sim.result-display` (this fn when not silent)
     - CLI shell: `io.scenario-runner`"
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
                               trace-id (:scenario-id trace)
                               runner-opts (scenario-runner/runner-opts-for-scenario effective-trace opts)
                               threshold-validation (validate-thresholds res thresholds
                                                                         {:expected-outcome expected-outcome})
                               base-entry (scenario-runner/build-entry-result
                                           {:name          (str trace-id)
                                            :replay-result res
                                            :scenario      effective-trace
                                            :source        :fixture}
                                           runner-opts)
                               theory-res-for-golden (get-in base-entry [:checks :theory :result])
                               report (generate-golden-report suite-key trace-id res
                                                              {:theory-res theory-res-for-golden
                                                               :theory-decl (:theory trace)})
                               comparison (when (= mode :verify)
                                            (compare-golden-report suite-key
                                                                   {:trace-id trace-id :golden-report report}
                                                                   {:golden-verify-mode golden-verify-mode}))]
                           (when (= mode :save)
                             (save-golden-report suite-key {:trace-id trace-id :golden-report report}))
                           (scenario-runner/finalize-fixture-entry
                            base-entry
                            {:expected-outcome     expected-outcome
                             :expected-halt-reason expected-halt-reason
                             :threshold-validation threshold-validation
                             :golden-comparison    comparison
                             :golden-report        report
                             :metrics              (:metrics res)
                             :trace-id             trace-id
                             :scenario-author      (:scenario-author trace)
                             :purpose              (:purpose trace)
                             :theory-source        (:theory trace)}
                            runner-opts)))
                       traces)
         elapsed-ms (- (System/currentTimeMillis) t0)
         suite-result (scenario-summary/build-summary results
                                                      {:suite-id           suite-key
                                                       :golden-verify-mode golden-verify-mode})
         expectations-by-trace-id (into {}
                                        (keep (fn [{:keys [trace]}]
                                                (when-let [id (:scenario-id trace)]
                                                  (when (:expectations trace)
                                                    [id (:expectations trace)]))))
                                        traces)
         display-opts (merge opts
                             {:elapsed-ms elapsed-ms
                              :expectations-by-trace-id expectations-by-trace-id
                              :result-display-level (or (:result-display-level opts) :summary)})]
     (when-not (:silent? opts)
       (reporter/print-suite-results suite-result display-opts))
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

;; ──────────────────────────────────────────────────────────────────────────────
;; MC batch runner: drives scenario protocol-params → stochastic batch analysis
;; ──────────────────────────────────────────────────────────────────────────────

(defn- lookup-scenario
  "Find a scenario map by its :scenario-id string in the Sew invariant scenario registry.
   Handles paired scenarios (e.g. S12) where the entry value is a vector of two maps."
  [scenario-id]
  (some (fn [[_ v]]
          (if (vector? v)
            (some (fn [s] (when (= scenario-id (:scenario-id s)) s)) v)
            (when (= scenario-id (:scenario-id v)) v)))
        sew-scenarios/all-scenarios))

(defn scenario->mc-params
  "Compute a complete MC param map from a scenario.
   Override chain (rightmost wins):
     stochastic.types/default-params
       ← governance.rules/default-rules
       ← protocol-params->mc-overrides
       ← scenario :mc-params"
  [scenario]
  (let [pp (:protocol-params scenario)
        mc (:mc-params scenario)
        escrow-size (or (:escrow-size (or mc {}))
                        (:escrow-size (or pp {}))
                        10000)]
    (merge stoch-types/default-params
           (gov-rules/default-rules escrow-size)
           (stoch-params/protocol-params->mc-overrides pp)
           mc)))

(defn run-mc-batch-for-scenario
  "Run an MC batch using a scenario's :protocol-params as the shared source.

   Looks up the scenario by :scenario-id in the invariant scenario registry,
   derives MC params via scenario->mc-params, and runs batch/run-batch.

   Override chain (rightmost wins):
     types/default-params
       ← protocol-params->mc-overrides(:protocol-params)
       ← scenario :mc-params
       ← {:n-trials n-trials :rng-seed rng-seed :scenario-id scenario-id}

   Returns the aggregate stats map from batch/run-batch."
  [scenario-id & {:keys [n-trials rng-seed]
                  :or   {n-trials 1000
                         rng-seed  42}}]
  (if-let [scenario (lookup-scenario scenario-id)]
    (let [params (merge (scenario->mc-params scenario)
                        {:n-trials    n-trials
                         :rng-seed    rng-seed
                         :scenario-id scenario-id})
          rng    (rng/make-rng rng-seed)]
      (batch/run-batch rng (:n-trials params) params))
    (throw (ex-info "Scenario not found" {:scenario-id scenario-id}))))

(defn run-mc-batch-for-scenarios
  "Run MC batches for a collection of scenario-ids. Returns a vector of [scenario-id aggregate]."
  [scenario-ids & {:keys [n-trials rng-seed]
                   :or   {n-trials 1000
                          rng-seed  42}}]
  (mapv (fn [sid] [sid (run-mc-batch-for-scenario sid :n-trials n-trials :rng-seed rng-seed)])
        scenario-ids))