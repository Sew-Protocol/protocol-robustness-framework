# Replay Engine & Dual-Engine Architecture

## 1. Dual-Engine Design

The framework operates two complementary simulation engines that share the same
economic formulas but address fundamentally different questions:

| Aspect | Deterministic Replay | Statistical (Monte Carlo) |
|--------|---------------------|---------------------------|
| **Purpose** | Deterministic executable invariant checking | Economic incentive analysis |
| **Input** | Scenario event sequences | Parameter maps + strategies |
| **RNG** | None (deterministic) | Seeded PRNG (reproducible) |
| **Invariants** | 47 per step | None |
| **Evidence** | Full evidence DAG with signatures | Aggregate statistics |
| **Results** | Per-step trace | Mean/std-dev over N trials |
| **Code** | `contract_model/replay/` | `sim/`, `stochastic/` |
| **Use case** | Bug finding, regression, audit | Parameter sensitivity, game theory |

The two engines are not redundant — they address different threat models.
Deterministic replay demonstrates for the executed trace that a *specific event sequence* does not violate
protocol invariants. Monte Carlo estimates or tests under the modelled distribution that under a *distribution of behaviours*, economic incentives point in the right direction.

*Deterministic replay detects violations on explicit, reproducible event traces. Statistical simulation estimates economic and behavioural properties under declared probabilistic assumptions. Neither result should be generalised beyond its scenario or model coverage without an explicit argument.*

---

## 2. Engine A: Deterministic Replay

### 2.1 Public API

The replay engine lives in `contract_model/replay/` and exposes a single main
entry point:

```clojure
(replay-with-protocol protocol scenario replay-opts?)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `protocol` | `SimulationAdapter` instance | Protocol adapter |
| `scenario` | map | Scenario with events, agents, params |
| `replay-opts` | map (optional) | Flags, evidence chain, profiles |

**Return map:**

```clojure
{:outcome :pass | :fail | :invalid
 :scenario-id  "s01-baseline-happy-path"
 :events-processed 14
 :halt-reason nil | :invariant-violated | ...
 :trace [{:seq, :time, :agent, :action, :params,
           :result, :error, :invariants-ok?, :violations, :world, ...}]
 :metrics {:events-accepted 14, :events-rejected 0, :invariant-violations 0, ...}
 :states {0 -> world-snapshot, 1 -> world-snapshot, ...}
 :world-checkpoints {seq -> pre-decision-world}
 :last-valid-world <world>
 :expected-error-analysis {:expected [] :unexpected [] :missing []}
 :risk-events [...]}
```

Additional entry points:

| Function | Purpose |
|----------|---------|
| `simple-replay` | Lightweight wrapper with relaxed defaults |
| `resume-from-snapshot` | Continue from an existing world for SPE forks |
| `replay-idempotent-same-trace?` | Run twice and verify byte-for-byte equality |
| `validate-scenario` | Validate scenario structure before replay |

### 2.2 Simulation Loop

`replay-with-protocol` orchestrates the full replay:

```
replay-with-protocol
  1. Setup fresh evidence chain context (with-fresh-registry,
     with-fresh-chain-cursor, with-fresh-risk-context)
  2. Merge replay flags from opts + scenario :options + profile defaults
  3. Build metric vocabulary (base + protocol-specific via EconomicModel)
  4. Validate scenario structure (agents, events, params)
  5. Build execution context (proto/build-execution-context)
  6. Initialize world (proto/init-world)
  7. Delegate to execution/run-simulation-loop
  8. Apply world checkpoint policy (trim per flag)
  9. Evaluate expectations (expected-error analysis, theory diagnostics)
  10. Finalize evidence (write diagnostics, optionally sign + timestamp)
  11. Attach risk events
```

The simulation loop (`execution/run-simulation-loop`) processes events one by
one (or in deterministic-batch groups):

```
for each event in events:
  resolve aliases (proto/resolve-id-alias)
  dispatch action (proto/dispatch-action)
  if dispatch fails → reject, continue
  if dispatch succeeds:
    if invariants enabled:
      check single-world invariants (proto/check-invariants-single)
      check transition invariants (proto/check-invariants-transition)
      if any invariant violated:
        revert world to pre-action state
        emit invariant failure evidence
        halt with outcome :fail
    emit transition evidence (before/after worlds, action, result)
    emit invariant attestation evidence (per-step invariant results)
    if AnalysisModule available:
      emit projection evidence (compute-projection hash)
    update metrics
