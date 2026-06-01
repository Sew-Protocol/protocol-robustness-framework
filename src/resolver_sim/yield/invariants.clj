(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism.")

(defn check-position-consistency
  "Check that position arithmetic is valid (non-negative principal/shares/yield)."
  [world]
  (let [positions (:yield/positions world {})]
    (every? (fn [[_ pos]]
              (and (>= (:principal pos 0) 0)
                   (>= (:shares pos 0) 0)
                   (>= (:unrealized-yield pos 0) 0)
                   (>= (:realized-yield pos 0) 0)))
            positions)))

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
                                             (+ acc (:principal pos 0) (:realized-yield pos 0))
                                             acc))
                                         0
                                         positions)]
                (>= held total-needed)))
            tokens)))

(defn check-all [world]
  {:yield/position-consistency (check-position-consistency world)})
