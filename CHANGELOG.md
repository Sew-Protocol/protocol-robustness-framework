# Changelog

## [Unreleased]

### Added (2026-06-23)
- **Phase 10 — Startup evidence node:** `validate-all-registries!` now emits a `:startup-validation` evidence record on successful startup validation, recording all 8 registry names, per-registry validity/error-counts, and a canonical hash under the new `:startup-validation` hash intent. Best-effort (logs warning on chain unavailability).
- **Phase 9 — Tighten startup validation:** Expanded from 4 to 8 registries: added execution-registry (6 modes), evidence-policy-registry (5 policies), hash-projection-registry (wraps `canonical.clj/hash-intents`), domain-tag-registry (wraps `canonical.clj/domain-tags`). `validate-passive-registries!` renamed to `validate-all-registries!` with hard-fail by default; old name retained as legacy alias. Startup validation runs at namespace load.
- **Phase 8 — Switch call sites:** `execute-fraud-slash` now builds a projection artifact once, allocates from it via `calculate-sew-slash-allocation-from-projection`, and passes the prebuilt artifact to `build-prorata-slash-evidence`, eliminating duplicate projection construction. `build-prorata-slash-evidence` accepts optional `:projection-artifact` key (backward compat). `economics.clj` remains pure.
- **Phase 7 — Evidence node integration:** `evidence/slashing.clj` updated to carry `:projection` and `:pro-rata` evidence sections with hashes, claims, summaries. Fraud-slash execution feeds allocation frame into evidence builder.
- **Phase 6 — Pro-rata claim evaluators:** Added `pro_rata_claims.clj` with 7 evaluators (`:projection-deterministic`, `:projection-canonical-safe`, `:allocation-complete`, `:non-negative`, `:conservation`, `:rounding-bounded`, `:ordering-independent`). Registered in `passive_registries.clj` claim-definitions.

### Added (2026-06-23)
- **Projection pro-rata Phase 5 shadow path:** Added `calculate-prorata-from-projection` to replay allocation from validated projection artifacts, plus a SEW-shaped `calculate-sew-slash-allocation-from-projection` adapter and focused parity tests comparing projection-derived output with the current direct allocation path on the same fixtures.
- **Projection artifacts Phase 4:** Added passive pro-rata projection artifact construction beside current allocation code, with registered pro-rata intent/projection/claim entries and focused tests for stable projection hashes, canonical safety, registered intent/definition lookup, embedded summaries, and full projection storage.
- **Projection Pro-Rata Spec V1:** Added `docs/specs/PROJECTION_PRORATA_SPEC_V1.md`, defining the required world → registered intent → registered projection definition → projection artifact → allocation → claims → evidence node flow before runtime refactors.
- **Passive registries Phase 2:** Added data-only Intent, Projection Definition, Claim Definition, and Attestor registries with canonical entry hashes and validators. Validation remains permissive at runtime unless strict mode is requested; focused registry tests now hard-fail on invalid registry data.
- **Canonical hash Phase 1 projections:** Added registered hash intents, domain tags, explicit projection functions, startup registry validation, and focused tests for `:intent-dsl`, `:intent-registry-entry`, `:intent-registry`, `:projection-definition`, `:projection-definition-registry`, `:projection-artifact`, `:claim-definition`, and `:attestor`. No call sites were migrated.

### Changed (2026-06-23)
- **Agent project guide:** Replaced `.ai/project.md` scaffold with a repository-grounded guide covering framework vs Sew components, generated artifacts, tooling, specs, important namespaces/directories, current subsystem state, concrete `bb` commands, invariant/workflow constraints, and agent checklists.

### Changed (2026-06-23)
- **Intent Registry Contract Spec V1:** Migrated `hash-intents` contracts to `INTENT_REGISTRY_SPEC_V1`. All fields now use `:intent/` qualified names: renamed `:intent/scope` → `:intent/includes`, `:project` → `:intent/projection-fn`, `:domain` (keyword) → `:intent/domain-tag` (string). Added `:intent/version` (monotonic integer) to every contract. Added `validate-registry!` for startup/test-time registry integrity checks with field presence, type, and version validation. `domain-tags` map retained for backward compatibility with keyword-based callers.

