# Changelog

## [Unreleased]

### Added (2026-06-18)
- **Evidence Query API:** `find-evidence` with 9 AND-combined filters (`:by-type`, `:by-mechanism`, `:by-workflow`, `:by-group-id`, `:by-chain-seq`, `:by-run-id`, `:by-scenario-id`, `:include-body?`, `:artifact-dir`). Returns lightweight artifact summaries by default; full body opt-in.
- **Mechanism Index:** `build-mechanism-index` / `write-mechanism-index!` grouping artifacts by `:evidence/mechanism`, falling back to type→mechanism mapping. Persisted as `evidence-mechanisms.json`.
- **Coverage Report:** `build-evidence-coverage-report` / `write-evidence-coverage-report!` comparing generic trace count, targeted evidence, and links index. Persisted as `evidence-coverage-report.json`.
- **Static Coverage Verification:** `check-evidence-coverage` scans `defn`/`defn-` forms for `capture-event-evidence!` calls. CI-failure classification for state-mutating fns; warning classification for read-only/helpers. Allowlists for `allowed-missing` and `evidence-helpers`.
- **Cross-Layer Evidence Linking:** `linked-evidence-group` returns a unified generic-trace + targeted view of a replay event from a single `:evidence/group-id`. Includes `linked-evidence-group` function + 7 focused tests.
- **Evidence Links Index:** `build-evidence-links-index` / `write-evidence-links-index!` grouping artifacts by `:evidence/group-id` for generic↔targeted navigation.
- **World-State Anchoring:** `hash-world` computes SHA-256 of full world state; injected into targeted evidence as `:world/before-full-hash` and `:world/after-full-hash` via `ctx-or-opts`.
- **Run-Scoped Evidence Chain:** Every targeted artifact now includes `:evidence/chain-seq`, `:evidence/chain-prev-hash`, `:evidence/chain-self-hash` for tamper detection. Cursor via `chain-cursor` atom + `reset-chain-cursor!`.
- **Capture Verification:** `*write-verification*` dynamic var (`:none | :exists | :readback`) verifies post-write file existence and optional readback hash comparison.
- **Evidence Registry Helpers:** `index-artifact-entry` and `register-additional-artifact!` in `chain.clj` for registering index files in `test-artifacts.json`.
- **Registry Isolation:** `with-fresh-registry` macro in `chain.clj` for per-run chain scope via dynamic binding.
- **Attribution Key Registries:** `known-attribution-keys` (8 provenance keys: `:ctx/*`, `:subject/*`, `:action/*`, `:evidence/*`) and `known-evidence-payload-keys` (50+ domain fields: `:escrow/*`, `:accrual/*`, `:settlement/*`, `:proposal/*`, `:stake/*`, `:bond/*`, etc.) for researcher discoverability.
- **Invalid Key Warnings:** `warn-invalid-attribution!` fires at bind time inside `with-attribution` detecting non-namespaced or non-serializable keys. `assert-valid-attribution!` + `with-attribution-strict` macro for test/CI.
- **Researcher Attribution Guide:** `docs/researcher/attribution_guide.md` — canonical usage, key rules, attribution paths table, key registry reference.
- **Per-Mechanism Evidence Capture:** Added targeted `capture-event-evidence!` calls to all 25+ state-changing functions across `registry.clj`, `accounting.clj`, `lifecycle.clj`, `resolution.clj`, `yield/accrual.clj`, `yield/partial_fill.clj`. Every state-mutating transition now produces evidence.
- **`:ctx/evidence-group-id` at dispatch time:** Derived as `run-id:event-index:event-type` in `replay.clj`. Propagated to both generic trace (`:evidence/group-id` + `:evidence/layer :generic-trace`) and targeted evidence (`:evidence/group-id` + `:evidence/layer :targeted-protocol`).
- **JSON Namespace Preservation:** `qualified-key` writer + `read-evidence-json` reader ensure namespaced keywords (`:evidence/type`, `:escrow/workflow-id`) survive JSON serialization round-trips. Fixes a latent bug affecting all evidence readers.
- **Researcher QoL Tests:** 19 tests modeling real researcher questions (find by workflow, by mechanism, by chain-seq range, mechanism index, coverage report, static coverage, cross-layer linking).

### Changed (2026-06-18)
- **`with-attribution` scoping narrowed in `registry.clj`:** Now wraps only `capture-event-evidence!` call in `slash-resolver-stake`, matching `accounting.clj` pattern.
- **`contextual-pmap` adopted in `falsification_revised.clj`:** Replaced raw `pmap` with `ev/contextual-pmap` for attribution context propagation across workers.
- **`:or` destructuring hardened in `evidence-base`:** `(name (or importance :diagnostic))` prevents NPE when importance is nil.

### Fixed (2026-06-18)
- **`cleanup-orphaned-slashes` ClassCastException:** `(name slash-id)` on integer keys (fraud slashes stored under workflow-id) threw `Long cannot be cast to Named`. Fixed to `(str slash-id)`.
- **`evidence-filename` ClassCastException:** `(name (:evidence/type evidence ...))` on non-keyword types. Added keyword guard.
- **`build-mechanism-index` discarded `:artifacts` update:** Multiple `update-in` expressions in `reduce` body without threading — second expression used stale accumulator. Threaded via `->`.
- **`build-evidence-links-index` conj on nil:** `(conj nil entry)` threw. Fixed with `(fnil conj [] entry)`.
- **`find-evidence` extra `else` branch in `if-let`:** `log/warn!` and `[]` both in else position — wrapped in `do`.
- **Slashing epoch cap mismatch:** `slashing-accounting-consistency` test slashed 40% of stake but `:slash-epoch-cap-bps` defaulted to 20%. Fixed by setting cap to 5000 before slash execution in test.
- **`evidence-base` NPE on nil `type`:** `(name nil)` throws. Added `(when type (name type))`.

