# Replay-Sensitive Actions: Per-Action Dedupe Semantics

Reference for the 8 actions subject to replay-boundary deduplication when
`event-id` is present.

## Dedupe op-key shape

```
[:sew :replay-dedupe <action> <agent> <workflow-id> <slash-id> <hop-scope> <event-id>]
```

Built by `sew/dedupe-op-key` in `protocols/sew.clj:99`.

## Field sources

| Component | Source | Notes |
|-----------|--------|-------|
| `action` | `compat/canonical-action(event)` | Normalised kebab-case string |
| `agent` | `(:agent event)` | Direct from event envelope |
| `workflow-id` | `event-workflow-id(event)` | `:workflow-id` param, fallback `:id` |
| `slash-id` | `event-slash-id(event)` | `:slash-id` param, fallback `:workflow-id` |
| `hop-scope` | `hop-id` param or dispute level | escalate/challenge only; `nil` for others |
| `event-id` | `event-id(event)` | Must be present for dedupe to activate |

## Per-action key reference

### Actions with `hop-scope ≠ nil`

| Action | `agent` | `workflow-id` | `slash-id` | `hop-scope` | `event-id` |
|--------|---------|---------------|------------|-------------|------------|
| `escalate-dispute` | caller | `:workflow-id` | = wf-id | explicit `hop-id` OR current dispute level | `:event-id` |
| `challenge-resolution` | caller | `:workflow-id` | = wf-id | explicit `hop-id` OR current dispute level | `:event-id` |

**Why hop-scope matters:** The same `event-id` could span multiple escalation
levels. Without hop-scope, a duplicate `challenge-resolution` at L1 would be
treated as a duplicate of the L0 challenge if both share the same `event-id`.

**Common mistake:** Omitting `hop-id` when the dispute level changes between
replay passes (e.g., after a reorg). The resolver looks up the current dispute
level from the world, which may differ from the level at which the action was
originally dispatched.

### Actions with `slash-id ≠ workflow-id`

| Action | `agent` | `workflow-id` | `slash-id` | `hop-scope` | `event-id` |
|--------|---------|---------------|------------|-------------|------------|
| `propose-fraud-slash` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |
| `resolve-appeal` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |
| `execute-fraud-slash` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |

**Why slash-id ≠ workflow-id matters:** A single workflow can accumulate
multiple independent slash operations (e.g., Track 1 reversal slashes at
level 0 and level 1). Each slash has a distinct `:slash-id` (e.g.,
`"0-reversal-0"`, `"0-reversal-1"`). Using `:workflow-id` as the slash-id
would conflate all slashes into a single dedupe scope, allowing a duplicate
of one slash to block a different slash on the same workflow.

**Correct:** `{:slash-id "0-reversal-0" :event-id "evt-slash-level0"}`
**Wrong:** `{:workflow-id 0 :event-id "evt-slash-level0"}` — conflates all
slash operations on workflow 0.

### Actions with flat key (all fields = same)

| Action | `agent` | `workflow-id` | `slash-id` | `hop-scope` | `event-id` |
|--------|---------|---------------|------------|-------------|------------|
| `execute-resolution` | caller | `:workflow-id` | = wf-id | `nil` | `:event-id` |
| `execute-pending-settlement` | caller | `:workflow-id` | = wf-id | `nil` | `:event-id` |
| `rotate-dispute-resolver` | caller | `:workflow-id` | = wf-id | `nil` | `:event-id` |

**Common mistake:** Assuming these follow the same pattern as slash actions.
`slash-id` always equals `workflow-id` for these three, so identical op-keys
differentiate solely by `event-id` (and `agent`, `action`). If two
`execute_pending_settlement` calls target different escrows but share an
`event-id`, the second will incorrectly dedupe.

**Correct:** Always use a unique `event-id` per action occurrence.

## Dedupe activation conditions

Dedupe is active only when ALL of the following hold:

1. The action is in `replay-sensitive-actions` (sew.clj:79)
2. The event has a non-nil `:event-id` in params
3. The `:require-event-id?` flag is NOT set to `true` (if it is, missing
   `event-id` causes rejection, not pass-through)

## Interaction with business-logic idempotence

These are independent layers:

| Layer | Mechanism | Duplicate outcome | Active when |
|-------|-----------|-------------------|-------------|
| **Replay-boundary** | `apply-once` wrapping `apply-action` | No-op (`:no-op-duplicate`) | `event-id` present |
| **Business-logic** | State guards in lifecycle/resolution | Reject (error keyword) | Always |

Business-logic idempotence is action-specific:
- `rotate-dispute-resolver`: same-target rotation returns `:idempotent? true`
- `execute-pending-settlement`: terminal state guard → `:transfer-not-in-dispute`
- `execute-fraud-slash`: status guard → `:already-executed`

## Effect on action state

When replay dedupe fires (`:no-op-duplicate`), the action is **skipped
entirely** — `apply-fn` never runs. This means:

- **State mutations do not occur** — no pending settlement created, no
  stake slashed, no resolver rotation recorded.
- **Audit trail entries are not appended** — the action leaves no trace
  beyond the `{:extra {:idempotency :no-op-duplicate}}` marker.
- **Invariant checks still run** — the step still appears in the trace
  with `:result :ok`, and invariants pass (world unchanged).
