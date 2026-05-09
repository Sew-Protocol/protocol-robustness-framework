# Fixture Backlog: High-Value Additions + Richer Diff Semantics

## Purpose

This backlog turns the fixture-gap assessment into actionable additions for:

1. semantic equivalence confidence (model ↔ EVM),
2. transition/guard regression strength,
3. richer path/accounting comparison semantics.

---

## A. High-Value Fixture Backlog

## A1) Authorization-path semantics (priority: P0)

| Proposed ID | Type | Intent | Expected outcome |
|---|---|---|---|
| `s43-auth-rejected-then-authorized-recovery` | pass | Unauthorized resolver attempt must reject, then authorized resolver succeeds with valid finality | pass |
| `s44-escalation-tier-mismatch-rejected` | pass | Resolver from wrong tier attempts resolution after escalation; must reject | pass |
| `s45-stale-module-snapshot-rejects-legacy-resolver` | pass | Resolver valid before governance/module change becomes invalid after snapshot boundary | pass |

**Required semantics to assert**
- `expected_semantics.participation.authorized_participant`
- `expected_semantics.resolution.authorized_resolver`
- per-step `accepted=false` + `rejection_reason` for unauthorized calls

---

## A2) Settlement-race counterfactual pairs (priority: P0)

| Proposed ID | Pair | Intent | Expected outcome |
|---|---|---|---|
| `s46a-settlement-before-escalation-window-edge` | pair A | Settlement executes just before escalation condition | pass |
| `s46b-escalation-before-settlement-window-edge` | pair B | Escalation lands first; settlement path must differ safely | pass |
| `s47a-appeal-window-last-second-settlement` | pair A | Settlement exactly at boundary (inclusive policy) | pass |
| `s47b-appeal-window-plus-one-rejected` | pair B | Settlement one second late must reject | pass |

**Required semantics to assert**
- `expected_semantics.timing.within_settlement_window`
- `expected_semantics.timing.pending_delay_seconds`
- `expected_semantics.resolution.settlement_executed`
- divergence in pair semantics must be intentional and documented

---

## A3) Escalation depth / pending-clear chains (priority: P1)

| Proposed ID | Type | Intent | Expected outcome |
|---|---|---|---|
| `s48-max-escalation-exact-boundary` | pass | Exact max level reached; accepted | pass |
| `s49-max-escalation-plus-one-rejected` | pass | One-above-max escalation rejected | pass |
| `s50-multi-hop-pending-cleared-every-hop` | pass | Pending settlement cleared consistently through multiple escalation hops | pass |

**Required semantics to assert**
- `expected_semantics.escalation.level`
- `expected_semantics.escalation.max_level_reached`
- `expected_semantics.escalation.accepted/rejected`
- `pending_cleared_on_escalation` equivalent marker for each hop

---

## A4) Accounting-integrity semantic fixtures (priority: P1)

| Proposed ID | Type | Intent | Expected outcome |
|---|---|---|---|
| `s51-held-delta-monotonic-under-resolution` | pass | `total-held` deltas match expected escrow exit path | pass |
| `s52-fee-delta-monotonic-under-fot` | pass | Fee-on-transfer + protocol fees preserve monotonic fee/held invariants | pass |
| `s53-pending-create-clear-balance-consistency` | pass | pending settlement create/clear has coherent accounting effects | pass |

**Required semantics to assert (new block; see section B)**
- `expected_semantics.accounting_min.*`

---

## A5) Adversarial sweep promotion fixtures (priority: P0 ongoing)

| Proposed ID Pattern | Type | Intent | Expected outcome |
|---|---|---|---|
| `adv-promo-f7-<hash>` | pass/fail depending claim | Promote top unsafe/sensitive points from profitability surfaces into deterministic traces | deterministic |
| `adv-promo-f8-<hash>` | pass/fail depending claim | Escalation economics boundary points | deterministic |
| `adv-promo-f10-<hash>` | pass/fail depending claim | Capacity-drain boundary points | deterministic |

**Required metadata**
- parameter signature (`fee_bps`, `latency_s`, `capacity`, `escalation_budget`)
- promotion source run id
- threat tags for CI grouping

---

## B. Richer Comparison Semantics Needed

Current diff semantics are good but not sufficient for equivalence-hard confidence.

## B1) Add minimal accounting semantic block (recommended now)

Add a lightweight optional block:

```json
"expected_semantics": {
  "accounting_min": {
    "held_delta_matches": true,
    "fees_delta_monotone": true,
    "pending_create_clear_consistent": true
  }
}
```

Use this before full v0.3 accounting richness.

## B2) Add path constraints (intermediate obligations)

```json
"expected_semantics": {
  "path_constraints": {
    "must_observe": [
      "rejection:unauthorized",
      "pending_cleared_after_escalation"
    ]
  }
}
```

This prevents false equivalence where end state matches but critical path obligations were skipped.

## B3) Add counterfactual pair metadata

```json
"comparison": {
  "comparison_group": "settlement-window-edge-01",
  "variant": "A",
  "counterfactual_of": "s47b-appeal-window-plus-one-rejected"
}
```

Enables intentional semantic divergence checks in pair runs.

---

## C. Suggested Suite Organization

- `:suites/equivalence-auth-paths` → S43–S45
- `:suites/equivalence-race-pairs` → S46/S47 pairs
- `:suites/equivalence-escalation-boundaries` → S48–S50
- `:suites/equivalence-accounting-min` → S51–S53
- `:suites/adversarial-promoted` → `adv-promo-*`

---

## D. Gating Recommendations

1. No missing fixture from P0 set for release candidates.
2. Pair suites must show expected A/B divergence with explicit semantic rationale.
3. All `accounting_min` checks pass when block is declared.
4. Promoted adversarial fixtures refreshed each cycle (or explicit no-new-risk report).

---

## E. Implemented Comparison Workflow (Current State)

The following comparison stack is now implemented and wired:

- `:suites/equivalence-auth-paths`
- `:suites/equivalence-race-pairs`
- `:suites/equivalence-escalation-boundaries`
- `:suites/equivalence-accounting-min`

Run all four with one command:

```bash
./scripts/test.sh equivalence-new
```

Validate pair metadata integrity only:

```bash
./scripts/test.sh comparison-lint
```

If local Clojure CLI is missing, the test runner now fails fast with a clear preflight error.

### CI gate

Workflow: `.github/workflows/sew-validation-gates.yml`

Job: `equivalence-comparison-gates`

Command:

```bash
./scripts/test.sh equivalence-new
```

Artifacts:

- `results/test-artifacts/` (uploaded as `equivalence-test-artifacts`)
- `results/test-artifacts/equivalence-comparison-summary.json` (generated by `equivalence-new`)

### Command / artifact matrix

| Command | Purpose | Key output |
|---|---|---|
| `./scripts/test.sh comparison-lint` | Structural validation of `comparison` + `path_constraints` metadata | `results/test-artifacts/.target-comparison-lint-<run>.log` + `test-summary.json` |
| `./scripts/test.sh equivalence-new` | Execute all comparison suites | `results/test-artifacts/equivalence-comparison-summary.json` + `test-summary.json` |

### Fixture authoring convention for pairwise comparisons

For counterfactual pair fixtures (A/B), include:

```json
"comparison": {
  "comparison_group": "<group-id>",
  "variant": "A|B",
  "counterfactual_of": "<paired-scenario-id>"
}
```

And include path obligations:

```json
"expected_semantics": {
  "path_constraints": {
    "must_observe": ["..."]
  }
}
```

This marks intentional divergence explicitly and reduces false-positive equivalence regressions.
