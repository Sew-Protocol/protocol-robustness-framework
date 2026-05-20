# Generalisation Review — 2026-05-17

## Scope

This review captures completed and remaining work for reducing hard SEW coupling in shared simulation modules.

## Completed in this tranche

### 1) Phase Z runner switched to protocol boundary

- File: `src/resolver_sim/sim/phase_z_scenarios.clj`
- Change:
  - Removed direct call to `sew/replay-with-sew-protocol`
  - Uses `replay/replay-with-protocol` with protocol resolved via `resolver-sim.protocols.registry`
- Effect:
  - Shared simulation flow no longer imports SEW protocol namespace directly.

### 2) Shared trace scoring decoupled from SEW metadata module

- File: `src/resolver_sim/io/trace_score.clj`
- Change:
  - Removed direct requires on `resolver-sim.protocols.sew.*`
  - Added protocol-agnostic local issue classifier for `:issue/type`
- Effect:
  - `io/trace_score` is now protocol-facing and reusable.

### 3) Shared scenario IO docs generalized

- File: `src/resolver_sim/io/scenarios.clj`
- Change:
  - Documentation now points callers to `replay/replay-with-protocol` + registry.

### 4) Deprecated canonical shim retired

- Removed file: `src/resolver_sim/canonical/actions.clj`
- Precondition verified:
  - No remaining internal references to `resolver-sim.canonical.actions` in `src/` or `test/`.

## Known residual coupling (intentional or deferred)

### A) Intentional (correctness-sensitive)

- `src/resolver_sim/economics/payoffs.clj`
  - Still requires `resolver-sim.protocols.sew.types` for fee semantics currently exercised by accounting flows.
  - Prior direct decoupling attempt produced accounting regressions.

### B) Expected protocol registration coupling

- `src/resolver_sim/protocols/registry.clj`
  - Must reference concrete protocols (including SEW) by design.

### C) Deferred SEW internals used by simulation modules

- `src/resolver_sim/sim/economic/phase_y.clj`
- `src/resolver_sim/sim/capacity_exhaustion.clj`
- `src/resolver_sim/sim/adversarial/reorg_check.clj`

These currently exercise SEW internal lifecycle/resolution/state helpers directly.

## Recommended next refactor tranche

1. **Phase Y**
   - Move to scenario-driven replay via `contract-model/replay` + protocol registry.
   - Keep yield assertions in a protocol-agnostic post-processing layer.

2. **Capacity exhaustion**
   - Replace direct calls (`lc/*`, `res/*`, `t/*`) with scenario definitions and replay execution.
   - Keep invariant checks through protocol boundary hooks.

3. **Reorg check**
   - Convert skeleton to replay-level fork tests without direct SEW invariants namespace imports.

4. **Economics/payoffs extraction (careful)**
   - Introduce shared fee math utility module with proven equivalence tests.
   - Migrate `payoffs` and `sew.types` to the shared utility after tests are green.

## Verification notes

- Targeted checks run during this tranche:
  - `resolver-sim.generators.equilibrium-test` passing after refactors.
  - No remaining references to deleted `resolver-sim.canonical.actions`.

## Phase 2 update — Yield module status + liquidity behavior

This phase extends the generalized yield DSL and Aave module behavior with
explicit stress/freeze semantics that can be configured per scenario.

### Added scenario DSL capability

- File: `src/resolver_sim/yield/registry.clj`
- `apply-yield-config` now accepts optional:
  - `:modules -> <module-id> -> :module-status`
- Stored at:
  - `[:yield/module-status <module-id>]`

### Aave behavior matrix (implemented)

| Condition | Deposit | Withdraw |
|---|---|---|
| `module-status = :active` + liquidity available | allowed | allowed |
| `module-status = :disabled-for-new-deposits` | blocked | allowed |
| `module-status = :paused` | blocked | blocked |
| `liquidity-mode in #{:shortfall :frozen :paused}` | blocked | blocked |

Notes:
- Module status is evaluated first for deposit policy (`:paused`, then
  `:disabled-for-new-deposits`).
- Liquidity blocked modes apply to both deposit and withdraw paths.
- Withdraw still crystallizes unrealized yield when allowed.

### Phase 2 coverage added

- Runtime behavior:
  - `src/resolver_sim/yield/modules/aave.clj`
    - status helpers + guarded deposit/withdraw transitions.
- Tests:
  - `test/resolver_sim/protocols/sew/yield/failure_test.clj`
    - shortfall blocks deposit/withdraw
    - emergency unwind marks active matching-token positions as `:unwinding`
    - paused status blocks deposit
    - disabled-for-new-deposits blocks deposit
    - scenario DSL `:module-status` initialization validated via protocol init.

---

This document is intended as the handoff for the next generalisation implementation cycle.