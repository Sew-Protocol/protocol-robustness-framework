# docs/archive/

Internal planning documents, superseded phase specifications, and historical
research notes that are no longer part of the primary documentation surface.

These are preserved for historical context and reproducibility — they document
the original reasoning behind design decisions made during early phase work.

## Contents

| File | Original location | Archived | Reason |
|------|------------------|----------|--------|
| `PHASE_J_SPECIFICATION.md` | `docs/` | original | Phase J spec — superseded by `protocols/sew/`, `sim/multi_epoch.clj`, `adversaries/ring_attacker.clj` |
| `phase-i-automatic-detection.md` | `docs/` | original | Phase I detection spec — superseded by `oracle/` |
| `phase-z-discovery.md` | `docs/` | original | Phase Z discovery spec — internal planning artifact |
| `GENERALISATION_REVIEW_2026-05-17.md` | `docs/` | original | Internal review at point of generalisation |
| `subgame-perfect-improvements.md` | `docs/` | original | Research note on subgame-perfect improvements |
| `subgame-counterfactual-improvements.md` | `docs/` | original | Research note on counterfactual reasoning |
| `cartesi-REPO_ORGANIZATION_PLAN.md` | `docs/archive/` | original | Cartesi org plan — historical only |
| `ASSESSMENT_RESOLUTION.md` | `docs/archive/` | original | Phase I assessment resolution (Feb 2026) |
| `WEAKNESS_ANALYSIS.md` | `docs/` | 2026-05-21 | Self-marked superseded (Feb 2026); many gaps since closed — keep as audit trail |
| `RESEARCH_NOTE_V0.md` | `docs/` | 2026-05-21 | v0 research note; superseded by `evidence/RESEARCHER_EVIDENCE_PACK.md` |
| `cdrs-v0.2-design.md` | `docs/` | 2026-05-21 | Branch-specific design doc (`cdrs-v0.2`); not canonical until merged |
| `trace-end-equilibrium-validation.md` | `docs/` | 2026-05-21 | Design spec, status "ready for implementation"; archive until implemented |
| `overview-monte-carlo-history.md` | `docs/overview/README.md` | 2026-05-21 | Historical Monte Carlo phase overview; self-marked historical |
| `testing/SEW_COVERAGE_ROADMAP_2_4_8_WEEKS.md` | `docs/testing/` | 2026-05-21 | Dated planning roadmap; superseded by current scenario coverage |
| `testing/SIMULATION_TESTING_OUTLINE.md` | `docs/testing/` | 2026-05-21 | Layman's guide; superseded by `ROBUSTNESS_FRAMEWORK.md` |
| `testing/FIXTURE_BACKLOG_DIFF_SEMANTICS.md` | `docs/testing/` | 2026-05-21 | Backlog/planning artifact; actionable items should move to issues |
| `challenge/BENCHMARK_CHALLENGE.md` | `docs/challenge/` | 2026-05-21 | References stale protocol version (v0.1, commit `31ba27b`); refresh before republishing |
| `evidence/summary.md` | `docs/evidence/` | 2026-07-10 | Marked stale in README; needs refresh before return |
| `benchmarks/BENCHMARK_CONCEPT_REVIEW_HANDOFF.md` | `docs/benchmarks/` | 2026-07-10 | Agent-to-agent handoff document; not user-facing |
| `forensic/MECHANISM_PERSISTENCE_MINI_HANDOFF.md` | `docs/forensic/` | 2026-07-10 | Mini handoff with action TODO list; superseded by `docs/specs/MECHANISM_PERSISTENCE_SPEC_V1.md` |
| `finality/INTERACTIVE_FINALITY_SESSION_LOG.md` | `docs/finality/` | 2026-07-10 | Raw interactive research session log; not canonical |
| `evidence/historical-note.md` | `docs/evidence/` | 2026-07-10 | Purely historical context about pre-standard hashing |
| `scenarios-S01-S23.generated.md` | `docs/` | 2026-07-10 | Superseded by `docs/scenarios.md` (canonical scenario index) |
| `idepotence_analysis.md` | `docs/` | 2026-07-10 | Misspelled filename; superseded by `docs/testing/IDEMPOTENCE_CHECKLIST.md` |
| `researcher-log/dispute-resolution-research-log-*.edn` (4 files) | `docs/researcher-log/` | 2026-07-10 | Raw research session logs; preserved for historical traceability |
| `overview/STATE_MACHINE_GENERATED.md` | `docs/overview/` | 2026-07-10 | Superseded by `docs/protocols/sew/STATE_MACHINE_GENERATED.md` (more current transition list) |
| `overview/TRANSITION_COVERAGE_GENERATED.md` | `docs/overview/` | 2026-07-10 | Superseded by `docs/protocols/sew/TRANSITION_COVERAGE_GENERATED.md` (includes S62, S78-S81, S83, S86, S103-S109) |

## What "archived" means here

- These files will not be updated.
- They should not be treated as current documentation.
- They are preserved because they are referenced in git history and may be
  relevant for understanding implementation decisions.
- External reviewers: treat these as primary sources for historical context,
  not as current specification.
