# sew-simulation Architecture

## What this system is

A Monte Carlo + protocol simulation engine validating that honest dispute
resolution incentives dominate malicious strategies in the SEW protocol.
It operates at two levels:

1. **Statistical simulation** (`sim/`, `stochastic/`) — probabilistic phases
   that test incentive properties across parameter spaces.
2. **Live protocol simulation** (`contract_model/`, `protocols/`) — deterministic
   execution of the SEW dispute protocol against adversarial strategies,
   recorded to XTDB.

Results feed engineering and research documentation in `docs/` (overview,
evidence, testing, and challenge artifacts) that drive protocol remediation.

Primary boundary reference for contributors:
- `docs/framework-boundaries.md`

---

## Design principles

- **Functional core, imperative shell**: all protocol and simulation logic is
  pure (no I/O, no DB). Only `io/` (file I/O) and `db/` (XTDB) are effectful.
- **Pure functions, explicit RNG**: randomness is an explicit parameter — same
  seed + params → identical output, byte-for-byte.
- **Pluggable protocol layer**: the replay kernel (`contract_model/replay.clj`)
  is protocol-agnostic. SEW is one implementation of `DisputeProtocol`; other
  protocols can be plugged in without touching the kernel.
- **Reproducibility**: every run is auditable via params EDN + git commit.
- **Transparent economics**: fee, bond, slashing calculations are explicit and
  independently verifiable.

---

## Namespace map

```
src/resolver_sim/

  contract_model/       ← Protocol-agnostic kernel (pure)
    replay.clj            open-world scenario replay engine; delegates all
                          protocol logic to a DisputeProtocol implementation

  protocols/            ← DisputeProtocol interface + implementations (pure)
    protocol.clj          DefProtocol: protocol adapter interface
                            (execution context, dispatch, invariants,
                             projection/metadata, advisory, persistence views)
    dummy.clj             DummyProtocol — always-pass proof-of-concept
    sew.clj               SEWProtocol adapter — wires sew/* into the interface
    sew/                  SEW Protocol implementation (pure)
      state_machine.clj     escrow FSM transitions
      lifecycle.clj         contract lifecycle (create → dispute → resolve)
      accounting.clj        fee/bond/slashing arithmetic
      resolution.clj        DR1/DR2/DR3 resolution logic
      authority.clj         resolver authority checks
      invariants.clj        protocol post-condition checks
      invariants/
        accounting.clj      accounting sub-invariants (FoT, projection hash)
      types.clj             SEW world-state shape, constructors, accessors
      runner.clj            top-level trial runner (live sim)
      invariant_runner.clj  in-process deterministic scenario runner
      invariant_scenarios.clj deterministic scenario definitions
      diff.clj              world-state hashing + EVM diff helpers
      trace_metadata.clj    transition/effect/resolution type vocabulary
      registry.clj          resolver stake/bond registry

  stochastic/           ← statistical/economic models (pure)
    types.clj, rng.clj, economics.clj, dispute.clj
    bribery_markets.clj, contingent_bribery.clj
    correlated_failures.clj, decision_quality.clj
    delegation.clj, difficulty.clj
    escalation_economics.clj, evidence_costs.clj, evidence_spoofing.clj
    information_cascade.clj, liveness_failures.clj
    panel_decision.clj, resolver_ring.clj

  sim/                  ← simulation phases (pure sweeps)
    phase_o.clj … phase_ai.clj   individual hypothesis phases
    engine.clj                   phase harness (make-result, run-parameter-sweep)
    batch.clj, sweep.clj         batch runners
    fixtures.clj                 deterministic suite runner
    minimizer.clj                trace minimisation
    adversarial.clj, waterfall.clj, multi_epoch.clj
    governance_impact.clj, trajectory.clj

  governance/           ← governance rule models (pure)
  adversaries/          ← adversary strategy models (pure)
    strategy.clj
    ring_attacker.clj
  oracle/               ← detection models (pure)

  economics/            ← canonical payoff calculations (pure)
  canonical/            ← canonical action vocabulary (pure)

  db/                   ← imperative shell: XTDB persistence
    store.clj             sim_trial_results + sim_entity_events tables;
                          summarise-outcomes (pure aggregate helper)
    telemetry.clj         adapter: protocols/sew/runner output → db writes

  io/                   ← imperative shell: file I/O
    params.clj            load + validate EDN params
    results.clj           write CSV / EDN / metadata
    trace_store.clj       trace persistence
    trace_export.clj      trace export helpers

  server/               ← gRPC server + session management
  core.clj              ← CLI entry point (imperative shell)
```

