(ns resolver-sim.time.invariants
  "Cross-cutting temporal invariants for simulation traces/world transitions."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.context :as time-ctx]))

(defn non-decreasing-time?
  "Transition invariant: block-time must be monotonic non-decreasing."
  [world-before world-after]
  (let [tb (time-ctx/block-ts world-before)
        ta (time-ctx/block-ts world-after)]
    {:holds? (<= tb ta)
     :violations (when (> tb ta)
                   [{:before tb :after ta :violation :time-decreased}])}))

(defn non-decreasing-step?
  "Transition invariant: scenario step must be monotonic non-decreasing."
  [world-before world-after]
  (let [sb (time-ctx/step world-before)
        sa (time-ctx/step world-after)]
    {:holds? (<= sb sa)
     :violations (when (> sb sa)
                   [{:before sb :after sa :violation :step-decreased}])}))

(defn check-temporal-consistency
  "Single-state invariant: ensures legacy root matches canonical context."
  [world]
  (time-ctx/check-temporal-consistency world))

(defn no-action-after-finality?
  "Transition invariant: terminal escrow states must remain terminal and unchanged.
   This is temporal framing over terminal-state irreversibility."
  [world-before world-after & {:keys [entities-before-fn state-after-fn terminal-states]
                               :or   {entities-before-fn (fn [w] (:escrow-transfers w {}))
                                      state-after-fn     (fn [w entity-id]
                                                           (get-in w [:escrow-transfers entity-id :escrow-state]))
                                       terminal-states    t/terminal-states}}]
  (let [terminals terminal-states
        violations
        (for [[entity-id entity-before] (entities-before-fn world-before)
              :let [sb (:escrow-state entity-before)
                    sa (state-after-fn world-after entity-id)]
              :when (and (contains? terminals sb) (not= sb sa))]
          {:entity-id entity-id :before sb :after sa :violation :action-after-finality})]
    {:holds? (empty? violations)
     :violations (vec violations)}))
