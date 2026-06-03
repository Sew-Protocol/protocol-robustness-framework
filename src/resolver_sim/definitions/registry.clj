(ns resolver-sim.definitions.registry
  "Canonical semantic definitions registry used by replay/report/evidence/Clerk.")

(def purposes
  {:regression {:label "Regression" :default-story-family :scenario-deep-dive}
   :adversarial-robustness {:label "Adversarial Robustness" :default-story-family :threat-detected}
   :theory-falsification {:label "Theory Falsification" :default-story-family :theory-falsification}
   :unclassified {:label "Unclassified (v1.0)" :default-story-family :scenario-deep-dive}})

(def statuses
  {:not-evaluated {:label "Not evaluated: no theory block"}
   :not-falsified {:label "Not falsified in this replay"}
   :falsified     {:label "Falsified by this replay"}
   :inconclusive  {:label "Inconclusive: evidence incomplete or invalid"}
   :not-applicable {:label "Not applicable"}})

(def proxy-statuses
  "Human-facing labels for mechanism/equilibrium validator status (not metric falsification)."
  {:pass            {:label "Proxy check passed"}
   :fail            {:label "Proxy check failed"}
   :inconclusive    {:label "Proxy check inconclusive"}
   :not-applicable  {:label "Proxy check not applicable"}
   :not-checked     {:label "Proxy check not run"}})

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
   :challenge_resolution {:label "Challenge resolution"}
   :submit_evidence {:label "Submit evidence"}})

(def transition-metadata
  {:create_escrow {:allowed-sources [:none]
                   :allowed-targets [:pending]
                   :guards [:unpaused :valid-params]
                   :actor-permissions [:sender]
                   :pause-effect :blocked-when-paused}
   :raise_dispute {:allowed-sources [:pending]
                   :allowed-targets [:disputed]
                   :guards [:participant :state-pending]
                   :actor-permissions [:sender :recipient]
                   :pause-effect :blocked-when-paused}
   :execute_resolution {:allowed-sources [:disputed]
                        :allowed-targets [:released :refunded]
                        :guards [:authorized-resolver :state-disputed]
                        :actor-permissions [:resolver]
                        :pause-effect :blocked-when-paused}
   :execute_pending_settlement {:allowed-sources [:disputed]
                                :allowed-targets [:released :refunded]
                                :guards [:pending-exists :deadline-expired]
                                :actor-permissions [:keeper :executor]
                                :pause-effect :blocked-when-paused}
   :automate_timed_actions {:allowed-sources [:pending :disputed]
                            :allowed-targets [:pending :released :refunded]
                            :guards [:deadline-eligible]
                            :actor-permissions [:keeper]
                            :pause-effect :blocked-when-paused}
   :release {:allowed-sources [:pending]
             :allowed-targets [:released]
             :guards [:authorized-release]
             :actor-permissions [:sender :authorized-release-address]
             :pause-effect :blocked-when-paused}
   :sender_cancel {:allowed-sources [:pending]
                   :allowed-targets [:pending]
                   :guards [:caller-is-sender]
                   :actor-permissions [:sender]
                   :pause-effect :blocked-when-paused}
   :recipient_cancel {:allowed-sources [:pending]
                      :allowed-targets [:pending :refunded]
                      :guards [:caller-is-recipient]
                      :actor-permissions [:recipient]
                      :pause-effect :blocked-when-paused}
   :auto_cancel_disputed {:allowed-sources [:disputed]
                          :allowed-targets [:refunded]
                          :guards [:timeout-expired]
                          :actor-permissions [:keeper]
                          :pause-effect :blocked-when-paused}
   :advance_time {:allowed-sources [:none :pending :disputed :released :refunded]
                  :allowed-targets [:none :pending :disputed :released :refunded]
                  :guards [:simulation-only]
                  :actor-permissions [:system]
                  :pause-effect :no-effect}
   :escalate_dispute {:allowed-sources [:disputed]
                      :allowed-targets [:disputed]
                      :guards [:pending-exists :appeal-window-open :max-level-not-reached]
                      :actor-permissions [:sender :recipient]
                      :pause-effect :blocked-when-paused}
   :register_stake {:allowed-sources [:none]
                    :allowed-targets [:none]
                    :guards [:stake-params-valid]
                    :actor-permissions [:resolver]
                    :pause-effect :blocked-when-paused}
   :challenge_resolution {:allowed-sources [:disputed]
                          :allowed-targets [:disputed]
                          :guards [:resolution-exists :challenge-window-open]
                          :actor-permissions [:challenger :watchdog]
                          :pause-effect :blocked-when-paused}
   :submit_evidence {:allowed-sources [:disputed]
                     :allowed-targets [:disputed]
                     :guards [:state-disputed]
                     :actor-permissions [:sender :recipient :challenger :watchdog]
                     :pause-effect :blocked-when-paused}})