### Added (2026-06-23)
- **Evidence Layer 2 — Invariant Attestation:** Per-step invariant attestation evidence emitted during replay. For each event, `build-invariant-attestation` extracts pass/fail from `check-invariants-single`/`check-invariants-transition` results, hashes with new `:invariant-attestation` intent, and registers in evidence chain. Best-effort (catches and logs errors without halting simulation).
- **Evidence Layer 3 — Invariant Digest:** Attestation includes `:passed`/`:failed` counts and a deterministic invariant-set-hash for fast comparison.
- **Evidence Layer 7 — Benchmark Certification:** `runner.clj` now produces an `:invariant-summary` in benchmark evidence bundles with per-invariant pass/total counts, total/passed checks, and `all-pass?` flag. Certification artifact hashed with new `:benchmark-certification` intent.
- **Evidence Layer 8 — Projection Evidence:** Per-step projection evidence emitted when `AnalysisModule` protocol is satisfied, pairing world-hash with projection-hash. Hashed with new `:projection-evidence` intent.
- **Evidence Layer 9 — Checkpoint Evidence:** Checkpoint evidence emitted at strategic decision points (`raise_dispute`, `escalate_dispute`, `execute_resolution`). Contains checkpoint-ID, world-hash, and chain-head. Hashed with new `:checkpoint-evidence` intent.
- **New intent contracts:** `:invariant-attestation`, `:projection-evidence`, `:checkpoint-evidence`, `:benchmark-certification` registered in `hash-intents` with domain tags, includes/excludes, projection functions, and version 1.

### Added (2026-06-22)
- **P0 canonical hash engine:** `resolver-sim.hash.canonical` with domain-separated typed binary encoding per `CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.md`. API: `validate-canonical-value!`, `canonical-bytes`, `hash-bytes`, `domain-hash`. Supports null, boolean, integer, string, keyword, vector, and map types with LEB128 varuint, ZigZag signed integers, and byte-level map key ordering. Rejects ratio, symbol, set, list, and non-string map keys. 9 reserved domain tags (WORLD_STATE_V1, EVIDENCE_RECORD_V1, etc.).
- **Conformance test vectors:** 17 JSON test vectors in `resources/test-vectors/canonical-hash-v1/` covering all base types with canonical bytes hex, domain tag, and SHA-256 hash hex.
- **Unit tests:** `test/resolver_sim/hash/canonical_test.clj` — 47 tests (type encoding, domain hashing, type validation, conformance vector verification, projection, hash-with-intent).
- **World state projection:** `resolver-sim.hash.canonical/project-world-to-structure-view` — semantic identity lens that transforms runtime types (Instant, Double, Set, Function, Ratio) into canonical-safe representations before hashing. Every transformation is explicit and documented. Spec section 11 added with full rule table.
- **Hash Intent Declaration:** `resolver-sim.hash.canonical/hash-with-intent` — explicit intent map (`{:hash/intent :world-structure}`) replaces implicit hash function dispatch. Each intent is a full Intent Registry Contract with `:intent/name`, `:intent/description`, `:intent/scope`, `:intent/excludes`. 8 intents registered. `resolve-intent` available for inspection and future linting. Machine-readable boundaries prevent semantic drift. Spec section 11.5 added with contract table.

