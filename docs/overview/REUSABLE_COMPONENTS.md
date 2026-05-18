# Reusable Components Guide

This repository is the **SEW validation implementation**. It also exposes a
set of reusable components that other dispute-resolution protocol teams can
adapt with low coupling.

## 1) Protocol adapter contract

File: `src/resolver_sim/protocols/protocol.clj`

- Defines `DisputeProtocol`.
- Establishes the boundary between protocol-specific logic and replay harness.
- Requires deterministic, pure behavior for replayed transitions.

Practical value:
- keeps replay flow protocol-agnostic,
- allows new adapters without rewriting scenario replay,
- makes invariants/metrics lifecycle explicit.

## 1.1) Shared action-context wrappers (new)

Files:
- `src/resolver_sim/protocols/common/action_context.clj`
- `src/resolver_sim/protocols/sew/action_context.clj` (SEW façade over common wrappers)

Reusable control-flow wrappers:
- `with-resolved-actor`
- `with-resolved-actor-and-check`
- `with-role-actor`

Error-boundary contract:
- common wrappers are orchestration-only,
- they do not translate/normalize error maps,
- resolver/check/role failures are returned unchanged,
- protocol modules own domain error semantics.

Practical value:
- consistent precondition flow across protocols,
- lower handler duplication,
- clearer separation between flow-control and domain decisions.

## 2) Deterministic replay harness

File: `src/resolver_sim/contract_model/replay.clj`

- Validates scenario structure.
- Replays events in deterministic order.
- Runs invariant checks and expectation/theory evaluation integration points.
- Produces trace and metric outputs suitable for regression and analysis.

Practical value:
- reproducible debugging,
- consistent pass/fail semantics,
- supports adapter-driven protocol experiments.

## 3) Fixture toolkit

Directory: `data/fixtures/`

- Composable fixture units (`protocol/`, `states/`, `actors/`, `authority/`,
  `tokens/`, `traces/`, `thresholds/`, `suites/`).
- Deterministic scenario suites for regression and adversarial checks.

Practical value:
- faster test authoring,
- reproducible scenario sharing,
- easier cross-team validation workflows.

## 4) Scenario evaluation utilities

Directory: `src/resolver_sim/scenario/`

- `expectations.clj`: execution-level checks.
- `theory.clj`: claim falsification semantics.
- `projection.clj`, `coverage.clj`, `equilibrium.clj`: supporting evaluators.

Practical value:
- structured analysis layer independent from one protocol file,
- clearer separation between simulation execution and claim interpretation.

## 5) Generator extension contract (new)

Files:
- `src/resolver_sim/generators/actions.clj` (protocol-agnostic orchestration)
- `src/resolver_sim/generators/sew/actions.clj` (SEW candidate templates)

Pattern:
- `generators/actions.clj` should only orchestrate:
  - select protocol-specific candidate template provider,
  - validate candidates through `protocol/dispatch-action`.
- Protocol-specific action vocab and world-shape assumptions must live under
  protocol-scoped generator namespaces (e.g. `generators/sew/*`).

Why this matters:
- keeps generic generator code free of protocol storage/action coupling,
- makes onboarding for new protocol adapters explicit,
- avoids hidden SEW assumptions in framework-level modules.

## Suggested onboarding path for new developers

1. `src/resolver_sim/protocols/sew.clj` and `protocols/sew/state_machine.clj`
2. `src/resolver_sim/protocols/protocol.clj`
3. `src/resolver_sim/protocols/common/action_context.clj`
4. `src/resolver_sim/contract_model/replay.clj`
5. `data/fixtures/README.md`
6. `src/resolver_sim/scenario/expectations.clj`

## Scope note

These components are reusable, but this repository does **not** claim universal
or complete coverage across all protocol designs.