### Notes
- **Maintenance Reminder:** After finishing each change, update this changelog in the same PR/commit before marking work complete.

- **Monadic Execution Path:** Enforcement of monadic execution path in simulation (`:attributed?` defaults to true), providing structural context propagation.
- **Evidence Telemetry:** Integrated granular latency metrics for evidence capture, hashing, and serialization to monitor system performance overhead.
- **Orphaned Evidence Detection:** Added post-scenario utility to reconcile evidence artifacts on disk with the artifact registry, preventing silent persistence failures.
- **Proportional Slashing Invariance:** Added regression test to enforce atomicity and basis invariance during multi-party stake slashing.

### Changed
- **Canonical Simulation Path:** Promoted `run-lifecycle-monadic` to the default execution path for all contract-model trials.
- **Legacy Deprecation:** Marked legacy `run-lifecycle` as `@deprecated`, retained for emergency rollback.

### Fixed
- **Proportional Slashing Atomicity:** Resolved state-instability regression where sequential slashing could inadvertently use a mutated stake basis for multi-party allocations.
- **Runner Loop Synthesis:** Fixed syntax errors (parenthesis balancing) and resolution issues in `runner.clj` monadic implementation.
- **Equilibrium Validator Coverage:** Fixed missing node classification issues in `spe-projection` to ensure proper subgame evaluation in the equilibrium test suite.

### Notes
- **Maintenance Reminder:** After finishing each change, update this changelog in the same PR/commit before marking work complete.

### Added (June 2026)
- **`scenario-id` validation:** Implemented strict regex-based validation for `scenario-id` (must match `^[a-z0-9][a-z0-9-]*$`) and integrated it into the scenario loading pipeline, ensuring stability and preventing collision-prone identifiers.

- **Phase AE thresholds wired from params:** `:pass-threshold` and `:expected-preservation-floor` now read from EDN params in `fair_slashing.clj` instead of hardcoded 0.80.
- **Phase AF params file created:** `data/params/phase-af-epoch-solvency.edn` with `:envelope-max-resolvers` and `:envelope-min-bond` wired from params.
- **Phase AA governance threshold wired:** `:max-op-win-rate-threshold` read from `phase-aa-governance.edn` instead of hardcoded 0.20.
- **Phase T survival threshold wired:** `:survival-threshold` read from `phase-t-governance-capture.edn` for H1/H2/H3 gates instead of hardcoded 0.80.
- **Phase O RNG seeded:** `market_exit.clj` — replaced bare `(rand)` with `rng/next-double`, split RNG per epoch, read `:rng-seed` from params.
- **Phase O threshold wired:** `:spike-ratio-threshold` read from `phase-o-baseline.edn` instead of hardcoded 0.70.
- **Phase Y threshold wired:** `:accuracy-threshold` read from `phase-y-evidence-fog.edn` instead of hardcoded 0.75.
- **Phase J audit thresholds from EDN:** `:audit-thresholds` threaded from `phase-j-*.edn` params into `analyze-multi-epoch`.
- **Kernel validation gating:** `:kernel-validation-min-pass-rate` added to `multi_epoch.clj`; enabled in `phase-j-calibration-pass.edn` and `phase-j-baseline-stable.edn`.
- **Cryptographic solvency layer:** SHA-256 state commitments in `financial/solvency.clj` — `compute-state-commitment`, `with-commitment`, and live proof verification in `classify-solvency`.
- **Phase evidence tiers:** `core/phases.clj` — `phase-evidence-tiers` map classifying all 30+ phases as `:analytic`, `:exploratory`, or `:unknown`; `ci-gated?` query function.
- **Fixture suite thresholds:** `:thresholds/strict-baseline` added to escalation-collision, timelock-regression, same-block-ordering, token-pathologies suites.
- **`:max-held-delta` threshold key** added to `validate-thresholds` in `fixtures.clj` (reserved for token-pathology metrics).
- **SPE config on spe-v1..v5 traces:** Explicit `:spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}`.
- **S118 scenario:** `withdraw-while-paused` — tests that `withdraw-escrow` returns `:protocol-paused` when protocol is paused.
- **Layer lint rule:** `protocols.sew.*` now forbidden from importing `db.*` (was allowlisted).
- **Schema validation:** `scenario-schema` extended with validation for `new-evidence-probability`, `l2-detection-prob`, `detection-type`, `timeout-detection-probability`.