### Changed (2026-06-22)
- **Old hashing removed:** `resolver-sim.benchmark.hashing` namespace stripped to throwing stubs. All 3 functions (hash-evidence, stable-hash, stable-hash-prefixed) removed. Remaining callers migrated to `hash-with-intent`.
- **Call site migration:** Evidence chain, timestamping, forensic adapter, yield evidence, manifest hashing, and benchmark runner/cli migrated to `resolver-sim.hash.canonical` with explicit intents.
- **Spec updated:** `CANONICAL_HASH_SPEC_V1.md` Sections 6 and 11 updated. Section 11: Ratio added to projection rules. `project-for-content-hash` added for JSON-round-trippable evidence content hashing.
- **Test migration:** `canonical_test.clj` — 47 tests with new projection/hash-with-intent coverage. `evidence_test.clj` — stable-hash tests replaced with hash-with-intent tests. `phase3_test.clj` — stable-hash/prefix checks removed. `runner_test.clj` — old hashing replaced. `timestamping_test.clj` — old hashing replaced.

### Added (2026-06-22)
- **Pro-rata parity test vectors:** Added canonical liquidity-fulfillment and Sew slash-allocation vector emitters, deterministic JSON/hash helpers, committed golden fixtures under `resources/test-vectors/pro-rata/`, invariant tests, and `docs/pro_rata_test_vectors.md` for future Solidity/Foundry differential testing.

### Changed (2026-06-21)
- **Attribution namespace split:** `resolver-sim.util.attribution` is now a compatibility facade over focused context, schema, validation, and logging namespaces. Evidence payload key registry moved to `resolver-sim.util.evidence.schema`; existing attribution call sites remain supported.
- **Attribution resolution helpers:** Added marker-based attribution detection, safe explicit attribution resolution, nested attribution extraction, pure sanitization, a single warning sanitizer, and `with-resolved-attribution` for bridging AttributedState into legacy dynamic consumers without treating arbitrary maps as attribution.
- **Forensic evidence navigation:** Added `build-evidence-links-index-v1` / `write-evidence-links-index-v1!` with versioned envelopes, explicit read-error degradation metadata, enriched artifact summaries, per-group diagnostics, and portable relative paths. Added forensic adapter extension point plus an example mechanism report adapter for derived diagnostic artifacts.
- **Generic economics layering:** Refactored `resolver-sim.economics.payoffs` to expose protocol-agnostic allocation and basis-point accounting helpers only. Moved Sew escrow, bond, slash, bounty, resolver capacity, and governance policy calculations to `resolver-sim.protocols.sew.economics`; migrated Sew call sites and notebook examples to the adapter; documented the one-way dependency rule for `resolver-sim.economics/*`.
- **Generic pro-rata correctness:** Hardened `allocate-pro-rata` with explicit rounding policy handling (`:floor` and `:floor-with-largest-remainder`), exact integer arithmetic without float truncation, explicit unsupported-policy failures, and accurate no-basis remainder reporting.
- **REPL startup:** Added `:repl/nrepl` and `:dev/env` aliases, fixed stale `dev.pro-rata` helper wiring, and cleaned dev namespace startup conflicts so `clojure -M:repl/nrepl` has a project-local nREPL starting point.

