(ns resolver-sim.protocols.dummy
  "DummyProtocol — minimal always-pass DisputeProtocol implementation.

   A SEW test double used to verify that the DisputeProtocol adapter layer
   compiles, type-checks, and that the generic replay engine machinery (alias
   resolution, metrics, trace shape) does not crash when every protocol method
   is a no-op.

   Behaviour:
   - All actions: succeed without modifying world state.
   - resolve-id-alias: always passes — no alias resolution needed since
     dispatch-action ignores all params.
   - created-id: always nil — no alias tracking.
   - open-disputes: always [] — no open-dispute end-check enforcement.
   - classify-event: always #{} — no lifecycle metrics incremented.
   - Invariant checks: always pass (no invariants enforced).

   This is a test double, not a useful protocol.  Use SEWProtocol for real runs.

   Layering: may import protocols/protocol only.
   Must NOT import contract_model/*, model/*, db/*, io/*."
  (:require [resolver-sim.protocols.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; DummyProtocol implementation
;; ---------------------------------------------------------------------------

(deftype DummyProtocol []
  proto/DisputeProtocol

  (build-execution-context [_ agents _protocol-params]
    {:agent-index (into {} (map (juxt :id identity) agents))})

  (dispatch-action [_ _context world _event]
    {:ok true :world world})

  (check-invariants-single [_ _world]
    {:ok? true :violations nil})

  (check-invariants-transition [_ _world-before _world-after]
    {:ok? true :violations nil})

  (world-snapshot [_ world]
    {:block-time (:block-time world)})

  (init-world [_ scenario]
    {:block-time (get scenario :initial-block-time 1000)})

  (compute-projection [_ _world]
    [nil nil])

  (classify-transition [_ _action _result-kw]
    nil)

  (resolve-id-alias [_ event _id-alias-map]
    {:ok true :event event})

  (created-id [_ _action _extra]
    nil)

  (open-disputes [_ _world]
    [])

  (classify-event [_ _event _result-kw _error-kw]
    #{})

  (metric-vocabulary [_]
    #{}))

;; ---------------------------------------------------------------------------
;; Shared singleton
;; ---------------------------------------------------------------------------

(def protocol
  "Ready-made DummyProtocol instance."
  (DummyProtocol.))
