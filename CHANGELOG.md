# Changelog

## [Unreleased]
### Added
- **Data-Driven Yield Mechanism:** Refactored yield implementation into a modular, data-driven system (`resolver-sim.yield` namespaces).
  - Replaced `YieldModule` protocol with declarative module definitions.
  - Implemented Aave v3 (share-based), Fixed-rate (principal-based), and Adversarial yield modules.
  - Decoupled yield mechanics (external mechanism) from yield policy (Sew-specific distribution).
  - Integrated yield-specific invariants into the main SEW invariant checker.
- **Yield Validation (Phase Y):** Created `phase_y.clj` to validate yield efficiency. Confirmed that 10% APR effectively covers protocol fees over long-duration disputes.
- **Protocol Solvency KPI:** Added a real-time solvency indicator to the Evidence Dashboard to monitor aggregate protocol health and value conservation.
- **Reorg Resilience (S46):** Added stochastic reorg and fork-reconciliation validation to ensure state derivation is idempotent across non-linear histories.

### Changed
- **Yield Generalisation (Phase 2):** Extended scenario-driven yield controls and failure semantics.
  - Added `:module-status` support to `:yield-config` initialization (`src/resolver_sim/yield/registry.clj`) via `[:yield/module-status <module-id>]`.
  - Enforced Aave module status behavior (`src/resolver_sim/yield/modules/aave.clj`):
    - `:active` allows deposits/withdrawals (subject to liquidity mode),
    - `:disabled-for-new-deposits` blocks new deposits while allowing withdrawals,
    - `:paused` blocks both deposits and withdrawals.
  - Enforced liquidity stress blocking for Aave ops when `:liquidity-mode` is `:shortfall`, `:frozen`, or `:paused`.
  - Added focused Phase 2 failure coverage (`test/resolver_sim/protocols/sew/yield/failure_test.clj`) for status/liquidity guard paths and emergency unwind behavior.
  - Updated replay/projection-facing yield tests to align with EVM-comparable projection boundaries (`test/resolver_sim/protocols/sew/yield/policy_test.clj`).
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
- **Simulation Logic (Phase AI):** Fixed capital drain bug in `phase_ai.clj` where system-wide costs were incorrectly applied in full to every individual resolver.
- **Protocol Logic:** Fixed cooldown bug that was incorrectly blocking first-time escalations.
- **Unit Tests:** Updated `replay_test.clj` to respect mandatory cooldown periods.
- **Simulation Gap H1:** Integrated `:identity/cheap-reentry` profile into Monte Carlo sweeps.
- **Simulation Gap H2:** Enabled and validated the per-member stochastic ring model for Phase AI sweeps.
- **CLI Robustness:** Restored `run-adversarial-search` in `src/resolver_sim/sim/adversarial.clj` to fix regression in CLI runner.
- **Reporting Logic:** Fixed bug in adversarial report generation that falsely flagged non-dominant attackers.
- **Documentation:** Updated `docs/simulation-checklist.md` with 3-dimensional evidence classification (Execution Backing, Model Depth, Claim Confidence).
- **Dashboard UI:** Hero section now supports direct jump via URL anchor (`#dashboard`) and shows 12 curated scenarios.
