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
- **Integration**: Python-based adversarial suite via gRPC bridge.

## Quick Start

### 1. Run canonical validation suite
```bash
./scripts/test.sh all
```
This authoritative entrypoint runs unit tests, generator regression, contract checks, invariant scenarios, and fixture validation suites.

### 2. Run invariant scenarios (fast, in-process)
```bash
clojure -M:run -- --invariants
```

### 3. Adversarial exploration
```bash
# Start gRPC simulation server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &

# Run failure-mode suite
python3 python/invariant_suite.py
```

## Documentation
- `docs/README.md` — documentation index
- `docs/SYSTEM_OVERVIEW.md` — narrative overview: two engines, findings, roadmap, technical architecture
- `docs/ROBUSTNESS_FRAMEWORK.md` — adversarial validation and simulation architecture
- `docs/testing/` — validation coverage and status
- `docs/scenarios.md` — scenario index and protocol properties
- `docs/overview/REUSABLE_COMPONENTS.md` — framework harness and adapter components