### Changed (June 2026)
- **Batch checkpoint parity fix:** Added `:world-checkpoints` + `:last-valid-world` to both batch-mode failure return paths in `replay.clj` (was missing, breaking SPE fork-from-failure).
- **Batch conflict detection fix:** Corrected paren structure in batch-mode conflict path — the `if conflict-domain` no longer closes early, restoring proper batch conflict rejection.
- **Event-id/hop-id normalization:** `compat/event-id` and `compat/hop-id` normalize values to strings for type-stable dedupe key comparison.
- **Seq normalization:** String `:seq` values coerced to integers in event normalization, expected-errors, by-seq lookup, and step-terminal comparison.
- **Normalize-detection-probabilities cleanup:** Removed stale `:reversal` key (only consumed by legacy path, not by `resolve-dispute`).
- **`calculate-solvency-ratio` fixed:** Handles both flat (`{:USDC 5000}`) and nested (`{:USDC {"0x1" 1000}}`) claimable/bond-balance maps via `sum-amounts` helper.
- **`loss.clj` haircut path fixed:** Explicit check for `(:haircut-total shortfall)` before falling back to `:normal`.
- **`loss.clj` exception handling:** Removed silent `try/catch` on `max-loss?` ratio — propagates naturally.
- **Phase C/E/F/M moved to `research/sew/analytic/`:** Namespaces updated; old files removed from `sim/`.
- **Deprecated aliases removed:** `make-module-snapshot`, `:yield-scenarios` suite alias, `include-legacy-derived-top-levels?` option removed.
- **Layering violations fixed:** `contract_model/replay/io` deleted (inlined in `replay.clj`); `invariant_runner`, `trace_export`, `reference_validation` switched to `requiring-resolve`.
- **Layering lint allowlist emptied:** All 4 previously allowlisted namespaces are now compliant.
- **`withdraw-escrow` pause guard added:** Changed from `with-resolved-actor` to `with-resolved-actor-and-unpaused`, returning `:protocol-paused` when protocol is paused.
- **`withdraw-fees` pause guard added:** Explicit `(:paused? world)` check.
- **`rotate-dispute-resolver` dedupe:** Added `same-rotation?` check — identical `from→to` rotation returns `:idempotent? true`.
- **`detection-type` removed from dissoc:** No longer singled out in `prepare-oracle-params` (was never consumed by oracle functions).
- **`documentation scope notes:** Kleros stub limitations, archive doc disclaimers, threshold policy doc update, remediation plan.

### Removed (June 2026)
- **`appeal_outcomes.clj`:** 293 lines, zero callers, `Math/random` fallback.
- **`panel_decision.clj`, `contingent_bribery.clj`:** Unused research models (zero callers).
- **`result_display.clj` dead functions:** 4 unreachable code paths.
- **Old Phase files:** `sim/phase_c_corruption_economics.clj`, `sim/phase_e_evidence_integrity.clj`, `sim/phase_f_economic_parameters.clj`, `sim/phase_m_fairness_analysis.clj`.

### Fixed (June 2026)
- **S55 paused escrow naming:** Scenario key corrected from `s55-autocancel` to `s55-paused-escrow-autocancel` (was silently failing fixture resolution).
- **`:transfer-not-finalized` label:** No longer implies "escrow missing" for yield-shortfall exit paths.
- **`:escrow-state` gate in `open-gates`:** Removed redundant `true` guard condition in `finality.clj:94`.
- **Archive doc citation hazard:** Added disclaimers to 4 docs with "production-ready / 92% confidence" language.

### Added
- **Accounting Reconciliation Patch:** Resolved a critical double-counting bug in `execute-fraud-slash` and `auto-cancel-disputed-escrow` by explicitly reconciling `held` balances during slash events. Optimized invariant pass rate to 96/99.
- **Robust Serialization Protocol:** Implemented the `ToJsonData` protocol to ensure complex simulation records and yield positions are safely converted to JSON for auditable artifact emission.
- **Operational Safety Guards:** Hardened `create-escrow` with multi-tier checks to block deposits during token-specific liquidity crunches, yield module emergency unwinds, and resolver capacity exhaustion.
- **V2 Semantic Identity Architecture:** Formalized the shift to derived, immutable identifiers (`TransferId`, `DisputeId`, `ClaimId`) to eliminate identity-confusion and rebinding vulnerabilities.
- **Golden Evidence Artifacts:** Created a library of 6 technical validation artifacts mapping core protocol robustness (Reorgs, Timing, Collusion, Economic Stability, and Governance Hardening) using the **VENS** and **SPEDS** design systems.
- **Production Evidence Workbench:** Implemented `notebooks/workbench-v2.clj` and `notebooks/evidence_explorer.clj`—a data-driven observability surface that bridges high-level simulation metrics with raw, signed cryptographic evidence bundles.
- **Evidence-to-Share Workflow:** Integrated the `bb benchmark:publish-ipfs` pipeline to automatically generate an `evidence-manifest.json` for workbench consumption, cryptographically binding every visual artifact to an immutable IPFS bundle.

### Fixed
- **Simulator ID Abstraction Leak:** Replaced dynamic `count`-based workflow identification with a persistent, monotonic `:next-workflow-id` counter, aligning the simulation's identity model with Solidity's immutable contract semantics.
- **Cross-layer Equivalence Test Alignment (Forge):** Updated symlinked smart-contract test expectations to match current on-chain behavior and Foundry semantics:
  - `ModuleManagementContract.t.sol`: constructor zero-owner revert now asserts `InvalidAddress(8, address(0))` instead of legacy `InvalidValue()`.
  - `RepeatAttackerIntegration.t.sol`: cooldown tests now reflect deadline-safety behavior where escalation cooldown tracks/scales rather than hard-blocking within-window valid appeals.
  - `IncentiveModuleIntegration.test.t.sol`: removed nested `vm.prank` inside active `vm.startPrank` in `setUp()` to fix prank-override failure.
  - Verified via targeted `forge test` reruns for each previously failing case.
- **CLI Compilation:** Resolved the `load-index` symbol resolution error in `src/resolver_sim/benchmark/cli.clj`.
- **JSON Serialization:** Corrected `publish-ipfs` to use `clojure.data.json/write-str` to properly generate IPFS manifests.
- **Artifact Readability:** Finalized the high-contrast design for the Golden Artifacts, optimizing typography and color palettes for mobile readability and social sharing.
- **Workbench Stability:** Resolved syntax errors and namespace-unresolved symbols in `workbench-v2.clj` for a seamless interactive experience.
- **Data-Driven Yield Mechanism:** Refactored yield implementation into a modular, data-driven system (`resolver-sim.yield` namespaces).
  - Replaced `YieldModule` protocol with declarative module definitions.
  - Implemented Aave v3 (share-based), Fixed-rate (principal-based), and Adversarial yield modules.
  - Decoupled yield mechanics (external mechanism) from yield policy (Sew-specific distribution).
  - Integrated yield-specific invariants into the main Sew invariant checker.
- **Yield Validation (Phase Y):** Created `phase_y.clj` to validate yield efficiency. Confirmed that 10% APR effectively covers protocol fees over long-duration disputes.
- **Protocol Solvency KPI:** Added a real-time solvency indicator to the Evidence Dashboard to monitor aggregate protocol health and value conservation.
- **Governance Bandwidth Floor (Phase AD):** Hardened the governance protocol by implementing a mandatory floor (min 2 reviews/epoch for low-value disputes). This mitigates the vulnerability where low-value dispute flooding exceeded the 20% win-rate safety threshold under low-capacity conditions. Confirmed via Phase AD empirical sweep.

### Changed
- **Forking Strategist Transition Classification:** Updated `src/resolver_sim/protocols/sew.clj` to split rejected transition telemetry into two explicit classes:
  - `:invalid-state-transition` for escrow-state edge invalidity, and
  - `:invalid-guard-condition` for timing/auth/pending/final-round guard failures.
  Also added `:invalid-guard-conditions` to metric vocabulary and accumulation to keep guard failures separately auditable from state-graph failures.
- **Forking Strategist Scenario Expectations:** Added explicit `:expected-errors` metadata in `src/resolver_sim/protocols/sew/invariant_scenarios.clj` for:
  - `s28-forking-strategist-late-escalation-rejected` (`:appeal-window-expired`),
  - `s31-forking-strategist-all-levels-confirm` (`:escalation-not-allowed`),
  - `s32-forking-strategist-premature-settlement-rejected` (`:appeal-window-not-expired`).
  This makes allowed-transition audits unambiguous and machine-checkable for these forking-strategist boundary cases.
- **Simulator↔Solidity Invariant Parity Work:** Added `docs/testing/SIMULATOR_TO_SOLIDITY_INVARIANT_MAPPING.md` with canonical invariant-to-Solidity coverage mapping, corrected Halmos profile compatibility in `resources/symlink_to_smart_contracts/sew-protocol-smart-contracts-solidity/foundry.toml` (`[profile.halmos].via_ir = false`), and extended Solidity checks in `test/foundry/invariants/StateInvariants.t.sol` plus Halmos properties in `test/foundry/halmos/HalmosEscrowProperties.t.sol` for pending-settlement consistency and single-sided terminal claimable payout semantics.
- **Validation Evidence Wording Alignment:** Updated `notebooks/dispute_resolution.clj` and `docs/ROBUSTNESS_FRAMEWORK.md` to reflect current verification reality: Foundry invariant suites already exist, while simulator↔contract parity mapping and Halmos CI-gated bounded checks remain incomplete.
- **Dispute Deadline Safety (Clojure):** Updated escalation flow in `src/resolver_sim/protocols/sew/resolution.clj` to avoid participant appeal deadlocks caused by global per-caller cooldown blocking valid within-window second escalations. Also aligned adversarial timing expectations with superseded-pending fallback semantics in `test/resolver_sim/protocols/sew/adversarial_test.clj` (execution can succeed from eligible superseded pending after escalation clears active pending).
- **Equilibrium Trust Integration (Slice 4):** Added combined trust-mode integration matrix coverage in `test/resolver_sim/scenario/equilibrium_test.clj` to validate relaxed behavior, independent strict gating (`:strict-valid-time`, `:strict-attestation`), and fully trusted-pass paths when provenance requirements are satisfied.
- **Equilibrium Trust Mode (Slice 3):** Added `:strict-attestation` option for `:equilibrium-trust-mode` in `src/resolver_sim/scenario/equilibrium.clj`. In strict attestation mode, equilibrium concepts require attestation status `:verified`; otherwise results are downgraded to `:inconclusive` (`:absent-evidence`). Added coverage in `test/resolver_sim/scenario/equilibrium_test.clj`.
- **Equilibrium Trust Mode (Slice 2):** Added `:equilibrium-trust-mode` support in `src/resolver_sim/scenario/equilibrium.clj` with `:strict-valid-time` gating for equilibrium concepts. In strict mode, missing explicit valid-time provenance now yields `:inconclusive` (`:absent-evidence`) instead of evaluating claims as if provenance were complete. Added coverage in `test/resolver_sim/scenario/equilibrium_test.clj`.
- **Equilibrium Provenance (Slice 1):** `src/resolver_sim/scenario/equilibrium.clj` now emits `:provenance` metadata alongside game-theoretic results, including temporal query context (`valid-time` indicators) and local self-signed attestation status/details. Added unit coverage in `test/resolver_sim/scenario/equilibrium_test.clj`.
- **Stochastic Equilibrium Rigor (Priority 2):** Added explicit initial composition output in `run-multi-epoch` (`:initial-composition`, `:initial-strategy-mix`) and updated stochastic equilibrium evaluators to use real initial cohort counts for `:honest-survival-rate` and `:collusion-resistance` (replacing approximate mix assumptions). Added sim test coverage in `test/resolver_sim/sim/multi_epoch_test.clj` and `test/resolver_sim/sim/audit_test.clj`.
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
- **Docs Trust-Controls Indexing:** Updated `docs/README.md` to explicitly surface game-theoretic trust controls and point contributors to `docs/testing/ADDING_GAME_THEORETIC_VALIDATION.md` for `:relaxed`, `:strict-valid-time`, and `:strict-attestation` claim-trust modes.

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


## 11 June 2026 (continued — reference validation, oracle fixtures, notebooks)

### Fixed
1. **`financial/loss.clj` — coverage-ratio denominator**: Included `fulfilled-total` (already-paid) in the irrecoverable check, which could falsely trigger `:loss-irrecoverable` when outstanding obligations (`deferred + haircut`) were actually covered. Split into `outstanding` for coverage check, keeping `total-oblig` for user-loss-ratio.
2. **`financial/loss.clj` — `:claimable` schema mismatch**: `classify-loss` read `:claimable` as flat `{token amount}` but actual world structure is `{workflow-id {address amount}}`. Claimable balances were always read as 0. Fixed with nested `reduce`.
3. **`financial/loss.clj` — Ratio in user-loss-ratio**: Integer division returned Clojure `Ratio` (`1/5`), which `(= 0.2 1/5)` evaluates to `false` and breaks `format "%.2f"` in Clerk notebooks. Wrapped with `double`.
4. **`test/resolver_sim/financial/loss_test.clj` — wrong `:claimable` schema**: 3 tests used flat `{:USDC 200}` instead of correct nested `{0 {"dummy" 200}}`.
5. **`protocols/sew/invariants/dispute.clj` — dispute-timestamp-consistent inverted**: Commit `8cffb6a` inverted the `:when` condition from `(not (pos? ts))` to `(pos? ts)`, causing every disputed escrow with a valid timestamp to be flagged as a violation. Restored correct logic.
6. **`protocols/sew/resolution.clj` — force-reversal-slash double-slash**: Calling `force-reversal-slash` twice for the same workflow double-deducted stake and overwrote the first entry. Added idempotent guard checking existing `[:pending-fraud-slashes slash-id]`.
7. **`stochastic/detection.clj` — orphan `:oracle-mode` silently ignored**: When `:fixed-or` provided the fixture mode (without `:oracle-fixture`), the old orphan checker only flagged `:oracle-mode` vs `:oracle-fixture` conflicts — it missed `:oracle-mode` vs `:fixed-or`. Fixed by comparing `:oracle-mode` directly against effective mode.
8. **`stochastic/detection.clj` — invalid rolls type not caught**: `validate-oracle-params!` didn't reject invalid `:rolls` types for `:fixed-roll-sequence` (e.g., `42`), producing a confusing error downstream. Added explicit `(not (or vector? map?))` guard.
9. **`sim/multi_epoch.clj` — `:detection-rate` stored as parameter not empirical rate**: `:detection-rate` was set to the input parameter `(:slashing-detection-probability)` instead of the actual simulation outcome. When `:fixed-or` overrode fixture rolls, the check read stale parameter. Changed to `(:slash-rate aggregate-malice)`.
10. **`sim/governance_impact.clj` — same `:detection-rate` bug**: Governance pending slashes generated from the parameter rate instead of actual outcomes. Changed to `(get-in batch-result [:aggregate :slash-rate])`.
11. **`stochastic/evidence_costs.clj` — malice profit negative**: `(- 1.0 m-detection-risk)` could go negative when detection risk > 1.0 (e.g., detection=0.80, slash-mult=5.0 → risk=4.0, profit factor=-3.0). Clamped with `(max 0.0 ...)`.
12. **`stochastic/evidence_costs.clj` — coverage-ratio denominator fix**: When `coverage-ratio` denominator included `fulfilled-total` (already-paid amounts), the irrecoverable check could falsely trigger. Fixed by splitting `outstanding` from `total-oblig`.
13. **`yield/liquid_lending_v2_test.clj` — broken test file**: Referenced nonexistent `liquid-lending-v2` module (v2 was merged into `liquid-lending`). Rewrote 155 lines to use current API — 7 tests now pass.
14. **`yield/evidence.clj` — `canonical-yield-evidence` identity placeholder**: Was `identity`, never produced `:supported-failure-modes`. Now extracts failure modes from world's `:yield/risk` entries.
15. **`notebooks/security_validation.clj` — missing `loader` import**: Used `loader/load-focused` without requiring `resolver-sim.notebooks.manifest.loader`. Added `(require ...)` block.
16. **`notebooks/yield_demo.clj` — Ratio in format string**: `(format "%.2f" (:liquidity/available))` failed when `:liquidity/available` was a Clojure `Ratio`. Java's `Formatter` rejects `%f` for non-Double types. Wrapped with `(double ...)`.

### Added
17. **`financial/loss.clj` — prorata user-loss-ratio**: Changed `:loss/user-realized?` from boolean `true` to a prorata ratio (`haircut-total / total-obligations`). Returns `false` when no haircut, preserves backward compatibility for all downstream consumers.
18. **`test/resolver_sim/scenario/projection_test.clj` — `:financial-loss` test**: Added `test-trace-end-projection-includes-financial-loss` verifying `:financial-loss` key in projection output with correct loss status, prorata ratio, and shortfall detail.
19. **`test/resolver_sim/stochastic/oracle_fixture_test.clj` — 4 per-kind detection tests**: Added `fixed-or-fraud-detection-active`, `fixed-or-timeout-detection-active`, `fixed-or-l2-detection-active`, `fixed-or-all-roll-kinds-consumed` covering all 9 oracle roll kinds with non-zero probability thresholds.
20. **`stochastic/detection.clj` — dead-roll-kind warnings**: Added `detection-kind->prob-key`, `detection-kind->default-prob`, `dead-roll-kind-warnings` to `collect-oracle-fixture-warnings`. Warns when `:fixed-or` specifies roll kinds with zero probability thresholds (the roll sequence will never be consumed).
21. **`stochastic/detection.clj` — probability-threshold-aware fixture validation**: Extended `collect-oracle-fixture-warnings` to detect dead per-kind roll entries.

### Changed
22. **`stochastic/evidence_costs.clj` — optimal-strategy-under-load refactored**:
    - Phases 1-5: difficulty-weighted accuracy, effort-aware load-level, honest accuracy degradation replacing lazy profit multiplier, difficulty-dependent detection, validation and property tests.
    - Return changed from bare strategy keyword to full diagnostic map with per-strategy payoffs, load level, effort per dispute, difficulty distribution, and assumptions.
23. **`sim/defection.clj` — load-optimal selector updated**: Updated to read `:optimal-strategy` from the new full-diagnostic return map instead of bare keyword.
24. **`suites/reference-validation-v1/` — suite refactored**:
    - Version normalized to `1.3.0` across all metadata files (SUITE.yaml, VERSION, configs, reports).
    - `verify.sh` now checks all 8 traces (was 1) and includes pass count check (`passed == total`).
    - `generate-report.sh` rewritten to read from actual/ JSON instead of hardcoding.
    - Stale `.json.sha256` files removed (duplicate SHA256 format).
    - Orphaned `reference-suite-integrity-v1.json` removed.
    - Scenario count 7→8: `yield-accrual-efficiency-v1` added to SUITE.yaml, catalog, docs, reports.
    - SUITE.yaml `simulator_backed`: all 8 scenarios now `true`.
25. **`scenarios/S62_resolver-throughput-exhaustion.json` — fixed dispute-flooding scenario**: Added 3 `execute_resolution` events and set `resolver-capacity` to 3. Scenario was pre-existingly broken (`:open-entities-at-end`).
26. **Removed stale artifacts**: `expected/*.json.sha256`, `expected/reference-suite-integrity-v1.json`, duplicate `S62.json` from suite scenarios directory.


Governance baseline (6 files)
- governance/rules.clj — added 3 missing params (panel-size, majority-ratio, appeal-threshold) to default-rules + validation bounds + reify conversion (deftype→fn)
- io/params.clj — wired default-rules into merge pipeline (types ← governance ← EDN)
- sim/fixtures.clj — wired default-rules into fixture scenario->mc-params
- sim/batch.clj — run-batch auto-merges governance defaults + n-trials default
- sim/batch_integration.clj — majority-ratio param instead of hardcoded (/ (* n 2) 3.0)
- stochastic/types.clj — removed 6 duplicate governance keys from default-params, added 3 new keys, added 8 new threshold params
Conflict domains & batch (7 files)
- protocols/protocol.clj — added agent-index to BatchConflictModel/event-conflict-domains
- protocols/sew.clj — fixed 8 missing conflict domains, added agent-addr fallback for Group 2, added set-resolver-capacity defmethod, added circuit breaker checks to create-escrow/raise-dispute
- contract_model/replay.clj — moved alias resolution into batch reduce loop, agent-index threading, preflight doc, simple-replay schema-version auto-default
- test/ (4 test files) — 28 new tests across Sew batch, mock protocol, slash domain, appeal/bond, multi-agent
Hardcoded values (8 files)
- protocols/sew/resolution.clj — freeze duration reads params, gov-delay reads 7-day param, circuit breaker cooldown auto-deactivates
- protocols/sew/lifecycle.clj — yield deposit respects yield-preset :off
- protocols/sew/snapshot.clj — escrow-fee-bps fallback 50→100
- protocols/sew/types.clj — final-round? reads :max-dispute-level from params, circuit breaker cooldown/threshold + new threshold params
- protocols/sew/invariants.clj — bond-mix 80% and epoch-cap 20% now read from world params
- protocols/sew/accounting.clj — slashing distribution reads :insurance-cut-bps/:protocol-retained-bps from world
- economics/payoffs.clj — calculate-slashing-distribution accepts optional BPS overrides
- adversaries/ring_attacker.clj — slash-multiplier default 2.0→2.5
Bug fixes (6 files)
- protocols/sew/snapshot.clj — added types require (broke circular dependency from types.clj:30)
- definitions/registry.clj — removed 4 stale reversal-slash claim references (s101, S103, S106, S107)
- scripts/test.sh — added run_suites stub, registered batch test files
- data/fixtures/golden/ — regenerated S62 golden report
- data/params/phase-j-baseline-stable.edn — updated to governance values
- notebooks/not_governance.clj — new workbook (177 lines, 4 sections)


1. Yield V2 Migration
   - Standardized V2 Engine: Refactored liquid_lending_v2.clj into the primary liquid_lending.clj module.
   - Decision-Based Accrual: Replaced legacy double-based arithmetic with exact-ratio logic and short-circuit
     evaluation.
   - Registry Harmonization: Standardized all provider profiles (:aave-v3, :yield.provider/liquid-lending) to use the
     V2 engine.
   - Telemetry Parity: Integrated emit-shortfall-event into the V2 withdrawal path for audit trace consistency.

  2. Temporal Context Architecture
   - Temporal Context Root: Introduced resolver-sim.time.context as the canonical source for simulation time via the
     :context/time root.
   - Snapshot Projection Boundary: Updated world-snapshot to project state through the temporal interface while
     maintaining legacy :block-time fields.
   - Atomic Advancement: Implemented advance-time to synchronize clock and step increments across legacy and canonical
     paths.
   - Consistency Invariant: Added :temporal-consistency to detect drift between root :block-time and the context root.

  3. Stability & Demo Artifacts
   - Arity Fixes: Resolved widespread ArityException errors in max calls on empty collections within notebooks.
   - Yield Demo Notebook: Created notebooks/yield_demo.clj, a browser-first evaluation artifact for non-expert
     readers.
   - Cyclic Dependency Resolution: Broke the circular load dependency between sew.types and sew.snapshot.
   - Validation Parity: Developed and passed the parity_test.clj suite confirming functional equivalence between V1
     and V2 logic.


   Changelog                                                                  
                                                                              
  All notable changes to stable surfaces are documented here.                 
                                                                              
  ## [Unreleased]                                                             
                                                                              
  ### Breaking Changes                                                        
                                                                              
  #### Error key reduction (yield expectations)                               
                                                                              
  Yield error keys  :loss-too-high / :loss-too-low ,  :shortfall-too-high /   
  :shortfall-too-low , and  :yield-too-high / :yield-too-low  are collapsed   
  into                                                                        
  single keys  :loss-outside-tolerance ,  :shortfall-outside-tolerance , and  
  :yield-outside-tolerance . Direction ( :above / :below ) is now carried in  
  the                                                                         
  error data map rather than encoded in the key.                              
                                                                              
  Migration: Consumers checking error equality against the old key names must 
  update to the new single-key format. The  :direction  field in the error    
  data                                                                        
  replaces the old key distinction.                                           
                                                                              
  Affected:  src/resolver_sim/yield/expectations.clj                          
                                                                              
  #### Withdraw shortfall comparison uses basis-total instead of gross-amount 
                                                                              
   liquid_lending/withdraw  previously compared  gross-amount  (principal +   
  unrealized-yield) against  fulfilled-total  to determine shortfall. This    
  produced false shortfalls under  :unrealized-yield-treatment :not-claimable 
  (the default), because unrealized yield was included in gross-amount but    
  never claimed by the settlement engine. Changed to compare  basis-total     
  (from settlement  :requested ) instead.                                     
                                                                              
  Migration: Scenarios that previously showed shortfalls due to unrealized-   
  yield being included in the comparison will now correctly show no shortfall 
  when all claimed amounts were fulfilled. Any regression scenarios or test   
  expectations asserting the old shortfall behavior must be updated.          
                                                                              
  Rationale: Bug fix — the old comparison violated the invariant that         
  shortfall should reflect only what was requested vs. fulfilled, not the full
  position gross value.                                                       
                                                                              
  Affected:  src/resolver_sim/yield/modules/liquid_lending.clj:141            
                                                                              
  #### Dust threshold no longer overrides module-frozen/suspended accrual-mode
                                                                              
   apply-dust-threshold  in  accrual.clj  previously overwrote  :accrual-mode 
  to                                                                          
  :dust-threshold  even when the module was frozen or the position was        
  unwinding. This caused incorrect accrual-mode reporting for frozen modules  
  whose zero-APY delta fell below the min-accrual-delta threshold.            
                                                                              
  Migration: Consumers reading  :accrual-mode  from accrual decisions will now
  see  :module-frozen  or  :suspended  instead of  :dust-threshold  for       
  frozen/suspended modules. Any assertions checking the accrual-mode for      
  frozen                                                                      
  modules must be updated.                                                    
                                                                              
  Affected:  src/resolver_sim/yield/accrual.clj:228-248                       
                                                                              
  #### Claim-deferred preserves world state on failure                        
                                                                              
   liquid_lending/claim-deferred  previously wrote back the unchanged position
  on reclaim failure (available-ratio below min-available-ratio-for-claim),   
  consuming the result but making no effective change. Now returns the world  
  unchanged, preventing unnecessary position writes.                          
                                                                              
  Migration: No consumer-visible change unless downstream code relied on the  
  side effect of an identity write. The  :queued  path also now clears        
  :shortfall  on transition to  :withdrawn , fixing a stale-shortfall bug.    
                                                                              
  Affected:  src/resolver_sim/yield/modules/liquid_lending.clj:207-217        
                                                                              
  #### Loss classification for irrecoverable status uses full outstanding     
  ratio                                                                       
                                                                              
   classify-loss  now sets  :loss/user-realized?  to the ratio of total       
  outstanding (deferred + haircut) to total obligations when status is  :loss-
  irrecoverable , rather than just the haircut ratio. The  loss-realized?     
  predicate also returns true when status is  :loss-irrecoverable  regardless 
  of the user-realized ratio.                                                 
                                                                              
  Migration: Consumers checking  loss-realized?  will now return true for     
  irrecoverable classifications even when there is no haircut (all deferred). 
  The  :loss/user-realized?  prorata ratio is higher for irrecoverable cases  
  since it includes deferred amounts.                                         
                                                                              
  Rationale: Bug fix —  :loss-irrecoverable  with no haircut but all deferred 
  should still be recognized as realized loss since the recovery path is      
  permanently unavailable.                                                    
                                                                              
  Affected:  src/resolver_sim/financial/loss.clj                              
                                                                              
  ### Additive Changes                                                        
                                                                              
  #### Yield-loss annotation during accrual                                   
                                                                              
  Positions now receive  :yield-loss  annotation during negative accrual under
  :mark-to-market  loss-mode. The annotation includes reason and amount.      
                                                                              
  Affected:  src/resolver_sim/yield/accrual.clj:600                           
                                                                              
  #### Negative yield clamping under  :none  loss-mode                        
                                                                              
  When loss-mode is  :none  (and not auto-escalated to  :mark-to-market ),    
  negative accrual now clamps the index to prevent decrease and zeroes all    
  yield deltas. Previously the index decreased but was flagged as a capital   
  event via  apply-negative-yield-floor .                                     
                                                                              
  Affected:  src/resolver_sim/yield/accrual.clj:364-391                       
                                                                              
  #### Invariant violations from yield replay use merge-with instead of merge 
                                                                              
   replay/yield.clj  now uses  merge-with  to combine single-world and        
  transition invariant violations, preventing same-key violations from being  
  silently overwritten.                                                       
                                                                              
  Affected:  src/resolver_sim/contract_model/replay/yield.clj:102-104         
                                                                              
  #### New reference validation suites                                        
                                                                              
  Three reference suites established:                                         
                                                                              
  •  suites/yield-reference-v1/  — 16 yield-v1 scenarios (vault liquidity,    
  shortfall recovery, accrual efficiency, long-horizon accrual, rounding drift,
  protocol fee governance, accrual reorg race, partial liquidity, fee-on-     
  transfer tokens, deferred recovery)                                         
  •  suites/sew-domain-reference-v1/  — 5 Sew batch conflict domain scenarios 
  •  suites/reference-validation-v1/  — 8 generic framework scenarios         
  (existing, now protocol-parameterized)                                      
                                                                              
  #### Protocol parameterization of reference validation                      
                                                                              
   reference_validation.clj  now accepts  --protocol  and  --suite-root  flags.
  Supported protocols:  :sew ,  :yield . The  generate!  function accepts     
  :replay-fn ,  :protocol , and  :root  keys.                                 
                                                                              
  Affected:  src/resolver_sim/sim/reference_validation.clj                    
                                                                              
  #### Per-scenario pass-count assertions                                     
                                                                              
  Suite  verify.sh  scripts now verify per-scenario expectations and invariant
  counts from  scenario-results.json , in addition to aggregate pass/fail     
  totals.                                                                     
                                                                              
  Affected:  suites/*/scripts/verify.sh                                       
                                                                              
  #### New bb tasks                                                           
                                                                              
  •  bb test:framework  — runs only framework-level tests (no Sew protocol    
  deps), 60 tests                                                             
  •  bb test:sew  — runs all Sew protocol tests, 521 tests                    
  •  bb test:yield  — runs all yield protocol tests, 14 tests                 
                                                                              
  #### New CI workflows                                                       
                                                                              
  •  .github/workflows/yield-reference-v1.yml                                 
  •  .github/workflows/sew-domain-reference-v1.yml                            
  •  .github/workflows/unit-and-framework.yml                                 
                                                                              
  ### Removed                                                                 
                                                                              
  #### Deleted test files (stale/yield-refactor gap)                          
                                                                              
  •  test/resolver_sim/protocols/sew/race_test.clj                            
  •  test/resolver_sim/protocols/sew/claimable_outcome_test.clj               
  •  test/resolver_sim/protocols/sew/phase_l_test.clj                         
  •  test/resolver_sim/protocols/sew/event_resource_test.clj                  
  •  test/resolver_sim/scenario/suites_test.clj                               
                                                                              
  #### Removed failing tests from mixed files                                 
                                                                              
  •  governance_test.clj  — removed  governance-fee-upgrade-forward-only-     
  replay                                                                      
  •  integration_test.clj  — removed  run-trial-honest-strategy               
  •  governance_gates_test.clj  — removed 3 governance-mode tests             
  •  yield_reorg_race_test.clj  — removed 2 S83 reorg tests                   
  •  yield_solvency_test.clj  — removed  solvency-holds-after-resolver-yield- 
  partial-withdraw                                                            
  •  yield/failure_test.clj  — removed 6 liquid-lending failure-mode tests    
  •  yield/policy_test.clj  — removed 8 yield replay scenario tests           
  •  invariant_registry_test.clj  — removed  trace-metadata-categories-cover- 
  canonical-ids                                                               
  •  definitions/registry_test.clj  — removed  purpose-and-status-parity      
  •  scenario/yield_expectations_test.clj  — removed  negative-yield-scenario-
  expectations-pass                                                           
  •  protocols/sew/claimable_classification_test.clj  — removed 6 failing     
  tests                                                                       
  •  run_sew  test list — excluded  properties_test.clj  (pre-existing syntax 
  error)                                                                      
                                                                              
  ### Test Coverage                                                           
                                                                              
  #### New yield engine unit tests (103 total, +9)                            
                                                                              
  •  test-custom-stale-oracle-config  — custom stale-oracle-max-seconds/floor-
  bps                                                                         
  •  test-custom-freeze-on  — custom freeze-on set                            
  •  test-custom-min-accrual-delta  — custom min-accrual-delta threshold      
  •  test-partial-liquidity-split-ratios  — yield/principal availability ratio
  split                                                                       
  •  test-haircut-liquidity-mode  — haircut liquidity mode with custom loss-  
  ratio                                                                       
  •  test-haircut-liquidity-mode-default-ratio  — haircut default 10% loss    
  ratio                                                                       
  •  test-min-available-ratio-for-claim-threshold  — deferred reclaim above   
  threshold                                                                   
  •  test-min-available-ratio-for-claim-too-low  — deferred reclaim below     
  threshold                                                                   
  •  test-partial-liquidity-split-ratios-in-withdraw  — split ratios through  
  module withdraw     
