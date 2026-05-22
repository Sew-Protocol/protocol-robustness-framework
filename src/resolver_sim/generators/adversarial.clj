(ns resolver-sim.generators.adversarial
  "Adversarial profile semantics for stateful generation.

   Encodes both action-priority policy and time-shaping policy so profiles
   are more than simple ordering maps.

   Compatibility adapter status:
   - Delegates to protocol-scoped adversarial generators.
   - Sew is the currently wired default provider."
  (:require [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.generators.sew.adversarial :as sew-adv]))

(defn profile-priority
  [profile action]
  (sew-adv/profile-priority profile action))

(defn valid-next-actions
  "Delegates to canonical action validity and applies adversarial profile sort bias."
  [profile protocol context world seq time]
  (case (proto/protocol-id protocol)
    "sew-v1" (sew-adv/valid-next-actions profile protocol context world seq time)
    []))

(defn next-time
  "Return next event timestamp according to profile semantics.
   - :same-block-ordering intentionally keeps same time on odd steps.
   - :timeout-boundary targets pending-settlement deadline at t-1/t/t+1 when known.
   - default increments by 1.

   Compatibility adapter: currently delegates to Sew semantics."
  [profile world prev-time step-idx]
  (sew-adv/next-time profile world prev-time step-idx))
