(ns resolver-sim.time.model
  "Explicit simulation time model utilities.")

(defn now
  "Return normalized simulation time map from world.
   Falls back to :block-time for backward compatibility."
  [world]
  (let [t (:time world)
        block-ts (or (:block-ts t) (:block-time world) 0)]
    {:time/wall-clock-ms (or (:wall-clock-ms t) (* 1000 block-ts))
     :time/block-number  (or (:block-number t) 0)
     :time/block-ts      block-ts
     :time/tx-index      (or (:tx-index t) 0)
     :time/epoch         (or (:epoch t) 0)
     :time/scenario-step (or (:scenario-step t) 0)}))

(defn with-time
  "Persist normalized time map back onto world (and :block-time mirror)."
  [world t]
  (let [wall-clock-ms (get t :time/wall-clock-ms)
        block-number  (get t :time/block-number)
        block-ts      (get t :time/block-ts)
        tx-index      (get t :time/tx-index)
        epoch         (get t :time/epoch)
        scenario-step (get t :time/scenario-step)]
  (-> world
      (assoc :block-time block-ts)
      (assoc :time {:wall-clock-ms wall-clock-ms
                    :block-number block-number
                    :block-ts block-ts
                    :tx-index tx-index
                    :epoch epoch
                    :scenario-step scenario-step}))))

(defn advance
  "Advance time domains by explicit deltas.
   Negative deltas are rejected." 
  [world {:keys [seconds blocks txs epochs steps]
          :or   {seconds 0 blocks 0 txs 0 epochs 0 steps 1}}]
  (when (some neg? [seconds blocks txs epochs steps])
    (throw (ex-info "Time deltas must be non-negative"
                    {:seconds seconds :blocks blocks :txs txs :epochs epochs :steps steps})))
  (let [n (now world)
        wall-clock-ms (get n :time/wall-clock-ms)
        block-number  (get n :time/block-number)
        block-ts      (get n :time/block-ts)
        tx-index      (get n :time/tx-index)
        epoch         (get n :time/epoch)
        scenario-step (get n :time/scenario-step)
        block-ts' (+ block-ts seconds)]
    (with-time world
      {:time/wall-clock-ms (+ wall-clock-ms (* 1000 seconds))
       :time/block-number  (+ block-number blocks)
       :time/block-ts      block-ts'
       :time/tx-index      (+ tx-index txs)
       :time/epoch         (+ epoch epochs)
       :time/scenario-step (+ scenario-step steps)})))
