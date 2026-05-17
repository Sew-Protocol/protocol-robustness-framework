# Changelog

## [Unreleased]
### Added
- **Yield Module Simulation:** Implemented full yield accrual and distribution logic in the protocol model (`lifecycle.clj`, `accounting.clj`).
  - Added `:accumulated-yield` tracking per escrow.
  - Implemented `accrue-yield` based on block-time deltas and annualized yield rates.
  - Implemented `distribute-yield` with configurable presets (`:to-sender`, `:to-recipient`, `:split-50-50`).
- **Yield Validation (Phase Y):** Created `phase_y.clj` to validate yield efficiency. Confirmed that 10% APR effectively covers protocol fees over long-duration disputes.
- **Protocol Solvency KPI:** Added a real-time solvency indicator to the Evidence Dashboard to monitor aggregate protocol health and value conservation.
- **Reorg Resilience (S46):** Added stochastic reorg and fork-reconciliation validation to ensure state derivation is idempotent across non-linear histories.

### Changed
- **Developer Docs Baseline:** Updated `docs/testing/RUNNING_TESTS.md` with a dated (2026-05-17) contributor baseline covering:
  - parser/namespace blockers resolved during generalisation cleanup,
  - remaining behavioural failures now visible in canonical test runs,
  - current known release-gate path issue classification.
- **Docs Indexing:** Updated `docs/README.md` to explicitly point contributors to `docs/testing/RUNNING_TESTS.md` for canonical test entrypoints and current known-failure baseline.
- **Architecture Docs Alignment:** Updated both `docs/architecture/ARCHITECTURE.md` and `docs/architecture.md` to reflect current generalized adapter/persistence terminology:
  - protocol adapter interface wording (no stale 8-method claim),
  - generic XTDB table names (`sim_trial_results`, `sim_entity_events`),
  - current gRPC session API naming (`StartSession`, `Step`, `DestroySession`).
- **Docs Navigation/Status UX:** Updated `docs/README.md` with a task-oriented "Start here" section and explicit doc status conventions (canonical/companion/archived) to improve contributor onboarding and reduce doc drift.

### Fixed
- **Generalisation Reader/Load Stabilisation:** Cleared multiple malformed escaped-docstring/string reader failures and namespace-load blockers across core protocol/server/db namespaces.
- **Protocol Adapter Loading:** Removed a cyclic namespace-load path between `resolver-sim.protocols.sew` and `resolver-sim.protocols.sew.io.trace-export` by lazy-loading trace export in the SEW `:forge-trace` projection branch.

### Fixed
- **Simulation Logic (Phase AI):** Fixed capital drain bug in `phase_ai.clj` where system-wide costs were incorrectly applied in full to every individual resolver.
- **Protocol Logic:** Fixed cooldown bug that was incorrectly blocking first-time escalations.
- **Unit Tests:** Updated `replay_test.clj` to respect mandatory cooldown periods.

### Fixed
- **Simulation Gap H1:** Integrated `:identity/cheap-reentry` profile into Monte Carlo sweeps.
- **Simulation Gap H2:** Enabled and validated the per-member stochastic ring model for Phase AI sweeps.
- **CLI Robustness:** Restored `run-adversarial-search` in `src/resolver_sim/sim/adversarial.clj` to fix regression in CLI runner.
- **Reporting Logic:** Fixed bug in adversarial report generation that falsely flagged non-dominant attackers.

### Changed
- **Documentation:** Updated `docs/simulation-checklist.md` with 3-dimensional evidence classification (Execution Backing, Model Depth, Claim Confidence).
- **Dashboard UI:** Hero section now supports direct jump via URL anchor (`#dashboard`) and shows 12 curated scenarios.
