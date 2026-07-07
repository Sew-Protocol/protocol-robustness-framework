(ns resolver-sim.time.context
  "Canonical temporal registry for the simulation world state.

   Provides accessors for temporal properties (block-ts, step, now) while maintaining
   backward compatibility with the legacy :block-time root key.")

(def default-temporal-schema-version "temporal-context.v2")

(def ^:const seconds-per-day
  "Canonical value: 86400. All namespaces computing day-based durations
   should reference this instead of hardcoding 86400."
  86400)

(def ^:const seconds-per-year
  "Canonical value: 365 * seconds-per-day. All namespaces should reference this."
  (* 365 seconds-per-day))

(defn ensure-temporal-context
  "Ensure the world has a valid temporal context. Initializes from legacy :block-time if missing."
  [world]
  (if (:context/time world)
    world
    (let [bt (or (:block-time world) 0)]
      (assoc world :context/time
             {:schema-version default-temporal-schema-version
              :step 0
              :event-seq 0
              :block-ts bt
              :instant (java.time.Instant/ofEpochSecond bt)
              :clock/source :legacy
              :clock/mode :discrete-step
              :tick-seconds seconds-per-day}))))

(defn temporal-context
  "Return the canonical temporal context from the world.
   Falls back to legacy :block-time if context is missing."
  [world]
  (or (:context/time world)
      (let [bt (or (:block-time world) 0)]
        {:schema-version default-temporal-schema-version
         :step 0
         :event-seq 0
         :block-ts bt
         :instant (java.time.Instant/ofEpochSecond bt)
         :clock/source :legacy
         :clock/mode :discrete-step
         :tick-seconds seconds-per-day})))

(defn block-ts
  "Canonical accessor for block timestamp (Unix seconds)."
  [world]
  (:block-ts (temporal-context world)))

(defn step
  "Canonical accessor for scenario step."
  [world]
  (:step (temporal-context world)))

(defn tick-seconds
  "Canonical accessor for the time-context tick rate (seconds per tick).
   All namespaces referencing time durations should use this accessor
   instead of hardcoding seconds-per-day."
  [world]
  (:tick-seconds (temporal-context world) seconds-per-day))

(defn now
  "Canonical accessor for java.time.Instant.
   Used for display, evidence, and future high-precision math."
  [world]
  (:instant (temporal-context world)))

(defn clock-mode
  "Canonical accessor for clock-mode."
  [world]
  (:clock/mode (temporal-context world)))

(defn project-legacy-time
  "Project canonical block-ts back to legacy root :block-time field.
   Used at boundary points for backward compatibility."
  [world]
  (let [bt (block-ts world)]
    (assoc world :block-time bt)))

(defn with-temporal-context
  "Update world with a new temporal context map.
   Maintains internal consistency between block-ts and instant.
   Projects canonical block-ts back to legacy :block-time for backward compatibility."
  [world ctx]
  (let [bt (:block-ts ctx)
        inst (or (:instant ctx) (when bt (java.time.Instant/ofEpochSecond bt)))
        ctx' (cond-> ctx
               (and bt (not (:instant ctx)))    (assoc :instant inst)
               (and inst (not (:block-ts ctx))) (assoc :block-ts (.getEpochSecond ^java.time.Instant inst)))]
    (project-legacy-time (assoc world :context/time ctx'))))

(defn advance-time
  "Atomically advance simulation time and step.
   Accepts :seconds (delta) or :to (absolute timestamp).
   Increments :step by 1. Resets :event-seq to 0 if time advances."
  [world {:keys [seconds to steps] :or {steps 1}}]
  (let [ctx (temporal-context world)
        old-ts (or (:block-ts ctx) 0)
        new-ts (cond
                 to      (if (instance? java.time.Instant to)
                           (.getEpochSecond ^java.time.Instant to)
                           (long to))
                 seconds (+ old-ts (long seconds))
                 :else   old-ts)
        time-advanced? (> new-ts old-ts)
        new-ctx (assoc ctx
                       :block-ts new-ts
                       :instant (java.time.Instant/ofEpochSecond new-ts)
                       :step (+ (or (:step ctx) 0) (long steps))
                       :event-seq (if time-advanced? 0 (inc (or (:event-seq ctx) 0))))]
    (with-temporal-context world new-ctx)))

(defn advance-step
  "Advance the logical step and event sequence counter without moving block-ts.
   Useful for multiple actions within the same block."
  [world]
  (let [ctx (temporal-context world)]
    (with-temporal-context world
      (-> ctx
          (update :event-seq inc)
          (update :step inc)))))

(defn check-temporal-consistency
  "Verify that legacy root matches canonical context.
   Returns {:holds? bool :violations [...]}.
   Used by invariants."
  [world]
  (let [legacy (:block-time world)
        canon  (block-ts world)]
    (if (or (nil? legacy) (= legacy canon))
      {:holds? true}
      {:holds? false :violations [{:error :temporal-drift
                                   :legacy legacy
                                   :canonical canon}]})))
