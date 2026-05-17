# Architecture

## System Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Python adversarial layer                                    │
│                                                              │
│  invariant_suite.py          eth_failure_modes.py            │
│  (scenarios, harness)        (attack agents)                 │
│                │                                             │
│                │  gRPC (port 7070)                           │
└────────────────┼────────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────────┐
│  Clojure gRPC server  (src/resolver_sim/server/)             │
│                                                              │
│  Session API: StartSession / Step / DestroySession            │
│                │                                             │
│  contract_model/replay.clj  ← protocol-agnostic kernel       │
│                │                                             │
│  protocols/sew.clj          ← SEWProtocol adapter            │
│    sew/state_machine.clj    ← escrow state transitions       │
│    sew/lifecycle.clj        ← create → dispute → resolve     │
│    sew/invariants.clj       ← 28+ post-condition checks      │
│    sew/resolution.clj       ← DR1/DR2/DR3 resolution logic   │
│    sew/authority.clj        ← resolver authorization         │
│    sew/accounting.clj       ← fee and profit calculations    │
│    sew/runner.clj           ← top-level trial runner         │
└─────────────────────────────────────────────────────────────┘
```

## Layered Architecture

The system is strictly layered. The functional core has no I/O. Only `db/` and `io/` have side effects.

### Functional core (no I/O, no DB)

```
contract_model/     protocol-agnostic replay kernel (replay.clj only)
protocols/          DisputeProtocol interface + SEW and Dummy implementations
stochastic/         statistical/economic models (pure functions)
sim/                Monte Carlo simulation phases (pure sweeps)
governance/         governance rule models (pure)
adversaries/        adversary strategy models (pure)
oracle/             detection models (pure)
```

### Imperative shell (I/O only)

```
db/                 XTDB persistence (protocol-agnostic trial/entity events)
  store.clj           table operations
  telemetry.clj       adapter: runner output → DB writes
io/                 file I/O
  params.clj          EDN param loading
  results.clj         result serialisation
server/             gRPC session server
```

### Layering rules

| Namespace | May import | Must NOT import |
|-----------|-----------|----------------|
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

**Key invariant:** The functional core is testable without a running XTDB instance or filesystem.

## How a Scenario Runs

1. Python opens a gRPC session with `StartSession`
2. Python sends a sequence of `Step` calls, each encoding one actor action (e.g., `raise_dispute`, `resolve`, `escalate`)
3. The Clojure server applies the action to the current escrow state, runs all 28+ invariants via the SEWProtocol adapter, and returns a `trace_entry` with the new state, any invariant violations, and whether the step was accepted or rejected
4. Python agents inspect the response and choose their next action (adversarial agents use this to decide whether to escalate, attack, or retreat)
5. Python asserts the scenario's specific success condition (e.g., "attack succeeded at least once" or "0 invariant violations")
6. Session is closed with `DestroySession`

## The 28+ Protocol Invariants

Checked after every state transition via `SEWProtocol.check-invariants-single`
and `check-invariants-transition` (implemented in `protocols/sew/invariants.clj`):

| Category | Invariants |
|----------|-----------|
| **Accounting** | Solvency, fee non-negative, held non-negative, conservation of funds, finalization accounting, token-tax reconciliation, fees monotone |
| **State machine** | Valid status combinations, pending settlement consistent, dispute timestamp consistent, dispute level bounded, terminal states unchanged, escalation level monotonic |
| **Economic** | Slash status, appeal bond conserved, bond liquidity, bond slash bounded, fee cap, slash distribution, resolver bond mix (80/20), senior coverage, slash epoch cap |
| **Safety** | No auto fraud execute, resolver not frozen on assign, reversal slash disabled, no withdrawal during dispute, time-lock integrity |
| **Liveness** | No stale automatable escrows, dispute resolution path exists |

## gRPC Session Protocol

The gRPC API is defined in `proto/`. The key methods:

- `OpenSession` — creates a new simulation session with initial escrow state
- `ProcessStep` — applies one actor action; returns new state + invariant results
- `CloseSession` — tears down the session

Each `ProcessStep` response includes a `trace_entry` with:
- The action that was attempted
- Whether it was accepted or rejected (with revert reason)
- The full escrow state after the step
- All invariant results (`{holds?: bool, violations: [...]}` per invariant)

## Monte Carlo Layer

The Monte Carlo layer (`src/resolver_sim/sim/`) runs parameter sweeps independently of the adversarial layer. It does not use gRPC — it calls the protocol simulation functions directly in-process via `contract_model/replay.clj` and `protocols/sew/`.

Each phase (`phase_o.clj` through `phase_ai.clj`) tests a falsifiable hypothesis against a threshold (typically ≥80% pass rate across all scenarios in the sweep).

## Foundry Differential Testing (In Progress)

`python/sew_sim/anvil_runner.py` implements a differential testing harness:

1. Starts a local Anvil instance (Foundry's EVM)
2. Deploys the full EscrowVault + DR module stack via a Forge script (`DifferentialSetup.s.sol`)
3. Drives the same scenario sequence using `cast send` / `cast call`
4. Compares every state transition against the `:projection` field in the Clojure trace

Any divergence between the EVM state and the Clojure model's projection is a bug — either in the model or in the Solidity implementation.
