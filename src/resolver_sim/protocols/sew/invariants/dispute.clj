(ns resolver-sim.protocols.sew.invariants.dispute
  "Dispute-related invariant predicates for the Sew contract model."
  (:require [resolver-sim.protocols.sew.types :as t]))

(defn dispute-timestamp-consistency?
  "True when every :disputed escrow has a dispute timestamp > 0."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (= :disputed (:escrow-state et))
              :let  [ts (get-in world [:dispute-timestamps wf] 0)]
              :when (not (pos? ts))]
          {:workflow-id wf :timestamp ts})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn dispute-level-bounded?
  "True when every :dispute-levels entry is in [0, max-dispute-level], refers to
   an existing escrow, and is absent while the escrow is still :pending (or :none).
   Terminal escrows may retain a level entry after finalization."
  [world]
  (let [transfers (:escrow-transfers world {})
        violations
        (for [[wf level] (:dispute-levels world)
              :let  [et    (get transfers wf)
                     state (:escrow-state et)
                     reason (cond
                              (nil? et) :orphan-dispute-level
                              (or (neg? level) (> level t/max-dispute-level))
                              :level-out-of-range
                              (contains? #{:pending :none} state)
                              :dispute-level-on-non-disputed
                              :else nil)]
              :when reason]
          (cond-> {:workflow-id wf :level level :reason reason}
            et (assoc :escrow-state state)
            (= reason :level-out-of-range) (assoc :max t/max-dispute-level)))]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
