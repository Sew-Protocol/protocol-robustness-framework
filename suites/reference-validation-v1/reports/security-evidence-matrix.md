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
