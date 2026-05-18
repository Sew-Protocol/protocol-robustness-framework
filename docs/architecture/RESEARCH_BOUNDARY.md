# Research Boundary: Stable Framework vs Exploratory Studies

This note clarifies how to classify modules and claims in this repository.

## 1) Stable framework (reusable public-good substrate)

Includes deterministic replay infrastructure, adapter contracts, shared
orchestration helpers, protocol-agnostic reporting/reconciliation mechanics,
and shell wiring required to run them.

Typical namespaces:
- `contract_model/*`
- `protocols/protocol.clj`
- `protocols/common/*`
- reusable portions of `scenario/*`, `time/*`, `db/*`, `io/*`, `server/*`

## 2) Reference implementation (SEW)

Includes the complete SEW adapter and semantics.

Typical namespaces:
- `protocols/sew/*`
- SEW-backed providers under `generators/sew/*`, `io/sew/*`
- currently SEW-integrated yield/accounting interpretation modules

## 3) Exploratory research

Includes phase studies, hypothesis sweeps, and adversarial exploration.

Typical namespaces:
- exploratory `sim/*`
- `sim/adversarial/*`
- research-oriented Python scripts under `python/*`

## Claiming guidance

- For framework claims, cite only stable framework modules.
- For protocol-semantic claims, cite SEW reference modules.
- For exploratory claims, mark results as research/evidence, not framework API.

## Accounting-specific boundary

Safe to generalize:
- reconciliation mechanics,
- aggregation mechanics,
- drift/conservation reporting contracts.

Do not prematurely generalize:
- escrow/dispute/claimability semantics,
- resolver/bond semantics,
- payout economics,
- protocol-specific solvency meaning.
