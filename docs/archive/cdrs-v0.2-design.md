# CDRS v0.2 Design

**Status**: In development — `cdrs-v0.2` branch  
**Predecessor**: CDRS v0.1 (`spec/cdrs-v0.1.md`)

## Motivation

CDRS v0.1 checks `state_bucket` and per-step accounting fields.  This allows two
executions to appear equivalent at the state-machine level while differing in:

- who resolved the dispute and whether they were authorised
- which outcome was chosen (release vs. refund)
- whether escalation happened correctly and at the right tier
- whether pending settlement lifecycle was respected
- whether timing and deadline constraints were obeyed

CDRS v0.2 adds a trace-level `expected_semantics` block and per-step acceptance
fields to prevent this class of false equivalence.

---

## 1. Schema Additions

### Top-level fixture (new fields)

```json
{
  "cdrs_version": "0.2",
  "schema_version": "2",
  "trace_kind": ["dispute-resolution", "pending-settlement"],

  "expected_semantics": {
    "resolution": {
      "outcome":                    "release",
      "finality":                   "final",
      "beneficiary":                "seller",
      "resolver":                   "resolver",
      "authorized_resolver":        true,
      "pending_settlement_created": true,
      "settlement_executed":        true,
      "resolution_hash":            "0xhash",
      "integrity":                  "fully-reconciled"
    },
    "escalation": {
      "level":                         1,
      "max_level_reached":             1,
      "tier":                          "l1",
      "attempted":                     true,
      "accepted":                      true,
      "rejected":                      false,
      "pending_cleared_on_escalation": true
    },
    "participation": {
      "dispute_initiator":      "buyer",
      "resolution_actor":       "l1resolver",
      "settlement_actor":       "keeper",
      "authorized_participant": true,
      "custom_resolver_used":   false
    },
    "timing": {
      "within_resolution_window": true,
      "within_settlement_window": true,
      "auto_release_triggered":   false,
      "auto_cancel_triggered":    false,
      "pending_delay_seconds":    130
    }
  }
}
```

`expected_semantics` sections are omitted entirely when not applicable
(e.g. a lifecycle trace with no dispute has no `resolution` section).

### Per-step `expected` additions

All v0.2 steps include `accepted` and `state_changed`.
`rejection_reason` is present only when `accepted: false`.

```json
{ "expected": {
    "escrow_state": 4,
    "accepted":          true,
    "state_changed":     true
} }

{ "expected": {
    "reverted":          true,
    "error":             "unauthorized",
    "accepted":          false,
    "rejection_reason":  "unauthorized",
    "state_changed":     false
} }
```

---

## 2. Field Priority Table

| # | Field | Section | Priority | Derivable? |
|---|---|---|---|---|
| 1 | `resolution.outcome` | resolution | **Critical** | ✓ world state |
| 2 | `resolution.finality` | resolution | **Critical** | ✓ world state |
| 3 | `resolution.authorized_resolver` | resolution | **Critical** | ✓ :result :ok on execute_resolution |
| 4 | `resolution.pending_settlement_created` | resolution | **Critical** | ✓ pending-settlements in projection |
| 5 | `resolution.settlement_executed` | resolution | **Critical** | ✓ :settlement-executed event-tag |
| 6 | `resolution.beneficiary` | resolution | **Critical** | ✓ outcome + escrow.to |
| 7 | `escalation.level` | escalation | **Critical (DR3)** | ✓ dispute-levels in world |
| 8 | `escalation.tier` | escalation | **Critical (DR3)** | ✓ protocol-params + resolver identity |
| 9 | `timing.within_resolution_window` | timing | **High** | ✓ event times + max-dispute-duration |
| 10 | `timing.within_settlement_window` | timing | **High** | ✓ event times + appeal-window-duration |
| 11 | `timing.auto_cancel_triggered` | timing | **High** | ✓ auto_cancel_disputed :ok in trace |
| 12 | `timing.pending_delay_seconds` | timing | **High** | ✓ settlement time − resolution time |
| 13 | `escalation.attempted/accepted/rejected` | escalation | **High** | ✓ escalate_dispute events |
| 14 | `escalation.pending_cleared_on_escalation` | escalation | **High** | ✓ projection before/after escalation |
| 15 | `participation.dispute_initiator` | participation | **High** | ✓ :dispute-raised entry agent |
| 16 | `participation.resolution_actor` | participation | **High** | ✓ last :dispute-resolved entry agent |
| 17 | `participation.authorized_participant` | participation | **High** | ✓ :result :ok on resolution |
| 18 | `resolution.integrity` | resolution | **High** | ✓ resolution-semantics |
| 19 | `escalation.max_level_reached` | escalation | **Medium** | ✓ max dispute-level across trace |
| 20 | `participation.custom_resolver_used` | participation | **Medium** | ✓ create_escrow params |
| 21 | `participation.settlement_actor` | participation | **Medium** | ✓ :settlement-executed entry agent |
| 22 | `step.accepted` | per-step | **Medium-high** | ✓ trace entry :result |
| 23 | `step.rejection_reason` | per-step | **Medium-high** | ✓ trace entry :error |
| 24 | `step.state_changed` | per-step | **Medium-high** | ✓ :result == :ok |
| 25 | `resolution.resolution_hash` | resolution | **Medium** | ✓ event params |
| 26 | `timing.auto_release_triggered` | timing | **Medium** | ✓ release :ok in trace |
| 27–N | `accounting.*` (balance deltas, fees, bonds) | accounting | **Defer → v0.3** | ✓ but significant export work |
| — | Raw timestamps | timing | **Defer** | Too unstable; use derived booleans |
| — | Coalition/payoff fields | — | **Defer → v0.4** | Multi-epoch only |

