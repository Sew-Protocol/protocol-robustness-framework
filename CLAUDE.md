# How to work in this repository

## What this system is

A **protocol-agnostic adversarial testing framework** for decentralised dispute
resolution, escrow, and coordination protocols — with the **Sew Protocol** as
the main subject of the current simulation work.

Two distinct modes:
- **Statistical model** (`sim/`, `stochastic/`) — Monte Carlo sweeps over protocol
  parameter space; tests falsifiable hypotheses about resolver incentives.
- **Live simulation** (`contract_model/`) — deterministic replay of dispute
  protocol kernels against adversarial strategies; records outcomes to XTDB.

---

## Principles

- Simulation logic is pure; I/O (file and database) is confined to the shell
- Hypotheses are falsifiable — every phase produces a pass/fail result with a
  specific threshold, not a qualitative judgement
- The contract model mirrors the on-chain spec exactly; divergence is a bug
- Params live in EDN files under `data/params/`; results are written to `results/`

---

## Architecture

### Functional core (no I/O, no DB)

```
contract_model/     ← Protocol-agnostic kernel (pure)
  replay.clj          open-world scenario replay engine; agnostic harness
  diff.clj            generic world diffing helpers (moved to protocols/sew for now)
  trace_metadata.clj  generic trace vocabulary (actors, effects, outcomes)

protocols/          ← Adapter interfaces + implementations
  protocol.clj        SimulationAdapter / EconomicModel / AnalysisModule interfaces (pure; no deps)
  dummy.clj           DummyProtocol — always-pass test double
  sew/                Sew domain logic (pure functions)
    state_machine.clj   escrow FSM and allowed-transitions graph
    lifecycle.clj       escrow lifecycle (create, release, cancel, dispute)
    accounting.clj      fee/bond/slashing arithmetic
    resolution.clj      dispute resolution (execute, escalate, settle)
    authority.clj       resolver authority checks
    invariants.clj      protocol post-conditions (30+ invariants)
    types.clj           world-state shape, constructors, accessors
    invariant_runner.clj in-process runner for deterministic scenarios
    invariant_scenarios.clj S01–S41 deterministic scenarios
    runner.clj          top-level live-simulation trial runner

  sew.clj             SewProtocol adapter — wires sew/* into the adapter interfaces

stochastic/         ← statistical/economic models (pure functions)
  economics.clj, dispute.clj, decision_quality.clj, ...

sim/                ← simulation phases (pure sweeps over params)
  phase_o.clj ... phase_ai.clj   one file per Monte Carlo phase
  engine.clj          phase harness: make-result, run-parameter-sweep, print-phase-header
  batch.clj           aggregate N trials into summary statistics
  sweep.clj           parameter-space sweep runner
  fixtures.clj        fixture-based suite runner (run-suite, list-suites, minimise-suite)
  minimizer.clj       trace minimisation (reduce failing trace to 1-minimal subset)
  trajectory.clj      equity/spread/displacement trajectory helpers
  multi_epoch.clj     multi-epoch reputation simulation (Phase J)
  adversarial.clj, waterfall.clj, ...

governance/         ← governance rule models (pure)
adversaries/        ← adversary strategy models (pure)
  strategy.clj        Adversary protocol definition
  ring_attacker.clj   RingAttack adversary for multi-epoch trajectory analysis
oracle/             ← detection models (pure)
```

### Imperative shell (I/O)

```
db/                 ← XTDB persistence for live simulation outcomes
  store.clj           sew_trial_outcomes + sew_escrow_events table ops;
                      summarise-batch (pure aggregate helper)
  telemetry.clj       adapter: protocols/sew/runner output → db writes

io/                 ← file I/O
  params.clj          EDN param loading
  results.clj         result serialisation
  trace_store.clj     trace persistence
  trace_export.clj    trace export helpers
```

### Entry point

```
core.clj            ← CLI dispatch; wires all phase flags
```

---

## Layering rules

These must not be violated as the project grows:

| Namespace | May import | Must NOT import |
|---|---|---|
| `protocols/protocol.clj` | nothing | everything else |
| `contract_model/*` | `protocols/protocol` | anything else |
| `protocols/sew/*` | `protocols/protocol`, `contract_model/*` | `sim/*`, `db/*`, `io/*` |
| `protocols/dummy` | `protocols/protocol` | everything else |
| `scenario/*` | `protocols/protocol` | anything else |
| `stochastic/*` | nothing outside `stochastic/` | everything else |
| `sim/*` | `contract_model/*`, `protocols/*`, `stochastic/*`, `governance/*`, `adversaries/*`, `oracle/*` | `db/*`, `io/*` |
| `governance/*`, `adversaries/*`, `oracle/*` | `stochastic/*` only | `db/*`, `io/*` |
| `db/*` | `contract_model/*`, `protocols/sew/*`, `evaluation.xtdb` | `sim/*` |
| `io/*` | `stochastic/*`, `sim/*` | `db/*` |
| `core.clj` | everything | — |

The key invariant: **the functional core is testable without a running XTDB instance
or filesystem.** `db/` and `io/` are the only namespaces with side effects.

> **`sim/engine.clj` vs `protocols/`** — `sim/engine.clj` is the *phase harness*
> (run-parameter-sweep, make-result, print-phase-header).  `protocols/` is the
> *adapter layer* (SimulationAdapter/EconomicModel/AnalysisModule interfaces + Sew/Dummy implementations).
> These are unrelated; the similar-sounding names are coincidental.

---

## Directory structure — current and intended growth

### Current layout

