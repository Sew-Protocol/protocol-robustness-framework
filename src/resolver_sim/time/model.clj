(ns resolver-sim.time.model
  "Simulation time model — minimal two-field representation.
   Only block-ts (Unix seconds) and scenario-step are tracked;
   all protocol timeouts and deadlines run off block-ts alone."
  (:require [resolver-sim.time.context :as time-ctx]))

(defn now
  "Return simulation time snapshot from world.
   Falls back to :block-time for backward compatibility."
  [world]
  (let [t (get world :time {})]
    {:block-ts      (or (:block-ts t) (:block-time world) 0)
     :scenario-step (or (:scenario-step t) 0)}))

(defn with-time
  "Persist time snapshot back onto world.
   Synchronizes with the canonical temporal context root."
  [world {:keys [block-ts scenario-step]}]
  (time-ctx/with-temporal-context
    (assoc world :time {:block-ts      block-ts
                        :scenario-step scenario-step})
    {:block-ts block-ts :step scenario-step}))

(defn advance
  "Advance the simulation clock by a duration map.
   Accepts :seconds (added to block-ts), :blocks (ignored for now),
   :txs (ignored), :epochs (ignored), :steps (added to scenario-step).
   Negative :seconds triggers a validation error.
   Returns an updated world with both :block-time and :time set."
  [world {:keys [seconds steps] :or {seconds 0 steps 0}}]
  (let [current (now world)
        new-ts  (+ (:block-ts current) (long seconds))
        new-step (+ (:scenario-step current) (long steps))]
    (when (neg? seconds)
      (throw (ex-info "advance: seconds must be non-negative"
                      {:seconds seconds})))
    (with-time world {:block-ts new-ts :scenario-step new-step})))
