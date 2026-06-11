(ns resolver-sim.time.context
  "Canonical temporal registry for the simulation world state.

   Provides accessors for temporal properties (block-ts, step) while maintaining
   backward compatibility with the legacy :block-time root key.")

(def default-temporal-schema-version "temporal-context.v1")

(defn temporal-context
  "Return the canonical temporal context from the world.
   Merges legacy :block-time into the canonical structure."
  [world]
  (let [legacy-block-time (:block-time world)]
    (merge
     {:schema-version default-temporal-schema-version
      :step 0
      :clock-source :legacy
      :clock-mode :discrete-step}
     (when legacy-block-time
       {:block-ts legacy-block-time
        :legacy/block-time legacy-block-time})
     (:context/time world))))

(defn block-ts
  "Canonical accessor for block timestamp."
  [world]
  (:block-ts (temporal-context world)))

(defn step
  "Canonical accessor for scenario step."
  [world]
  (:step (temporal-context world)))

(defn with-temporal-context
  "Update world with a new temporal context, maintaining legacy :block-time compatibility."
  [world ctx]
  (let [ctx' (merge (temporal-context world) ctx)
        block-ts' (:block-ts ctx')]
    (cond-> (assoc world :context/time ctx')
      block-ts' (assoc :block-time block-ts'))))
