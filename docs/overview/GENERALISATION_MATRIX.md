# Generalisation / Reusability / SEW-Specificness Matrix

Concise view of the current state.

| Area | Generalisation Level | Reusability | SEW-Specificness | Notes |
|---|---|---|---|---|
| Replay kernel (`contract_model/replay.clj`) | **High** | **High** | Low | Protocol-agnostic orchestration boundary is stable. |
| Protocol interface (`protocols/protocol.clj`) | **High** | **High** | Low | Tiered adapter contracts (Simulation/Economic/Analysis). |
| Shared action context (`protocols/common/action_context.clj`) | **High** | **High** | Low | Reusable flow-control wrappers; no domain error semantics. |
| Scenario analysis utilities (`scenario/*`) | **Medium-High** | **High** | Low-Medium | Generic over trace shape; domain meaning may still be protocol-specific. |
| Fixtures + scenario DSL (`data/fixtures/*`, `scenarios/*`) | **Medium** | **Medium-High** | Medium | Reusable structure, but many fixtures encode SEW domain behaviors. |
| Generic adapter facades (`generators/actions.clj`, `generators/adversarial.clj`, `io/trace_score.clj`) | **Medium** | **Medium-High** | Medium | Stable façade, currently defaulting to SEW providers underneath. |
| Use-of-funds output contract (`:funds-ledger-view`) | **High (output contract), Medium (computation)** | **High** | Medium | Output contract is intended protocol-adaptable; current producer/computation is SEW-specific. |
| SEW projection implementation (`protocols/sew/projection.clj`) | Low-Medium | Medium | **High** | Contains SEW semantics and now funds/drift summaries. |
| SEW protocol implementation (`protocols/sew/*`) | Low | Low-Medium | **Very High** | Core domain state machine, accounting, resolution, invariants. |
| Yield modules (`yield/modules/*`) | Low-Medium | Medium | **High** | Conceptually portable, but integrated to SEW world semantics. |
| Server session layer (`server/session.clj`) | **Medium** | **Medium-High** | Medium | Generic session pattern with optional protocol projections. |
| Experimental simulation namespaces (`sim/adversarial/*`, exploratory `sim/*`) | Low | Low-Medium | High | Research-track; not treated as core reusable capability. |

## Readout (quick)

- **Most generalized/reusable today:** replay kernel + protocol contracts + shared action context.
- **Best “reusable with adapter hooks” layer:** scenario analysis + funds-ledger output contract.
- **Most SEW-specific:** protocol/sew domain modules and their direct accounting semantics.