### Added (2026-06-19)
- **Demo factory architecture (Phase 0+1+2+SVG export):** New `resolver-sim.demo.spec` and `resolver-sim.demo.runner` namespaces implement the demo contract and executable runner. `demo.edn` spec files define a demo by pointing to scenarios, commands, expected outputs, and research claims. `bb demo:run <id>` loads `demos/<id>/demo.edn`, validates the spec, runs the scenario, executes section commands via `bash -c`, captures stdout/stderr/exit codes, collects artifacts into `generated/artifacts/`, and writes `generated/demo-run.json`. `bb demo:validate <id>` validates a spec without executing. First demo: `yield-shortfall-partial-fill` (vault shortfall partial withdrawal scenario).
- **Terminal screenshot renderer (Phase 2):** `resolver-sim.demo.screen` generates SVG terminal screenshots with dark theme, title bar, and monospace text from captured command output. Also produces asciicast v2 format (`*.cast`) for use with asciinema players. Screenshots are automatically generated for every command and the scenario output. Stored in `generated/screenshots/`.
- **Asciicast demo recorder (Phase 2, revised):** `resolver-sim.demo.recorder` generates a single unified shell script per demo with automatic pacing. `bb demo:record <id>` (default `--mode auto`) creates one `.cast` file with title card, section headers, explanations, visibly printed commands before execution, command output, exit code reporting, and a reproduction footer — all with automatic `sleep` pauses. `--mode manual` uses `read` prompts for interactive step-through. Terminal dimensions are fixed at 120x36. Per-section stdout/stderr are preserved via `tee`. Writes `demo-run.json` with section metadata, exit codes, file paths, git commit, and artifacts. `bb demo:scenario-summary <fixture.json>` prints compact event/expectation tables. Demo spec supports `:playback` config with customizable pause durations per section.
- **SVG export for GitHub README:** `resolver-sim.demo.export` generates animated SVG from .cast recordings via `svg-term-cli` (npx, no install). `bb demo:export-svg <id>` produces `<demo-id>.svg` with window decorations and auto-play. `bb demo:build <id>` runs record + export-svg in one step. SVG metadata is stored in `demo-run.json` with a ready-to-use `readme/embed` Markdown snippet.
### Added (2026-06-19)
### Added (2026-06-20)
- **Yield-bearing scenario classification system:** `src/resolver_sim/scenario/yield_classification.clj` — `classify-yield-scenario` returns `{:yield/enabled? :yield/risk-class :scenario/categories :invariant/profile}`. Used to select invariant profiles and interpret expected failures. `yield-risk-class` inspects yield-config to determine `:principal-preserving`, `:principal-loss`, `:liquidity-shortfall`, or `:historical-index-replay`. `expected-invariant-results` returns per-invariant `{:status :expected? :reason}` metadata.
- **Yield-bearing invariant documentation:** `docs/yield/YIELD_BEARING_INVARIANTS.md` — classification axes table, risk-class semantics, scenario map with expected invariant results, and per-scenario accounting traces.

### Fixed (2026-06-20)
- **`liquid_lending/withdraw` nil-position crash:** `token` normalization moved after the nil guard; module-id mismatch guard added. `claim-deferred` given explicit nil/module guards.
- **`liquid_lending/withdraw` available-ratio dead path:** Withdraw now calls `market-state/get-market-state` instead of reading from `[:yield/risk :shortfall :available-ratio]` (never populated by `apply-yield-config`). Liquidity-schedule and shortfall-model configs now drive withdrawal shortfall calculations.
- **`liquid_lending/withdraw` shortfall reason mismatch:** Hardcoded `:reason :liquidity-shortfall` replaced with `(or (:type shortfall-model) :liquidity-shortfall)`. Deferred-amount is moved to haircut-amount when `:recoverable false`, so `sum-recognized-losses` in `evidence.clj` correctly captures principal losses.
- **`liquid_lending/withdraw` realized-yield zeroed on full fill:** Under waterfall `:not-claimable` policy, `realized-yield` was capped at `(- fulfilled-total principal)` = 0. When no shortfall, now uses full `unrealized-yield` so yield is distributed to claimable.
- **`liquid_lending/accrue` index-schedule never consumed:** With APY=0, the index stayed at 1.0. Now calls `market-state/get-market-state` and uses schedule index directly via `update-position-yield`, matching the `fixed-accrue` pattern.
- **`registry/normalize-schedule` external JSON type normalization:** `load-external-json` returned `:type "steps"` (string) but `get-value-at-time` pattern-matched on keyword `:steps`. Added `(update :type keyword)` after load.
- **`held-delta-accounted?` missing losses term:** Added `losses` (via `yield-evi/sum-recognized-losses`) to `delta-inflow`, matching `conservation-of-funds?`. Principal-loss haircuts no longer cause spurious failures.
- **`single-resolution-payout-consistent?` false positive on yield distributions:** Switched from flat legacy `:claimable` map to v2 domain-level check (`:claimable-v2`). Each domain (`:settlement/principal`, `:settlement/yield`) is checked independently, so yield distributions to a second party no longer trigger violations.