---

## Functional core / imperative shell boundary

```
FUNCTIONAL CORE (no I/O, easily testable)
  contract_model/*, protocols/*, stochastic/*, sim/*,
  governance/*, adversaries/*, oracle/*, economics/*, canonical/*

IMPERATIVE SHELL (effectful)
  db/*      — XTDB reads/writes
  io/*      — file reads/writes
  server/*  — gRPC session state
  core.clj  — CLI, wires shell to core
```

**Rule**: namespaces in the functional core must never import `db/*` or `io/*`.
Shell code flows inward; core code never reaches out.

---

## Stable Framework vs Reference Implementation vs Research

This section is the canonical maturity boundary for contributors and external
readers.

### 1) Stable framework substrate (reusable)

Purpose:
- deterministic replay orchestration,
- adapter contract boundaries,
- scenario execution plumbing,
- invariant/reporting scaffolding,
- temporal/state tooling where semantics are protocol-agnostic.

Primary namespaces:
- `contract_model/*`
- `protocols/protocol.clj`
- `protocols/common/*`
- `scenario/*`
- `time/*` (where logic is protocol-neutral)
- infrastructure portions of `server/*`, `db/*`, `io/*`

Rule:
- may define reusable mechanics and contracts,
- must not encode protocol-specific economic semantics.

### 2) Reference implementation (SEW)

Purpose:
- provide a complete, testable protocol implementation using framework
  interfaces,
- serve as the primary reference adapter for architecture and testing patterns.

Primary namespaces:
- `protocols/sew/*`
- SEW-backed adapter providers under `generators/sew/*`, `io/sew/*`
- SEW-integrated policy/accounting modules (including current yield integration)

Rule:
- protocol semantics are owned here (escrow/dispute/claimability/payout/
  authority/bond/resolution semantics),
- reusable output contracts are allowed, but semantic interpretation remains
  SEW-specific unless proven otherwise.

### 3) Research / exploratory modules

Purpose:
- hypothesis testing,
- adversarial exploration,
- phase-specific studies,
- robustness research support.

Typical locations:
- exploratory portions of `sim/*`
- `sim/adversarial/*`
- research-oriented Python workflows under `python/*`

Rule:
- valuable for investigation, but not treated as stable framework API,
- should be labeled or placed to avoid implying production-grade generic
  capability.

### Cross-cutting accounting boundary

The framework can generalize:
- reconciliation mechanics,
- aggregation mechanics,
- drift/conservation reporting contracts,
- read-only projection interfaces.

The framework must not prematurely generalize:
- escrow liability meaning,
- dispute/payout semantics,
- claimability semantics,
- resolver/bond economic semantics,
- protocol-specific solvency interpretation.

Current position:
- `:funds-ledger-view` is a reusable output contract,
- current computation remains SEW-scoped by design.

---

## Layering rules

| Namespace | May import | Must NOT import |
|---|---|---|
| `protocols/protocol.clj` | nothing | everything else |
| `contract_model/*` | `protocols/protocol` | anything else |
| `protocols/sew/*` | `protocols/protocol`, `contract_model/*` | `sim/*`, `db/*`, `io/*` |
| `protocols/dummy` | `protocols/protocol` | everything else |
| `stochastic/*` | nothing outside `stochastic/` | everything else |
| `sim/*` | `contract_model/*`, `protocols/*`, `stochastic/*`, `governance/*`, `adversaries/*`, `oracle/*` | `db/*`, `io/*` |
| `governance/*`, `adversaries/*`, `oracle/*` | `stochastic/*` only | `db/*`, `io/*` |
| `db/*` | `contract_model/*`, `protocols/sew/*`, `evaluation.xtdb` | `sim/*` |
| `io/*` | `stochastic/*`, `sim/*` | `db/*` |
| `core.clj` | everything | — |

