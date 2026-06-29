(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections (path lists) for run-collection.

   THREE GROUPING CONCEPTS exist in this codebase.
   They differ in registration, execution model, and purpose:

   ─────────────────────────────────────────────────────────────────
   SUITE  (run:scenario:suite)
     Registered, curated path list in `suites` (this file).
     Executes in a single Clojure process via run-collection.
     Used by: CI gates, golden report refresh, coverage tiers.
     Examples: :dispute-resolution-scenarios, :sew-yield-scenarios
     Keyword namespace: bare keywords (no prefix).

   BENCHMARK PACK SUITE  (bb benchmark:run)
     Same registry as suites but kept in `pack-suites` to avoid
     duplicate scenario-id validation errors.  Referenced by
     benchmark pack manifests via :benchmark/scenario-suite.
     Executes inside a benchmark evidence bundle.
     Keyword namespace: :suite/ prefix (e.g. :suite/sew-dispute-safety-v1).
     NOTE: pack suites currently share the exact same path lists
     as functional suites — they are aliases, not distinct sets.

   SEARCH  (run:scenario:search)
     NOT a registered grouping.  Ad-hoc text search across all scenario
     file paths and contents (case-insensitive substring).  Executes
     each match in a separate subprocess.  Results are not suitable
     for canonical CI, benchmark definitions, or published evidence.
     No file in this namespace implements search logic;
     the selector runs entirely in bb.edn.

   ─────────────────────────────────────────────────────────────────
   OVERLAP WARNING:
   The 44 dispute-resolution files (S-DR-001..S-DR-085) appear in
   THREE groupings — one suite + two pack suites.  This is by design
   (pack suites reuse the same path lists), but means the same
   scenarios execute under different names depending on the entry
   point.  See `pack-suites` docstring for details.

   Suite naming:
     :yield-provider-scenarios — standalone `yield-v1` provider scenarios (scenarios/Y01..Y05)
     :sew-yield-scenarios       — Sew escrow + yield integration (scenarios/S*)
     :yield-scenarios           — removed June 2026; use :sew-yield-scenarios")

(def ^:private yield-provider-scenario-paths
  ["scenarios/Y01_vault-shared-liquidity.json"
   "scenarios/Y02_vault-shortfall-partial-withdraw.json"
   "scenarios/Y03_vault-risk-override-schedule-shadowing.json"
   "scenarios/Y04_vault-recovery-claim-deferred.json"
   "scenarios/Y05_auto-generated-shortfall.json"])

(def ^:private dispute-resolution-scenario-paths
  ["scenarios/S-DR-001-basic-release-ruling.json"
   "scenarios/S-DR-002-basic-refund-ruling.json"
   "scenarios/S-DR-003-duplicate-dispute-rejected.json"
   "scenarios/S-DR-004-timeout-default-resolution.json"
   "scenarios/S-DR-010-missing-evidence.json"
   "scenarios/S-DR-011-contradictory-evidence.json"
   "scenarios/S-DR-012-late-evidence-rejected.json"
   "scenarios/S-DR-013-evidence-at-deadline.json"
   "scenarios/S-DR-020-false-claimant-slashed.json"
   "scenarios/S-DR-021-griefing-claim-cost.json"
   "scenarios/S-DR-022-lazy-counterparty-timeout.json"
   "scenarios/S-DR-030-biased-resolver-appealed.json"
   "scenarios/S-DR-031-colluding-resolver-detected.json"
   "scenarios/S-DR-032-resolver-insufficient-stake.json"
   "scenarios/S-DR-040-finality-blocked-during-appeal.json"
   "scenarios/S-DR-041-finality-after-appeal-window.json"
   "scenarios/S-DR-042-duplicate-claim-after-finality-rejected.json"
   "scenarios/S-DR-043-payout-shortfall-deferred.json"
   "scenarios/S-DR-044-slash-obligation-unmet-recorded.json"
   "scenarios/S-DR-050-resolution-module-plus-kleros.json"
   "scenarios/S-DR-051-challenge-without-escalation.json"
   "scenarios/S-DR-052-custom-resolver-bypasses-module.json"
   "scenarios/S-DR-053-module-false-fallthrough.json"
   "scenarios/S-DR-054-missing-escalation-level.json"
   "scenarios/S-DR-055-sender-cancel-refund.json"
   "scenarios/S-DR-056-evidence-non-disputed-rejected.json"
   "scenarios/S-DR-060-rotate-resolver-mid-dispute.json"
   "scenarios/S-DR-061-slash-propose-execute.json"
   "scenarios/S-DR-062-rotate-resolver-rejected.json"
   "scenarios/S-DR-063-slash-appeal-upheld.json"
   "scenarios/S-DR-064-slash-appeal-rejected-executed.json"
   "scenarios/S-DR-070-empty-string-resolver-rejected.json"
   "scenarios/S-DR-071-governance-rotate-biased-ruling.json"
   "scenarios/S-DR-072-resolver-unavailable-timeout.json"
   "scenarios/S-DR-073-capacity-exhaustion-permanent-lock.json"
   "scenarios/S-DR-074-governance-capacity-bypass.json"
   "scenarios/S-DR-075-insufficient-bond-deterrence.json"
   "scenarios/S-DR-076-non-governance-rotate-rejected.json"
   "scenarios/S-DR-080-stake-capacity-enforced.json"
   "scenarios/S-DR-081-stake-capacity-bypass.json"
   "scenarios/S-DR-082-stake-capacity-sufficient.json"
   "scenarios/S-DR-083-evidence-after-resolution.json"
   "scenarios/S-DR-084-evidence-after-settlement-rejected.json"
   "scenarios/S-DR-085-repeated-frivolous-disputes.json"])

(def ^:private sew-reference-scenario-paths
  "Curated reference scenarios for external verifier reproducibility.
   All scenarios are cancellation/terminal-state traces that exercise
   the core griefing-protection, same-timestamp, and auto-cancel paths.
   Each file is a standalone .trace.json — no Clojure source needed."
  ["data/fixtures/traces/s-auto-cancel-time-via-keeper.trace.json"
   "data/fixtures/traces/s-auto-cancel-time-boundary.trace.json"
   "data/fixtures/traces/s-auto-cancel-time-orphaned-by-dispute.trace.json"
   "data/fixtures/traces/s-same-timestamp-auto-cancel-vs-dispute.trace.json"
   "data/fixtures/traces/s-same-timestamp-dispute-vs-auto-cancel.trace.json"
   "data/fixtures/traces/s-extortion-unilateral-cancel.trace.json"
   "data/fixtures/traces/s-extortion-unilateral-cancel-dual.trace.json"])

(def ^:private reference-validation-scenario-paths
  "Reference validation v1 scenarios — simulator-backed scenarios
   covering resolver accountability, dispute-flooding liveness, and
   autopush settlement safety.  Used by the protocol-robustness-v0
   benchmark pack."
  ["scenarios/S25_profit-maximizer-slash-lifecycle.json"
   "scenarios/S62_resolver-throughput-exhaustion.json"
   "scenarios/S05_pending-settlement-execute.json"])

(def ^:private yield-scenario-paths
  ["scenarios/S78_yield-aave-partial-liquidity-release.json"
   "scenarios/S78_yield-negative-yield-release-path.json"
   "scenarios/S79_yield-aave-partial-liquidity-dispute-resolution.json"
   "scenarios/S79_yield-negative-yield-dispute-refund-path.json"
   "scenarios/S80_yield-mostly-liquid-partial-liquidity.json"
   "scenarios/S80_yield-aave-partial-liquidity-governance-disable-post-create.json"
   "scenarios/S81_escrow-yield-may-be-partially-deferred.json"
   "scenarios/S82_shortfall-recovery-cycle.json"
   "scenarios/S83_yield-accrual-reorg-race.json"
   "scenarios/S87_resolver-frozen-while-yield-due.json"
   "scenarios/S88_yield-accrual-efficiency.json"
   "scenarios/S103_negative-yield-shortfall-cascade.json"
   "scenarios/S108_negative-yield-mild.json"
   "scenarios/S109_negative-yield-severe-repair.json"
   "scenarios/S110_resolver-yield-accrual.json"])

(def suites
  "Suite keyword → {:paths [relative-path-str ...] :protocol-id ...}.

   The registry is the source of truth for named file-backed scenario suites. Keep the
   metadata here aligned with task/docs entrypoints and protocol inference.

   Protocol pack suites (prefixed with :suite/) are used by benchmark pack manifests
   under benchmarks/packs/. They reference the same scenario file lists as the
   functional suite keywords for execution purposes."
  {:dispute-resolution-scenarios {:paths        dispute-resolution-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Dispute-resolution scenarios"
                                  :description  "Sew dispute-resolution coverage scenarios."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :sew-yield-scenarios          {:paths        yield-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Sew yield integration scenarios"
                                  :description  "Sew escrow scenarios that exercise yield integration behavior."
                                  :kind         :file-path-suite
                                  :ci-tier      :integration}
   :yield-provider-scenarios     {:paths        yield-provider-scenario-paths
                                  :protocol-id  "yield-v1"
                                  :title        "Yield provider scenarios"
                                  :description  "Standalone yield-v1 scenarios backed by canonical top-level scenarios/Y01..Y05 files."
                                  :kind         :file-path-suite
                                  :ci-tier      :provider}
   :sew-reference-v1             {:paths        sew-reference-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Sew reference v1 — external verifier suite"
                                  :description  "Curated reference scenarios for external-verifier reproducibility.
                                                  Tests cancellation griefing protection, auto-cancel-time, same-timestamp
                                                  ordering, and extortion resistance.  All files are standalone .trace.json."
                                  :kind         :file-path-suite
                                  :ci-tier      :reference}})

(def pack-suites
  "Benchmark pack suite keywords — used by benchmarks/packs/*/ manifests.

   NOTE — OVERLAP WITH FUNCTIONAL SUITES:
   Pack suites currently reference the EXACT SAME path lists as functional
   suites.  They are semantic aliases, not distinct scenario sets.

     :suite/sew-dispute-safety-v1  ≡ :dispute-resolution-scenarios  (44 S-DR files)
     :suite/sew-yield-safety-v1    ≡ :sew-yield-scenarios           (15 S files)
     :suite/prf-replay-v1          ≡ :dispute-resolution-scenarios  (44 S-DR files)

   This means `bb benchmark:run escrow-dispute-v1.edn` and
   `bb run:scenario:suite dispute-resolution-scenarios` execute the
   same 44 scenarios through different entry points.

   Kept separate from `suites` to avoid duplicate scenario-id
   errors in file-backed suite registry validation.  When new
   protocol-specific scenarios are added for a benchmark pack,
   its pack suite should define its own path list here."
  {:suite/sew-dispute-safety-v1  {:paths        dispute-resolution-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Sew dispute-safety benchmark suite"
                                  :description  "Sew escrow dispute, slashing, and liveness scenarios for benchmark execution."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :suite/sew-yield-safety-v1    {:paths        yield-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Sew yield-safety benchmark suite"
                                  :description  "Sew escrow + yield integration scenarios for benchmark execution."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}
   :suite/prf-replay-v1          {:paths        dispute-resolution-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "PRF deterministic replay benchmark suite"
                                  :description  "Core deterministic replay scenarios for benchmark execution.
                                    NOTE: currently points to Sew dispute-resolution scenarios because
                                    PRF-specific replay scenarios do not yet exist.  A genuine PRF replay
                                    suite would contain protocol-agnostic replay/evidence scenarios."
                                  :kind         :file-path-suite
                                  :ci-tier      :coverage}

    :suite/reference-validation-v1 {:paths        reference-validation-scenario-paths
                                    :protocol-id  "sew-v1"
                                    :title        "Reference validation v1 — protocol robustness scenarios"
                                    :description  "Simulator-backed scenarios for resolver accountability,
                                     liveness under adversarial load, and autopush settlement safety.
                                     Used by the protocol-robustness-v0 benchmark pack."
                                    :kind         :file-path-suite
                                    :ci-tier      :coverage}

    :suite/sew-shortfall-allocation-v0
    {:paths        ["scenarios/S-DR-043-payout-shortfall-deferred.json"
                    "scenarios/S103_negative-yield-shortfall-cascade.json"
                    "scenarios/S104_resolver-stake-shortfall.json"]
     :protocol-id  "sew-v1"
     :title        "Shortfall allocation v0 — partial fill and pro-rata scenarios"
     :description  "Sew scenarios exercising yield shortfall with partial fill,
      negative yield cascade with deferred recovery, and resolver stake
      shortfall. Used by the shortfall-allocation-v0 benchmark pack."
     :kind         :file-path-suite
     :ci-tier      :coverage}})

(defn- resolve-suite-registry
  "Return the registry map for a suite keyword — checks `suites` first,
   then `pack-suites` for benchmark pack references."
  [suite-key]
  (or (get suites suite-key) (get pack-suites suite-key)))

(defn suite-protocol-id
  "Protocol registry id for a named path suite (default sew-v1)."
  [suite-key]
  (or (:protocol-id (resolve-suite-registry suite-key)) "sew-v1"))

(defn suite-paths
  "Return scenario file paths for a registered suite keyword, or nil if unknown."
  [suite-key]
  (:paths (resolve-suite-registry suite-key)))

(defn suite-definition
  "Return the registry entry for a named suite keyword, or nil if unknown."
  [suite-key]
  (resolve-suite-registry suite-key))

(defn suite-metadata
  "Return suite metadata without the path list."
  [suite-key]
  (some-> (suite-definition suite-key)
          (dissoc :paths)))

(defn suite-path-count
  "Return the number of scenario files registered for suite-key."
  [suite-key]
  (count (or (suite-paths suite-key) [])))

(defn known-suite-keys []
  (vec (sort (keys suites))))

(defn known-suite-definitions
  "Return the full registry map for all named scenario suites."
  []
  suites)