(def invariants
  {:invariant/conservation {:invariant/id :invariant/conservation
                            :label "Conservation"
                            :default-severity :high
                            :class :safety}
   :invariant/solvency {:invariant/id :invariant/solvency
                        :label "Solvency"
                        :default-severity :high
                        :class :safety}
   :invariant/finality {:invariant/id :invariant/finality
                        :label "Finality"
                        :default-severity :medium
                        :class :liveness}})

(def invariant-metadata
  {:invariant/conservation
   {:related-transitions [:create_escrow :release :execute_resolution :execute_pending_settlement]
    :related-scenario-families [:scenario-deep-dive :economic-solvency]}
   :invariant/solvency
   {:related-transitions [:create_escrow :release :execute_resolution :execute_pending_settlement :automate_timed_actions]
    :related-scenario-families [:economic-solvency :threat-detected]}
   :invariant/finality
   {:related-transitions [:release :execute_resolution :execute_pending_settlement :automate_timed_actions]
    :related-scenario-families [:scenario-deep-dive :deadline-boundary]}})

(def claims
  {:claims/forking-l1-reversal
   {:claim/id :claims/forking-l1-reversal
    :claim/title "L1 reversal can overturn L0 decision under valid escalation"
    :claim/type :dispute-resolution
    :claim/statement "When challenged within the window, L1 resolver may legally reverse L0 outcome."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality :invariant/solvency]}

   :claims/forking-l2-path
   {:claim/id :claims/forking-l2-path
    :claim/title "Escalation to L2 path remains valid and bounded"
    :claim/type :dispute-resolution
    :claim/statement "Escalation from L0→L1→L2 follows bounded levels and valid guards."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality :invariant/conservation]}

   :claims/appeal-window-enforced
   {:claim/id :claims/appeal-window-enforced
    :claim/title "Appeal window enforces settlement timing"
    :claim/type :time-safety
    :claim/statement "Pending settlements execute only after appeal deadline."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality]}

   :claims/fork-isolation
   {:claim/id :claims/fork-isolation
    :claim/title "Forking outcomes remain escrow-isolated"
    :claim/type :safety
    :claim/statement "Escalation and settlement on one escrow do not leak state/balance to another escrow."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/conservation :invariant/solvency]}

   :claims/dr3-reversal-slash-disabled
   {:claim/id :claims/dr3-reversal-slash-disabled
    :claim/title "DR3 v3 disables non-zero reversal slashes"
    :claim/type :safety
    :claim/statement "When reversal-slash-bps is 0 in the module snapshot, no reversal slash entry carries a positive amount."
    :claim/evidence-mode :support
    :claim/related-invariants [:reversal-slash-disabled]}

   :claims/bribery-neutralized-by-l1
   {:claim/id :claims/bribery-neutralized-by-l1
    :claim/title "L1 challenge reverses biased L0 ruling"
    :claim/type :dispute-resolution
    :claim/statement "An honest L1 decision after challenge can overturn a collusive L0 refund and release escrow to the seller."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/finality :invariant/conservation]}

   :claims/reversal-slash-track1
   {:claim/id :claims/reversal-slash-track1
    :claim/title "Same-evidence reversal slash executes immediately"
    :claim/type :safety
    :claim/statement "When reversal-slash-bps > 0 and no new evidence, the prior resolver is slashed on stake basis with :executed status."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/solvency :invariant/conservation]}

   :claims/reversal-slash-track2-reversed
   {:claim/id :claims/reversal-slash-track2-reversed
    :claim/title "Track 2 reversal slash can be reversed on appeal"
    :claim/type :safety
    :claim/statement "When new evidence triggers a pending reversal slash, a upheld appeal reverses the slash before stake is executed."
    :claim/evidence-mode :support
    :claim/related-invariants [:slash-status-consistent? :invariant/solvency]}

   :claims/reversal-slash-track2-executes
   {:claim/id :claims/reversal-slash-track2-executes
    :claim/title "Rejected Track 2 reversal appeal allows slash execution"
    :claim/type :safety
    :claim/statement "When governance rejects a reversal-slash appeal after the timelock, the pending slash executes and reduces resolver stake."
    :claim/evidence-mode :support
    :claim/related-invariants [:slash-status-consistent? :invariant/solvency]}

   :claims/resolver-capacity-enforced
   {:claim/id :claims/resolver-capacity-enforced
    :claim/title "Resolver concurrent dispute capacity is enforced"
    :claim/type :safety
    :claim/statement "When a resolver is at max concurrent disputes, additional disputes on that resolver are rejected."
    :claim/evidence-mode :support
    :claim/related-invariants [:invariant/solvency]}})

