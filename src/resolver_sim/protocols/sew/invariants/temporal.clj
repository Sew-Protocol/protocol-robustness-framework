(ns resolver-sim.protocols.sew.invariants.temporal
  "Temporal consistency invariants for the Sew contract model."
  (:require [resolver-sim.time.context :as time-ctx]))

(defn check-temporal-consistency
  "Invariant: Ensures :block-time matches the :block-ts in :context/time."
  [world]
  (let [ctx (time-ctx/temporal-context world)
        legacy (:block-time world)
        canon (:block-ts ctx)]
    (if (or (nil? legacy) (= legacy canon))
      {:holds? true}
      {:holds? false :violations [{:error :temporal-drift
                                   :legacy legacy
                                   :canonical canon}]})))
