(ns resolver-sim.minimal-runner
  "Standalone CLI entry point.  Loads the scenario runner lazily so
   --help and --compute-hash work without triggering Sew protocol or DB init.
   Usage:
     java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner --help
     java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner \\
       --fixtures ./fixtures --scenario scenario.trace.json"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:gen-class))

(def cli-options
  [["-s" "--suite SUITE" "Suite keyword (e.g. :sew-invariants)"
    :parse-fn keyword]
   ["-f" "--fixture-suite SUITE" "Fixture suite keyword"
    :parse-fn keyword]
   ["-c" "--scenario PATH" "Single scenario file path"]
   ["-d" "--fixtures DIR" "Fixture directory (resolves relative scenario paths)"]
   ["-o" "--output FILE" "Output file for JSON results"]
   ["-p" "--protocol PROTOCOL" "Protocol ID (default sew-v1)"
    :default "sew-v1"]
   ["-h" "--help"]])

(defn- print-help [summary]
  (println "PRF Minimal Scenario Runner — Sew protocol replay")
  (println)
  (println "Usage:")
  (println "  java -jar prf-runner-sew.jar -m resolver-sim.minimal-runner [options]")
  (println)
  (println summary)
  (println)
  (println "Examples:")
  (println "  Replay a single scenario:")
  (println "    --scenario s-auto-cancel-time-via-keeper.trace.json")
  (println)
  (println "  With fixture directory:")
  (println "    --fixtures ./data/fixtures/traces --scenario s-auto-cancel-time-via-keeper.trace.json")
  (println)
  (println "  Suite replay:")
  (println "    --suite :sew-invariants")
  0)

(defn- resolve-scenario-path
  "Prepend fixtures dir to scenario path if --fixtures is given
   and the scenario path is relative (doesn't start with /)."
  [fixtures-dir scenario-path]
  (if (and fixtures-dir scenario-path
           (not (.startsWith scenario-path "/"))
           (not (.startsWith scenario-path ".")))
    (str (io/file fixtures-dir scenario-path))
    scenario-path))

(defn- run-scenario!
  "Load the scenario runner lazily and execute."
  [options]
  (let [runner (requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
        fixtures (:fixtures options)
        scenario (resolve-scenario-path fixtures (:scenario options))
        scenario-path (or scenario (:scenario options))]
    (when scenario-path
      (let [f (java.io.File. scenario-path)]
        (when-not (.exists f)
          (.println System/err (str "ERROR: Scenario file not found: " scenario-path))
          (when fixtures
            (.println System/err (str "  (resolved via --fixtures " fixtures ")")))
          (.println System/err "  Provide a valid path or use --suite for built-in suites.")
          (System/exit 1))))
    (let [result (runner {:protocol (:protocol options)
                          :suite (:suite options)
                          :fixture-suite (:fixture-suite options)
                          :scenario scenario-path
                          :output-file (:output options)}
                         {:report-format :summary})]
      (System/exit (:exit-code result)))))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors (do (run! println errors) (System/exit 1))
      (:help options) (System/exit (print-help summary))
      :else (run-scenario! options))))
