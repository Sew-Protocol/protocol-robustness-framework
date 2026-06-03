(ns resolver-sim.scenario.yield-provider-metrics
  "Yield-engine metrics for provider-only replay (no Sew escrow fields).")

(defn- position-for-scenario
  "Read from full world or from replay snapshot (`:yield-positions`)."
  [world scenario]
  (let [owner (or (get-in scenario [:protocol-params :default-owner-id])
                  (get-in (first (:agents scenario)) [:id])
                  "vault")
        positions (or (:yield/positions world)
                      (:yield-positions world)
                      {})]
    (or (get positions owner)
        (first (vals positions)))))

(defn compute-provider-metrics
  [result scenario]
  (let [trace      (:trace result)
        last-world (when (seq trace) (:world (last trace)))
        pos        (when last-world (position-for-scenario last-world scenario))
        shortfall  (:shortfall pos)]
    (cond-> {}
      pos
      (assoc :yield/position-principal     (long (or (:principal pos) 0))
             :yield/position-unrealized    (long (or (:unrealized-yield pos) 0))
             :yield/position-realized      (long (or (:realized-yield pos) 0))
             :yield/position-gross         (long (+ (or (:principal pos) 0)
                                                    (or (:unrealized-yield pos) 0)))
             :yield/accrual-loss           (long (or (:amount (:yield-loss pos)) 0))
             :yield/position-deferred      (long (or (:deferred-amount shortfall) 0))
             :yield/position-haircut       (long (or (:haircut-amount shortfall) 0)))
      (:status pos)
      (assoc :yield/position-status (name (:status pos)))
      (or (:reason shortfall) (:reason (:yield-loss pos)))
      (assoc :yield/loss-reason (name (or (:reason shortfall) (:reason (:yield-loss pos))))))))

(defn merge-provider-metrics
  [result scenario]
  (update result :metrics merge (compute-provider-metrics result scenario)))