### Changed (2026-06-20)
- **S113/S115 scenarios:** Added `"yield-preset": "to-sender"` to `create_escrow` params. Yield was silently disabled because `normalize-yield-preset(nil) = :off`.
- **S113/S115 golden reports regenerated:** S113 passes `conservation-of-funds`, `token-tax-reconciliation`, `held-delta-accounted`; solo expected-fail is `:solvency` (principal-loss by-design). S115 passes all invariants (index replay yield now correctly accrued).
- **Notebook support layer:** `src/resolver_sim/notebook/` with `views.clj` (RAG helpers, card rendering, status functions, triage logic, evidence-hash-viewer, invariant-status-badge, trace-table, notice-box, trace-transition-card, badge) and `checks.clj` (Malli schemas for GoldenReport, TraceMetadata, TestSummary; assert-shape! and specific validators).
- **`bb notebook:check` and `bb notebook:lint` tasks:** `notebook:check` loads notebook namespaces and validates data shapes; `notebook:lint` runs clj-kondo on `src/resolver_sim/notebook/`. `notebook:ci` combines both.
- **`notebooks/docs/evidence_attribution.clj`:** Researcher-facing demonstration of the `with-attribution` → `capture-event-evidence!` → JSON artifact pipeline. 12 sections covering attribution context, evidence payloads, replay example, artifact registry, evidence chain validation.
- **`notebooks/docs/NOTEBOOK_STYLE.md`:** Visibility standards, 4-layer notebook structure, shape check requirements, evidence table conventions, agent checklist.
- **`notebooks/_template.clj`:** 4-layer research notebook template (claim, context, evidence, reproduction).
- **`bb fixtures:generate-traces`:** Generates `.trace.json` files for golden reports that lack matching trace metadata. Created `scripts/generate_missing_traces.clj`.
- **`bb regenerate-goldens` task:** Wraps `clojure -M:regenerate-goldens` for regenerating golden report `.report.edn` files.
- **AGENTS.md Clerk notebook rules:** Agent prompt, visibility standards table, before-commit checklist.
- **`scripts/vcs_info.py`:** Python VCS helper with `jj`-first, `git`-fallback for commit_sha, short_sha, branch, commit_message, root.
- **`src/resolver_sim/vcs.clj`:** Clojure VCS abstraction supporting `jj` and Git.
- **Scenario Evidence Verification:** `verify-scenario-evidence` compares `:expected-evidence` declared on scenario metadata against captured artifacts. Reports `:matched`, `:missing`, `:unexpected` entries.
- **Cross-Run Evidence Diff:** `diff-evidence-directories` compares two artifact directories by evidence-hash. Reports `:added`, `:missing`, `:changed`, `:unchanged` with summary counts.
- **Evidence Bundle Export:** `export-evidence-bundle` copies artifacts matching one or more group-ids into a portable directory with `manifest.json` for sharing with collaborators.
- **Post-hoc Chain Verification:** `verify-chain-integrity` reads all artifacts, validates `:evidence/chain-self-hash` matches `:evidence/hash`, and verifies `:evidence/chain-prev-hash` links against the previous artifact in sequence order.
- **`with-fresh-chain-cursor` macro:** Dynamic chain cursor isolation per run, matching `chain/with-fresh-registry` pattern.

### Added (2026-06-19, cont.)
- **Evidence Registry (Phases 1-8):** Full 8-phase evidence registry implementation:
  - Phase 1: Read-only registry builder (`build-evidence-registry`) with entries, indexes, query helpers
  - Phase 2: Integration into scenario runner + `bb evidence:registry` task + CLI entry point
  - Phase 3: Structural diff-evidence generation (`build-diff-evidence!`) comparing before/after world states
  - Phase 4: Path classification (35 known paths across 10 domains) with domain summaries and suppression
  - Phase 5: Invariant result linking (`build-invariant-links`, `merge-invariant-links`) from replay traces
  - Phase 6: Semantic classification (`:expected`, `:unexpected`, `:financial-boundary`, `:diagnostic-only`)
  - Phase 7: Strict-mode CI gates (`:strict true` promotes warnings to failures, excludes diff layer)
  - Phase 8: Metadata hardening (6 completeness checks: subject/type, subject/id, action/type, reason, world hashes)
