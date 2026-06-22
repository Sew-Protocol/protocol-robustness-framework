(ns dev.yield
  (:require [dev.repl :as repl]))

(defn run-shortfall
  []
  ((requiring-resolve 'resolver-sim.scenarios.yield/run-shortfall-demo)))

(defn explain-partial-fill
  [input]
  (let [f (requiring-resolve
           'resolver-sim.yield.partial-fill/calculate-fulfillment-pro-rata)
        result (f input)]
    (tap> {:type :yield/partial-fill
           :input input
           :result result})
    result))
