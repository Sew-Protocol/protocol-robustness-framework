(ns resolver-sim.io.invariant-runner
  "Deprecated alias — use `resolver-sim.io.scenario-runner`."
  (:require [resolver-sim.io.scenario-runner :as scenario-runner]
            [resolver-sim.logging :as log]))

(defn run-scenario-file
  [scenario-path output-path]
  (scenario-runner/run-scenario-file-and-report scenario-path output-path {}))

(defn run-and-report
  ([] (scenario-runner/run-and-report {} {}))
  ([scenario-path output-path]
   (log/warn! :deprecated-ns
              {:namespace "resolver-sim.io.invariant-runner"
               :message "Use resolver-sim.io.scenario-runner directly"})
   (scenario-runner/run-and-report {:scenario scenario-path :output-file output-path} {})))
