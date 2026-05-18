# Framework Boundaries

This document is the canonical boundary guide for contributors.

Terminology used throughout:

- **framework substrate**
- **adapter**
- **reference implementation**
- **research track**

## 1) What is reusable today (framework substrate)

Reusable today:

- deterministic replay orchestration (`contract_model/*`)
- adapter contracts (`protocols/protocol.clj`)
- shared adapter flow-control wrappers (`protocols/common/*`)
- scenario-analysis substrate (`scenario/*`, core reporting plumbing)
- accounting-evidence output contracts (including `:funds-ledger-view` contract shape)

These should remain protocol-agnostic and adapter-oriented.

## 2) What is SEW-specific (reference implementation)

SEW is the current **reference adapter** and most complete **reference implementation**:

- `protocols/sew.clj`
- `protocols/sew/*`
- SEW-integrated yield/accounting interpretation modules
- SEW-oriented providers under `generators/sew/*`, `io/sew/*`

SEW semantics are not assumed to be universal protocol semantics.

## 3) What an adapter must implement

At minimum, implement the required simulation adapter contract methods in
`protocols/protocol.clj`:

- protocol id
- world initialization
- execution-context construction
- action dispatch
- single-step and transition invariants
- world snapshot projection for replay
- id resolution helpers

## 4) What an adapter may optionally implement

Optional extensions:

- economic model hooks
- analysis/projection module hooks
- adapter-specific reports
- `:funds-ledger-view` producer

## 5) `:funds-ledger-view` guarantees

` :funds-ledger-view` is an adapter-facing accounting evidence contract.

### Framework-defined guarantees

- stable output contract shape
- explicit conservation verdict (`holds?`)
- explicit drift summary (`drift-total`, `drift-by-token`)
- read-only semantics (projection only; no state mutation)

### Adapter-defined semantics

- exact meaning of each bucket in that protocol world
- mapping from protocol world model into buckets
- protocol-specific liability/claimability interpretation

### Required fields (contract level)

- `:as-of-block-time`
- `:by-token`
- `:global`
- `:conservation`

### Typical buckets (adapter-mapped)

- deposited
- locked
- claimable
- released
- fees
- bonds
- slashed
- externalized
- drift

These labels are an adapter-facing accounting vocabulary; they do **not** imply
identical economic meaning across protocols.

## 6) What the framework deliberately does not generalize (yet)

Do not prematurely generalize:

- claimability calculation
- bond exposure calculation
- escrow solvency semantics
- protocol liability semantics
- payout/dispute economic interpretation

## 7) Research track boundary

Research-track modules are valuable but not part of stable framework API claims.

Typical locations:

- exploratory `sim/*`
- `sim/adversarial/*`
- `research/sew/*`

## 8) Priority policy

Current priority is **Scope 1 + carefully bounded Scope 2**:

1. Stabilize and document framework boundaries.
2. Keep SEW explicit as reference adapter/reference implementation.
3. Promote `:funds-ledger-view` as documented adapter-facing capability.
4. Extract only post-projection reconciliation utilities.

Delay full multi-protocol provider extraction until a second real adapter or
clear contributor demand exists.
