(ns resolver-sim.io.scenario-runner
  "CLI shell: load scenarios, run collections, print reports, exit codes.

   Does not judge pass/fail — delegates to `scenario.runner` and `sim.fixtures`.
   Table output via `scenario.report/print-report`; legacy fixture detail via
   `sim.reporter` when `:report-format :fixture`."
  (:require [clojure.java.io :as io]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-scenarios :as inv-sc]
            [clojure.string :as str]
            [resolver-sim.protocols.sew.narrative :as narrative]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.yield.invariant-catalog :as yield-inv-cat]
            [resolver-sim.sim.fixtures :as fixtures]
            [resolver-sim.io.serialization :as serialization]))

(defn- replay-fn-for-protocol
  [protocol-id]
  (let [protocol-id (or protocol-id preg/default-protocol-id)
        protocol    (preg/get-protocol protocol-id)]
    (when-not protocol
      (throw (ex-info "Unknown scenario protocol"
                      {:protocol protocol-id
                       :known-protocols (vec (preg/known-protocol-ids))})))
    (if (= "yield-v1" protocol-id)
      #(replay/replay-yield-scenario protocol %)
      (fn [scenario]
        (if (= "sew-v1" protocol-id)
          (sew/replay-with-sew-protocol scenario)
          (replay/replay-with-protocol protocol scenario))))))

(defn- scenario-file-details
  [scenario-path default-protocol-id]
  (let [scenario (-> scenario-path io-sc/load-scenario-file normalize/normalize-scenario)
        protocol-id (or (:protocol scenario) default-protocol-id)]
    {:scenario scenario
     :protocol protocol-id}))

(defn- sew-replay-fn []
  (replay-fn-for-protocol "sew-v1"))

(defn- default-report-opts [opts]
  (merge {:outline-printer (fn [name result]
                             (let [{:keys [header lines footer separator]}
                                   (narrative/scenario-outline name result)]
                               (println header)
                               (doseq [line lines] (println line))
                               (println footer)
                               (println separator)))}
         opts))

(defn run-registry-suite
  "Run the Sew in-process invariant registry (S01–S100). Returns summary map."
  ([] (run-registry-suite {}))
  ([opts]
   (runner/run-collection
    {:entries     inv-sc/all-scenarios
     :replay-fn   (sew-replay-fn)
     :type-meta-fn (fn [sid] (get inv-sc/scenario-type-registry sid {}))}
    (merge {:suite-id :sew-invariants} opts))))

(defn run-paths
  "Run scenario JSON files from `paths`. Each path becomes one entry.
   Files are normalized before replay. Returns summary map.

   opts may include `:protocol` (registry id, default sew-v1)."
  [paths opts]
  (let [default-protocol-id (or (:protocol opts) preg/default-protocol-id)
        entries (mapv (fn [path]
                        (let [{:keys [scenario protocol]} (scenario-file-details path default-protocol-id)
                              scenario* (if (= "yield-v1" protocol)
                                          (yield-inv-cat/enrich-expectations scenario)
                                          scenario)
                              name      (or (:scenario-id scenario*) path)]
                          {:name     (str name "  [" path "]")
                           :scenario scenario*
                           :source   :file
                           :protocol protocol}))
                      paths)]
    (doseq [{:keys [name protocol]} entries]
      (println (format "[run:scenario] %s -> protocol %s" name protocol)))
    (runner/run-collection
     {:entries   entries
      :replay-fn (fn [scenario]
                   ((replay-fn-for-protocol (or (:protocol scenario) default-protocol-id))
                    scenario))}
     (merge {:normalize? false :suite-id (:suite-id opts)} opts))))

(defn run-scenario-file
  "Run a single scenario file. Returns summary map with one entry."
  [scenario-path opts]
  (run-paths [scenario-path] opts))

(defn write-result-json
  [output-path result]
  (when (and output-path (not= output-path "-"))
    (io/make-parents output-path)
    (spit output-path (serialization/serialize-artifact (dissoc result :protocol)
                                                        {:pretty? true}))))

(defn run-registry-suite-and-report
  "Run invariant registry, print report, return exit code."
  ([] (run-registry-suite-and-report {}))
  ([opts]
   (let [summary (run-registry-suite opts)
         title   "Sew Invariant Suite — Deterministic Scenarios (Clojure in-process)"
         code    (report/print-report (assoc summary :suite-id nil)
                                      (default-report-opts
                                       (assoc opts :title title)))]
     code)))

(defn run-paths-and-report
  "Run path list, print report, return exit code."
  [paths opts]
  (let [summary (run-paths paths opts)
        suite-id (:suite-id opts)
        title    (when suite-id (format "Scenario suite: %s" (name suite-id)))
        code     (report/print-report summary
                                      (default-report-opts
                                       (cond-> opts
                                         title (assoc :title title))))]
    code))

