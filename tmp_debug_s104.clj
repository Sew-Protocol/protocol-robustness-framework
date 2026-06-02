(require '[resolver-sim.protocols.sew.invariant-scenarios.reversal :as r]
         '[resolver-sim.protocols.sew :as sew])

(def res (sew/replay-with-sew-protocol r/s104))
(println :outcome (:outcome res) :reverts (get-in res [:metrics :reverts]))
(println :fails (filter (comp not :holds? second) (get-in res [:metrics :invariant-results])))
(doseq [step (:trace res)]
  (when (:error step)
    (println :seq (:seq step) :action (:action step) :error (:error step))))
