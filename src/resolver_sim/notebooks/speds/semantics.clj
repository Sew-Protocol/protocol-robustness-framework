(ns resolver-sim.notebooks.speds.semantics
  "SPEDS-local semantic mappings built on top of shared outcome semantics."
  (:require [resolver-sim.scenario.outcome-semantics :as ose]))

(defn purpose->kind [purpose]
  (case (ose/normalize-purpose purpose)
    :theory-falsification "expected_negative"
    :adversarial-robustness "liveness_risk"
    :regression "regression"
    "inconclusive_result"))

(defn purpose->story-family-str [purpose]
  (case (ose/normalize-purpose purpose)
    :theory-falsification "theory_falsification"
    :adversarial-robustness "threat_detected"
    "scenario_deep_dive"))

(defn purpose->story-family-kw [purpose]
  (case (ose/normalize-purpose purpose)
    :theory-falsification :theory-falsification
    :adversarial-robustness :threat-detected
    :scenario-deep-dive))

(defn classification-for-purpose [purpose]
  (case (ose/normalize-purpose purpose)
    :theory-falsification
    {:label "research_finding"
     :status "assumption_falsified"
     :confidence "high"
     :rationale "Scenario is explicitly tagged theory-falsification; negative outcomes are expected evidence, not regressions."}

    :regression
    {:label "regression"
     :status "unexpected_behavior"
     :confidence "high"
     :rationale "Scenario is explicitly tagged regression and should be treated as engineering defect signal."}

    {:label "operational_signal"
     :status "requires_triage"
     :confidence "medium"
     :rationale "Scenario does not explicitly declare falsification/regression semantics; manual triage recommended."}))