(def claim-scenario-map
  {:claims/forking-l1-reversal
   {:supporting ["S26_forking-strategist-l1-reversal"]
    :falsifying []}
   :claims/forking-l2-path
   {:supporting ["S27_forking-strategist-l2-fork"
                 "S31_forking-strategist-all-levels-confirm"]
    :falsifying []}
   :claims/appeal-window-enforced
   {:supporting ["S32_forking-strategist-premature-settlement-rejected"
                 "S36_profit-maximizer-pre-window-execute-rejected"
                 "S74_appeal-deadline-boundary"]
    :falsifying []}
   :claims/fork-isolation
   {:supporting ["S33_forking-strategist-two-escrow-fork-isolation"
                 "S62_cross-token-isolation-under-dispute-load"
                 "S62_cross-token-fee-on-transfer-under-dispute-load"
                 "S62_cross-token-parallel-appeal-depths-under-dispute-load"]
    :falsifying []}
   :claims/resolver-capacity-enforced
   {:supporting ["S62_resolver-capacity-concurrent-dispute-load"]
    :falsifying []}
   :claims/dr3-reversal-slash-disabled
   {:supporting ["S41_dr3-reversal-slash-disabled"]
    :falsifying []}
   :claims/bribery-neutralized-by-l1
   {:supporting ["S42_resolver-buyer-bribery-loop"]
    :falsifying []}
   :claims/reversal-slash-track1
   {:supporting ["s101-reversal-slash-track1-enabled"
                 "S103_l2-reversal-slash-ids"]
    :falsifying []}
   :claims/reversal-slash-track2-reversed
   {:supporting ["S106_reversal-track2-evidence-appeal"]
    :falsifying []}
   :claims/reversal-slash-track2-executes
   {:supporting ["S107_reversal-track2-appeal-rejected-executes"]
    :falsifying []}})

(defn purpose-def [k] (get purposes k))
(defn status-def [k] (get statuses k))
(defn proxy-status-def [k] (get proxy-statuses k))
(defn severity-def [k] (get severities k))
(defn story-family-def [k] (get story-families k))
(defn valid-purpose? [k] (contains? purposes k))
(defn valid-status? [k] (contains? statuses k))
(defn status->story-family* [k] (get status->story-family k :scenario-deep-dive))
(defn purpose->default-story-family [k] (or (get-in purposes [k :default-story-family]) :scenario-deep-dive))
(defn purpose->kind [k] (get speds-purpose-kind k (:default speds-purpose-kind)))
(defn purpose->classification [k] (get speds-purpose-classification k (:default speds-purpose-classification)))
(defn transition-def [k] (get transitions k))
(defn transition-meta [k] (get transition-metadata k))
(defn invariant-def [k] (get invariants k))
(defn invariant-meta [k] (get invariant-metadata k))
(defn claim-def [k] (get claims k))
(defn claim-scenarios [k] (get claim-scenario-map k))
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
           :transition-metadata transition-metadata
           :invariants invariants
           :invariant-metadata invariant-metadata
           :claims claims
           :claim-scenario-map claim-scenario-map
           :status->story-family status->story-family}))

(defn definitions-hash []
  (-> (hash (definitions-canonical-edn)) Math/abs str))

(defn vocab-markdown []
  (str "# Semantic Vocabulary\n\n"
       "## Purposes\n"
       (apply str (for [[k v] purposes] (str "- `" (name k) "` — " (:label v) "\n")))
       "\n## Statuses\n"
       (apply str (for [[k v] statuses] (str "- `" (name k) "` — " (:label v) "\n")))))
