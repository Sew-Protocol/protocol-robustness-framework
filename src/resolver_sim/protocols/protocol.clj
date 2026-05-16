(ns resolver-sim.protocols.protocol
  "DisputeProtocol — the interface that protocol plugins must implement.

   The replay engine calls these methods during scenario execution.
   Implementations must be pure (no I/O, no DB) and deterministic.

   Usage:
     Implement DisputeProtocol via deftype or defrecord, then pass an instance
     to replay/replay-with-protocol alongside a scenario map. The SEW
     implementation lives in resolver-sim.protocols.sew.

   Layering: this namespace has no dependencies on contract_model/*, model/*,
   sim/*, db/*, or io/*. It serves as the harness interface.")

(defprotocol DisputeProtocol
  "Plugin interface for dispute-resolution protocol implementations.

   This interface provides a harness structure for replay-driven simulation. 
   It is currently instantiated for the SEW Protocol but is designed as a 
   template for future integration of other protocols."

  (build-execution-context [protocol agents protocol-params]
    "Build an execution context from agent list and protocol parameters.
     agents          — [{:id str :address str :role str :strategy str ...}]
     protocol-params — map of protocol-specific config (same shape as
                       :protocol-params in a scenario map)
     Returns a context map passed opaquely to every dispatch-action call.
     Must include :agent-index {agent-id → agent} for replay metrics.")

  (dispatch-action [protocol context world event]
    "Apply one event to the world state.
     context — opaque map returned by build-execution-context
     world   — current world state
     event   — {:seq n :time t :agent str :action str :params {...}}
     Returns {:ok bool :world world' :error kw :extra {...}}.
     Must never throw — all error paths must return {:ok false :error kw}.")

  (check-invariants-single [protocol world]
    "Single-world invariant checks (e.g. solvency, non-negative balances).
     Called after every successful transition on the post-transition world.
     Returns {:ok? bool :violations map-or-nil}.")

  (check-invariants-transition [protocol world-before world-after]
    "Cross-world invariant checks (e.g. terminal-state irreversibility).
     Called after every successful transition with both worlds.
     Returns {:ok? bool :violations map-or-nil}.")

  (world-snapshot [protocol world]
    "Create a lean, serializable map of the world state for trace output.
     Should exclude large internal state and keep only relevant metrics.")

  (init-world [protocol scenario]
    "Return an initial world-state map from the scenario.
     scenario — the full scenario map; the protocol may read :initial-block-time,
                :token-params, :events, and any other fields it needs.
     Returns a world map ready to be passed to dispatch-action.")

  (compute-projection [protocol world]
    "Return [projection projection-hash] for trace output.
     projection      — a lean map of fields comparable against EVM state, or nil.
     projection-hash — SHA-256 of the projection, or nil.
     Return [nil nil] for protocols that do not support differential testing.")

  (classify-transition [protocol action result-kw]
    "Return a :trace-metadata map for a completed transition, or nil.
     action     — the action string that was dispatched
     result-kw  — :ok, :rejected, or :invariant-violated
     Specific fields produced by SEWProtocol:
       :transition/type  — semantic action category (see trace-metadata/transition-types)
       :resolution/path  — resolution route, or :resolution/none
     Note: :effect/type is intentionally absent — accounting effects depend on
     world state at resolution time and cannot be derived from action name alone.
     Return nil for protocols that do not produce transition metadata.")

  (resolve-id-alias [protocol event id-alias-map]
    "Resolve any ID alias in the event params to its integer ID.
     id-alias-map — {alias-string → integer-id}

     Called by the replay engine before dispatching each event.  The protocol
     inspects the event params and, if an alias pattern is present, resolves it:
       - If the alias is in id-alias-map → return {:ok true :event event'}
         where event' has the alias replaced with the integer ID.
       - If an alias pattern is present but not in id-alias-map → return
         {:ok false :error :unresolved-alias :alias str :seq n}.
       - If no alias is present → return {:ok true :event event} unchanged.

     Protocols that do not use ID aliases should return {:ok true :event event}.")

  (created-id [protocol action extra]
    "If action just created a new tracked entity and succeeded, return its
     integer ID from the extra map (as returned by dispatch-action), else nil.
     Used by the replay engine to populate the id-alias-map after a successful
     create event annotated with :save-id-as.

     Protocols that do not assign integer IDs to created entities return nil.")

  (open-disputes [protocol world]
    "Return a seq of entity IDs that are still open/unresolved at end of scenario.
     The replay engine calls this after the event loop when :allow-open-disputes?
     is not set, to detect incomplete runs.
     Return empty seq (or nil) when all entities have reached a terminal state.")

  (classify-event [protocol event result-kw error-kw]
    "Return a set of metric tags for the completed event.
     event      — the full event map {:action :params ...} that was dispatched
     result-kw  — :ok, :rejected, or :invariant-violated
     error-kw   — error keyword when result-kw is :rejected, else nil

     Tags drive metric accumulation in the replay engine's accum-metrics.
     See protocol documentation for the tag vocabulary your implementation uses.

     Return #{} for untagged events.
     Protocols that do not produce lifecycle metrics should always return #{}.") 

  (metric-vocabulary [protocol]
    "Return the set of protocol-specific metric keywords this protocol may produce.

     The replay engine merges this set with its own base-metrics (universal
     counters such as :reverts, :attack-attempts, :invariant-violations) to
     form the full set of valid metric references for scenario validation.

     Declare every keyword that your classify-event implementation may cause to
     be incremented — i.e. every key you expect to see in the :metrics map of
     a replay result, beyond the universal base set.

     Return #{} for protocols that produce no named metrics beyond the base set.") )
