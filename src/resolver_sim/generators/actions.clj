(ns resolver-sim.generators.actions
  "State-aware action generation using protocol dispatch as the source of truth.

   This namespace is protocol-agnostic orchestration.

   Current provider status:
   - Uses protocol-scoped candidate-template providers.
   - SEW is the currently wired default provider.

   Protocol-specific candidate templates live in protocol-scoped generator
   namespaces."
  (:require [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.generators.sew.actions :as sew-actions]))

(defn- candidate-events
  "Build candidate events for the current world.
   Candidates are validated by protocol dispatch before use."
  [protocol world seq time]
  (case (proto/protocol-id protocol)
    "sew-v1" (sew-actions/candidate-events world seq time)
    []))

(defn valid-next-actions
  "Return protocol-valid next events for this world at seq/time.
   Uses protocol dispatch to validate candidate actions and avoid shadow logic."
  [protocol context world seq time]
  (->> (candidate-events protocol world seq time)
       (filter (fn [ev]
                 (let [r (proto/dispatch-action protocol context world ev)]
                   (:ok r))))
       vec))
