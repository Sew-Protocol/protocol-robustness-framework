# Protocol Robustness Framework

A framework for adversarial multi-actor scenario testing, specializing in the robustness analysis of escrow and dispute-resolution systems.

## What this is

This repository provides a **Protocol Robustness Framework**. It enables adversarial simulation, invariant checking, and deterministic replay to verify that complex state-machine systems (such as escrow and dispute resolution) remain reliable under real-world and adversarial conditions.

It models how multiple actors interact over time — including strategic and adversarial behaviour — and verifies that the protocol maintains critical correctness, safety, and liveness guarantees.

The framework provides reusable building blocks for protocol engineering:
- **Protocol Adapter Interface**: (`src/resolver_sim/protocols/protocol.clj`)
- **Deterministic Replay Harness**: (`src/resolver_sim/contract_model/replay.clj`)
- **Composable Fixture System**: (`data/fixtures/`)

*The repository includes a detailed Sew protocol model as the primary validation target. The Sew adapter (`protocols/sew/*`) is also the main worked example of how to implement the framework interfaces for a new protocol.*

## Why it exists

Traditional smart contract testing often answers:
> “Does this function work?”

Our framework answers:
> “Does the protocol still work when participants behave strategically or adversarially over time?”

For escrow and dispute resolution systems, failures rarely stem from invalid code; they arise from valid actions interacting in unexpected, sequence-dependent ways.

## What it verifies

- **State Machine Correctness**: Transitions are validated against the formal protocol model.
- **Invariant Enforcement**: Critical properties (e.g., bond liquidity, withdrawal safety, fee caps, time-locks) are continuously checked.
- **Accounting Integrity**: Funds are conserved and reconciled across all transitions.
- **Adversarial Liveness**: Detects conditions where assets become stuck due to rational or malicious behaviour.
- **Deterministic Replay**: Scenarios are fully reproducible and replayed step-by-step.
- **Model ↔ EVM Equivalence**: Execution traces are validated against production-grade Solidity implementations.

### Game-theoretic validation scope

- **Trace-end analysis**: Provides proxy validation on realised traces.
- **Consistency**: A `:pass` confirms the observed trace is consistent with claimed protocol properties.
- **Analytical Limits**: Single-trace execution is not a formal proof of safety across all possible information sets. Concepts like Subgame Perfect Equilibrium (SPE) remain `:inconclusive` without supporting deviation evidence.

## Key Features

- **Deterministic Fixture System**: Composable scenario suites for regression testing.
- **Golden Snapshotting**: Detects behavioural drift across protocol changes.
- **Invariant-Driven Testing**: Failures are defined by violated guarantees, not just incorrect outputs.

## Current Status

- **Core Framework**: Robust, in-process deterministic runner operational.
- **Reference Implementation**: Sew Protocol (v1) fully integrated.
- **SPE Equilibrium**: Bounded public-state epsilon-SPE proxy with counterexample generation active. Forward and backward-induction evaluation modes supported.
- **Integration**: Python-based adversarial suite via gRPC bridge.

## Development Workflow

The project uses a multi-agent workspace model with `jj` (Jujutsu):

- **agent-a/b/c/d** — parallel exploration workspaces for independent changes
- **integration** — merge target where all agent workspaces are combined
- **main** — release branch pushed to remote

### Workspace sync process

```bash
# Rebase all agents onto main from their workspaces
jj rebase -d main

# On integration: create a merge commit with all agents
jj new agent-a agent-b agent-c agent-d

# Resolve conflicts, then validate
bb validate
bb test:unit

# Push to main
jj git push -B main -B integration
```

## Quick Start

### 1. Lint and structural validation
```bash
bb validate
```
Runs clj-kondo lint over src, test, notebooks, and dev sources.

### 2. Run unit tests
```bash
bb test:unit
```
Runs all unit tests — framework, Sew protocol, equilibrium, and yield modules.

### 3. Run invariant scenarios (fast, in-process)
```bash
clojure -M:run -- --invariants
```

### 4. Adversarial exploration
```bash
# Start gRPC simulation server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &

# Run failure-mode suite
python3 python/invariant_suite.py
```

### 5. Dispute Resolution Robustness Validation (Monte Carlo)
```bash
# Run all DR phases (Economic Parameters, Corruption, Evidence, Fairness)
./run.sh all

# Or run individual phases
./run.sh phase-f-dr  # Economic parameter validation sweep
./run.sh phase-c-dr  # Corruption economics sweep
./run.sh phase-e-dr  # Evidence integrity sweep
./run.sh phase-m-dr  # Fairness analysis sweep

# Or via direct CLI
clojure -M:run -- --phase-f-dr
clojure -M:run -- --phase-c-dr
clojure -M:run -- --phase-e-dr
clojure -M:run -- --phase-m-dr
```

**Dispute Resolution Phases (22 sub-phases total):**

- **Phase F (Economic Parameters)**: 6 sweeps validating safe parameter zones where malicious EV < 0
  - F1: Detection Probability Sensitivity (0.1–0.9)
  - F2: Bond Size Sweep (0.5×–10.0× escrow)
  - F3: Fee Adequacy (50–1000 bps)
  - F4: Escrow Concentration (100–1M)
  - F5: Multi-resolver Equilibrium (1–100 resolvers)
  - F6: Appeal Window Adequacy (0–10k blocks)

- **Phase C (Corruption Economics)**: 6 sweeps validating cost-of-corruption exceeds profit
  - C1: Bribery Cost Model (0.1×–2.0× escrow)
  - C2: External Collusion (2–50 parties)
  - C3: Layer Escalation Attack (1–5 rounds)
  - C4: Detection Probability Trade-off (2D grid)
  - C5: Profit-Maximizer Lifecycle (slash sweep)
  - C6: Strategic Abstention (timeout penalty sweep)

- **Phase E (Evidence Integrity)**: 6 sweeps validating evidence layer robustness
  - E1: Deadline Enforcement
  - E2: Hash Mismatch Detection
  - E3: Conflicting Evidence Resolution
  - E4: Evidence Bloat Griefing Bounds (1KB–1GB)
  - E5: Yield Accrual During Dispute (0%–10% APY)
  - E6: Evidence Availability Guarantee

- **Phase M (Fairness Analysis)**: 4 sweeps validating procedural fairness
  - M1: Access-to-Justice Validation (95%+ can afford appeals)
  - M2: Asymmetric Information Cost (≤10% asymmetry)
  - M3: Frivolous Appeal Discouragement (70%+ reduction)
  - M4: Expert Availability & Cost (80%+ have supply)


## Documentation
- `docs/README.md` — documentation index
- `docs/SYSTEM_OVERVIEW.md` — narrative overview: two engines, findings, roadmap, technical architecture
- `docs/ROBUSTNESS_FRAMEWORK.md` — adversarial validation and simulation architecture
- `docs/testing/` — validation coverage and status
- `docs/scenarios.md` — scenario index and protocol properties
- `docs/overview/REUSABLE_COMPONENTS.md` — framework harness and adapter components
