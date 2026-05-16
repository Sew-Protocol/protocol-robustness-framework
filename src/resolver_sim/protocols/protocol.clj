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
     The shape of the returned map is protocol-defined. A protocol may include
     any metadata fields appropriate to its transition model (e.g. action category,
     resolution path, effect type). Fields that cannot be derived from the action
     name alone (such as accounting effects) should be omitted.
     Return nil for protocols that do not produce transition metadata.")

  (resolve-id-alias [protocol event id-alias-map]
    "Resolve any ID alias in the event params to its entity ID.
     id-alias-map — {alias-string → entity-id} (entity IDs may be any opaque type)

     Called by the replay engine before dispatching each event.  The protocol
     inspects the event params and, if an alias pattern is present, resolves it:
       - If the alias is in id-alias-map → return {:ok true :event event'}
         where event' has the alias replaced with the entity ID.
       - If an alias pattern is present but not in id-alias-map → return
         {:ok false :error :unresolved-alias :alias str :seq n}.
       - If no alias is present → return {:ok true :event event} unchanged.

     Protocols that do not use ID aliases should return {:ok true :event event}.")

  (created-id [protocol action extra]
    "If action just created a new tracked entity and succeeded, return its
     entity ID from the extra map (as returned by dispatch-action), else nil.
     Used by the replay engine to populate the id-alias-map after a successful
     create event annotated with :save-id-as.

     Entity IDs may be any opaque type (integer, UUID, string, etc.).
     Protocols that do not assign IDs to created entities return nil.")

  (open-entities [protocol world]
    "Return a seq of entity IDs that are still open/unresolved at end of scenario.
     The replay engine calls this after the event loop when :allow-open-entities?
     is not set in the scenario map, to detect incomplete runs.
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

     Return #{} for protocols that produce no named metrics beyond the base set.")

  (adversarial-event? [protocol event agent]
    "Return true when the event should be classified as adversarial for metric
     purposes.

     Called by the replay engine for each event to determine :attack-attempts,
     :attack-successes, :rejected-attacks, and to enable the :funds-lost
     calculation in accum-protocol-metrics.

     event — the full event map {:action :params :adversarial? ...}
     agent — the agent map from the scenario's :agents list, keyed by :id,
             or nil when the agent is not found in the scenario.

     The per-event :adversarial? flag (if true) must always return true,
     enabling mixed-role actors to mark individual calls as adversarial.

     Protocols should return false for any event they consider honest, even
     if the calling agent's type/role/strategy would otherwise indicate an
     attack.

     Return false for protocols that do not model adversarial behaviour.")

  (accum-protocol-metrics [protocol metrics event-tags event accepted? attack? world-before world-after]
    "Accumulate protocol-specific metrics for one completed event step.

     Called by the replay engine after base-metric accumulation for each event.

     protocol   — this protocol implementation (ignored by most implementations)
     metrics    — current accumulated metrics map (after base-metric updates)
     event-tags — set of tags returned by classify-event for this event
     event      — the full event map {:action :params ...}
     accepted?  — true when the action result was :ok
     attack?    — true when the engine classified the event as adversarial
                  (per-event :adversarial? flag or agent role/strategy/type)
     world-before — world state snapshot before the event was applied
     world-after  — world state snapshot after the event was applied

     Return the updated metrics map.  Implementations should only update keys
     declared in their metric-vocabulary; updating base-metric keys is an error,
     except for :funds-lost which protocols may update to record value lost to
     accepted adversarial actions (semantics are protocol-defined).

     Protocols that produce no named metrics beyond the base set should return
     metrics unchanged.")

  (trace-projection [protocol result]
    "Return the terminal trace projection for this replay result.

     The projection is a protocol-specific map consumed by
     evaluate-mechanism-properties and evaluate-equilibrium-concepts.
     It should contain at minimum:
       :terminal-world   — terminal state (keys at protocol's discretion)
       :metrics          — the result's :metrics map
       :trace-summary    — {:events-count :actors :dispute-count ...}

     Return nil when the result has no trace (e.g. :outcome :invalid
     with 0 events), or when the protocol does not support projections.")

  (mechanism-property-validators [protocol]
    "Return a map of {keyword → validator-fn} for protocol-specific mechanism
     properties. These are merged with the framework's built-in generic
     validators in evaluate-mechanism-properties.

     Each validator-fn accepts a projection map (as returned by trace-projection)
     and returns a result map with :property :status :severity :basis etc.

     Return {} for protocols that add no mechanism-property validators.")

  (equilibrium-concept-validators [protocol]
    "Return a map of {keyword → validator-fn} for protocol-specific equilibrium
     concepts. These are merged with the framework's built-in generic
     validators in evaluate-equilibrium-concepts.

     Each validator-fn accepts a projection map (as returned by trace-projection)
     and returns a result map with :property :status :severity :basis etc.

     Return {} for protocols that add no equilibrium-concept validators.")

  (protocol-id [protocol]
    "Return a stable string identifier for this protocol implementation.
     Used as a discriminator key in generic persistence (e.g. the protocol_id
     column in sim_trial_results) and for I/O routing.

     Convention: lowercase, hyphenated, version-tagged.
     Examples: \"sew-v1\", \"dummy\", \"my-protocol-v2\".")

  (io-projection [protocol data target-type]
    "Return a protocol-specific I/O projection of `data` for the given target.

     data        — context-dependent input:
                     :world-view       → a world state map
                     :forge-trace      → a replay result map (as from replay-with-protocol)
                     :telemetry-record → a replay result map
                     :event-records    → a map {:trial-id :params :result}
     target-type — one of:
       :world-view       — lean snapshot of world state for gRPC client responses.
                           Returns a flat map, e.g. {:block-time n :entity-count n}.
       :forge-trace      — Forge/Foundry-compatible trace fixture map.
                           Returns a fixture map ready for JSON serialisation.
       :telemetry-record — protocol-specific metrics map for DB persistence.
                           Returns a flat map stored as a blob alongside generic
                           header fields in sim_trial_results.
       :event-records    — vector of entity state-transition event maps for DB
                           persistence in sim_entity_events. Each map must have:
                           {:id :trial-id :entity-id :event-type :entity-state
                            :block-time :valid-from}.
                           Return [] for protocols that do not emit event records.

     Protocols may return nil for target types they do not support.
     Protocols that produce no I/O projections should always return nil.")

  (advisory [protocol world request-type context]
    "Return advisory analysis results for the given request type.

     Provides protocol-specific analysis without executing any state transitions.
     Used by the gRPC server for action suggestion, signal reporting, and
     payoff/objective evaluation.

     world        — the current canonical world state map
     request-type — one of:
       :suggest-actions          — action templates for an actor.
                                   context: {:actor-id string :agent-index map}
                                   Returns: {:active-workflow-ids [...] :suggested-actions [...]}
       :session-signals          — read-only risk/economic signals.
                                   context: {}
                                   Returns: a flat map of protocol-specific signals.
       :evaluate-payoff          — realised payoff projection for an actor.
                                   context: {:actor-id string}
                                   Returns: a flat map of payoff fields.
       :evaluate-attack-objective — adversarial objective score for an actor.
                                   context: {:actor-id string :objective string-or-nil}
                                   Returns: {:score n :decomposition {...}}

     Return {:not-supported true} for request types the protocol does not implement.
     Non-advisory protocols should always return {:not-supported true}.") )