---

## 3. Trace-Kind Conditional Required Fields

`trace_kind` is a string array computed from trace content. Multiple values are
inclusive (a trace can be both `"escalation"` and `"pending-settlement"`).

| `trace_kind` | Required `expected_semantics` fields |
|---|---|
| `lifecycle` | *(none — baseline/happy-path, no dispute)* |
| `dispute-resolution` | `resolution.outcome`, `resolution.finality`, `resolution.beneficiary`, `participation.dispute_initiator`, `participation.resolution_actor`, `resolution.authorized_resolver` |
| `escalation` | All `dispute-resolution` fields + `escalation.level`, `escalation.max_level_reached`, `escalation.tier`, `escalation.attempted`, `escalation.accepted` |
| `pending-settlement` | `resolution.pending_settlement_created`, `resolution.settlement_executed`, `timing.pending_delay_seconds`, `timing.within_settlement_window` |
| `timing` | At least one of `timing.within_resolution_window`, `timing.auto_cancel_triggered`, `timing.auto_release_triggered` |
| `authorization` | `participation.authorized_participant`, `resolution.authorized_resolver` |
| `adversarial` | No additional required fields; per-step `accepted`/`rejection_reason` recommended |

---

## 4. TraceEquivalence.t.sol Implementation Plan

`TraceEquivalence.t.sol` lives in the on-chain contracts repo.  The simulation
generates fixtures it consumes.  This section specifies the Solidity interface.

### Data structures

```solidity
struct ResolutionExpected {
    bool    present;
    bytes32 outcome;           // "release"|"refund"|"settled"|"cancelled"|"timeout"|"unresolved"
    bytes32 finality;          // "final"|"appealable"|"stalled"|"reversed"
    bytes32 beneficiary;       // role label: "seller"|"buyer"|"split"
    bytes32 resolver;          // agent id
    bool    authorizedResolver;
    bool    pendingSettlementCreated;
    bool    settlementExecuted;
    bytes32 integrity;
}

struct EscalationExpected {
    bool    present;
    uint8   level;
    uint8   maxLevelReached;
    bytes32 tier;              // "l0"|"l1"|"l2"|"kleros"|"arbitration"
    bool    attempted;
    bool    accepted;
    bool    rejected;
    bool    pendingClearedOnEscalation;
}

struct ParticipationExpected {
    bool    present;
    bytes32 disputeInitiator;
    bytes32 resolutionActor;
    bool    authorizedParticipant;
    bool    customResolverUsed;
}

struct TimingExpected {
    bool    present;
    bool    withinResolutionWindow;
    bool    withinSettlementWindow;
    bool    autoRelease;
    bool    autoCancel;
    uint256 pendingDelaySeconds;    // 0 = not checked
}
```

### Test flow

```
1. Load fixture (vm.readFile + json parse)
2. if cdrs_version == "0.1": run v0.1 checks only
   if cdrs_version == "0.2": run v0.1 checks + semantic checks
3. Per step: assertStepEquivalent (existing + accepted/rejection_reason)
4. After all steps:
   assertResolutionEquivalent(expected.resolution, actualWorld)
   assertEscalationEquivalent(expected.escalation, actualWorld)
   assertParticipationEquivalent(expected.participation, actualWorld)
   assertTimingEquivalent(expected.timing, actualWorld)
```

### Optional-field check pattern