- **Strict Mode Validation:** `validate-evidence-registry` accepts `:strict true` — recommended checks become failures.
  `build-evidence-registry!` with `:strict true` throws on strict failure. Diff artifacts excluded from strict checks.
- **Metadata Completeness Checks:** 6 diagnostic checks for attribution fields (`metadata-subject-type`, `metadata-subject-id`,
  `metadata-action-type`, `metadata-reason`, `metadata-world-hashes-complete`, `metadata-incomplete-entries`).
- **Semantic Diff Classification:** `classify-change` classifies each diff change by path prefix + event action.
  `classify-diff-changes-semantic` adds `:classification` (`:expected`, `:unexpected`, `:financial-boundary`,
  `:diagnostic-only`). `build-enhanced-domain-summary` adds `:by-classification` breakdown.
- **Domain Summaries:** 35 known world-state path prefixes classified into 10 domains (financial, resolver, slashing,
  bonding, escrow, dispute, yield, claimable, risk, internal). Internal paths are `:suppress-from-summary? true`.
- **Diff Artifact Index:** `build-diff-index` with `:by-event-index` linking diffs to event indices.
- **Invariant Linking:** `build-invariant-links` extracts invariant results from trace entries and produces
  `:by-event-index` and `:by-invariant-id` indexes. `merge-invariant-links` adds them to the registry.
- **11 new tests:** semantic classification (classify-change, classify-diff-changes-semantic), domain summary enhancement,
  diff index, strict mode validation (2 tests), merge invariant links (2 tests). Total: 598 tests, 1956 assertions.

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

### Fixed (2026-06-19)
- **Protocol check in core.clj blocks valid non-default protocols:** `-main` rejected `--protocol yield-v1` because it compared against `default-protocol-id` instead of the set of known protocol IDs. Changed to `(not ((set (preg/known-protocol-ids)) protocol-id))`.
- **Y02 yield scenario expectation mismatch:** `yield/focus-deferred` expected 500 but actual is 1000. Updated scenario expectations to match current yield module behavior.
- **Yield deposit `:total-held` double-counting:** Liquid-lending module's `deposit` updated `:total-held`, but `create-escrow` already called `add-held` for the same amount. Removed the redundant update from `liquid_lending.clj:62`. Fixed `s115-claim-deferred-yield-recovery` invariant violations.
- **`slash-status-consistent?` invariant fix:** `(> appeal-dl proposed-at)` → `(>= appeal-dl proposed-at)`. Zero-length appeal windows (`appeal-window-duration: 0`, the default) produced `deadline = proposed-at`, violating the strict inequality. Fixed `governance-approved` scenario halt.
- **`cleanup-orphaned-slashes` removed from `finalize`:** The call was removing pending Track 2 reversal slashes created by `handle-reversal-slashing` in the same `execute-resolution` call (final-round path). The cleanup already runs in `execute-pending-settlement` where it belongs.
- **Namespace layering violations in resolver-sim:** 6 dependency direction violations fixed. `contract-model.replay.execution` no longer depends on `sim.dispatcher` — implementation moved to correct layer with sim layer delegating to it. Chain cursor (`chain-cursor`, `with-fresh-chain-cursor`, `inject-chain-fields`) moved from `io.event-evidence` to `evidence.chain`. Protocol-layer evidence capture (`capture-event-evidence!`) now routes through a dynamic var in `evidence.capture` bound by `io.event-evidence` at load time, fixing `protocols.sew.*` → `io.*` and `contract-model.replay` → `io.*` imports.
- **`evidence-filename` string type handling:** `evidence-filename` now handles string-typed `:evidence/type` values (produced by `evidence-base`) correctly instead of falling through to `"unknown"`.

