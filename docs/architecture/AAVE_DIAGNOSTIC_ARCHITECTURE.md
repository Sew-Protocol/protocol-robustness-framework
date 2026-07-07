# Aave Diagnostic Architecture

Data flow through the diagnostic pipeline, from scenario execution to diagnostic output.

```
                  AAVE DIAGNOSTIC DATA FLOW
                  ==========================

  SCENARIO INPUT          EXECUTION              INVARIANTS              EVIDENCE              DIAGNOSTIC
                                                                                                OUTPUT

 ┌─────────────────┐   ┌──────────────┐   ┌─────────────────┐   ┌────────────────┐   ┌─────────────────────┐
 │ S68 / S78 / S79 │──▶│ Replay       │──▶│ Position        │──▶│ Capture        │──▶│ Evidence Registry   │
 │ S80 / S112      │   │ Engine       │   │ Consistency     │   │ at event       │   │ (indexed by         │
 │ (JSON scenarios)│   │              │   ├─────────────────┤   │ boundary       │   │  event / group /    │
 └─────────────────┘   │ replay-      │   │ Exposure        │   └────────────────┘   │  subject / type /   │
                       │ yield-       │   ├─────────────────┤           │            │  layer)              │
 ┌─────────────────┐   │ scenario     │   │ Shortfall       │           ▼            ├─────────────────────┤
 │ Yield Presets   │   └──────────────┘   │ Splits          │   ┌────────────────┐   │ Scenario Report     │
 │ aave-baseline   │           │          ├─────────────────┤   │ Yield Evidence │──▶├─────────────────────┤
 │ shortfall-      │           ▼          │ Status FSM      │   │ (shortfall     │   │ Triage              │
 │ partial         │   ┌──────────────┐   │ (active →       │   │  events,       │   ├─────────────────────┤
 │ oracle-stale-   │──▶│ Yield        │──▶│  unwinding →    │   │  recognized    │   │ Yield Classification│
 │ aave            │   │ Protocol     │   │  withdrawn)     │   │  losses)       │   │ (4 axes: enablement │
 └─────────────────┘   │ Adapter      │   ├─────────────────┤   └────────────────┘   │  / module / risk /  │
                       │ :aave-v3     │   │ Partial-        │           │            │  invariant profile) │
 ┌─────────────────┐   │ defaults     │   │ Liquidity       │           ▼            ├─────────────────────┤
 │ Config          │   └──────────────┘   │ Principal       │   ┌────────────────┐   │ Yield Metrics       │
 │ evidence.json   │           │          ├─────────────────┤   │ Evidence Chain │──▶├─────────────────────┤
 │ suite manifests │           ▼          │ Value           │   │ (audit trail   │   │ Partial Liquidity   │
 └─────────────────┘   ┌──────────────┐   │ Conservation    │   │  linking)      │   │ Metrics             │
                       │ Liquid       │   ├─────────────────┤   └────────────────┘   ├─────────────────────┤
                       │ Lending      │   │ Deferred        │           │            │ Coverage            │
                       │ Module       │   │ Reclaim         │           ▼            ├─────────────────────┤
                       │ (Aave v3     │   └─────────────────┘   ┌────────────────┐   │ Validation Root     │
                       │  archetype)  │              │          │ Evidence       │   │ / State / Suite     │
                       │ deposit      │              ▼          │ Config         │   └─────────────────────┘
                       │ accrue       │   ┌─────────────────┐   └────────────────┘              │
                       │ withdraw     │   │ Index Monotone  │                                  ▼
                       │ emergency-   │   │ (cross-step     │            ┌──────────────────────────────────────┐
                       │ unwind       │   │  transition     │            │         DIAGNOSTIC ARTIFACTS           │
                       │ claim-       │   │  invariant)     │            │  ┌────────┐ ┌────────┐ ┌─────────────┐ │
                       │ deferred     │   └─────────────────┘            │  │Reports │ │Traces  │ │ Golden      │ │
                       └──────────────┘                                 │  │.edn    │ │.json   │ │ Fixtures    │ │
                                                                        │  └────────┘ └────────┘ └─────────────┘ │
                                                                        │  ┌────────┐ ┌────────────────────────┐ │
                                                                        │  │Clerk   │ │ Suite Verification     │ │
                                                                        │  │Notebooks│ │ (expectations, claims) │ │
                                                                        │  └────────┘ └────────────────────────┘ │
                                                                        └──────────────────────────────────────┘
```

---

## Component Reference

### Layer 1: Scenario Inputs (what gets diagnosed)

| Component | Path | Role |
|-----------|------|------|
| Aave JSON scenarios (S68/S78/S79/S80/S112) | `scenarios/` | Executable diagnostic cases: 10yr accrual, partial liquidity, dispute, governance disable, recovery |
| Yield provider scenarios (Y01-Y07) | `scenarios/yield/` | Vault-centric diagnostic cases: shared liquidity, shortfall, risk override, recovery lifecycle |
| Yield presets | `src/resolver_sim/yield/presets.clj` | Plugged into scenarios; defines initial APY, liquidity, oracle state for Aave |
| Config | `config/evidence.json`, `suites/` | Evidence chain config, suite manifest with claims and expectations |

