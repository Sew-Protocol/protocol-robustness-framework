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
  [world-before world-after & {:keys [entities-before-fn state-after-fn terminal-states]
                               :or   {entities-before-fn (fn [w] (:escrow-transfers w {}))
                                      state-after-fn     (fn [w entity-id]
                                                           (get-in w [:escrow-transfers entity-id :escrow-state]))
                                      terminal-states    #{:released :refunded :resolved}}}]
  (let [terminals terminal-states
        violations
        (for [[entity-id entity-before] (entities-before-fn world-before)
              :let [sb (:escrow-state entity-before)
                    sa (state-after-fn world-after entity-id)]
              :when (and (contains? terminals sb) (not= sb sa))]
          {:entity-id entity-id :before sb :after sa :violation :action-after-finality})]
    {:holds? (empty? violations)
     :violations (vec violations)}))
