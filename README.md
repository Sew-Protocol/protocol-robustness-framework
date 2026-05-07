# SEW Protocol Validation Suite

SEW validation implementation built on a protocol-adapter replay harness and deterministic fixture tooling.

## What this is

This repository is the SEW validation implementation. It tests whether SEW dispute-resolution behavior remains reliable under real-world and adversarial conditions.

It models how multiple actors interact over time — including adversarial behaviour — and verifies that the protocol maintains correctness, safety, and liveness.

The codebase also contains reusable building blocks for other dispute-resolution teams:
- a protocol adapter contract (`src/resolver_sim/protocols/protocol.clj`),
- a deterministic replay harness (`src/resolver_sim/contract_model/replay.clj`), and
- a composable fixture system (`data/fixtures/`).

This is intentionally **not** a claim of universal protocol coverage.

## Why it exists

Most smart contract testing answers:

“Does this function work?”

This system answers:

“Does the protocol still work when participants behave strategically or adversarially over time?”

For escrow and dispute resolution systems, failures don’t come from invalid code —
they come from valid actions interacting in unexpected sequences.

## What it verifies

- **State Machine Correctness**: Every transition is validated against the protocol model.
- **Invariant Enforcement**: Critical properties (e.g. bond liquidity, withdrawal safety, fee caps, time-locks) are continuously checked.
- **Accounting Integrity**: Funds are conserved and reconciled across all transitions.
- **Adversarial Liveness**: Detects conditions where funds can become stuck due to rational or malicious behaviour.
- **Deterministic Replay**: All scenarios are reproducible and can be replayed step-by-step.
- **Model ↔ EVM Equivalence (ongoing)**: Execution traces are validated against Solidity implementations.

### Game-theoretic validation scope (important)

- **Trace-end mechanism/equilibrium checks are proxy validations** on realised traces.
- A `:pass` means the observed trace is **consistent** with the claimed property.
- It is **not** a proof that the property holds across all deviations or information sets.
- Concepts such as **subgame-perfect equilibrium (SPE)** can be expected to be
  `:inconclusive` in single-trace mode unless supporting counterfactual/deviation
  evidence is available.

See `docs/trace-end-equilibrium-validation.md` for precise status semantics
(`:pass`, `:fail`, `:inconclusive`, `:not-applicable`) and evidence requirements.

## Key Features

- **Deterministic Fixture System**: Composable scenario suites for regression testing and protocol exploration.
- **Golden Snapshotting**: Detects behavioural drift across protocol changes.
- **Invariant-Driven Testing**: Failures are defined by violated guarantees, not just incorrect outputs.

## Example: Phase Z Discovery

The system identified a critical liquidity reconciliation failure in timeout handling:

- Protocol reached a terminal state
- Underlying balances were not fully reconciled
- Result: hidden loss of funds across multiple escrows

This was reproduced and fixed using deterministic traces:

- Known failure: `data/fixtures/traces/regression/phase-z-known-failure.trace.json`
- Verified fix: `data/fixtures/traces/regression/phase-z-fixed-regression.trace.json`

## Current Status

- ✅ Core invariant suite passing
- ✅ Fixture-based scenario system operational
- ✅ Golden snapshot regression framework active
- ✅ Python ↔ gRPC integration validated

## Grant-ready impact metrics (for applications)

- **44 deterministic trace fixtures** in `data/fixtures/traces/*.trace.json` covering
  happy-path, adversarial, regression, and equilibrium-proxy scenarios.
- **12 adversarial failure-mode scenarios (S24–S35)** implemented in the Python
  invariant suite (`python/invariant_suite.py`, with scenario modules in
  `python/eth_failure_modes.py` and `python/eth_failure_modes_2.py`).

These metrics are intentionally concrete and repository-verifiable.

## Demo links to include (suggested)

- **Grant page:** `docs/giveth.md`
- **Core scenario fixtures:** `data/fixtures/traces/`
- **Equilibrium boundary examples:**
  - `data/fixtures/traces/eq-v3-dominant-strategy-inconclusive.trace.json`
  - `data/fixtures/traces/eq-v6-spe-always-inconclusive.trace.json`
  - `docs/trace-end-equilibrium-validation.md`
- **Adversarial suite entrypoint:** `python/invariant_suite.py`
- **Replay harness + protocol adapter seam:**
  - `src/resolver_sim/contract_model/replay.clj`
  - `src/resolver_sim/protocols/protocol.clj`

## In progress

