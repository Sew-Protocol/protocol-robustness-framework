(ns resolver-sim.scenario.suites
  "Named deterministic scenario collections (path lists) for run-collection.")

(def ^:private yield-scenario-paths
  ["scenarios/S108_negative-yield-mild.json"
   "scenarios/S103_negative-yield-shortfall-cascade.json"
   "scenarios/S78_yield-negative-yield-release-path.json"
   "scenarios/S79_yield-negative-yield-dispute-refund-path.json"
   "scenarios/S109_negative-yield-severe-repair.json"])

(def suites
  "Suite keyword → {:paths [relative-path-str ...]}."
  {:yield-scenarios {:paths yield-scenario-paths}})

(defn suite-paths
  "Return scenario file paths for a registered suite keyword, or nil if unknown."
  [suite-key]
  (get-in suites [suite-key :paths]))

(defn known-suite-keys []
  (keys suites))
