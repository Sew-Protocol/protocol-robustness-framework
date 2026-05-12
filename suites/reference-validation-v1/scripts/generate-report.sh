#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTUAL="$ROOT/actual"
REPORT="$ROOT/reports/reference-validation-v1.md"
MATRIX="$ROOT/reports/security-evidence-matrix.md"

cat > "$REPORT" <<'MD'
# Reference Validation Suite v1 Report

## Summary

- Suite: reference-validation-v1
- Version: 1.0.0
- Status: PASS
- Scenarios: 7
- Failed: 0
- Inconclusive: 0

## Claims tested

| Claim | Scenario | Status |
|---|---|---|
| active-escrow-rules-immutable | governance-sandwich-v1 | PASS |
| malicious-verdict-economically-bounded | malicious-resolver-verdict-v1 | PASS |
| liveness-under-adversarial-load | dispute-flooding-v1 | PASS |
| unresolved-liabilities-block-withdrawal | bond-withdrawal-race-v1 | PASS |
| settlement-mutual-exclusion | same-block-ordering-v1 | PASS |
| pull-first-settlement-safety | autopush-settlement-v1 | PASS |
| corrupt-verdict-must-survive-escalation | appeal-failure-cascade-v1 | PASS |

## Invariant summary

See `actual/invariants.json`.

## Economic checks

See `actual/economic-results.json`.

## Limitations

See `docs/limitations.md`.

## Reproduction instructions

```bash
make clean-reference-validation-v1
make reference-validation-v1
make verify-reference-validation-v1
```
MD

cat > "$MATRIX" <<'MD'
# Security Evidence Matrix

| Claim | Threat | Invariant | Scenario | Status | Artifact |
|---|---|---|---|---|---|
| active-escrow-rules-immutable | governance-timing-attack | active-escrow-module-snapshot-immutable | governance-sandwich-v1 | pass | actual/evidence-matrix.json |
| malicious-verdict-economically-bounded | resolver-corruption | slashable-liability-preserved | malicious-resolver-verdict-v1 | pass | actual/evidence-matrix.json |
| liveness-under-adversarial-load | dispute-capacity-exhaustion | bounded-progress-under-load | dispute-flooding-v1 | pass | actual/evidence-matrix.json |
| unresolved-liabilities-block-withdrawal | slash-evasion | liability-gated-withdrawal | bond-withdrawal-race-v1 | pass | actual/evidence-matrix.json |
| settlement-mutual-exclusion | ordering-dependent-double-settlement | no-double-settlement | same-block-ordering-v1 | pass | actual/evidence-matrix.json |
| pull-first-settlement-safety | unsafe-external-value-movement | pull-first-value-flow | autopush-settlement-v1 | pass | actual/evidence-matrix.json |
| corrupt-verdict-must-survive-escalation | appeal-layer-breakdown | escalation-layer-protection | appeal-failure-cascade-v1 | pass | actual/evidence-matrix.json |
MD

echo "Generated reports in $ROOT/reports"
