# Changelog

## [Unreleased]
### Notes
- **Maintenance Reminder:** After finishing each change, update this changelog in the same PR/commit before marking work complete.

### Added
- **Data-Driven Yield Mechanism:** Refactored yield implementation into a modular, data-driven system (`resolver-sim.yield` namespaces).
  - Replaced `YieldModule` protocol with declarative module definitions.
  - Implemented Aave v3 (share-based), Fixed-rate (principal-based), and Adversarial yield modules.
  - Decoupled yield mechanics (external mechanism) from yield policy (Sew-specific distribution).
  - Integrated yield-specific invariants into the main Sew invariant checker.
- **Yield Validation (Phase Y):** Created `phase_y.clj` to validate yield efficiency. Confirmed that 10% APR effectively covers protocol fees over long-duration disputes.
- **Protocol Solvency KPI:** Added a real-time solvency indicator to the Evidence Dashboard to monitor aggregate protocol health and value conservation.
- **Reorg Resilience (S46):** Added stochastic reorg and fork-reconciliation validation to ensure state derivation is idempotent across non-linear histories.

### Changed
- **Equilibrium Evidence Policy (Priority 1):** Added `:equilibrium-claim-tier` handling in `src/resolver_sim/scenario/equilibrium.clj` with deviation-bundle gating for `:dominant-strategy-equilibrium` and `:nash-equilibrium` under `:deviation-tested` / `:population-tested` tiers. Added unit coverage in `test/resolver_sim/scenario/equilibrium_test.clj` and updated schema docs.
- **Game-Theory Formalization Maintenance:** Aligned `docs/CDRS-v1.1-THEORY-SCHEMA.md` mechanism/equilibrium proxy descriptions with current runtime behavior in `src/resolver_sim/scenario/equilibrium.clj`, and added `:evidence-schema-version "1.0"` to equilibrium evaluation output for explicit evidence payload versioning.
- **Cancellation Research Roadmap:** Added `docs/testing/CANCELLATION_GAME_THEORY_GAP_CHECKLIST.md` and linked it from `docs/testing/RUNNING_TESTS.md` to track concrete steps from proxy-level cancellation evidence toward stronger game-theoretic validation.
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
- **Dispute/Appeal Test Regression:** Updated `test/resolver_sim/protocols/sew/resolution_test.clj` to validate pending-clear-on-escalation via the public `escalate-dispute` API path, replacing a stale reference to removed internal helper `cancel-pending-on-escalation`.
- **Deadline Boundary Coverage (t-1):** Added explicit boundary regression tests for `escalate-dispute` and `challenge-resolution` to enforce exact window semantics: allowed at `t-1`, rejected at `t` with `:appeal-window-expired`.
- **Settlement Deadline Boundary Coverage:** Added `execute-pending-settlement` boundary coverage to enforce execution at exact deadline (`t == appeal-deadline`) and superseded-pending fallback selection when active pending was cleared near deadline.
- **Same-Timestamp Deadline Ordering Sweep:** Added ordering regression tests at the exact deadline to verify deterministic outcomes for `execute-pending-settlement`, `escalate-dispute`, and `challenge-resolution` when actions occur at the same timestamp in different orders.
- **Withdrawal Freeze/Timing Coverage:** Added replay-level withdrawal timing regression tests ensuring `withdraw_stake` is rejected while resolver freeze is active after executed fraud slash, and allowed exactly at the unfreeze boundary timestamp.
- **Withdraw Escrow Ordering + Invariant Fix:** Added same-timestamp `withdraw_escrow` liquidity-crunch ordering coverage and fixed `single-resolution-payout-consistent` invariant to allow terminal workflows with zero positive claimable balances after successful `withdraw_escrow`.
- **Withdraw Fees Ordering + Invariant Fix:** Added same-timestamp `withdraw_fees` liquidity-crunch ordering coverage and fixed cross-world `fees-monotone` invariant to allow fee decreases only when exactly matched by `total-withdrawn` increases (preserving drain detection while permitting legitimate fee withdrawals).
- **Mixed Withdrawal Ordering Final Pass:** Added same-timestamp mixed ordering coverage where `withdraw_escrow` and `withdraw_fees` execute in both orders within the same block/time, confirming deterministic success and no cross-operation accounting drift.
- **Withdraw Fees Authorization Hardening:** Fixed a privilege gap where any resolved actor could call `withdraw_fees`; now only governance actors can withdraw protocol fees. Added replay regression coverage for non-governance rejection and updated mixed/order tests to use governance caller for fee withdrawals.
- **Appeal-Window Edge Case (S77):** Prevented pending-settlement loss when a challenge/escalation occurs at `t-1` by archiving superseded pending decisions and allowing deadline execution fallback when no active replacement pending exists.
- **Temporal Boundary Expectations:** Updated S77 temporal boundary assertions to reflect fixed behavior: settlement executes at deadline from archived pending, and subsequent resolver action is rejected because the transfer is no longer in dispute.
- **Generalisation Reader/Load Stabilisation:** Cleared multiple malformed escaped-docstring/string reader failures and namespace-load blockers across core protocol/server/db namespaces.
- **Protocol Adapter Loading:** Removed a cyclic namespace-load path between `resolver-sim.protocols.sew` and `resolver-sim.protocols.sew.io.trace-export` by lazy-loading trace export in the Sew `:forge-trace` projection branch.
- **Simulation Logic (Phase AI):** Fixed capital drain bug in `phase_ai.clj` where system-wide costs were incorrectly applied in full to every individual resolver.
- **Protocol Logic:** Fixed cooldown bug that was incorrectly blocking first-time escalations.
- **Unit Tests:** Updated `replay_test.clj` to respect mandatory cooldown periods.
- **Simulation Gap H1:** Integrated `:identity/cheap-reentry` profile into Monte Carlo sweeps.
- **Simulation Gap H2:** Enabled and validated the per-member stochastic ring model for Phase AI sweeps.
- **CLI Robustness:** Restored `run-adversarial-search` in `src/resolver_sim/sim/adversarial.clj` to fix regression in CLI runner.
- **Reporting Logic:** Fixed bug in adversarial report generation that falsely flagged non-dominant attackers.
- **Documentation:** Updated `docs/simulation-checklist.md` with 3-dimensional evidence classification (Execution Backing, Model Depth, Claim Confidence).
- **Dashboard UI:** Hero section now supports direct jump via URL anchor (`#dashboard`) and shows 12 curated scenarios.
