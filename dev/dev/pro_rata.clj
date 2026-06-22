(ns dev.pro-rata)

(defn explain-generic-allocation
  [input]
  (let [f (requiring-resolve
           'resolver-sim.economics.payoffs/allocate-pro-rata)
        result (f input)]
    (tap> {:type :pro-rata/generic-allocation
           :input input
           :result result})
    result))

(defn explain-sew-slash-allocation
  [input]
  (let [f (requiring-resolve
           'resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation)
        result (f input)]
    (tap> {:type :pro-rata/sew-slash-allocation
           :input input
           :result result})
    result))
