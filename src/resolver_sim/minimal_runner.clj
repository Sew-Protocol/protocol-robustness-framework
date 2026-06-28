(ns resolver-sim.minimal-runner
  "Standalone CLI entry point that loads ONLY the scenario runner.
   No gRPC, no XTDB, no server infrastructure.
   Usage: clojure -M:runner/sew -m resolver-sim.minimal-runner --suite :sew-invariants"
  (:require [clojure.tools.cli :as cli]
            [resolver-sim.io.scenario-runner :as runner])
  (:gen-class))

(def cli-options
  [["-s" "--suite SUITE" "Suite keyword (e.g. :sew-invariants)"
    :parse-fn keyword]
   ["-f" "--fixture-suite SUITE" "Fixture suite keyword"
    :parse-fn keyword]
   ["-c" "--scenario PATH" "Single scenario file path"]
   ["-o" "--output FILE" "Output file for JSON results"]
   ["-p" "--protocol PROTOCOL" "Protocol ID (default sew-v1)"
    :default "sew-v1"]
   ["-h" "--help"]])

(defn- check-file!
  "Exit with clear message if path is non-nil but doesn't exist on disk."
  [path label]
  (when (and path (not (.exists (java.io.File. path))))
    (.println System/err (str "ERROR: " label " not found: " path))
    (.println System/err "  Provide a valid file path, or use --suite for built-in suites.")
    (System/exit 1)))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (do (run! println errors) (System/exit 1))
      (:help options)
      (do (println "PRF Minimal Scenario Runner")
          (println (str "Usage: clojure -M:runner/sew -m resolver-sim.minimal-runner [options]"))
          (println summary)
          (System/exit 0))
      :else
      (do
        (check-file! (:scenario options) "Scenario file")
        (let [result (runner/run-and-report
                      {:protocol (:protocol options)
                       :suite (:suite options)
                       :fixture-suite (:fixture-suite options)
                       :scenario (:scenario options)
                       :output-file (:output options)}
                      {:report-format :summary})]
          (System/exit (:exit-code result)))))))
