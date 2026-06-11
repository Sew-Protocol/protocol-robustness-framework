# Reference Validation Suite v1 Report

## Summary

- Suite: reference-validation-v1
- Version: 1.3.0
- Status: PASS
- Scenarios: 8
- Passed: 8
- Failed: 0
- Inconclusive: 0
- Expected passed: 8
- Generated: 2026-06-11T21:10:50Z

## Claims tested

| Claim | Scenario | Status |
|---|---|---|
| governance-sandwich-v1 | active-escrow-rules-immutable | pass |
| malicious-resolver-verdict-v1 | malicious-verdict-economically-bounded | pass |
| dispute-flooding-v1 | liveness-under-adversarial-load | pass |
| bond-withdrawal-race-v1 | unresolved-liabilities-block-withdrawal | pass |
| same-block-ordering-v1 | settlement-mutual-exclusion | pass |
| autopush-settlement-v1 | pull-first-settlement-safety | pass |
| appeal-failure-cascade-v1 | corrupt-verdict-must-survive-escalation | pass |
| yield-accrual-efficiency-v1 | yield-accrual-covers-protocol-fees | pass |

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
