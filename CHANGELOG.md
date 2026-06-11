# Changelog

## [Unreleased]
### Notes
- **Maintenance Reminder:** After finishing each change, update this changelog in the same PR/commit before marking work complete.

### Added (June 2026)
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


## 11 June 2026

# Bug fixes

1. financial/loss.clj — coverage-ratio denominator included fulfilled-total (already-paid). Split into outstanding = deferred + haircut for coverage check, keeping total-oblig = fulfilled + deferred + haircut for user-loss-ratio.
2. financial/loss.clj:173 — :claimable read as flat {token amount} but actual world structure is {wf {addr amt}}. Fixed with nested reduce summing across workflows.
3. financial/loss.clj:181 — Integer division returned Clojure Ratio instead of Double (1/5 ≠ 0.2). Wrapped with double.
4. test/resolver_sim/financial/loss_test.clj — 3 tests used wrong :claimable schema {:USDC 200} → {0 {"dummy" 200}}.
Tests added
5. test/resolver_sim/scenario/projection_test.clj — Added test-trace-end-projection-includes-financial-loss verifying :financial-loss in projection output.
6. test/resolver_sim/yield/liquid_lending_v2_test.clj — Rewrote 155 lines (was broken — referenced nonexistent liquid-lending-v2 module). Now uses current liquid-lending API with 7 passing tests.
Migration clean-up
7. test/resolver_sim/stochastic/oracle_fixture_test.clj — Added fixed-or-fraud-detection-active, fixed-or-timeout-detection-active, fixed-or-l2-detection-active, fixed-or-all-roll-kinds-consumed tests covering all 9 oracle roll kinds with non-zero probability thresholds.
8. src/resolver_sim/stochastic/detection.clj — Added detection-kind->prob-key, detection-kind->default-prob, dead-roll-kind-warnings to warn when :fixed-or specifies roll kinds with zero probability thresholds. Removed dead oracle-roll-consumption-order vector.

# Added

1. Artifact Registry v1.1 & Hardening
   * Upgraded Schema: Formally defined test-artifacts.v1.1 with mandatory importance (CORE/DIAGNOSTIC) and dependencies (SHA256
     binding).
   * Emitter Unification: Centralized all registry emission in write_scenario_run_manifest.py, removing 130+ lines of brittle inline
     Python from test.sh.
   * Transitive Dependency Closure: Emitter now automatically includes lower-importance artifacts if they are required to verify a
     CORE claim.
   * Overwrite Protection: Implemented a "chain-final" gate in the emitter that refuses to modify any directory containing a signed
     envelope.

  2. Authenticity & Researcher Attribution
   * Evidence Envelopes: Implemented envelope.json to cryptographically bind the registry hash, run ID, and timestamp to a signature.
   * Researcher Registry: Created keys/owners.json and keys/add_key.clj to map researcher identities to verified public key
     fingerprints.
   * Strict Verification: Created verify_claim.py and verify_evidence_bundle.py to validate the end-to-end chain: Signature → Envelope
     → Registry → Artifacts.

  3. Yield-v1 & Strategy Hardening
   * Vault-Centric Replay: Enabled multi-owner yield scenarios (Y01–Y04) using a "Thin Runner" that bypasses legacy Sew workflow-id
     requirements.
   * Precision Guard: Implemented canon-round in evidence_costs.clj to prevent non-deterministic settlement drift in stochastic profit
     models.
   * Strategy Correction: Fixed a bug where load-mult was incorrectly penalizing honest strategy profit instead of incentivizing lazy
     defection.

  4. Diagnostic Infrastructure
   * Attribution Quality: Upgraded with-attribution to report :complete or :partial metadata status in event-evidence artifacts.
   * Commit Provenance: Emitter now captures both the Git Hash and the Git Message at runtime for immediate human auditability.
   * Registry-First Notebooks: Refactored security_validation.clj and protocol_provenance.clj to load data via registry IDs rather
     than hardcoded file paths.


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