(defn run-scenario-file-and-report
  "Run one scenario file; optional JSON output. Returns exit code."
  [scenario-path output-path opts]
  (try
    (let [summary (run-scenario-file scenario-path opts)
          entry   (first (:results summary))
          code    (report/print-report summary (default-report-opts opts))]
      (when output-path
        (write-result-json output-path (:replay-result entry)))
      code)
    (catch Exception e
      (println (format "Error running scenario %s: %s" scenario-path (.getMessage e)))
      1)))

(defn run-named-suite-and-report
  "Run a registered suite keyword (e.g. :yield-provider-scenarios). Returns exit code."
  [suite-key opts]
  (if-let [paths (suites/suite-paths suite-key)]
    (run-paths-and-report paths
                          (assoc opts
                                 :suite-id suite-key
                                 :protocol (or (:protocol opts)
                                               (suites/suite-protocol-id suite-key))))
    (do
      (println (str "Unknown suite: " suite-key
                    ". Known: " (str/join ", " (map name (suites/known-suite-keys)))))
      1)))

(defn known-suite-summaries
  "Return registry-backed metadata for all named scenario suites."
  []
  (mapv (fn [suite-key]
          (let [{:keys [title description kind ci-tier protocol-id paths] :as defn*}
                (suites/suite-definition suite-key)]
            {:suite-key    suite-key
             :title        title
             :description  description
             :kind         kind
             :ci-tier      ci-tier
             :protocol-id  protocol-id
             :path-count   (count paths)
             :paths        paths
             :definition   defn*}))
        (suites/known-suite-keys)))

(defn run-fixture-suite
  "Run a composed EDN fixture suite (e.g. :suites/all-invariants).
   Returns the unified summary map; does not print unless opts request it."
  [suite-key mode opts]
  (fixtures/run-suite suite-key mode nil (assoc opts :silent? true)))

(defn run-fixture-suite-and-report
  "Run fixture suite with the invariant-style table report. Returns exit code.

   opts:
     :report-format — :table (default) uses scenario.report; :fixture uses sim.reporter
     Other opts forwarded to fixtures/run-suite (e.g. :result-display-level for :fixture)."
  [suite-key mode opts]
  (let [report-format (or (:report-format opts) :table)
        silent?       (= :table report-format)
        summary       (fixtures/run-suite suite-key mode nil
                                          (assoc opts :silent? silent?))]
    (if (= :table report-format)
      (report/print-report summary
                           (default-report-opts
                            (assoc opts
                                   :title (format "Fixture suite: %s" (name suite-key)))))
      (if (:ok? summary) 0 1))))

(defn run-and-report
  "CLI dispatcher: full invariant suite, named suite, or single file.

   `dispatch` is a map with optional keys:
     :suite          — path-list keyword (e.g. :yield-provider-scenarios, :sew-yield-scenarios)
     :fixture-suite  — EDN fixture keyword (e.g. :suites/all-invariants)
     :scenario       — file path
     :output-file    — JSON path when running a single scenario
     :protocol       — protocol id (default sew-v1)
     opts are forwarded to report/runner."
  [dispatch opts]
  (let [default-protocol-id (or (:protocol dispatch) preg/default-protocol-id)
        inferred-protocol-id (when (:scenario dispatch)
                               (:protocol (scenario-file-details (:scenario dispatch)
                                                                 default-protocol-id)))
        suite-protocol-id (when (:suite dispatch)
                            (suites/suite-protocol-id (:suite dispatch)))
        protocol-id (or inferred-protocol-id suite-protocol-id default-protocol-id)
        dispatch*   (cond
                      (:suite dispatch)
                      (assoc dispatch :protocol protocol-id)

                      (:scenario dispatch)
                      (assoc dispatch :protocol protocol-id)

                      :else
                      dispatch)]
    (ev-node/with-execution-node
      {:execution-id :execution/replay
       :inputs {:dispatch dispatch*
                :opts {:protocol protocol-id
                       :report-format (:report-format opts)
                       :suite-id (:suite-id opts)}}
       :status-fn #(if (zero? %) :pass :fail)
       :outputs-fn (fn [exit-code]
                     {:exit-code exit-code
                      :dispatch dispatch*})
       :failure-details-fn (fn [exit-code]
                             (if (zero? exit-code)
                               []
                               [{:failure-type :replay-failed
                                 :class :unexpected
                                 :message (str "Replay exited with code " exit-code)
                                 :expected? false}]))}
      (fn []
        (cond
          (:fixture-suite dispatch*)
          (run-fixture-suite-and-report (:fixture-suite dispatch*) nil opts)

          (:suite dispatch*)
          (run-named-suite-and-report (:suite dispatch*) opts)

          (:scenario dispatch*)
          (run-scenario-file-and-report (:scenario dispatch*)
                                        (:output-file dispatch*)
                                        (assoc opts :protocol protocol-id))

          :else
          (run-registry-suite-and-report opts))))))
