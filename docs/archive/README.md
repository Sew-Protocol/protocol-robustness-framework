# docs/archive/

Internal planning documents, superseded phase specifications, and historical
research notes that are no longer part of the primary documentation surface.

These are preserved for historical context and reproducibility — they document
the original reasoning behind design decisions made during early phase work.

## Contents

| File | Original location | Status |
|------|------------------|--------|
| `PHASE_J_SPECIFICATION.md` | `docs/` | Phase J implementation spec — superseded by implemented code in `protocols/sew/`, `sim/multi_epoch.clj`, `adversaries/ring_attacker.clj` |
| `phase-i-automatic-detection.md` | `docs/` | Phase I detection phase spec — superseded by implemented code in `oracle/` |
| `phase-z-discovery.md` | `docs/` | Phase Z discovery spec — internal planning artifact |
| `GENERALISATION_REVIEW_2026-05-17.md` | `docs/` | Internal review at the point of generalization to protocol-agnostic framework |
| `subgame-perfect-improvements.md` | `docs/` | Research note on subgame-perfect improvements to resolver incentive model |
| `subgame-counterfactual-improvements.md` | `docs/` | Research note on counterfactual reasoning for resolver strategies |

## What "archived" means here

- These files will not be updated.
- They should not be treated as current documentation.
- They are preserved because they are referenced in git history and may be
  relevant for understanding implementation decisions.
- External reviewers: treat these as primary sources for historical context,
  not as current specification.
