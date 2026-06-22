(ns dev.scenarios
  (:require
   [clojure.pprint :refer [pprint]]
   [dev.repl :as repl]))

(defn run-scenario
  [scenario-id]
  ;; Replace with your actual runner.
  ;; Keep this wrapper stable even if internals change.
  (let [result ((requiring-resolve 'resolver-sim.scenario/run-scenario)
                scenario-id)]
    (tap> {:type :scenario/result
           :scenario-id scenario-id
           :result result})
    result))

(defn run-scenario-summary
  [scenario-id]
  (let [result (run-scenario scenario-id)
        summary ((requiring-resolve 'resolver-sim.reporting/summarize-run)
                 result)]
    (tap> summary)
    summary))

(defn run-yield-shortfall-demo
  []
  (run-scenario-summary :S103))

(defn run-baseline
  []
  (mapv run-scenario-summary
        [:S01 :S02 :S03 :S04 :S05 :S06 :S07 :S08 :S09]))
