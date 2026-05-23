# Documentation Index

Primary project framing and status live in the root `../README.md`.

## Start here by task

| Goal | Document |
|---|---|
| System overview (narrative) | `SYSTEM_OVERVIEW.md` |
| System overview (technical / architecture) | `architecture/ARCHITECTURE.md` |
| Run validation suite | `testing/RUNNING_TESTS.md` |
| Understand trust controls for game-theoretic claims | `testing/ADDING_GAME_THEORETIC_VALIDATION.md` |
| Set up and run locally | `quickstart/QUICKSTART.md` |
| Understand adapter / framework boundaries | `framework-boundaries.md` |
| Reusable abstractions (kernel/adapter/components) | `overview/REUSABLE_COMPONENTS.md` |
| Scenario index and protocol properties | `scenarios.md` |
| Evidence for external reviewers | `evidence/RESEARCHER_EVIDENCE_PACK.md` |

## Documentation status convention

- **Canonical**: primary source of truth for a topic.
- **Companion**: practical or narrowed companion to a canonical doc.
- **Archived**: historical context only; do not use for current implementation decisions.

## System and architecture

- `SYSTEM_OVERVIEW.md` — **canonical** narrative: two engines, current findings, roadmap, technical architecture
- `ROBUSTNESS_FRAMEWORK.md` — **canonical** adversarial validation, deterministic replay, simulation architecture
- `architecture/ARCHITECTURE.md` — **canonical** layering rules, namespace map, generalisation matrix
- `framework-boundaries.md` — **canonical** framework / adapter / Sew / research track boundaries
- `architecture/ADAPTER_AUTHORING_GUIDE.md` — how to implement a protocol adapter
- `architecture/DECISION_FRAMEWORK.md` — confidence-level calibration for reviewers
- `interface-contract.md` — Python/Clojure gRPC bridge interface contract
- `architecture/ADR-0003-canonical-scenario-generation-boundary.md` — ADR
- `architecture/ADR-0004-cross-protocol-funds-ledger-extraction.md` — ADR

## Testing and validation

- `testing/RUNNING_TESTS.md` — canonical test entrypoints and baseline
- `testing/TEST_SUITE.md` — full suite coverage and structure
- `testing/TRAJECTORIES.md` — trajectory technical reference
- `scenarios.md` — scenario index, evidence classification, protocol properties
- `CDRS-v1.1-THEORY-SCHEMA.md` — CDRS theory schema (scenario classification)
- `testing/ADDING_GAME_THEORETIC_VALIDATION.md` — contributor guide for adding new game-theoretic validation checks (single-trace and multi-epoch)
  - Includes trust-control slices for equilibrium evidence:
    - `:equilibrium-trust-mode :relaxed` (default)
    - `:equilibrium-trust-mode :strict-valid-time`
    - `:equilibrium-trust-mode :strict-attestation`

## Evidence and research

- `evidence/README.md` — evidence directory entry point
- `evidence/RESEARCHER_EVIDENCE_PACK.md` — ≤15-minute reproducibility pack
- `evidence/summary.md` — adversarial simulation evidence summary ⚠️ *(stale commit ref — needs refresh)*
- `evidence/detailed/` — per-failure-class evidence with raw JSON traces

## Security and threat model

- `security-model.md` — simulation threat model and security assumptions

## Usage and onboarding

- `quickstart/QUICKSTART.md` — setup and first run
- `usage.md` — CLI/API usage reference

## Notebooks

- `notebooks/README.md` — Clerk interactive workbench index

## Protocol-specific reference

- `overview/REUSABLE_COMPONENTS.md` — adapter/harness/fixture reuse guide
- `overview/USE_OF_FUNDS.md` — use-of-funds accounting contract
- `requirements/sybil-mitigation-roadmap.md` — Sybil ring mitigation requirements

## Historical and superseded

Archived docs are in `archive/`. They document original reasoning and are preserved
for reproducibility but must not be treated as current specification.