```
src/resolver_sim/         ← Clojure namespace root (resolver-sim.*)
  contract_model/         Replay engine (protocol-agnostic)
  protocols/              SimulationAdapter interfaces + implementations
    sew/                  Sew domain logic (pure functions)
  scenario/               CDRS v1.1 theory and expectation evaluators
  stochastic/             pure statistical/economic models (~17 files)
  sim/                    simulation phases + phase infrastructure (~38 files)
  governance/             pure governance rule models
  adversaries/            adversary strategy models
  oracle/                 detection models
  economics/              canonical payoff calculations
  canonical/              canonical action vocabulary
  db/                     shell: XTDB persistence
    store.clj               resolver-sim.db.store
    telemetry.clj           resolver-sim.db.telemetry
  io/                     shell: file I/O
    params.clj
    results.clj
  server/                 gRPC server + session management
  core.clj

data/                     ← Static and simulation data
  params/                 Monte Carlo parameter definitions (EDN)
  fixtures/               Deterministic test scenarios and suites (EDN/JSON)
    traces/               Execution traces for replay and regression
```

> **Why `resolver_sim/sim/`?** The outer `resolver_sim/` is the Clojure toolchain
> convention for the `resolver-sim` package root — it cannot be renamed without
> breaking the build.  The inner `sim/` is the `resolver-sim.sim` sub-namespace
> (simulation phases).  They are unrelated despite the similar names.

### Growth triggers and responses

**`sim/` reaches ~50 files** — group by test domain:
```
sim/
  economic/         phases testing fee/profit/incentive hypotheses
  governance/       phases testing governance capture, rule drift
  adversarial/      phases testing attack strategies
  batch.clj, sweep.clj, waterfall.clj, engine.clj   (shared infrastructure)
```

**`stochastic/` develops two distinct sub-domains** — e.g. economic models vs.
adversarial models — split into `stochastic/economic/` and `stochastic/adversarial/`.
Do not split until the sub-domain boundary is clear; a flat `stochastic/` is correct
for ≤20 files.

**New XTDB tables** (governance events, metrics snapshots, etc.) — add new files
in `db/`, e.g. `db/governance.clj`. Do not extend `db/store.clj` indefinitely;
split by table group when `store.clj` exceeds ~200 lines of query logic.

**Protocol Kernel grows** — if the replay kernel expands to support more complex
multi-party interactions, consider splitting `replay.clj` into:
```
contract_model/
  engine/           event loop and transition management
  instrumentation/  metrics and trace collection
  validation/       generic CDRS schema validation
```

---

## Cross-project coupling (eval-engine)

sew-simulation depends on eval-engine as a local dep:
```clojure
og/eval-engine {:local/root "../og/eval-engine"}
```

**Only `resolver-sim.db.*` may import `evaluation.xtdb`** (or its future home
`evaluation.db.xtdb` when eval-engine restructures its own shell layer).

This import must never appear in `contract_model/`, `sim/`, `stochastic/`, or any
other core namespace.

When eval-engine moves `xtdb.clj` → `evaluation/db/xtdb.clj`, update:
- `resolver-sim.db.store` require: `evaluation.xtdb` → `evaluation.db.xtdb`

---

## File map (Highlights)

| File | Role |
|---|---|
| `core.clj` | CLI entry point; dispatch only, no logic |
| `contract_model/replay.clj` | Open-world scenario replay; agnostic harness |
| `protocols/protocol.clj` | `SimulationAdapter` / `EconomicModel` / `AnalysisModule` interfaces |
| `protocols/sew.clj` | `SewProtocol` adapter — wires sew/* logic |
| `scenario/theory.clj` | Game-theoretic claim falsification (v1.1) |
| `scenario/expectations.clj` | Execution-level outcome validation (v1.1) |
| `protocols/sew/invariant_runner.clj` | Sew-specific deterministic test runner |
| `db/store.clj` | XTDB table ops + `summarise-batch` (pure) |
| `sim/phase_*.clj` | One file per simulation phase; entry point `run-phase-*-sweep` |
| `sim/fixtures.clj` | Fixture suite runner: `run-suite`, `list-suites`, `minimise-suite` |
| `sim/minimizer.clj` | Trace minimiser: reduce failing trace to 1-minimal subset |
| `sim/trajectory.clj` | Equity / spread / displacement trajectory helpers |
| `sim/multi_epoch.clj` | Multi-epoch reputation simulation (Phase J backbone) |

---

## Params and results

- Params files live in `data/params/` as EDN; must include full schema (use
  `data/params/phase-o-baseline.edn` as the canonical template)
- Results are written to `results/` (gitignored; use `git add -f` to force-track)
- Docs live in `docs/results/` (also gitignored; force-add when recording findings)

---

## Testing

- **Deterministic invariant suite** (S01–S41, 41 scenarios): `clojure -M:run -- --invariants` (in-process, no gRPC required; ~1 s)
- **Fixture suite runner**: `clojure -M -e "(require '[resolver-sim.sim.fixtures :as f])(f/run-suite :suites/all-invariants)"`
- **gRPC server**: `nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &`
- **Monte Carlo phases** (O–AI): `scripts/monte-carlo/test-all.sh`
- **Single phase**: `clojure -M:run -- -p data/params/<phase>.edn <flags>`
- **Unit tests**: `./scripts/test.sh`
- **Protocol Adapter tests**: `clojure -M:test -e "(require '[resolver-sim.protocols.protocol-adapter-test :as t]) (clojure.test/run-tests 'resolver-sim.protocols.protocol-adapter-test))"`
- Integration tests (`db/telemetry_integration_test.clj`) require XTDB on localhost:5432
- Pass threshold for most hypotheses: **≥80% across all scenarios in a sweep**
