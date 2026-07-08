(ns resolver-sim.commands.scenario
  "Run scenario(s) through the replay engine.
   Invoked by `prf.jar run-scenario`."
  (:require [clojure.string :as str]))

(defn- parse-suite
  "Convert a suite string to a keyword, stripping leading : if present."
  [s]
  (when s
    (keyword (str/replace s #"^:" ""))))

(defn run
  "Run one or more scenarios. Options:
     :scenario      — scenario ID or file path
     :suite         — suite keyword (string, converted to keyword)
     :out           — output directory for results
     :json?         — when true, output as JSON
     :cmd/args      — extra positional args (e.g. scenario file path as bare arg)"
  [{:keys [scenario suite scenario-file out json?] :as opts}]
  (let [cmd-args (:cmd/args opts)
        scenario-path (or scenario scenario-file (first cmd-args))
        suite-kw (parse-suite suite)
        dispatch (cond-> {}
                   scenario-path (assoc :scenario scenario-path)
                   suite-kw (assoc :suite suite-kw)
                   out (assoc :output-file (str out "/scenario-run.json")))
        runner-opts (cond-> {}
                      json? (assoc :report-format :json))]
    (println "Running scenario(s)...")
    (println (str "  scenario: " (or scenario-path "<none>")))
    (println (str "  suite: " (or suite-kw "<none>")))
    (println (str "  output: " (or out "<none>")))
    (flush)
    (let [scenario-runner (requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
          result (scenario-runner dispatch runner-opts)]
      (or (:exit-code result) 0))))
