# Scenario Catalog

| ID | Scenario | Claim | Threat | Invariants | Status | Evidence Type | Simulator Backed | Confidence | Upgrade Path |
|---|---|---|---|---|---|---|---|---|---|
| 001 | governance-sandwich-v1 | active-escrow-rules-immutable | governance-timing-attack | active-escrow-module-snapshot-immutable, governance-forward-only | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
| 002 | malicious-resolver-verdict-v1 | malicious-verdict-economically-bounded | resolver-corruption | slashable-liability-preserved | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
| 003 | dispute-flooding-v1 | liveness-under-adversarial-load | dispute-capacity-exhaustion | bounded-progress-under-load | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
| 004 | bond-withdrawal-race-v1 | unresolved-liabilities-block-withdrawal | slash-evasion | liability-gated-withdrawal | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
| 005 | same-block-ordering-v1 | settlement-mutual-exclusion | ordering-dependent-double-settlement | no-double-settlement | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
| 006 | autopush-settlement-v1 | pull-first-settlement-safety | unsafe-external-value-movement | pull-first-value-flow | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
| 007 | appeal-failure-cascade-v1 | corrupt-verdict-must-survive-escalation | appeal-layer-breakdown | escalation-layer-protection | pass | pinned-derivation | false | provisional | wire deterministic simulator replay + trace hash |