- **Multi-agent adversarial dynamics** *(active: live-agent runner and failure-mode scenarios implemented; coverage expanding)*
- **Parameter sensitivity + stronger multi-trace equilibrium evidence** *(active: trace-end proxy checks implemented; deeper deviation/multi-trace evidence expanding)*
- **Expanded EVM trace equivalence** *(active: model↔EVM projection/diff workflow implemented; scenario coverage expanding)*

## Program Status Update (2026-06-05)

### 1) Refining multi-agent adversarial dynamics to expand coverage
**Status:** Active, progressing.

- Multi-agent adversarial dynamics are implemented and explicitly tracked as in-progress.
- Baseline coverage includes **12 adversarial failure-mode scenarios (S24–S35)** in the Python invariant suite (`python/invariant_suite.py`, with scenario modules in `python/eth_failure_modes.py` and `python/eth_failure_modes_2.py`).
- Latest evidence summary reports **33/33 scenarios passed** with **0 invariant violations**, while still capturing adversarial attack-success events that expose sequence-level design risk (`docs/evidence/summary.md`).

**Current interpretation:** coverage is already producing meaningful adversarial signal and is being expanded further.

### 2) Strengthening multi-trace equilibrium evidence through deeper deviation analysis
**Status:** Active, partially implemented, boundary acknowledged.

- Trace-end mechanism/equilibrium checks are active as **single-trace proxy validations**.
- `:pass` means the realised trace is consistent with the claimed property; it is not a full equilibrium proof.
- Concepts requiring counterfactual/deviation comparisons across traces (e.g., SPE-style claims) are expected to remain `:inconclusive` without additional multi-trace evidence (`docs/trace-end-equilibrium-validation.md`).

**Current interpretation:** the proxy layer is operational; deeper deviation-based and multi-trace evidence is the main expansion frontier.

### 3) Increasing Model ↔ EVM trace-equivalence coverage
**Status:** Active, workflow in place, coverage expanding.

- Model↔EVM equivalence is ongoing and supported by projection/diff workflow and deterministic fixture traces.
- Solidity-side equivalence validation entrypoint is documented (`forge test --match-test test_trace_equivalence`, run from the protocol repo).
- Scope is intentionally described as expanding scenario coverage rather than complete parity.

**Current interpretation:** equivalence infrastructure is operational and usable; confidence increases as scenario breadth/depth grows.

## Quick Start

### Canonical validation command (recommended)
```bash
./scripts/test.sh all
```

This is the authoritative test entrypoint for this repository. It runs:
- Clojure unit tests
- generator/property regression tests
- cross-layer contract checks (`proto/simulation.proto` ↔ `server/grpc.clj` ↔ `python/sew_sim/grpc_client.py`)
- deterministic invariant scenarios (`--invariants`)
- fixture suites (`all-invariants`, `equilibrium-validation`, `spe-validation`)

### Run invariant validation suite (fast, in-process)
```bash
clojure -M:run -- --invariants
```

### Run fixture suites (deterministic scenarios)
```bash
clojure -M -e "(require '[resolver-sim.sim.fixtures :as f])(f/run-suite :suites/all-invariants)"
```

### Run Python adversarial failure-mode suite
```bash
# Start gRPC simulation server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &

# Run suite
python3 python/invariant_suite.py
```

### Adversarial strategy presets (stake-withdraw / fraud discovery)

These presets are tuned for **attack discovery throughput** rather than minimizing rejected actions.
They use effective-step budgeting so failed probes do not prematurely end a run.

#### Broad discovery preset
```bash
python3 python/adversarial_agent.py \
  --target-effective-steps 40 \
  --max-attempts 300 \
  --guided-ratio 0.65 \
  --rejected-step-weight 0.10
```

#### Deeper exploit-chain preset
```bash
python3 python/adversarial_agent.py \
  --target-effective-steps 80 \
  --max-attempts 800 \
  --guided-ratio 0.80 \
  --rejected-step-weight 0.05
```

Tip: for reproducible runs, add `--seed <int>`.

Convenience aliases are also available via `bb`:

```bash
# Start/verify gRPC server on :50051
bb adv:server

# Presets
bb adv:broad
bb adv:deep

# One-shot helpers (start server, then run preset)
bb adv:all:broad
bb adv:all:deep
```

### Verify Solidity equivalence
```bash
# From sew-protocol repo
forge test --match-test test_trace_equivalence
```

## Documentation
- `data/fixtures/README.md` — fixture composition and schema
- `docs/testing/` — validation coverage and status
- `docs/scenarios.md` — scenario index and protocol properties
- `docs/overview/REUSABLE_COMPONENTS.md` — reusable harness and fixture components

## Positioning

This repository provides:
- A validation layer for the SEW Protocol
- A deterministic replay harness with protocol-adapter seams
- A fixture-driven testing toolkit useful to other protocol teams
