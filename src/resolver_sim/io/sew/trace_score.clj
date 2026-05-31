(ns resolver-sim.io.sew.trace-score
  "Backward-compatible Sew trace-score namespace — delegates to sim.trace-score."
  (:require [resolver-sim.sim.trace-score :as sim-ts]))

(defn score-result
  [result]
  (sim-ts/score-result result))
