(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections (path lists) for run-collection.

   Suite naming:
     :yield-provider-scenarios — standalone `yield-v1` (scenarios/yield/Y*)
     :sew-yield-scenarios       — Sew escrow + yield integration (scenarios/S*)
     :yield-scenarios           — deprecated alias for :sew-yield-scenarios")

(def ^:private yield-provider-scenario-paths
  ["scenarios/yield/Y01_deposit-accrue-positive.json"
   "scenarios/yield/Y02_negative-yield-mtm.json"
   "scenarios/yield/Y03_partial-liquidity-shortfall-affected.json"
   "scenarios/yield/Y04_liquidity-shortfall-withdraw.json"
   "scenarios/yield/Y05_shortfall-affected-recovery.json"
   "scenarios/yield/Y06_liquidity-shortage-deposit-blocked.json"
   "scenarios/yield/Y07_monthly-accrual-one-year.json"])

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

(def suite-aliases
  "Deprecated suite keywords → canonical keys."
  {:yield-scenarios :sew-yield-scenarios})

(def suites
  "Suite keyword → {:paths [relative-path-str ...] :protocol-id ...}."
  {:sew-yield-scenarios      {:paths yield-scenario-paths
                              :protocol-id "sew-v1"}
   :yield-provider-scenarios {:paths yield-provider-scenario-paths
                              :protocol-id "yield-v1"}})

(defn resolve-suite-key
  "Map deprecated suite keywords to canonical keys."
  [suite-key]
  (get suite-aliases suite-key suite-key))

(defn suite-protocol-id
  "Protocol registry id for a named path suite (default sew-v1)."
  [suite-key]
  (or (get-in suites [(resolve-suite-key suite-key) :protocol-id]) "sew-v1"))

(defn suite-paths
  "Return scenario file paths for a registered suite keyword, or nil if unknown."
  [suite-key]
  (get-in suites [(resolve-suite-key suite-key) :paths]))

(defn known-suite-keys []
  (vec (sort (concat (keys suites) (keys suite-aliases)))))
