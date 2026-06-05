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

(defn- liquidity-index-metric [world scenario]
  (when-let [pos (position-for-scenario world scenario)]
    (let [idx (or (:current-index pos)
                  (let [mid (:module/id pos)
                        tok (:token pos)]
                    (get-in world [:yield/indices mid tok]
                                (get-in world [:yield-indices mid tok]
                                            (get-in world [:yield-indices mid (name tok)])))))]
      (when idx (double idx)))))

(defn compute-provider-metrics
  [result scenario]
  (let [trace      (:trace result)
        last-world (when (seq trace) (:world (last trace)))
        pos        (when last-world (position-for-scenario last-world scenario))
        shortfall  (:shortfall pos)
        liq-idx    (liquidity-index-metric last-world scenario)]
    (cond-> {}
      pos
      (assoc :yield/position-principal     (long (or (:principal pos) 0))
             :yield/position-unrealized    (long (or (:unrealized-yield pos) 0))
             :yield/position-realized      (long (or (:realized-yield pos) 0))
             :yield/position-gross         (long (+ (or (:principal pos) 0)
                                                    (or (:unrealized-yield pos) 0)))
             :yield/accrual-loss           (long (or (:amount (:yield-loss pos)) 0))
             :yield/position-deferred      (long (or (:deferred-amount shortfall) 0))
             :yield/position-haircut       (long (or (:haircut-amount shortfall) 0))
             :yield/position-reclaimed     (long (or (:reclaimed-amount pos) 0)))
      (:status pos)
      (assoc :yield/position-status (name (:status pos)))
      (or (:reason shortfall) (:reason (:yield-loss pos)))
      (assoc :yield/loss-reason (name (or (:reason shortfall) (:reason (:yield-loss pos)))))
      liq-idx
      (assoc :yield/liquidity-index liq-idx))))

(defn merge-provider-metrics
  [result scenario]
  (update result :metrics merge (compute-provider-metrics result scenario)))
