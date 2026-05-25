(ns resolver-sim.scenario.outcome-semantics
  "Shared outcome interpretation semantics used across reporting and suite evaluation.")

(defn normalize-purpose [purpose]
  (keyword (or purpose "")))

(defn negative-test-purpose?
  "True when the scenario purpose indicates expected-failure / falsification semantics."
  [purpose]
  (= (normalize-purpose purpose) :theory-falsification))

(defn theory-label [status]
  (case status
    :not-evaluated "Not evaluated"
    :not-falsified "Claim not falsified"
    :falsified     "Claim falsified"
    :inconclusive  "Inconclusive"
    "Not evaluated"))

(defn theory-expected?
  "Whether a theory status is expected under a scenario purpose.
   A falsified claim is expected only for theory-falsification scenarios."
  [status purpose]
  (case status
    :not-evaluated true
    :not-falsified true
    :falsified     (negative-test-purpose? purpose)
    :inconclusive  false
    true))

(defn theory-status-ok?
  "Suite pass/fail semantics for theory evaluation statuses.
   :inconclusive is soft by default, optionally strict via require-conclusive?."
  [status purpose {:keys [require-conclusive?] :or {require-conclusive? false}}]
  (case status
    nil            true
    :not-evaluated true
    :not-falsified true
    :falsified     (negative-test-purpose? purpose)
    :inconclusive  (not require-conclusive?)
    true))

(defn hard-failure-results?
  "True when any result entry is an explicit hard failure."
  [results]
  (some #(and (= :fail (:status %))
              (= :hard (:severity %)))
        results))

(defn inconclusive-status?
  [status]
  (contains? #{:inconclusive :not-applicable} status))

(defn domain-results-ok?
  "Generic pass/fail logic for mechanism/equilibrium result groups.
   Hard failures fail except in negative-test scenarios.
   In strict mode, inconclusive/not-applicable status fails.
   :not-checked is treated as no-op and passes."
  [purpose status results {:keys [require-conclusive?] :or {require-conclusive? false}}]
  (let [neg-test? (negative-test-purpose? purpose)]
    (and
     (or (not (hard-failure-results? results)) neg-test?)
     (or (not require-conclusive?)
         (not (inconclusive-status? status))))))

(defn purpose-label
  "Human-readable purpose label with stable fallback for unknown purposes."
  [p]
  (case p
    :regression              "Regression"
    :adversarial-robustness  "Adversarial Robustness"
    :theory-falsification    "Theory Falsification"
    :unclassified            "Unclassified (v1.0)"
    (if p (name p) "Unclassified (v1.0)")))