```

### 2.3 Invariant Checking

Two classes of invariants are checked after every action:

**Single-world invariants** — properties that must hold on the world-state
snapshot in isolation. Examples: solvency, fees-non-negative, held-non-negative,
terminal-states-unchanged. These are checked by `check-invariants-single`.

**Transition invariants** — properties that must hold across two consecutive
world states. Examples: time-non-decreasing, no-action-after-finality,
escrow-state-transition-valid. These are checked by
`check-invariants-transition`.

**Violation flow:**

```
dispatch succeeds
  → check-invariants-single(world-after) → :violations?
    → check-invariants-transition(world-before, world-after) → :violations?
      → if violated: revert world, halt, emit invariant-failure evidence
      → if clean: emit transition-evidence + invariant-attestation-evidence
```

Both invariant checks are protocol-provided via the `SimulationAdapter`
interface. The replay engine does not know what specific invariants exist.

### 2.4 Evidence Capture During Replay

Every replay step produces layered evidence records:

| Layer | Evidence | When | Fields |
|-------|----------|------|--------|
| 2 | Transition evidence | After every dispatch | before/after worlds, action, seq, result |
| 2 | Invariant attestation | After every step | per-invariant pass/fail, links to transition hash |
| 6 | Invariant failure | On violation | failing invariant IDs, step index |
| 8 | Projection evidence | When AnalysisModule available | projection hash link |
| 9 | Checkpoint evidence | At decision nodes (raise_dispute, etc.) | world hash links |

At scenario end, the chain is finalized via `chain/finalize-and-attest!`, which
writes the evidence registry, optionally signs with Ed25519 and timestamps via
RFC 3161. These artifacts feed into the evidence DAG (see
`docs/architecture/EVIDENCE_DAG_ARCHITECTURE.md`).

### 2.5 Replay Profiles

Three replay profiles control which features are enabled:

| Profile | Invariants | Expectations | Theory | Temporal | Projections | Checkpoints |
|---------|-----------|-------------|--------|----------|-------------|-------------|
| `:golden-full` | Yes | Yes | Yes | Yes | Yes | Yes |
| `:fast-regression` | Yes | Yes | Deferred | Yes | Limited | No |
| `:minimal` | Yes | No | No | No | No | No |

Flags are resolved from explicit opts, scenario `:options`, and profile defaults
via `replay-flags/resolve-replay-flags`.

### 2.6 Deterministic Batch Mode

When multiple events share the same timestamp (`:deterministic-batch` mode),
the engine groups them and applies a deterministic-first-wins ordering:

1. All events with the same timestamp are collected
2. Protocol's `BatchConflictModel/event-conflict-domains` classifies serialization boundaries
3. Events with non-conflicting domains execute in parallel; conflicting domains
   resolve via deterministic ordering
4. Post-batch invariants check the net world state after all batch events
5. Unknown actions receive the conservative `[:global :unknown]` domain

### 2.7 Idempotency

Eight actions are subject to `apply-once` deduplication (`REPLAY_SENSITIVE_ACTIONS.md`):

| Action | Dedupe key fields |
|--------|-------------------|
| `execute-resolution` | `[action agent workflow-id nil nil event-id]` |
| `propose-fraud-slash` | `[action agent workflow-id slash-id nil event-id]` |
| `challenge-proposed-slash` | `[action agent workflow-id slash-id nil event-id]` |
| `resolve-challenged-slash` | `[action agent workflow-id slash-id nil event-id]` |
| `create_escrow` | `[action agent nil nil nil event-id]` |
| `dispute` | `[action agent workflow-id nil nil event-id]` |
| `cancel` | `[action agent workflow-id nil nil event-id]` |
| `finalize` | `[action agent workflow-id nil nil event-id]` |

Deduplication uses `(action agent workflow-id slash-id hop-scope event-id)` as
the composite key, preventing cross-action collisions.

---

## 3. Engine B: Statistical Simulation (Monte Carlo)

### 3.1 Single Trial Model

Each Monte Carlo trial simulates one dispute resolution with RNG-driven outcomes:

```clojure
(resolve-dispute rng params)
;; => {:honest-ev 142.0 :malicious-ev 201.0
;;     :outcome :release :slash-amount 0, ...}
```

A trial involves:
1. Draw random detection roll (oracle detection probability)
2. Determine verdict correctness (resolver skill + noise)
3. Compute fee income and bond loss
4. Compute payoff for honest and malicious strategies
5. Return expected value comparison

Key difference from deterministic replay: no step-by-step state machine, no
invariant checking, no evidence chain. The MC model uses a simplified
closed-form payoff calculation.

### 3.2 Batch Runner

The batch runner (`sim/batch.clj`) runs N trials and aggregates results:

```clojure
(run-batch rng params n-trials)
;; => {:n 10000 :honest-mean 142 :malicious-mean 201
;;     :dominance-ratio 0.71 :pass? false, ...}
```

With early stopping: when 80% confidence threshold is met, the batch stops
early without running all N trials.

### 3.3 Parameter Sweeps

The sweep runner (`sim/sweep.clj`) varies one or more parameters across a grid:

```clojure
(run-parameter-sweep rng base-params sweep-spec)
;; => [{:fee-bps 100 :dominance-ratio 1.2 :pass? true}
;;     {:fee-bps 200 :dominance-ratio 0.9 :pass? false}
;;     ...]
```

The primary question is dominance: `EV(honest) / EV(malicious) > 1.0`.
A sweep pass threshold is normally ≥ 80% of trials satisfying dominance.

### 3.4 Phase System

Statistical simulations are organized into phases (`core/phases.clj`):

| Phase category | Examples | Evidence class |
|----------------|----------|---------------|
| Adversarial | `--adversarial` (hill-climb) | `:protocol-kernel-evidence` |
| Waterfall | `--waterfall` (pool solvency) | `:protocol-kernel-evidence` |
| Multi-epoch | `--multi-epoch` (reputation) | `:protocol-kernel-evidence` |
| Governance | `--governance` (delay/impact) | `:protocol-kernel-evidence` |
| Analytic | `--phase-f` (closed-form math) | `:analytic` |
| Exploratory | `--phase-ae` (attack research) | `:exploratory` |

Each phase is registered in `phase-runners` map mapping CLI flags to runner
functions. Evidence classification (`:protocol-kernel-evidence` vs `:analytic`
vs `:exploratory`) determines which phases are included in evidence packs.

### 3.5 Key Monte Carlo Modules

| Module | What it models |
|--------|---------------|
| `stochastic/dispute.clj` | Resolution outcomes, appeal dynamics |
| `stochastic/detection.clj` | Oracle/detection probability, slash detection |
| `stochastic/economics.clj` | Fee/bond/EV calculations |
| `stochastic/decision_quality.clj` | Verdict accuracy vs time pressure |
| `stochastic/evidence_costs.clj` | Effort budget, verification costs |
| `stochastic/liveness_failures.clj` | Juror boredom, adverse selection |
| `stochastic/correlated_failures.clj` | Shared bias, herding |
| `sim/waterfall.clj` | Insurance pool solvency |
| `sim/multi_epoch.clj` | Reputation decay, Sybil, governance |
| `sim/governance_delay.clj` | Response time sensitivity |
| `sim/governance_impact.clj` | Governance capture modelling |
| `sim/adversarial.clj` | Hill-climb adversarial search |
| `sim/stochastic_equilibrium.clj` | Equilibrium stability |
| `sim/defection.clj` | Rational defection dynamics |

---

## 4. How the Two Engines Relate

### 4.1 Shared Arithmetic

The engines share fee, bond, slashing, and appeal-bond formulas via
`stochastic/economics.clj`. Cross-engine calibration tests (over 700
assertions) verify that both engines produce identical arithmetic results
for the same inputs.

### 4.2 Complementary Threat Models

| Question | Engine |
|----------|--------|
| Can this specific attack succeed? | Deterministic replay |
| Is fraud economically rational on average? | Monte Carlo |
| Does the state machine follow the spec? | Deterministic replay |
| Does governance capture become viable at scale? | Monte Carlo |
| Are funds conserved across all transitions? | Deterministic replay |
| Do honest resolvers out-earn malicious ones? | Monte Carlo |

### 4.3 Calibration Link

The Monte Carlo model uses `fraud-success-rate` (default 0.0) calibrated from
deterministic adversarial scenario results. The adversarial suite's 41 attack
scenarios produce an effective fraud-success-rate bound that feeds into MC
parameter selection.

At baseline params: detection must be ≥ 70% or bond 21× current to deter fraud through incentives alone. No successful fraud was observed in the covered adversarial scenarios for the 41 modelled attack vectors — making the state machine the load-bearing mechanism for economic security.

---

## 5. Adapter Interface Design

The replay engine interacts with all protocols through a three-tier interface
system defined in `protocols/protocol.clj`. Interfaces are detected at runtime
via `satisfies?` — protocols opt in to features they support.

### 5.1 SimulationAdapter (Mandatory)

11 methods required for deterministic replay. The replay engine knows nothing
about protocol semantics — all domain logic is injected through this interface.

| Method | Purpose |
|--------|---------|
| `protocol-id` | Stable string identifier |
| `init-world` | Construct initial world from scenario map |
| `build-execution-context` | Build opaque context for dispatch |
| `dispatch-action` | Apply one event to world |
| `check-invariants-single` | Single-world invariant checks |
| `check-invariants-transition` | Cross-transition invariant checks |
| `world-snapshot` | Serializable world projection for trace |
| `available-actions` | Valid actions for an actor |
| `resolve-id-alias` | Resolve entity ID aliases in events |
| `created-id` | Extract entity ID from create action |
| `open-entities` | Unresolved entities at scenario end |
| `project-state` | Query world via protocol-specific projection |

### 5.2 EconomicModel (Optional)

6 methods for adversarial metrics and payoff analysis. The engine degrades
gracefully when absent.

| Method | Purpose |
|--------|---------|
| `adversarial-event?` | Classify event as adversarial |
| `classify-event` | Tag event with metric labels |
| `metric-vocabulary` | Protocol-specific metric keywords |
| `accum-protocol-metrics` | Update metrics after each event |
| `summarise-batch` | Summary stats over trial outcomes |
| `advisory` | Protocol-specific analysis |

### 5.3 AnalysisModule (Optional)

7 methods for differential testing, formal projections, and mechanism
validation.

| Method | Purpose |
|--------|---------|
| `compute-projection` | Protocol projection hash |
| `classify-transition` | Trace metadata for transition |
| `trace-projection` | Terminal trace projection |
| `io-projection` | Protocol I/O view (e.g. `:funds-ledger-view`) |
| `mechanism-property-validators` | Mechanism-specific validators |
| `equilibrium-concept-validators` | Equilibrium validators |
| `reference-model` | Idealized reference result |

### 5.4 BatchConflictModel (Optional)

1 method for deterministic batch ordering.

| Method | Purpose |
|--------|---------|
| `event-conflict-domains` | Classify serialization boundaries |

### 5.5 Protocol Registry

Protocols register in `protocols/registry.clj`:

```clojure
{"sew-v1"   resolver-sim.protocols.sew/protocol
 "yield-v1" resolver-sim.protocols.yield/protocol
 "dummy"    resolver-sim.protocols.dummy/protocol}