### Layer 2: Execution Engine (generates diagnostic data)

| Component | Path | Diagnostic Relevance |
|-----------|------|---------------------|
| `replay-yield-scenario` | `contract_model/replay.clj` | Deterministic step-by-step execution; each step is a diagnostic data point |
| `YieldProviderProtocol` | `protocols/yield.clj` | Adapter; defaults to `:aave-v3`; constructs world, dispatches actions, snapshots state |
| Liquid-lending module | `yield/modules/liquid_lending.clj` | Aave v3 archetype; each operation (deposit/accrue/withdraw/unwind) is an observable diagnostic event |

### Layer 3: Invariant Checks (diagnostic signals per step)

| Component | Path | What It Detects |
|-----------|------|----------------|
| `position-consistency` | `yield/invariants.clj` | Position state corruptions |
| `exposure` | `yield/invariants.clj` | Wrong-way exposure or uncovered positions |
| `shortfall-splits` | `yield/invariants.clj` | Whether shortfall was split correctly among positions |
| `status-fsm` | `yield/invariants.clj` | Illegal state transition (active→withdrawn skipping unwinding) |
| `realized-non-negative` | `yield/invariants.clj` | Negative realized balance |
| `partial-liquidity-principal` | `yield/invariants.clj` | Principal not reduced during partial-liquidity |
| `value-conservation` | `yield/invariants.clj` | Value leak across operations |
| `deferred-reclaim` | `yield/invariants.clj` | Deferred yield not reclaimable after recovery |
| `index-monotone` | `yield/invariants_transition.clj` | Cross-step index going backwards |

### Layer 4: Evidence Capture (structured diagnostic records)

| Component | Path | Role |
|-----------|------|------|
| Evidence capture | `evidence/capture.clj` | At every event boundary; produces structured evidence artifacts |
| Yield evidence extractor | `yield/evidence.clj` | Shortfall events, recognized losses, canonical yield evidence |
| Evidence chain | `evidence/chain.clj` | Links evidence artifacts into verifiable audit trails |

### Layer 5: Diagnostic Aggregation (where Aave health signals are computed)

| Component | Path | What It Produces |
|-----------|------|------------------|
| Evidence registry | `evidence/registry.clj` | Master index of all evidence artifacts (default role `:diagnostic`) |
| Scenario report | `scenario/report.clj` | Full diagnostic report per scenario run |
| Triage | `scenario/triage.clj` | Failure-mode classification when invariants fail |
| Yield classification | `scenario/yield_classification.clj` | 4-axis classification (enablement, module, risk class, invariant profile) |
| Yield metrics | `scenario/yield_metrics.clj` | APY tracking, position-level value changes, module-level aggregation |
| Partial liquidity metrics | `scenario/partial_liquidity_metrics.clj` | Shortfall ratio, deferred amount, recovery schedule compliance |
| Coverage | `scenario/coverage.clj` | Which scenarios cover which protocol behaviors |

### Cross-Cutting: Validation Pipeline

| Component | Path | Role |
|-----------|------|------|
| Validation root | `validation/root.clj` | Status derivation (pass/fail/error) for the full diagnostic run |
| Validation state | `validation/state.clj` | Record-pass, record-error, record-evidence, snapshot operations |
| Suite result | `validation/suite_result.clj` | Aggregates results across a suite of Aave scenarios |

### Interactive Diagnostics

| Component | Path | Role |
|-----------|------|------|
| Yield workbench notebook | `notebooks/yield_scenarios_workbench.clj` | Interactive replay of Y01-Y06 with live state inspection |
| Shortfall notebook | `notebooks/yield_shortfall_partial_withdrawal_fills.clj` | Step-by-step partial liquidity shortfall walkthrough |
| Recovery notebook | `notebooks/partial_liquidity_recovery_deferred_claim.clj` | Recovery + deferred claim settlement walkthrough |
| Production workbench | `notebooks/workbench_v2.clj` | Evidence workbench with cryptographic solvency attestation |

### Diagnostic Artifacts (output)

| Artifact | Location | Content |
|----------|----------|---------|
| Golden reports | `data/fixtures/golden/s68-*.edn` | Expected diagnostic output for each Aave scenario |
| Trace files | `data/fixtures/traces/s68-*.json` | Full step-by-step execution trace with world snapshots |
| Suite traces | `suites/yield-reference-v1/expected/traces/` | Expected traces for yield-reference suite |

---

## Diagnostic Queries the Architecture Answers

1. **"Did accrual compute correctly?"** → Position-consistency + value-conservation invariants at each step; yield metrics report APY tracking.
2. **"Was the shortfall handled correctly?"** → Shortfall-splits invariant; partial-liquidity metrics; deferred-reclaim invariant.
3. **"Did the position state machine follow the correct path?"** → Status-fsm invariant; evidence chain links all state transitions.
4. **"Is the module-level state consistent?"** → Exposure invariant; yield metrics aggregate module totals.
5. **"Which scenarios cover this failure mode?"** → Coverage analysis; triage classifies failure.
6. **"What evidence exists for this event?"** → Evidence registry indexed by event + group + subject + type + layer.
