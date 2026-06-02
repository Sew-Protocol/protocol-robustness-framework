(ns resolver-sim.protocols.sew.invariants.state
  "State-related invariant predicates for the Sew contract model.")

(defn terminal-states-unchanged?
  "True when every escrow that was terminal in world-before is still terminal
   in world-after, and has the same state."
  [world-before world-after]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et-before] (:escrow-transfers world-before)
              :when (contains? terminals (:escrow-state et-before))
              :let  [et-after (get-in world-after [:escrow-transfers wf])]
              :when (not= (:escrow-state et-before) (:escrow-state et-after))]
          {:workflow-id wf
           :before      (:escrow-state et-before)
           :after       (:escrow-state et-after)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
