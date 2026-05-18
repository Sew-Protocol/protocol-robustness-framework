# Protocol Robustness Framework — System Overview

## What this system is

`sew-simulation` is an adapter-oriented robustness framework with a **framework substrate**
and a **protocol adapter layer** (with SEW as the current **reference adapter/reference implementation**).

At a high level:

- The replay kernel (`contract_model/replay.clj`) is protocol-agnostic.
- Protocol logic is provided via the `DisputeProtocol` interface
  (`protocols/protocol.clj`).
- SEW is the current reference adapter/reference implementation (`protocols/sew.clj` + `protocols/sew/*`).

This allows the simulation engine, fixtures, and many testing workflows to
remain reusable while protocol-specific rules stay isolated.

## Architecture in one view

| Layer | Purpose | Key namespaces |
|---|---|---|
| Protocol-agnostic kernel | Deterministic replay of scenario actions | `contract_model/replay.clj` |
| Protocol adapters | Plug-in interface + concrete implementation wiring | `protocols/protocol.clj`, `protocols/sew.clj`, `protocols/dummy.clj` |
| Shared adapter flow-control | Reusable precondition wrappers (actor resolution, checks, role gates) | `protocols/common/action_context.clj` |
| SEW domain implementation | State machine, lifecycle, accounting, resolution, invariants | `protocols/sew/*` |
| Simulation and stochastic models | Parameter sweeps, adversarial/economic phases, deterministic fixtures | `sim/*`, `stochastic/*`, `adversaries/*` |
| Shell/integration | Persistence, file I/O, gRPC/session management, CLI | `db/*`, `io/*`, `server/*`, `core.clj` |

## Namespace maturity map (public-facing)

### Stable core (framework-level)
- `contract_model/*`
- `protocols/protocol.clj`
- `protocols/common/*`
- `scenario/*`
- `io/scenarios.clj`, `db/*`, `server/*`

### SEW bespoke implementation (production-grade for this repo)
- `protocols/sew/*`
- `yield/modules/aave.clj` and SEW-integrated yield flows

SEW should be treated as the reference implementation (reference study), not as
proof that all protocol semantics are already framework-generic.

### Generic adapters (stable API surface; SEW default providers today)
- `generators/actions.clj` → `generators/sew/actions.clj`
- `generators/adversarial.clj` → `generators/sew/adversarial.clj`
- `io/trace_score.clj` → `io/sew/trace_score.clj`

### Experimental / research track (exclude from core capability claims)
- `sim/adversarial/reorg_check.clj`
- selected exploratory `sim/*` modules
- `research/sew/*`

## What is generalized vs SEW-specific

### Generalized
- Replay orchestration and step execution flow
- Protocol interface and adapter boundary
- Shared adapter flow-control wrappers (`protocols/common/action_context.clj`)
- A significant portion of simulation harness and I/O/telemetry plumbing

### SEW-specific (current primary implementation)
- Escrow lifecycle and dispute transitions
- Resolver authority and registry behavior
- SEW invariants, accounting, and projection semantics

### Yield module status/risk controls (Phase 2)

Yield behavior is configured through the scenario DSL and enforced at module op
execution time:

- Scenario config supports `:yield-config` with per-module `:module-status`.
- Module status semantics (Aave v3 currently):
  - `:active` — deposits and withdrawals allowed (subject to liquidity mode)
  - `:disabled-for-new-deposits` — deposits blocked, withdrawals allowed
  - `:paused` — deposits and withdrawals blocked
- Liquidity stress modes (`:shortfall`, `:frozen`, `:paused`) block both deposit
  and withdraw paths for affected token/module pairs.

These controls are tested in focused yield failure tests and are intended to be
replay/scenario-driven rather than ad-hoc fixture mutation.

## Reproducibility and testing posture

- Determinism remains a core design property (explicit RNG where applicable,
  deterministic replay for fixture scenarios).
- Canonical test entrypoint:

```bash
./scripts/test.sh            # all
./scripts/test.sh unit       # unit tests
./scripts/test.sh invariants # deterministic invariant scenario run
./scripts/test.sh suites     # fixture suites
```

For current test shape and suite details, see:
- `docs/testing/TEST_SUITE.md`
- `docs/testing/RUNNING_TESTS.md`

## How to navigate from here

- Deep architecture and layering rules:
  `docs/architecture/ARCHITECTURE.md`
- Adapter implementation boundaries and authoring:
  `docs/architecture/ADAPTER_AUTHORING_GUIDE.md`
- Framework substrate vs adapter vs reference implementation vs research track:
  `docs/framework-boundaries.md`
- Reusable adapter/harness design notes:
  `docs/overview/REUSABLE_COMPONENTS.md`
- Use-of-funds accounting contract and drift interpretation:
  `docs/overview/USE_OF_FUNDS.md`
- End-user and CLI workflows:
  `docs/quickstart/QUICKSTART.md`, `docs/usage.md`

## Scope note

This overview intentionally avoids hard-coding volatile counts (exact scenario
totals, phase totals, benchmark percentages) so it stays accurate as the
simulation suite evolves.
