(ns resolver-sim.scenario.yield-metrics
  "Yield-specific metrics derived from replay snapshots for scenario expectations.")

(defn- escrow-owner-path [workflow-id]
  [:sew/escrow (if (number? workflow-id) workflow-id (long workflow-id))])

(defn- yield-position [world workflow-id]
  (get-in world [:yield-positions (escrow-owner-path workflow-id)]))

(defn compute-yield-metrics
  "Extract numeric yield/escrow metrics from the final trace snapshot.

   All amounts are integer base units. Workflow-id defaults to 0."
  [result & {:keys [workflow-id] :or {workflow-id 0}}]
  (let [trace      (:trace result)
        last-world (when (seq trace) (:world (last trace)))
        pos        (yield-position last-world workflow-id)
        shortfall  (:shortfall pos)
        token      (or (:token pos) :USDC)
        claimable  (get-in last-world [:claimable workflow-id])
        buyer      (get-in last-world [:escrow-transfers workflow-id :from])
        recipient  (get-in last-world [:escrow-transfers workflow-id :to])]
    (cond-> {:yield/escrow-principal     (long (or (:principal pos) 0))
             :yield/escrow-unrealized    (long (or (:unrealized-yield pos) 0))
             :yield/escrow-realized      (long (or (:realized-yield pos) 0))
             :yield/accrual-loss         (long (or (:amount (:yield-loss pos)) 0))
             :yield/escrow-deferred      (long (or (:deferred-amount shortfall) 0))
             :yield/escrow-haircut       (long (or (:haircut-amount shortfall) 0))
             :yield/escrow-reclaimed     (long (or (:reclaimed-amount pos) 0))
             :yield/escrow-gross         (long (+ (or (:principal pos) 0)
                                                   (or (:unrealized-yield pos) 0)))
             :escrow/amount-after-fee    (long (or (get-in last-world [:escrow-amounts workflow-id])
                                                  (get-in last-world [:escrow-transfers workflow-id :amount-after-fee])
                                                  0))
             :protocol/fees-usdc         (long (or (get-in last-world [:total-fees token]
                                                            (get-in last-world [:total-fees (name token)]))
                                                  0))
             :buyer/claimable            (long (or (when claimable (get claimable buyer))
                                                  (get claimable (keyword buyer))
                                                  0))
             :recipient/claimable        (long (or (when claimable (get claimable recipient))
                                                  (get claimable (keyword recipient))
                                                  0))}
      (:status pos) (assoc :yield/escrow-status (name (:status pos)))
      (or (:reason shortfall) (:reason (:yield-loss pos)))
      (assoc :yield/loss-reason (name (or (:reason shortfall) (:reason (:yield-loss pos)))))
      (:escrow-state (get-in last-world [:escrow-transfers workflow-id]))
      (assoc :escrow/state (name (:escrow-state (get-in last-world [:escrow-transfers workflow-id]))))
      (get-in last-world [:live-states workflow-id])
      (assoc :escrow/live-state (name (get-in last-world [:live-states workflow-id]))))))

(defn merge-yield-metrics
  [result & opts]
  (update result :metrics merge (apply compute-yield-metrics result opts)))

(def ^:private yield-display-keys
  #{:yield/escrow-principal :yield/escrow-unrealized :yield/escrow-realized
    :yield/escrow-deferred :yield/escrow-haircut :yield/escrow-reclaimed
    :yield/escrow-status})

(defn yield-metric-key?
  "True when k names a yield/* metric.

   Accepts namespaced keywords (`:yield/escrow-principal`), plain strings
   (`\"yield/escrow-principal\"`), and JSON-normalized keyword strings
   (`\":yield/escrow-principal\"`)."
  [k]
  (cond
    (and (keyword? k) (= "yield" (namespace k))) true
    (string? k)
    (let [s (if (and (.startsWith ^String k ":") (> (count k) 1))
              (subs k 1)
              k)]
      (.startsWith ^String s "yield/"))
    :else false))

(defn yield-metric-label
  "Stable display label for a yield metric key."
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn yield-metrics-for-display
  "Compact yield subset for human reports — no world paths."
  [metrics]
  (select-keys metrics yield-display-keys))
