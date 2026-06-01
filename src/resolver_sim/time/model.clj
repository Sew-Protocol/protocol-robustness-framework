(ns resolver-sim.time.model
  "Simulation time model — minimal two-field representation.
   Only block-ts (Unix seconds) and scenario-step are tracked;
   all protocol timeouts and deadlines run off block-ts alone.")

(defn now
  "Return simulation time snapshot from world.
   Falls back to :block-time for backward compatibility."
  [world]
  (let [t (get world :time {})]
    {:block-ts      (or (:block-ts t) (:block-time world) 0)
     :scenario-step (or (:scenario-step t) 0)}))

(defn with-time
  "Persist time snapshot back onto world (and :block-time mirror)."
  [world {:keys [block-ts scenario-step]}]
  (-> world
      (assoc :block-time block-ts)
      (assoc :time {:block-ts      block-ts
                    :scenario-step scenario-step})))
