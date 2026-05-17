(ns resolver-sim.time.invariants
  "Cross-cutting temporal invariants for simulation traces/world transitions.")

(defn non-decreasing-time?
  "Transition invariant: block-time must be monotonic non-decreasing."
  [world-before world-after]
  (let [tb (long (or (:block-time world-before) 0))
        ta (long (or (:block-time world-after) 0))]
    {:holds? (<= tb ta)
     :violations (when (> tb ta)
                   [{:before tb :after ta :violation :time-decreased}])}))

(defn no-action-after-finality?
  "Transition invariant: terminal escrow states must remain terminal and unchanged.
   This is temporal framing over terminal-state irreversibility."
  [world-before world-after]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et-before] (:escrow-transfers world-before {})
              :let [sb (:escrow-state et-before)
                    sa (get-in world-after [:escrow-transfers wf :escrow-state])]
              :when (and (contains? terminals sb) (not= sb sa))]
          {:workflow-id wf :before sb :after sa :violation :action-after-finality})]
    {:holds? (empty? violations)
     :violations (vec violations)}))
