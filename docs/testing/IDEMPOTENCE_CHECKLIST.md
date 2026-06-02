# Idempotence Checklist (Sew Protocol Model)

This checklist tracks operations that should be idempotent (or replay-idempotent), current status, and hardening plan.

## Current checklist

| Surface | Expected idempotence contract | Status | Notes |
|---|---|---|---|
| `clear-claimable-v2-kind` | Repeated cleanup is a no-op; no negatives; no nil claimant keys | PASS | Implemented via `dissoc` in v2 ledger |
| `clear-pending-settlement` | Repeated replacement clears stale principal effects and does not derive from legacy map | PASS | Uses v2 helper only |
| `execute-pending-settlement` | Finalize once; subsequent calls rejected | PASS | Terminal + pending guards |
| `execute-fraud-slash` | Execute once; subsequent calls rejected | PASS | Status guards (`:executed`/`:appealed`/`:reversed`) |
| `unfreeze-resolver` | Multiple calls converge to same state | PASS | Idempotent state overwrite |
| `rotate-dispute-resolver` | Same target rotation should not create duplicate audit events | GAP | Currently appends repeated identical rotation records |
| `escalate_dispute` / `challenge_resolution` | Replay-idempotent under duplicate ingestion (same logical tx) | GAP | Business action is non-idempotent by design; dedupe needed at replay boundary |
| superseded pending fallback | Repeated keeper execution over superseded history cannot re-finalize | PARTIAL | Guarded by terminal/pending logic; add explicit regression tests for superseded cases |

## Shared helper direction

To minimize repetition and standardize behavior, add a shared helper module for idempotent transitions:

- Namespace proposal: `resolver-sim.contract-model.idempotency` (protocol-agnostic)
- Core helper (shape):
  - `apply-once`:
    - input: `world`, `op-key`, `apply-fn`
    - behavior:
      - if `op-key` already seen in `[:idempotency/applied]`, return unchanged world with `:idempotent/no-op`
      - else apply `apply-fn`, then mark `op-key` as applied
- Companion helper:
  - `noop-if` to short-circuit duplicate semantic transitions (e.g., same resolver rotation)

Suggested `op-key` patterns (built in protocol adapters):

- `[:rotate workflow-id new-resolver at-time]`
- `[:escalate workflow-id level caller event-id]`
- `[:challenge workflow-id level caller event-id]`
- `[:settle workflow-id pending-hash]`

`event-id` can be optional in deterministic scenarios and mandatory in external log replay.

## Phased plan

1. **Immediate hardening**
   - Add no-op guard to `rotate-dispute-resolver` when `new-resolver == old-resolver`.
   - Add explicit tests for superseded-pending repeated keeper calls.

2. **Replay-boundary dedupe**
   - Introduce optional `event-id` in replay events.
   - Use shared `contract-model.idempotency/apply-once` for `escalate_dispute` and `challenge_resolution` when `event-id` is present.

3. **Audit-grade trace consistency**
   - Emit explicit no-op trace metadata for deduped operations.
   - Add fixture scenarios with intentional duplicate events to prove deterministic convergence.

4. **Policy enforcement**
   - Require idempotence contract for new state transitions in PR checklist.
   - Add quick CI target for idempotence checklist test namespace.
