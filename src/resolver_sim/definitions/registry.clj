(ns resolver-sim.definitions.registry
  "Canonical semantic definitions registry used by replay/report/evidence/Clerk.")

(def purposes
  {:regression {:label "Regression" :default-story-family :scenario-deep-dive}
   :adversarial-robustness {:label "Adversarial Robustness" :default-story-family :threat-detected}
   :theory-falsification {:label "Theory Falsification" :default-story-family :theory-falsification}
   :unclassified {:label "Unclassified (v1.0)" :default-story-family :scenario-deep-dive}})

(def statuses
  {:not-evaluated {:label "Not evaluated"}
   :not-falsified {:label "Claim not falsified"}
   :falsified {:label "Claim falsified"}
   :inconclusive {:label "Inconclusive"}
   :not-applicable {:label "Not applicable"}})

(def severities
  {:critical {:rank 4}
   :high {:rank 3}
   :medium {:rank 2}
   :low {:rank 1}})

(def story-families
  {:theory-falsification {:label "Theory falsification"}
   :threat-detected {:label "Threat detected"}
   :scenario-deep-dive {:label "Scenario deep dive"}
   :deflection {:label "Deflection"}
   :deadline-boundary {:label "Deadline boundary"}
   :collusion {:label "Collusion"}
   :economic-solvency {:label "Economic solvency"}})

(def confidence-levels
  {:high {:score 0.9}
   :medium {:score 0.6}
   :low {:score 0.3}})

(def speds-purpose-kind
  {:theory-falsification "expected_negative"
   :adversarial-robustness "liveness_risk"
   :regression "regression"
   :default "inconclusive_result"})

(def speds-purpose-classification
  {:theory-falsification
   {:label "research_finding"
    :status "assumption_falsified"
    :confidence "high"
    :rationale "Scenario is explicitly tagged theory-falsification; negative outcomes are expected evidence, not regressions."}

   :regression
   {:label "regression"
    :status "unexpected_behavior"
    :confidence "high"
    :rationale "Scenario is explicitly tagged regression and should be treated as engineering defect signal."}

   :default
   {:label "operational_signal"
    :status "requires_triage"
    :confidence "medium"
    :rationale "Scenario does not explicitly declare falsification/regression semantics; manual triage recommended."}})

(def status->story-family
  {:falsified :theory-falsification
   :not-falsified :scenario-deep-dive
   :inconclusive :scenario-deep-dive
   :not-evaluated :scenario-deep-dive})

(def transitions
  {:create_escrow {:label "Create escrow"}
   :raise_dispute {:label "Raise dispute"}
   :execute_resolution {:label "Execute resolution"}
   :execute_pending_settlement {:label "Execute pending settlement"}
   :automate_timed_actions {:label "Automate timed actions"}
   :release {:label "Release"}
   :sender_cancel {:label "Sender cancel"}
   :recipient_cancel {:label "Recipient cancel"}
   :auto_cancel_disputed {:label "Auto-cancel disputed"}
   :advance_time {:label "Advance time"}
   :escalate_dispute {:label "Escalate dispute"}
   :register_stake {:label "Register stake"}
   :challenge_resolution {:label "Challenge resolution"}})

(def invariants
  {:invariant/conservation {:label "Conservation" :default-severity :high :class :safety}
   :invariant/solvency {:label "Solvency" :default-severity :high :class :safety}
   :invariant/finality {:label "Finality" :default-severity :medium :class :liveness}})

(defn purpose-def [k] (get purposes k))
(defn status-def [k] (get statuses k))
(defn severity-def [k] (get severities k))
(defn story-family-def [k] (get story-families k))
(defn valid-purpose? [k] (contains? purposes k))
(defn valid-status? [k] (contains? statuses k))
(defn status->story-family* [k] (get status->story-family k :scenario-deep-dive))
(defn purpose->default-story-family [k] (or (get-in purposes [k :default-story-family]) :scenario-deep-dive))
(defn purpose->kind [k] (get speds-purpose-kind k (:default speds-purpose-kind)))
(defn purpose->classification [k] (get speds-purpose-classification k (:default speds-purpose-classification)))
(defn transition-def [k] (get transitions k))
(defn invariant-def [k] (get invariants k))
(defn canonical-transition-ids [] (set (keys transitions)))

(defn definitions-canonical-edn []
  (pr-str {:purposes purposes
           :statuses statuses
           :severities severities
           :story-families story-families
           :confidence-levels confidence-levels
           :speds-purpose-kind speds-purpose-kind
           :speds-purpose-classification speds-purpose-classification
           :transitions transitions
           :invariants invariants
           :status->story-family status->story-family}))

(defn definitions-hash []
  (-> (hash (definitions-canonical-edn)) Math/abs str))

(defn vocab-markdown []
  (str "# Semantic Vocabulary\n\n"
       "## Purposes\n"
       (apply str (for [[k v] purposes] (str "- `" (name k) "` — " (:label v) "\n")))
       "\n## Statuses\n"
       (apply str (for [[k v] statuses] (str "- `" (name k) "` — " (:label v) "\n")))))
