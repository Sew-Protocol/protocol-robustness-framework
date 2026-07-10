# Adapter Authoring Guide

This guide explains how to add protocol adapters while preserving a clear
boundary between reusable framework infrastructure and protocol-owned semantics.

Canonical terminology:
- framework substrate
- adapter / Sew adapter
- primary protocol model (Sew — the main validation target in this repo)
- research track

See also: `docs/architecture/framework-boundaries.md`

## Design principle

Generalize infrastructure, not protocol semantics.

- Generalize: replay orchestration, scenario execution, invariant plumbing,
  temporal/state tooling, reconciliation/reporting contracts.
- Keep adapter-owned: escrow/dispute/claimability/payout semantics, authority
  model, resolver/bond logic, settlement economics.

## 1) Minimum implementation

Implement `SimulationAdapter` in `src/resolver_sim/protocols/protocol.clj`:

- `protocol-id`
- `init-world`
- `build-execution-context`
- `dispatch-action`
- `check-invariants-single`
- `check-invariants-transition`
- `world-snapshot`
- `resolve-id-alias`
- `created-id`
- `open-entities`

This is sufficient for deterministic replay + invariant-aware scenario runs.

## 2) Optional implementation layers

### `EconomicModel` (optional)

Use when the adapter needs adversarial/economic metrics and advisory outputs.

### `AnalysisModule` (optional)

Use when the adapter needs trace projection, reporting transforms, and
theoretical/mechanism evaluation integration.

## 3) `:funds-ledger-view` contract (optional)

If implementing `AnalysisModule/io-projection` target `:funds-ledger-view`:

- output contract shape is reusable,
- accounting semantics remain adapter-specific,
- target must be read-only (no world/session mutation).

See:
- `docs/overview/USE_OF_FUNDS.md`
- `docs/architecture/ADR-0004-cross-protocol-funds-ledger-extraction.md`

## 4) Namespace placement conventions

- Adapter contracts and registry: `resolver_sim/protocols/*`
- Shared adapter orchestration helpers: `resolver_sim/protocols/common/*`
- Protocol implementation: `resolver_sim/protocols/<protocol>/*`
- Experimental studies: `resolver_sim/research/*` (or clearly marked `sim/*`)

## 5) Sew adapter — worked example

The Sew adapter is the main worked example of how to implement the framework interfaces:

- complete protocol semantics in `protocols/sew/*`,
- framework-compatible projections/reporting exposed through adapter interfaces,
- reusable contracts without claiming universal protocol semantics.