**Key invariant**: the functional core is testable without a running XTDB
instance or filesystem. `db/` and `io/` are the only namespaces with
side effects.

---

## Cross-project coupling (eval-engine)

`sew-simulation` depends on `eval-engine` as a local dep:
```clojure
og/eval-engine {:local/root "../og/eval-engine"}
```

Only `resolver-sim.db.*` may import `evaluation.xtdb` (eval-engine's shared
XTDB infrastructure). The rest of the codebase is decoupled from eval-engine.

---

## XTDB persistence layer (`db/`)

Two tables, auto-created by XTDB on first INSERT:

| Table | Purpose |
|---|---|
| `sim_trial_results` | One row per simulation trial — protocol id, outcome, invariants/divergence, params/metrics blobs |
| `sim_entity_events` | One row per entity state transition within a trial — protocol-agnostic event stream with valid-time semantics |

Valid-time semantics: `_valid_from` = simulated block timestamp. Queries with
`FOR VALID_TIME AS OF` reproduce the escrow state at any point in the simulated
chain timeline.

Pass `nil` as datasource to skip all writes — enables offline simulation runs
and unit tests without a live XTDB instance.

---

## Evaluation pipeline (live simulation)

```
data/fixtures/*.edn / *.json
  ↓  sim/fixtures.clj  (load + compose scenario)
Scenario map
  ↓  contract_model/replay.clj  (replay-with-protocol / replay-scenario)
  │     ↓  protocols/sew.clj   (SEWProtocol.dispatch-action)
  │           ↓  protocols/sew/*.clj  (state machine, lifecycle, resolution)
  │     ↓  protocols/sew.clj   (SEWProtocol.check-invariants-*)
  │           ↓  protocols/sew/invariants.clj  (28+ invariant checks)
  │   Trace map (per-step outcomes) returned to caller
  │
  └── db/telemetry.clj  (write to XTDB if ds provided)
```

---

## Namespace growth guidance

### `sim/` — currently ~38 files
When it reaches ~50, group by test domain:
```
sim/
  economic/     phases testing fee/profit/incentive hypotheses
  governance/   phases testing governance capture, rule drift
  adversarial/  phases testing attack strategies
  engine.clj, batch.clj, sweep.clj, waterfall.clj  (shared infrastructure)
```

### `stochastic/` — currently ~17 files
Split into sub-domains only when two distinct areas emerge (e.g.
`stochastic/economic/` vs `stochastic/adversarial/`). Flat is correct for ≤20 files.

### `protocols/sew/` — currently ~13 files
If the SEW kernel expands significantly, consider splitting by concern:
```
protocols/sew/
  core/         state_machine, lifecycle, resolution, authority
  accounting/   accounting, invariants/accounting
  dr3/          registry, runner (DR3-specific logic)
```

### `db/` — currently 2 files
New tables get new files here (e.g. `db/governance.clj`). Do not grow
`store.clj` indefinitely — one file per table group.

---

## Validation phases

The simulation stack includes multiple phase modules and adversarial programs
that are run through `scripts/test.sh`, fixture suites, and research/evidence
workflows. Treat exact phase ranges and counts as moving targets; use the
testing and usage docs as the operational source of truth.

---

## Testing

**Canonical test runner:** `./scripts/test.sh [mode]`

| Mode | What runs | Command |
|---|---|---|
| `all` (default) | unit + invariants + suites | `./scripts/test.sh` |
| `unit` | Clojure unit tests only | `./scripts/test.sh unit` |
| `invariants` | deterministic scenario run (`--invariants`) | `./scripts/test.sh invariants` |
| `suites` | Fixture suites (all-invariants + equilibrium-validation) | `./scripts/test.sh suites` |

Unit test namespaces:
- `test/resolver_sim/protocols/sew/*_test.clj` — SEW state machine, invariants, lifecycle
- `test/resolver_sim/contract_model/replay_bridge_test.clj` — kernel bridge functions
- `test/resolver_sim/protocols/protocol_adapter_test.clj` — SEWProtocol + DummyProtocol parity
- `test/resolver_sim/db/` — XTDB persistence (requires live XTDB on localhost:5432)
