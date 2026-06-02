(ns resolver-sim.io.scenario-runner
  "CLI shell: load scenarios, run collections, print reports, exit codes.

   Does not judge pass/fail — delegates to `scenario.runner` and `sim.fixtures`.
   Table output via `scenario.report/print-report`; legacy fixture detail via
   `sim.reporter` when `:report-format :fixture`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-scenarios :as inv-sc]
            [clojure.string :as str]
            [resolver-sim.protocols.sew.narrative :as narrative]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.sim.fixtures :as fixtures]))

(defn- sew-replay-fn []
  (fn [scenario] (sew/replay-with-sew-protocol scenario)))

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
   Files are normalized before replay. Returns summary map."
  [paths opts]
  (let [entries (mapv (fn [path]
                        (let [raw      (io-sc/load-scenario-file path)
                              scenario (normalize/normalize-scenario raw)
                              name     (or (:scenario-id scenario) path)]
                          {:name     (str name "  [" path "]")
                           :scenario scenario
                           :source   :file}))
                      paths)]
    (runner/run-collection
     {:entries   entries
      :replay-fn (sew-replay-fn)}
     (merge {:normalize? false :suite-id (:suite-id opts)} opts))))

(defn run-scenario-file
  "Run a single scenario file. Returns summary map with one entry."
  [scenario-path opts]
  (run-paths [scenario-path] opts))

(defn write-result-json
  [output-path result]
  (when (and output-path (not= output-path "-"))
    (io/make-parents output-path)
    (with-open [w (io/writer output-path)]
      (json/write (dissoc result :protocol) w :indent true))))

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
  "Run a registered suite keyword (e.g. :yield-scenarios). Returns exit code."
  [suite-key opts]
  (if-let [paths (suites/suite-paths suite-key)]
    (run-paths-and-report paths (assoc opts :suite-id suite-key))
    (do
      (println (str "Unknown suite: " suite-key
                    ". Known: " (str/join ", " (map name (suites/known-suite-keys)))))
      1)))

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
     :suite          — path-list keyword (e.g. :yield-scenarios)
     :fixture-suite  — EDN fixture keyword (e.g. :suites/all-invariants)
     :scenario       — file path
     :output-file    — JSON path when running a single scenario
     :protocol       — protocol id (default sew-v1)
     opts are forwarded to report/runner."
  [dispatch opts]
  (let [protocol-id (or (:protocol dispatch) preg/default-protocol-id)]
    (cond
      (not= protocol-id preg/default-protocol-id)
      (do (println (str "Scenario runner supports only " preg/default-protocol-id " for now."))
          1)

      (:fixture-suite dispatch)
      (run-fixture-suite-and-report (:fixture-suite dispatch) nil opts)

      (:suite dispatch)
      (run-named-suite-and-report (:suite dispatch) opts)

      (:scenario dispatch)
      (run-scenario-file-and-report (:scenario dispatch) (:output-file dispatch) opts)

      :else
      (run-registry-suite-and-report opts))))
