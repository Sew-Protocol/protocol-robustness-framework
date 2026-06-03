(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism."
  (:require [resolver-sim.yield.risk :as risk]))

(defn check-position-consistency
  "Check that position arithmetic is valid.

   Under :mark-to-market loss mode, unrealized yield may be negative; principal
   and shares remain non-negative."
  [world]
  (let [positions (:yield/positions world {})]
    (every? (fn [[_ pos]]
              (let [risk      (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
                    mark-to-market? (= :mark-to-market (risk/effective-loss-mode risk))]
                (and (>= (:principal pos 0) 0)
                     (>= (:shares pos 0) 0)
                     (>= (:realized-yield pos 0) 0)
                     (or mark-to-market?
                         (>= (:unrealized-yield pos 0) 0)))))
            positions)))

(defn position-custody-need
  "Custody required for an active position at current mark.

   Under :mark-to-market, negative unrealized yield reduces economic value
   (principal + unrealized + realized); legacy mode counts principal + realized only."
  [world pos]
  (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
        mtm? (= :mark-to-market (risk/effective-loss-mode risk))]
    (if mtm?
      (max 0 (+ (:principal pos 0) (:unrealized-yield pos 0) (:realized-yield pos 0)))
      (+ (:principal pos 0) (:realized-yield pos 0)))))

(defn check-yield-exposure
  "Check that the protocol has enough physical funds to cover all active yield positions.
   
   live-position-pred — (fn [owner-id position]) -> boolean
   held-balance-fn    — (fn [token]) -> amount"
  [world live-position-pred held-balance-fn]
  (let [positions (get world :yield/positions {})
        tokens    (into #{} (map :token (vals positions)))]
    (every? (fn [token]
              (let [held (held-balance-fn token)
                    total-needed (reduce (fn [acc [oid pos]]
                                           (if (and (= (:token pos) token)
                                                    (= (:status pos) :active)
                                                    (live-position-pred oid pos))
                                             (+ acc (position-custody-need world pos))
                                             acc))
                                         0
                                         positions)]
                (>= held total-needed)))
            tokens)))

(defn check-all [world]
  {:yield/position-consistency (check-position-consistency world)})
