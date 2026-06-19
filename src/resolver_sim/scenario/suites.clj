(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections (path lists) for run-collection.

   Suite naming:
     :yield-provider-scenarios — standalone `yield-v1` (scenarios/yield/Y*)
     :sew-yield-scenarios       — Sew escrow + yield integration (scenarios/S*)
     :yield-scenarios           — removed June 2026; use :sew-yield-scenarios")

(def ^:private yield-provider-scenario-paths
  ["scenarios/yield/Y01_deposit-accrue-positive.json"
   "scenarios/yield/Y02_negative-yield-mtm.json"
   "scenarios/yield/Y03_partial-liquidity-shortfall-affected.json"
   "scenarios/yield/Y04_liquidity-shortfall-withdraw.json"
   "scenarios/yield/Y05_shortfall-affected-recovery.json"
   "scenarios/yield/Y06_liquidity-shortage-deposit-blocked.json"
   "scenarios/yield/Y07_monthly-accrual-one-year.json"])

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
   "scenarios/S-DR-061-slash-propose-execute.json"])

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
  "Suite keyword → {:paths [relative-path-str ...] :protocol-id ...}."
  {:dispute-resolution-scenarios {:paths dispute-resolution-scenario-paths
                                   :protocol-id "sew-v1"}
   :sew-yield-scenarios      {:paths yield-scenario-paths
                              :protocol-id "sew-v1"}
   :yield-provider-scenarios {:paths yield-provider-scenario-paths
                              :protocol-id "yield-v1"}})

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

(defn known-suite-keys []
  (vec (sort (keys suites))))
