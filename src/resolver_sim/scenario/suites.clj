(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections (path lists) for run-collection.

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

   The registry is the source of truth for named JSON scenario suites. Keep the
   metadata here aligned with task/docs entrypoints and protocol inference."
  {:dispute-resolution-scenarios {:paths        dispute-resolution-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Dispute-resolution scenarios"
                                  :description  "Sew dispute-resolution coverage scenarios."
                                  :kind         :json-path-suite
                                  :ci-tier      :coverage}
   :sew-yield-scenarios          {:paths        yield-scenario-paths
                                  :protocol-id  "sew-v1"
                                  :title        "Sew yield integration scenarios"
                                  :description  "Sew escrow scenarios that exercise yield integration behavior."
                                  :kind         :json-path-suite
                                  :ci-tier      :integration}
   :yield-provider-scenarios     {:paths        yield-provider-scenario-paths
                                  :protocol-id  "yield-v1"
                                  :title        "Yield provider scenarios"
                                  :description  "Standalone yield-v1 scenarios backed by canonical top-level scenarios/Y01..Y05 files."
                                  :kind         :json-path-suite
                                  :ci-tier      :provider}})

(defn resolve-suite-key
  "Resolve suite keyword (identity — no remaining deprecated aliases)."
  [suite-key]
  suite-key)

(defn suite-protocol-id
  "Protocol registry id for a named path suite (default sew-v1)."
  [suite-key]
  (or (get-in suites [(resolve-suite-key suite-key) :protocol-id]) "sew-v1"))

(defn suite-paths
  "Return scenario file paths for a registered suite keyword, or nil if unknown."
  [suite-key]
  (get-in suites [(resolve-suite-key suite-key) :paths]))

(defn suite-definition
  "Return the registry entry for a named suite keyword, or nil if unknown."
  [suite-key]
  (get suites (resolve-suite-key suite-key)))

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
