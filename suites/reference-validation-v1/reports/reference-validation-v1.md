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
