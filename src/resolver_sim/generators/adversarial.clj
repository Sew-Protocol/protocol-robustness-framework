(ns resolver-sim.generators.adversarial
  "Adversarial profile semantics for stateful generation.

   Encodes both action-priority policy and time-shaping policy so profiles
   are more than simple ordering maps.

   Provider status (2026-06):
   ┌──────────────────────┬─────────────────────────────┬──────────────────────┐
   │ Function             │ Dispatch model               │ Wired provider       │
   ├──────────────────────┼─────────────────────────────┼──────────────────────┤
   │ profile-priority     │ unconditional Sew delegate   │ sew-v1 only          │
   │ valid-next-actions   │ case on proto/protocol-id    │ sew-v1 only          │
   │ next-time            │ unconditional Sew delegate   │ sew-v1 only          │
   └──────────────────────┴─────────────────────────────┴──────────────────────┘

   profile-priority and next-time delegate unconditionally — non-Sew protocols
   receive Sew semantics. This is safe only because no second protocol currently
   exercises this path with a different profile model. To add a new protocol,
   add a case dispatch and a protocol-scoped implementation."
  (:require [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.generators.sew.adversarial :as sew-adv]))

(defn profile-priority
  ;; NOTE: unconditional Sew delegate — no protocol dispatch.
  ;; Non-Sew protocols receive Sew priority semantics.
  [profile action]
  (sew-adv/profile-priority profile action))

(defn valid-next-actions
  "Delegates to canonical action validity and applies adversarial profile sort bias."
  [profile protocol context world seq time]
  ;; Protocol dispatch: add a case branch for each supported protocol.
  ;; Non-wired protocols return [] (no valid actions).
  (case (proto/protocol-id protocol)
    "sew-v1" (sew-adv/valid-next-actions profile protocol context world seq time)
    []))

(defn next-time
  "Return next event timestamp according to profile semantics.
   - :same-block-ordering intentionally keeps same time on odd steps.
   - :timeout-boundary targets pending-settlement deadline at t-1/t/t+1 when known.
   - default increments by 1.

   Compatibility adapter: currently delegates to Sew semantics."
  ;; NOTE: unconditional Sew delegate — no protocol dispatch.
  ;; Non-Sew protocols receive Sew time-shaping semantics.
  [profile world prev-time step-idx]
  (sew-adv/next-time profile world prev-time step-idx))
