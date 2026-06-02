(ns resolver-sim.io.invariant-runner
  "Deprecated alias — use `resolver-sim.io.scenario-runner`."
  (:require [resolver-sim.io.scenario-runner :as scenario-runner]))

(defn run-scenario-file
  [scenario-path output-path]
  (scenario-runner/run-scenario-file-and-report scenario-path output-path {}))

(defn run-and-report
  ([] (scenario-runner/run-and-report {} {}))
  ([scenario-path output-path]
   (scenario-runner/run-and-report {:scenario scenario-path :output-file output-path} {})))
