(ns resolver-sim.notebooks.speds.config
  "SPEDS Configuration: Centralized paths and protocol identifiers."
  (:require [clojure.string :as str]))

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

(def sew-profile
  {:protocol-label "SEW_PROT"
   :suite-id "dispute-resolution-validation-v1"
   :finding-category "dispute_resolution"
   :bundle-cert-label "BUNDLE_v1.1"
   :default-theory-falsification-scenario-id "scenarios/S26_forking-strategist-l1-reversal"
   :story-family-rules
   {:default :deflection
    :families
    [{:family :theory-falsification
      :purposes #{:theory-falsification}}
     {:family :deadline-boundary
      :id-substrings ["appeal-deadline" "deadline"]
      :tag-substrings ["timing-boundary" "appeal-escalation"]}
     {:family :collusion
      :id-substrings ["collusion" "bribery"]
      :tag-substrings ["collusion"]}
     {:family :economic-solvency
      :id-substrings ["yield" "solvency"]
      :tag-substrings ["solvency" "conservation"]}
     {:family :deflection
      :purposes #{:adversarial-robustness}
      :tag-substrings ["fork" "reorg"]}]}})

(def sample-generic-profile
  "Example profile showing how SPEDS can be retargeted without code changes.
   Not active by default; intended as a copy/template for protocol-specific overrides."
  {:protocol-label "PROTOCOL_X"
   :suite-id "protocol-x-validation-v1"
   :finding-category "protocol_risk"
   :bundle-cert-label "BUNDLE_v1"
   :default-theory-falsification-scenario-id "scenarios/X01_hypothesis-boundary-check"
   :story-family-rules
   {:default :deflection
    :families
    [{:family :theory-falsification
      :purposes #{:theory-falsification :hypothesis-test}}
     {:family :deadline-boundary
      :id-substrings ["deadline" "timeout" "expiry"]
      :tag-substrings ["timing" "window-boundary"]}
     {:family :collusion
      :id-substrings ["cartel" "coalition" "bribery"]
      :tag-substrings ["collusion" "coordination"]}
     {:family :economic-solvency
      :id-substrings ["liquidity" "solvency" "reserve"]
      :tag-substrings ["conservation" "liability-coverage"]}
     {:family :deflection
      :purposes #{:adversarial-robustness :security-stress}
      :tag-substrings ["reorg" "fork" "adversarial"]}]}})

(def profiles
  {:sew sew-profile
   :generic sample-generic-profile})

(defn selected-profile-key
  "Select active SPEDS profile via env var `SPEDS_PROFILE`.
   Supported values: sew, generic. Defaults to sew."
  []
  (let [raw (some-> (System/getenv "SPEDS_PROFILE") str/lower-case)]
    (case raw
      "generic" :generic
      "sew" :sew
      :sew)))

(defn active-profile []
  (get profiles (selected-profile-key) sew-profile))

(def profile
  "Active profile map used by SPEDS modules.
   Override via `SPEDS_PROFILE=generic` (or `sew`)."
  (active-profile))
