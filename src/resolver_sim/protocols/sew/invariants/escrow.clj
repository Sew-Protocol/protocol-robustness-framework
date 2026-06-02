(ns resolver-sim.protocols.sew.invariants.escrow
  "Escrow-related invariant predicates for the Sew contract model.")

(defn cancellation-mutex?
  "True when any escrow in :resolved, :released, or :refunded status
   rejects any cancellation attempt."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (and (contains? #{:resolved :released :refunded} (:escrow-state et))
                         (or (not= :none (:sender-status et))
                             (not= :none (:recipient-status et))))]
          {:workflow-id wf :state (:escrow-state et)})]
    {:holds? (empty? violations) :violations (vec violations)}))
