# Changelog

## [Unreleased]

### Added (2026-06-19)
- **Scenario Evidence Verification:** `verify-scenario-evidence` compares `:expected-evidence` declared on scenario metadata against captured artifacts. Reports `:matched`, `:missing`, `:unexpected` entries.
- **Cross-Run Evidence Diff:** `diff-evidence-directories` compares two artifact directories by evidence-hash. Reports `:added`, `:missing`, `:changed`, `:unchanged` with summary counts.
- **Evidence Bundle Export:** `export-evidence-bundle` copies artifacts matching one or more group-ids into a portable directory with `manifest.json` for sharing with collaborators.
- **Post-hoc Chain Verification:** `verify-chain-integrity` reads all artifacts, validates `:evidence/chain-self-hash` matches `:evidence/hash`, and verifies `:evidence/chain-prev-hash` links against the previous artifact in sequence order.
- **`with-fresh-chain-cursor` macro:** Dynamic chain cursor isolation per run, matching `chain/with-fresh-registry` pattern.

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
- **Yield deposit `:total-held` double-counting:** Liquid-lending module's `deposit` updated `:total-held`, but `create-escrow` already called `add-held` for the same amount. Removed the redundant update from `liquid_lending.clj:62`. Fixed `s115-claim-deferred-yield-recovery` invariant violations.
- **`slash-status-consistent?` invariant fix:** `(> appeal-dl proposed-at)` → `(>= appeal-dl proposed-at)`. Zero-length appeal windows (`appeal-window-duration: 0`, the default) produced `deadline = proposed-at`, violating the strict inequality. Fixed `governance-approved` scenario halt.
- **`cleanup-orphaned-slashes` removed from `finalize`:** The call was removing pending Track 2 reversal slashes created by `handle-reversal-slashing` in the same `execute-resolution` call (final-round path). The cleanup already runs in `execute-pending-settlement` where it belongs.

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
