(ns resolver-sim.notebooks.speds.config
  "SPEDS Configuration: Centralized paths and protocol identifiers.")

(def artifact-paths
  {:test-summary "results/test-artifacts/test-summary.json"
   :coverage     "results/test-artifacts/coverage.json"
   :equivalence  "results/test-artifacts/equivalence-comparison-summary.json"
   :findings     "results/test-artifacts/findings.json"
   :issues       "results/test-artifacts/issues.json"
   :manifest     "evidence-manifest.json"
   :traces-dir   "data/fixtures/traces"
   :golden-dir   "data/fixtures/golden"})

(def protocol-defaults
  {:id          "dispute-resolution-validation-v1"
   :version     "1.1"
   :run-id      "UNNAMED"
   :git-sha     "AE8F2C1"
   :hash-suffix "8f2a74c1e5f6d3b2a1c9c8d7e6f5a4b3"})
