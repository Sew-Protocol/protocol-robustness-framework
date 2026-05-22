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

(defn check-all [world]
  {:yield/position-consistency (check-position-consistency world)})
