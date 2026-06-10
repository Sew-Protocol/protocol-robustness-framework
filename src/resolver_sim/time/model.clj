(ns resolver-sim.time.model
  "Simulation time model using java.time.Instant for temporal representation.")

(defn now
  "Return simulation time snapshot from world."
  [world]
  (let [t (get world :time {})]
    {:block-ts      (or (:block-ts t) java.time.Instant/EPOCH)
     :scenario-step (or (:scenario-step t) 0)}))

(defn with-time
  "Persist time snapshot back onto world."
  [world {:keys [block-ts scenario-step]}]
  (assoc world :time {:block-ts      block-ts
                      :scenario-step scenario-step}))

(defn advance
  "Advance the simulation clock by a duration map."
  [world {:keys [seconds steps] :or {seconds 0 steps 0}}]
  (let [current (now world)
        new-ts  (.plusSeconds ^java.time.Instant (:block-ts current) (long seconds))
        new-step (+ (:scenario-step current) (long steps))]
    (when (neg? seconds)
      (throw (ex-info "advance: seconds must be non-negative"
                      {:seconds seconds})))
    (with-time world {:block-ts new-ts :scenario-step new-step})))
