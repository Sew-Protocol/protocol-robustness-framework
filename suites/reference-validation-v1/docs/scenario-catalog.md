# Scenario Catalog

Canonical CI set (7 scenarios). Source of truth: `../manifest.edn`.

| ID | Claim | Threat | Evidence | Simulator | Confidence |
|---|---|---|---|---|---|
| governance-sandwich-v1 | active-escrow-rules-immutable | governance-timing-attack | simulator-backed | yes (S61) | high |
| malicious-resolver-verdict-v1 | malicious-verdict-economically-bounded | resolver-corruption | pinned-derivation | no | provisional |
| dispute-flooding-v1 | liveness-under-adversarial-load | dispute-capacity-exhaustion | pinned-derivation | no | provisional |
| bond-withdrawal-race-v1 | unresolved-liabilities-block-withdrawal | slash-evasion | pinned-derivation | no | provisional |
| same-block-ordering-v1 | settlement-mutual-exclusion | ordering-dependent-double-settlement | pinned-derivation | no | provisional |
| autopush-settlement-v1 | pull-first-settlement-safety | unsafe-external-value-movement | pinned-derivation | no | provisional |
| appeal-failure-cascade-v1 | corrupt-verdict-must-survive-escalation | appeal-layer-breakdown | pinned-derivation | no | provisional |

Draft / future scenarios (not gated in CI) live under `../draft/expected/`.
