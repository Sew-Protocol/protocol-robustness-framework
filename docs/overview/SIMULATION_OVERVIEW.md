# SEW Simulation — System Overview

## What this system is

`sew-simulation` is a protocol simulation stack with a **generalized replay core**
and a **protocol adapter layer** (with SEW as the current primary implementation).

At a high level:

- The replay kernel (`contract_model/replay.clj`) is protocol-agnostic.
- Protocol logic is provided via the `DisputeProtocol` interface
  (`protocols/protocol.clj`).
- SEW is the primary implementation (`protocols/sew.clj` + `protocols/sew/*`).

This allows the simulation engine, fixtures, and many testing workflows to
remain reusable while protocol-specific rules stay isolated.

## Architecture in one view

| Layer | Purpose | Key namespaces |
|---|---|---|
| Protocol-agnostic kernel | Deterministic replay of scenario actions | `contract_model/replay.clj` |
| Protocol adapters | Plug-in interface + concrete implementation wiring | `protocols/protocol.clj`, `protocols/sew.clj`, `protocols/dummy.clj` |
| SEW domain implementation | State machine, lifecycle, accounting, resolution, invariants | `protocols/sew/*` |
| Simulation and stochastic models | Parameter sweeps, adversarial/economic phases, deterministic fixtures | `sim/*`, `stochastic/*`, `adversaries/*` |
| Shell/integration | Persistence, file I/O, gRPC/session management, CLI | `db/*`, `io/*`, `server/*`, `core.clj` |

## What is generalized vs SEW-specific

### Generalized
- Replay orchestration and step execution flow
- Protocol interface and adapter boundary
- A significant portion of simulation harness and I/O/telemetry plumbing

### SEW-specific (current primary implementation)
- Escrow lifecycle and dispute transitions
- Resolver authority and registry behavior
- SEW invariants, accounting, and projection semantics

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
- Reusable adapter/harness design notes:
  `docs/overview/REUSABLE_COMPONENTS.md`
- End-user and CLI workflows:
  `docs/quickstart/QUICKSTART.md`, `docs/usage.md`

## Scope note

This overview intentionally avoids hard-coding volatile counts (exact scenario
totals, phase totals, benchmark percentages) so it stays accurate as the
simulation suite evolves.