```solidity
function assertResolutionEquivalent(
    ResolutionExpected memory exp,
    ActualResolution memory actual
) internal {
    if (!exp.present) return;
    if (exp.outcome != bytes32(0))
        assertEq(exp.outcome, actual.outcome, "resolution.outcome");
    assertTrue(
        actual.resolverWasAuthorized == exp.authorizedResolver,
        "resolution.authorized_resolver"
    );
    // ... etc.
}
```

**Critical rule**: if a field is present in `expected_semantics` but the contract
cannot observe it, the test MUST fail loudly — never silently skip a declared field.

---

## 5. Migration Plan

### Phase 1 — fixture generator (`trace_export.clj`) ← current branch

- Add `compute-trace-kind`: derives `trace_kind` array from scenario + trace content
- Add `compute-expected-semantics`: derives all semantic fields from replay result
- `export-trace-fixture` emits `cdrs_version: "0.2"`, `trace_kind`, `expected_semantics`
- Per-step `expected` gains `accepted`, `state_changed`, `rejection_reason`

### Phase 2 — schema files (`spec/`)

- Add `spec/cdrs-v0.2.md` (normative text)
- Add `spec/cdrs-trace-v0.2.schema.json` (alongside v0.1 schema)
- Add `spec/cdrs-event-v0.2.schema.json` (per-step additions)

### Phase 3 — TraceEquivalence.t.sol (on-chain contracts repo)

- Implement data structures and helper assertions
- Version gate: parse `cdrs_version`, run appropriate check set
- Per-step: add `accepted`/`rejection_reason` assertions
- Trace-end: full semantic assertion suite

### Phase 4 — fixture migration

- Existing `data/fixtures/traces/regression/` remain at v0.1 — no changes
- Named fixtures (`s01-*` through `s23-*`) are scenario inputs, not Forge fixtures — unchanged
- Newly generated fixtures automatically emit v0.2

### Backward compatibility

| Fixture version | TraceEquivalence behaviour |
|---|---|
| v0.1 | All existing checks pass; no new checks run |
| v0.2, no `expected_semantics` | Same as v0.1 (all sections `present: false`) |
| v0.2, partial `expected_semantics` | Only declared sections are checked |

---

## 6. Negative Test List

| ID | Setup | Injected drift | Field that catches it |
|---|---|---|---|
| N01 | Happy-path release | Change expected outcome to `"refund"` | `resolution.outcome` |
| N02 | Authorized resolver resolves | Mark `authorized_resolver: false` | `resolution.authorized_resolver` |
| N03 | Pending settlement trace | Mark `settlement_executed: false` | `resolution.settlement_executed` |
| N04 | DR3 L1 resolution | Set expected `escalation.level: 0` | `escalation.level` |
| N05 | DR3 L1 resolution | Set expected `tier: "l0"` | `escalation.tier` |
| N06 | s21 escalation clears pending | Set `pending_cleared_on_escalation: false` | `escalation.pending_cleared_on_escalation` |
| N07 | Release to seller | Set expected `beneficiary: "buyer"` | `resolution.beneficiary` |
| N08 | Pending settlement after appeal window | Mark `within_settlement_window: false` | `timing.within_settlement_window` |
| N09 | Auto-cancel expected but resolution ran | Set `auto_cancel_triggered: true` | `timing.auto_cancel_triggered` |
| N10 | Unauthorized rejection (s07 class) | Flip step `accepted: true` | `step.accepted` |
| N11 | Buyer raised dispute | Set expected `dispute_initiator: "seller"` | `participation.dispute_initiator` |
| N12 | Accounting-mismatch trace | Set expected `integrity: "fully-reconciled"` | `resolution.integrity` |
| N13 | Settled trace, beneficiary field declared | Contract cannot observe post-settlement beneficiary | Fails loudly: declared field unobservable |

---

## 7. Deferred Fields

| Field | Reason |
|---|---|
| `accounting.*` (all balance deltas, fees, bonds) | Requires per-step projection delta computation. Schema slots reserved; no `expected_semantics.accounting` block emitted or checked until v0.3 |
| Raw timestamps | Too unstable for equivalence. Covered by derived booleans |
| `resolution.resolution_hash` | Optional in schema; not verified in Solidity until a pre-image registry is available |
| `timing.auto_release_triggered` | Low frequency; deferred unless a timing trace explicitly exercises it |
| Coalition / payoff matrix | Multi-epoch only; deferred to v0.4 |
| `escalation.pending_cleared_on_escalation` | Included in schema and computed where derivable; complex for multi-escalation traces |
