(ns resolver-sim.io.invariant-runner
  "File-backed invariant scenario runner (shell layer).

   Loads scenario JSON from disk, replays via Sew protocol, optionally writes results."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-runner :as runner]))

(defn run-scenario-file
  "Load a scenario from a file, run it, and optionally write the result to output-path.
   Returns exit code (0 for pass, 1 for fail/error)."
  [scenario-path output-path]
  (try
    (let [scenario (io-sc/load-scenario-file scenario-path)
          result   (sew/replay-with-sew-protocol scenario)
          pass?    (= :pass (:outcome result))]
      (when (and output-path (not= output-path "-"))
        (io/make-parents output-path)
        (with-open [w (io/writer output-path)]
          (json/write (dissoc result :protocol) w :indent true)))
      (if pass?
        (do (println (format "✓ Scenario %s passed." scenario-path)) 0)
        (do (println (format "✗ Scenario %s failed: %s" scenario-path (:halt-reason result "unknown"))) 1)))
    (catch Exception e
      (println (format "Error running scenario %s: %s" scenario-path (.getMessage e)))
      1)))

(defn run-and-report
  "Run a scenario file or the full in-process suite. Returns exit code."
  ([] (runner/run-and-report))
  ([scenario-path output-path]
   (if scenario-path
     (run-scenario-file scenario-path output-path)
     (runner/run-and-report))))