```

The registry uses `requiring-resolve` for lazy loading.

---

## 6. Namespace Architecture

### 6.1 Layering

```
core.clj                     ← CLI entry point (impure shell)
  ├── io/scenario_runner.clj ← Scenario loading, dispatch, reporting (impure)
  ├── core/phases.clj        ← Phase registration, I/O orchestration (impure)
  │
  ├── contract_model/replay/ ← Deterministic replay engine
  │   ├── replay.clj         ← Public API, orchestration (contains some I/O)
  │   ├── execution.clj      ← Simulation loop, step processing (pure-ish)
  │   ├── flags.clj          ← Replay profile resolution (pure)
  │   ├── checkpoints.clj    ← World checkpoint management (pure)
  │   ├── metrics.clj        ← Metric accumulation (pure)
  │   ├── analysis.clj       ← Expected error analysis (pure)
  │   ├── validation.clj     ← Scenario validation (pure)
  │   ├── temporal.clj       ← Temporal rule evaluation (pure)
  │   └── yield.clj          ← Yield-v1 replay (contains some I/O)
  │
  ├── sim/                   ← Statistical/Monte Carlo engine
  │   ├── batch.clj          ← Batch runner, aggregation (pure)
  │   ├── engine.clj         ← Result schema, sweep runner (pure)
  │   ├── sweep.clj          ← Parameter sweeps (pure)
  │   ├── fixtures.clj       ← Fixture suite runner (pure)
  │   ├── minimizer.clj      ← Trace minimization (pure)
  │   ├── adversarial.clj    ← Hill-climb search (pure)
  │   ├── waterfall.clj      ← Pool solvency (pure)
  │   ├── multi_epoch.clj    ← Reputation simulation (pure)
  │   └── ...                ← Phase-specific modules
  │
  ├── stochastic/            ← Statistical model functions (pure)
  │   ├── dispute.clj        ← Single trial resolution
  │   ├── detection.clj      ← Oracle/detection models
  │   ├── economics.clj      ← Fee/bond calculations
  │   └── ...
  │
  ├── protocols/             ← Adapter interfaces + implementations
  │   ├── protocol.clj       ← defprotocol definitions (pure)
  │   ├── registry.clj       ← Protocol lookup (side-effectful)
  │   ├── sew.clj            ← SewProtocol adapter
  │   ├── dummy.clj          ← DummyProtocol test double
  │   └── sew/*              ← Sew domain logic (pure)
  │
  ├── evidence/              ← Evidence chain + DAG (impure)
  ├── db/                    ← XTDB persistence (impure shell)
  ├── io/                    ← File I/O (impure shell)
  └── server/                ← gRPC server (impure shell)
```

### 6.2 Purity Constraints

The key invariant: the functional core (replay engine + MC engine + protocol
domain logic) is fully testable without a running database or filesystem.

| Layer | Purity | Testable without I/O? |
|-------|--------|-----------------------|
| `protocols/sew/*` | Pure | Yes |
| `contract_model/replay/execution.clj` | Pure-ish | Yes (evidence is best-effort) |
| `contract_model/replay/flags.clj` | Pure | Yes |
| `contract_model/replay/checkpoints.clj` | Pure | Yes |
| `contract_model/replay/metrics.clj` | Pure | Yes |
| `contract_model/replay/analysis.clj` | Pure | Yes |
| `contract_model/replay/validation.clj` | Pure | Yes |
| `contract_model/replay/temporal.clj` | Pure | Yes |
| `sim/*` | Pure | Yes |
| `stochastic/*` | Pure | Yes |
| `contract_model/replay/replay.clj` | Contains I/O | Partially |
| `io/scenario_runner.clj` | Impure | No |
| `evidence/chain.clj` | Impure | No |
| `db/*` | Impure | No |

---

## 7. CLI Dispatch

The CLI entry point (`resolver-sim.core/-main`) dispatches between engines:

```
-main
  ├── --invariants      → scenario-runner/run-and-report (deterministic replay)
  ├── --serve           → gRPC server
  ├── --diff-traces     → diff-runner
  └── <phase flag> or default
       ├── --adversarial   → hill-climb search (MC)
       ├── --sweep         → parameter sweep (MC)
       ├── --multi-epoch   → reputation simulation (MC)
       ├── --waterfall     → pool solvency (MC)
       ├── --governance    → governance impact (MC)
       ├── --phase-*       → named phase runner (MC or analytic)
       └── (default)       → run-simulation batch (MC)
```

The invariant branch routes through `scenario-runner/run-and-report` which:

1. Loads scenarios from CLI args or suite manifests
2. Resolves the protocol adapter
3. Creates a protocol-specific replay function via `replay-fn-for-protocol`
   (sew-v1 → `replay-with-protocol`, yield-v1 → `replay-yield-scenario`)
4. Runs each scenario through the replay function
5. Aggregates results, writes artifacts, validates the evidence chain

Phase branches route through `core/phases.clj` phase-runners map, which
associates CLI keywords with runner functions. Evidence class annotation
determines inclusion in evidence packs.

---

## 8. Key Design Decisions

### 8.1 Protocol-Agnostic Kernel

The replay engine knows nothing about protocol semantics. All domain logic is
injected through `SimulationAdapter`. This means any protocol implementing the
interface gets deterministic replay, invariant checking, and evidence chain
integration without engine changes.

### 8.2 Runtime Interface Detection

`satisfies?` over compile-time protocols. Features are detected at runtime,
allowing protocols to gradually adopt optional interfaces without breaking
changes to the engine.

### 8.3 Evidence chain and Evidence DAG integration

Every replay step emits hashed evidence records linked by content hashes.
At scenario end, the chain is finalized with optional cryptographic signatures
and RFC 3161 timestamps. This makes replay output independently verifiable.

### 8.4 Separation of I/O from Computation

The intended architecture is a pure replay core with I/O at the caller level.
Currently `replay-with-protocol` writes theory diagnostics to disk (a known
deviation documented in a TODO comment at `replay.clj:200`).

### 8.5 Deterministic Batch Ordering

Same-timestamp events use conflict-domain classification for deterministic
ordering. Unknown actions get the conservative `[:global :unknown]` domain.

### 8.6 Two-Tier Metrics

Base metrics (event counts, acceptance, invariant violations) are engine-owned.
Protocol-specific metrics via `EconomicModel/accum-protocol-metrics` extend the
set. The `:metrics-profile` flag selects the accumulation path.

### 8.7 Three Replay Profiles

Profiles (`:golden-full`, `:fast-regression`, `:minimal`) allow trading
thoroughness for speed depending on context (CI, development, library use).

### 8.8 MC Engine Uses Simplified Model

The Monte Carlo engine does not run the protocol state machine. It uses
closed-form stochastic models of dispute resolution. This is a deliberate
simplification: the MC engine measures *directional incentive alignment*, not
*mechanistic protocol adherence*. The deterministic engine covers the latter.

---

## 9. References

- `src/resolver_sim/contract_model/replay.clj` — Replay engine public API
- `src/resolver_sim/contract_model/replay/execution.clj` — Simulation loop
- `src/resolver_sim/contract_model/replay/flags.clj` — Replay profile resolution
- `src/resolver_sim/contract_model/replay/checkpoints.clj` — World checkpoints
- `src/resolver_sim/protocols/protocol.clj` — Adapter interface definitions
- `src/resolver_sim/protocols/registry.clj` — Protocol adapter registry
- `src/resolver_sim/core/phases.clj` — Phase registration and dispatch
- `src/resolver_sim/core.clj` — CLI entry point
- `src/resolver_sim/io/scenario_runner.clj` — Scenario orchestration
- `src/resolver_sim/sim/batch.clj` — MC batch runner
- `src/resolver_sim/sim/engine.clj` — MC result schema
- `src/resolver_sim/stochastic/dispute.clj` — Single MC trial
- `src/resolver_sim/stochastic/economics.clj` — Shared formula layer
- `docs/architecture/ARCHITECTURE.md` — Overall namespace and layer map
- `docs/architecture/EVIDENCE_DAG_ARCHITECTURE.md` — Evidence DAG architecture
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` — Evidence chain architecture
- `docs/architecture/framework-boundaries.md` — Framework vs adapter vs protocol boundaries
- `docs/architecture/ADAPTER_AUTHORING_GUIDE.md` — Protocol adapter authoring guide
- `docs/replay/REPLAY_SENSITIVE_ACTIONS.md` — Idempotency specification
- `docs/replay/DEVIATION_EVENT_POLICY.md` — SPE counterfactual event policy
- `docs/ROBUSTNESS_FRAMEWORK.md` — Invariant catalogue and scenario suite
- `docs/SYSTEM_OVERVIEW.md` — Plain-language engine overview