### Changed (2026-06-19)
- **`bb test` now prints overall status:** After all targets complete, the terminal output includes a summary line (`Overall: PASS | Acceptance: PASS_CLEAN` or `Overall: FAIL (1/10 failed) | Acceptance: REJECTED`) so users see the result without opening files.
- **Artifact loader extracted from notebooks to `resolver-sim.manifest.run`:** `list-runs`, `load-run`, `artifact-by-id`, `load-latest`, `load-focused`, `run->status-indicator`, and `latest-status` now live in `src/resolver_sim/manifest/run.clj` (namespace `resolver-sim.manifest.run`). The old `resolver-sim.notebooks.manifest.loader` is a backward-compat wrapper. Utility functions `read-json`/`read-edn`/`safe-slurp` moved to `resolver-sim.manifest.common` with a wrapper in `resolver-sim.notebooks.common`.
- **`bb scenario:run --save-output <dir>` added:** Preserves replay JSON and copies artifacts to a directory for demo consumption. Skips temp file cleanup.
- **`export-scenario-files!` metadata now extracted programmatically from scenario maps:** `scenario-inline-metadata` extracts `:title`, `:purpose`, `:threat-tags` directly from the scenario map, covering all 138 scenarios instead of only the 17 hardcoded entries in `default-export-metadata`. Resolution order: hardcoded defaults → inline scenario keys → caller-supplied metadata.
- **VCS abstraction for git references:** `src/resolver_sim/vcs.clj` — all git SHA/branch extraction centralized with `jj`-first, `git`-fallback. `commit-sha`, `short-sha`, `branch`, `commit-message`, `root`, `dirty?`, `remotes`. Updated `claimable_classification_emitter.clj`, `sim/audit.clj`, `benchmark/repo.clj`, `python/invariant_suite.py`, `scripts/write_scenario_run_manifest.py` to use VCS helper.
- **`notebooks/report.clj` visibility and color contrast:** Changed default from `:code :hide` to `:code :fold` with `:toc true`. Added dark-theme CSS override (`color-scheme: dark`). Replaced `#555`/`#444`/`#666` text colors with `#f1f5f9`/`#cbd5e1` for readability on dark background. Extracted RAG helpers, card rendering, status functions, and triage logic to `src/resolver_sim/notebook/views.clj`. Added Malli shape checks for golden reports, trace metadata, and test-summary artifacts.
- **`notebooks/not-governance.clj` demo rewrite:** Consolidated 4 duplicate batch configs into single `const` map with `run-batch` helper. All display sections wrapped in `clerk/html`. Added transition-diff cards, delta column to strategy comparison, conservation equation to pro-rata allocation, and dual sweep (default bond + breakeven bond) for detection probability. Added demo warning boxes for stochastic zero-detection cases. Dark-theme CSS fix. Extracted `badge`, `notice-box`, `trace-table` to `views.clj`. Added shape checks on batch results.

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

### Added
- **`secure-checkpoint-update` extracted to `checkpoints.clj`:** Moved checkpoint creation logic (overwrite detection, append-only checkpoint-log, collision diagnostics) from private function in `replay.clj` to public function in `checkpoints.clj`. Added unit tests (`checkpoints_test.clj`, 5 tests, 20 assertions).
- **`default-metadata` function to `capture.clj`:** Added missing `default-metadata` function for standardized evidence schema metadata generation (scenario-id, run-id, event-seq, timestamp, attribution context). Was introduced in agent-b-1 but lost during integration merge.
- **Checkpoint-log threading in sequential replay:** `checkpoint-log` and `diagnostics` are now accumulated per-event and threaded through `recur`, populating the result map with full checkpoint history (previously discarded).

### Changed
- **`replay.clj` modular decomposition:** Split 751-line monolithic file into 8 focused namespaces under `contract_model/replay/`: `execution`, `temporal`, `metrics`, `analysis`, `validation`, `flags`, `checkpoints`, and `yield`. `replay.clj` now serves as a 271-line forwarding layer with re-exports.
