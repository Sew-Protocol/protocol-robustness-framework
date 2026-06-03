(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections (path lists) for run-collection.")

(def ^:private yield-provider-scenario-paths
  ["scenarios/yield/Y01_deposit-accrue-positive.json"
   "scenarios/yield/Y02_negative-yield-mtm.json"
   "scenarios/yield/Y03_partial-liquidity-shortfall-affected.json"
   "scenarios/yield/Y04_liquidity-shortfall-withdraw.json"
   "scenarios/yield/Y05_shortfall-affected-recovery.json"
   "scenarios/yield/Y06_liquidity-shortage-deposit-blocked.json"])

(def ^:private yield-scenario-paths
  ["scenarios/S108_negative-yield-mild.json"
   "scenarios/S103_negative-yield-shortfall-cascade.json"
   "scenarios/S78_yield-negative-yield-release-path.json"
   "scenarios/S79_yield-negative-yield-dispute-refund-path.json"
   "scenarios/S109_negative-yield-severe-repair.json"])

(def suites
  "Suite keyword → {:paths [relative-path-str ...]}."
  {:yield-scenarios         {:paths yield-scenario-paths
                              :protocol-id "sew-v1"}
   :yield-provider-scenarios {:paths yield-provider-scenario-paths
                              :protocol-id "yield-v1"}})

(defn suite-protocol-id
  "Protocol registry id for a named path suite (default sew-v1)."
  [suite-key]
  (or (get-in suites [suite-key :protocol-id]) "sew-v1"))

(defn suite-paths
  "Return scenario file paths for a registered suite keyword, or nil if unknown."
  [suite-key]
  (get-in suites [suite-key :paths]))

(defn known-suite-keys []
  (keys suites))
