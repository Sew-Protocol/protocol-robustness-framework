(ns resolver-sim.scenario.equilibrium
  "Trace-end mechanism-property and equilibrium-concept validation.

   Activates the :mechanism-properties and :equilibrium-concept fields in CDRS
   v1.1 theory blocks. These fields were previously RESERVED; this namespace
   makes them ACTIVE as terminal trace proxy validations.

   ## What this is

   A lightweight falsification layer that checks whether realised terminal
   outcomes are consistent with claimed economic properties. The output always
   says 'trace-consistent with claimed property', never 'equilibrium proven'.

   A Nash equilibrium requires comparing deviations across many traces.
   A single replay can only check that no observed attack succeeded and no
   invariant was violated. All results carry a :basis field that declares
   the strength of the check (see Claim-Strength Taxonomy below).

   ## Claim-Strength Taxonomy (:basis values)

     :single-trace-terminal-proxy   — terminal world state only; no deviation comparison
     :single-trace-metric-proxy     — accumulated metrics from one trace
     :absent-evidence               — required evidence fields not present → inconclusive
     :not-applicable                — property logically cannot apply in this scenario context
     :multi-trace-required          — only meaningful across N traces; single-trace inconclusive
     :multi-epoch-required          — only meaningful across epochs; single-trace inconclusive

   ## Result statuses

     :pass           — trace is consistent with the claimed property
     :fail           — trace violates the property (hard failure when :severity :hard)
     :inconclusive   — required evidence absent or property requires more than one trace
     :not-applicable — property cannot be evaluated in this scenario context

   ## Severity

     :hard — a :fail blocks the suite (same as expectations failure)
     :soft — a :fail is a warning; :inconclusive is always soft

   This namespace is pure — no I/O, no DB, no side effects."
  (:require [resolver-sim.scenario.projection :as proj]
            [resolver-sim.scenario.subgame-counterfactual :as subgame-cf]))

;; ---------------------------------------------------------------------------
;; Result constructors
;; ---------------------------------------------------------------------------

(defn- pass [property basis observed expected]
  {:property  property
   :status    :pass
   :severity  :hard
   :basis     basis
   :observed  observed
   :expected  expected
   :offending []
   :requires  []})

(defn- fail [property basis observed expected offending]
  {:property  property
   :status    :fail
   :severity  :hard
   :basis     basis
   :observed  observed
   :expected  expected
   :offending (vec offending)
   :requires  []})

(defn- inconclusive [property basis reason]
  {:property  property
   :status    :inconclusive
   :severity  :soft
   :basis     basis
   :observed  nil
   :expected  nil
   :offending []
   :requires  [reason]})

(defn- not-applicable [property reason]
  {:property  property
   :status    :not-applicable
   :severity  :soft
   :basis     :not-applicable
   :observed  nil
   :expected  nil
   :offending []
   :requires  [reason]})

;; ---------------------------------------------------------------------------
;; Mechanism-property validators
;; ---------------------------------------------------------------------------

(defn- check-budget-balance
  "No residual protocol-held funds remain after all relevant escrows reach
   terminal states, excluding explicitly retained fees.

   :not-applicable when escrows are still open (terminal? = false) or
   when the scenario used allow-open-disputes?.
   :pass when every token's total-held-by-token = 0.
   :fail when any token still holds funds."
  [{:keys [terminal-world trace-summary]}]
  (let [halt     (:halt-reason trace-summary)
        terminal (:terminal? terminal-world)]
    (cond
      ;; Scenario explicitly permits open disputes at end
      (= halt :open-disputes-at-end)
      (not-applicable :budget-balance "scenario allows open disputes at end")

      ;; Non-terminal escrows legitimately hold funds
      (not terminal)
      (not-applicable :budget-balance "non-terminal escrows remain; held funds are expected")

      :else
      (let [held (:total-held-by-token terminal-world {})]
        (if (every? #(zero? (val %)) held)
          (pass :budget-balance :single-trace-terminal-proxy
                held "all total-held-by-token values equal zero when all escrows terminal")
          (let [offending (filterv (fn [[_ v]] (pos? v)) held)]
            (fail :budget-balance :single-trace-terminal-proxy
                  held {:EXPECTED "all token balances zero" :actual held}
                  offending)))))))

(defn- check-incentive-compatibility
  "No actor obtained higher realised payoff through labelled adversarial action
   than through honest baseline, when adversarial actors are present.

   :inconclusive when no adversarial actors appear in the trace.
   :pass when attack-successes = 0 and funds-lost = 0.
   :fail when any adversarial event succeeded or funds were lost."
  [{:keys [metrics]}]
  (let [attempts  (:attack-attempts metrics 0)
        successes (:attack-successes metrics 0)
        lost      (:funds-lost metrics 0)]
    (cond
      (zero? attempts)
      (inconclusive :incentive-compatibility :single-trace-metric-proxy
                    "no adversarial actors in trace; property vacuously consistent but untested")

      (or (pos? successes) (pos? lost))
      (fail :incentive-compatibility :single-trace-metric-proxy
            {:attack-successes successes :funds-lost lost}
            {:attack-successes 0 :funds-lost 0}
            (cond-> []
              (pos? successes) (conj {:metric :attack-successes :observed successes})
              (pos? lost)      (conj {:metric :funds-lost :observed lost})))

      :else
      (pass :incentive-compatibility :single-trace-metric-proxy
            {:attack-successes successes :funds-lost lost}
            "no adversarial action succeeded; no funds lost"))))

(defn- check-individual-rationality
  "No required honest participant has a negative net payoff.

   :inconclusive when payoff-ledger is not tracked (negative-payoff-count = nil).
   Falls back to partial proxy (funds-lost = 0) when full ledger absent.
   :pass when negative-payoff-count = 0 or (partial) funds-lost = 0."
  [{:keys [metrics payoff-ledger-summary]}]
  (let [npc  (:negative-payoff-count metrics)
        ledger (get payoff-ledger-summary :per-actor {})
        lost (:funds-lost metrics 0)]
    (cond
      ;; Full payoff ledger available
      (some? npc)
      (if (zero? npc)
        (pass :individual-rationality :single-trace-metric-proxy
              {:negative-payoff-count npc
               :actors-evaluated (count ledger)}
              "no participant ended with negative net payoff")
        (fail :individual-rationality :single-trace-metric-proxy
              {:negative-payoff-count npc
               :actors-evaluated (count ledger)}
              {:negative-payoff-count 0}
              (if (seq ledger)
                (->> ledger
                     (keep (fn [[actor row]]
                             (let [net (long (:net-payoff row 0))]
                               (when (neg? net)
                                 {:actor actor :net-payoff net :metric :negative-payoff-count}))))
                     vec)
                [{:metric :negative-payoff-count :observed npc}])))

      ;; Partial proxy: funds-lost only
      (pos? lost)
      (fail :individual-rationality :single-trace-metric-proxy
            {:funds-lost lost}
            {:funds-lost 0}
            [{:metric :funds-lost :observed lost
              :note "partial proxy — full payoff-ledger not tracked"}])

      :else
      (inconclusive :individual-rationality :absent-evidence
                    "payoff-ledger not tracked; cannot fully evaluate individual rationality"))))

(defn- check-collusion-resistance
  "Labelled coalition does not profit relative to non-collusive baseline.

   :inconclusive for single-trace (coalition-net-profit requires multi-epoch
   runner or population data). Checks metric if present."
  [{:keys [metrics payoff-ledger-summary]}]
  (let [cnp (:coalition-net-profit metrics)]
    (if (nil? cnp)
      (inconclusive :collusion-resistance :multi-trace-required
                    "coalition-net-profit metric absent; requires multi-epoch batch runner")
      (if (<= cnp 0)
        (pass :collusion-resistance :single-trace-metric-proxy
              {:coalition-net-profit cnp
               :ledger-coalition-net-profit (get payoff-ledger-summary :coalition-net-profit)}
              "coalition net profit ≤ 0")
        (fail :collusion-resistance :single-trace-metric-proxy
              {:coalition-net-profit cnp
               :ledger-coalition-net-profit (get payoff-ledger-summary :coalition-net-profit)}
              {:coalition-net-profit "≤ 0"}
              [{:metric :coalition-net-profit :observed cnp}])))))

(defn- check-sybil-resistance
  "No resolver gained advantage through unauthorized identity.

   Proxy: attack-successes = 0 (blunt; does not distinguish identity attacks).
   :inconclusive when no attacks present."
  [{:keys [metrics]}]
  (let [attempts  (:attack-attempts metrics 0)
        successes (:attack-successes metrics 0)]
    (cond
      (zero? attempts)
      (inconclusive :sybil-resistance :single-trace-metric-proxy
                    "no adversarial actors; sybil resistance untested in this trace")

      (pos? successes)
      (fail :sybil-resistance :single-trace-metric-proxy
            {:attack-successes successes}
            {:attack-successes 0}
            [{:metric :attack-successes :observed successes}])

      :else
      (pass :sybil-resistance :single-trace-metric-proxy
            {:attack-successes successes}
            "no unauthorized identity attack succeeded"))))

(defn- check-force-refund-path-integrity
  "Ensure no workflow marked :refunded is also marked as release path.
   Placeholder integrity check over projection-level workflow outcomes."
  [{:keys [money-movement-summary]}]
  (let [outcomes (get money-movement-summary :workflow-outcomes {})
        bad      (->> outcomes
                      (filter (fn [[_ {:keys [terminal-state path]}]]
                                (and (= :refunded terminal-state)
                                     (= :release path))))
                      (mapv first))]
    (if (seq bad)
      (fail :force-refund-path-integrity :single-trace-terminal-proxy
            {:workflow-outcomes outcomes}
            "refunded workflows must not have release path"
            bad)
      (pass :force-refund-path-integrity :single-trace-terminal-proxy
            {:workflow-count (count outcomes)}
            "all refunded workflows preserve refund-only terminal path"))))

(defn- check-pending-lifecycle-integrity
  "Pending lifecycle should not clear more entries than it created.
   Also, superseded count cannot exceed cleared count." 
  [{:keys [money-movement-summary]}]
  (let [pl (get-in money-movement-summary [:pending-lifecycle :unknown] {:created 0 :cleared 0 :superseded 0})
        {:keys [created cleared superseded]} pl]
    (cond
      (> cleared created)
      (fail :pending-lifecycle-integrity :single-trace-metric-proxy
            pl
            "pending cleared cannot exceed pending created"
            [{:field :cleared :observed cleared :max-allowed created}])

      (> superseded cleared)
      (fail :pending-lifecycle-integrity :single-trace-metric-proxy
            pl
            "pending superseded cannot exceed pending cleared"
            [{:field :superseded :observed superseded :max-allowed cleared}])

      :else
      (pass :pending-lifecycle-integrity :single-trace-metric-proxy
            pl
            "pending lifecycle counts are consistent"))))

(defn- check-stake-flow-conservation
  "For each resolver: start - withdrawn - slashed == end."
  [{:keys [stake-flow-summary]}]
  (let [violations (->> stake-flow-summary
                        (keep (fn [[resolver {:keys [start withdrawn slashed end]}]]
                                (let [lhs (- (long start) (long withdrawn) (long slashed))]
                                  (when (not= lhs (long end))
                                    {:resolver resolver :start start :withdrawn withdrawn :slashed slashed :end end :expected-end lhs}))))
                        vec)]
    (if (seq violations)
      (fail :stake-flow-conservation :single-trace-metric-proxy
            {:stake-flow-summary stake-flow-summary}
            "start - withdrawn - slashed must equal end stake"
            violations)
      (pass :stake-flow-conservation :single-trace-metric-proxy
            {:resolver-count (count stake-flow-summary)}
            "stake flow balances for all resolvers"))))

;; ---------------------------------------------------------------------------
;; Equilibrium-concept validators
;; ---------------------------------------------------------------------------

(defn- check-dominant-strategy-equilibrium
  "Honest behavior was a dominant strategy: the observed outcome is consistent
   with honest play being optimal regardless of others' strategies.

   Single-trace proxy: invariant-violations = 0 AND attack-successes = 0.
   This does NOT verify dominance across all opponent strategies — it only
   checks that no deviation from honest behavior was profitable in this trace.

   :inconclusive when no adversarial actors are present (untested)."
  [{:keys [metrics]}]
  (let [violations (:invariant-violations metrics 0)
        successes  (:attack-successes metrics 0)
        attempts   (:attack-attempts metrics 0)]
    (cond
      (and (zero? attempts) (zero? violations))
      (inconclusive :dominant-strategy-equilibrium :single-trace-metric-proxy
                    "no adversarial actors in trace; dominance is consistent but untested")

      (or (pos? violations) (pos? successes))
      (fail :dominant-strategy-equilibrium :single-trace-metric-proxy
            {:invariant-violations violations :attack-successes successes}
            {:invariant-violations 0 :attack-successes 0}
            (cond-> []
              (pos? violations) (conj {:metric :invariant-violations :observed violations})
              (pos? successes)  (conj {:metric :attack-successes :observed successes})))

      :else
      (pass :dominant-strategy-equilibrium :single-trace-metric-proxy
            {:invariant-violations violations :attack-successes successes}
            "no deviation from honest behavior was profitable in this trace (single-trace proxy)"))))

(defn- check-nash-equilibrium
  "No profitable unilateral deviation was observed. Trace is consistent with
   Nash equilibrium.

   Single-trace proxy: attack-successes = 0 AND invariant-violations = 0.
   This does NOT verify that no profitable deviation exists — only that no
   deviation succeeded in this trace.

   :inconclusive when no adversarial actors present."
  [{:keys [metrics]}]
  (let [violations (:invariant-violations metrics 0)
        successes  (:attack-successes metrics 0)
        attempts   (:attack-attempts metrics 0)]
    (cond
      (and (zero? attempts) (zero? violations))
      (inconclusive :nash-equilibrium :single-trace-metric-proxy
                    "no adversarial actors; Nash consistency untested in this trace")

      (or (pos? violations) (pos? successes))
      (fail :nash-equilibrium :single-trace-metric-proxy
            {:invariant-violations violations :attack-successes successes}
            {:invariant-violations 0 :attack-successes 0}
            (cond-> []
              (pos? successes)  (conj {:metric :attack-successes :observed successes})
              (pos? violations) (conj {:metric :invariant-violations :observed violations})))

      :else
      (pass :nash-equilibrium :single-trace-metric-proxy
            {:invariant-violations violations :attack-successes successes}
            "no unilateral deviation succeeded in this trace (single-trace proxy)"))))

(defn- check-subgame-perfect-equilibrium
  "Heuristic: No Profitable Regret (Trace-level SPE Proxy).
   An agent strategy is SPE-consistent if every decision node in the trace
   leads to a terminal payoff that is at least as good as the immediate
   alternative (settlement/inaction).

   This is a proxy check, not a formal proof. It detects when an agent takes
   an action that resulted in an ex-post loss given the observed future.

   Classifications:
     :ex-post-regret          — wealth(T) < wealth(t-1) after strategic choice
     :strict-dominated-action — (future) action always worse than alternative
     :insufficient-information — trace ends before subgame resolution
     :inconclusive-payoff     — wealth cannot be calculated

   :inconclusive when no strategic decisions were made.

   Phase F: proper subgame boundary coverage counts included in observed.
   Phase G: strategy-profile included in observed.
   Phase H: :spe-result rich vocabulary key included in observed.
   Phase I: :spe-counterexamples structured counterexample maps.
   Phase J: :spe-off-path-coverage map included in observed.
   Phase L: :spe-proof-sketch human-readable summary."
  [projection]
  (let [{:keys [status basis regret-table max-regret mean-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec class-counts
                exceed-epsilon-count regret-distribution epsilon-abs epsilon-rel
                max-deviation-depth memoization
                spe-result strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage]}
        (subgame-cf/evaluate-subgame-counterfactual projection)
        ;; Phase L: human-readable proof sketch
        proof-sketch (str
                      "Claim: Bounded public-state SPE proxy under declared strategy profile "
                      (or (:id strategy-profile) "unknown") ".\n\n"
                      "Method:\n"
                      "  - continuation-policy: " (or (:mode continuation-policy) :unknown)
                      " (version " (or (:version continuation-policy) "unknown") ")\n"
                      "  - utility-spec: " (or (:type utility-spec) :unknown)
                      " (version " (or (:version utility-spec) "unknown") ")\n"
                      "  - max deviation depth: " (long (or max-deviation-depth 1)) "\n"
                      "  - epsilon: abs=" epsilon-abs ", rel=" epsilon-rel "\n\n"
                      "Note: Alternatives are heuristic utility estimates under the configured "
                      "continuation policy, not independent protocol replays. Regret values are "
                      "proxy measurements; this is not a formal SPE proof.\n\n"
                      "Checked:\n"
                      "  - " (long (or proper-subgames-checked 0)) " proper subgame node(s)\n"
                      "  - " (long (or information-set-nodes-checked 0)) " information-set node(s) (inconclusive)\n"
                      "  - " (long (or not-checkable-nodes 0)) " not-checkable node(s)\n"
                      "  - memoization: " (if (get-in memoization [:enabled])
                                             (str "enabled, entries=" (long (or (get memoization :entries) 0))
                                                  ", hits=" (long (or (get memoization :hits) 0)))
                                             "disabled")
                      "\n\n"
                      "Result:\n"
                      (case status
                        :pass   (str "  - No profitable deviation exceeded epsilon = " epsilon-abs "\n"
                                     "  - Max regret: " max-regret "\n"
                                     "  - SPE result: " spe-result)
                        :fail   (str "  - Profitable deviation detected\n"
                                     "  - Max regret: " max-regret " (threshold: " threshold ")\n"
                                     "  - SPE result: " spe-result "\n"
                                     "  - Counterexamples: " (count counterexamples))
                        (str "  - Inconclusive: " (or (first requires) "evidence unavailable") "\n"
                             "  - SPE result: " spe-result)))
        observed {:spe-status      status
                  :spe-result      spe-result
                  :spe-summary     (case status
                                     :pass (str "bounded counterfactual regret <= threshold across " checked-nodes " node(s)")
                                     :fail (str "bounded counterfactual regret exceeds threshold at one or more nodes")
                                     :inconclusive (or (first requires) "counterfactual evidence unavailable")
                                     "counterfactual evidence unavailable")
                  :spe-regret-table regret-table
                  :spe-max-regret   max-regret
                  :spe-mean-regret  mean-regret
                  :spe-threshold    threshold
                  :spe-epsilon-abs epsilon-abs
                  :spe-epsilon-rel epsilon-rel
                  :spe-max-deviation-depth max-deviation-depth
                  :spe-continuation-policy continuation-policy
                  :spe-replay-boundary replay-boundary
                  :spe-utility-spec utility-spec
                  :spe-strategy-profile strategy-profile
                  :spe-proper-subgames-checked proper-subgames-checked
                  :spe-information-set-nodes-checked information-set-nodes-checked
                  :spe-not-checkable-nodes not-checkable-nodes
                  :spe-class-counts class-counts
                  :spe-exceed-epsilon-count exceed-epsilon-count
                  :spe-memoization memoization
                  :spe-regret-distribution regret-distribution
                  :spe-counterexamples (vec counterexamples)
                  :spe-off-path-coverage off-path-coverage
                  :spe-proof-sketch proof-sketch
                  :decisions-checked checked-nodes
                  :spe-violations   (vec (filter (fn [r] (pos? (long (or (:local-regret r) 0)))) regret-table))}]
    (case status
      :pass
      (pass :subgame-perfect-equilibrium basis
            observed
            {:spe-status :pass :max-regret (str "<= " threshold)})

      :fail
      (fail :subgame-perfect-equilibrium basis
            observed
            {:spe-status :pass :max-regret (str "<= " threshold)}
            (:spe-violations observed))

      (inconclusive :subgame-perfect-equilibrium basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-bounded-public-state-epsilon-spe
  "Phase K: Bounded public-state epsilon-SPE proxy.

   Distinct from :subgame-perfect-equilibrium in that it:
   - requires an explicit :spe-config in the theory block,
   - explicitly declares the equilibrium concept as bounded and public-state only,
   - uses the full Phase F–J evaluator (subgame classification, strategy profile,
     counterexamples, off-path coverage, proof sketch),
   - falsifies if max-regret > epsilon-abs or profitable-deviation-count > 0.

   :inconclusive when no proper subgames were found (only information-set nodes)."
  [projection]
  (let [{:keys [status basis regret-table max-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec
                spe-result strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret]}
        (subgame-cf/evaluate-subgame-counterfactual projection)
        eq-concept :bounded-public-state-epsilon-spe]
    (cond
      (zero? (long (or proper-subgames-checked 0)))
      (inconclusive eq-concept :absent-evidence
                    (str "no proper subgames found (proper-subgames-checked=0); "
                         "all nodes were information-set or not-checkable"))

      (= status :pass)
      (pass eq-concept basis
            {:spe-result spe-result
             :spe-max-regret max-regret
             :spe-threshold threshold
             :spe-epsilon-abs epsilon-abs
             :spe-epsilon-rel epsilon-rel
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :counterexamples counterexamples
             :off-path-coverage off-path-coverage
             :decisions-checked checked-nodes}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0})

      (= status :fail)
      (fail eq-concept basis
            {:spe-result spe-result
             :spe-max-regret max-regret
             :spe-threshold threshold
             :counterexamples counterexamples
             :profitable-deviation-count (count counterexamples)
             :proper-subgames-checked proper-subgames-checked
             :strategy-profile strategy-profile}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0}
            (mapv (fn [ce] {:metric :profitable-deviation
                            :node/id (:node/id ce)
                            :regret (:regret ce)
                            :agent (:agent ce)})
                  counterexamples))

      :else
      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-bounded-backward-induction-spe
  "Phase K: Bounded backward-induction epsilon-SPE proxy.

   Same as check-bounded-public-state-epsilon-spe but evaluates decision nodes in
   descending seq order (highest seq first), classifies each alternative as
   :terminal-deviation or :continuation-deviation, and propagates downstream
   continuation values for continuation deviations.

   Inject :evaluation-mode :backward-induction into spe-config so the evaluator
   selects the backward-induction path.

   :inconclusive when no proper subgames were found."
  [projection]
  (let [projection' (update projection :spe-config assoc :evaluation-mode :backward-induction)
        {:keys [status basis regret-table max-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec
                spe-result strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret
                evaluation-mode backward-induction-depth
                deviation-terminal-count deviation-continuation-count]}
        (subgame-cf/evaluate-subgame-counterfactual projection')
        eq-concept :bounded-backward-induction-spe]
    (cond
      (zero? (long (or proper-subgames-checked 0)))
      (inconclusive eq-concept :absent-evidence
                    (str "no proper subgames found (proper-subgames-checked=0); "
                         "all nodes were information-set or not-checkable"))

      (= status :pass)
      (pass eq-concept basis
            {:spe-result spe-result
             :spe-max-regret max-regret
             :spe-threshold threshold
             :spe-epsilon-abs epsilon-abs
             :spe-epsilon-rel epsilon-rel
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :counterexamples counterexamples
             :off-path-coverage off-path-coverage
             :decisions-checked checked-nodes
             :evaluation-mode evaluation-mode
             :backward-induction-depth backward-induction-depth
             :deviation-terminal-count deviation-terminal-count
             :deviation-continuation-count deviation-continuation-count}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0})

      (= status :fail)
      (fail eq-concept basis
            {:spe-result spe-result
             :spe-max-regret max-regret
             :spe-threshold threshold
             :counterexamples counterexamples
             :profitable-deviation-count (count counterexamples)
             :proper-subgames-checked proper-subgames-checked
             :strategy-profile strategy-profile
             :evaluation-mode evaluation-mode
             :deviation-terminal-count deviation-terminal-count
             :deviation-continuation-count deviation-continuation-count}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0}
            (mapv (fn [ce] {:metric :profitable-deviation
                            :node/id (:node/id ce)
                            :regret (:regret ce)
                            :agent (:agent ce)})
                  counterexamples))

      :else
      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-resolver-reputation-spe
  "Reputation-aware epsilon-SPE proxy (Gap D).

   Same as check-bounded-public-state-epsilon-spe but forces utility-spec type to
   :resolver-reputation-v1 so each decision node's utility includes the additional
   future-earnings reputation penalty for slashed resolvers.

   Key accounting note: terminal-realized-wealth already includes the stake reduction
   from slashing. The reputation-slash-penalty is an ADDITIONAL future-earnings term
   only — it is not a second subtraction of the stake loss.

   Parameters from spe-config.utility-spec:
     :reputation-slash-penalty  — token-equivalent future earnings lost per slash event
     :reputation-discount-rate  — multiplier on penalty (default 1.0)
     :slash-detection-mode      — :explicit-slash-total (default) | :stake-delta
     :slash-threshold           — minimum stake drop for :stake-delta mode (default 1)

   When :reputation-slash-penalty is 0 (the default), results match
   :terminal-realized-v1 (zero-penalty compatibility).

   Observed map includes :min-reputation-penalty-for-spe-pass: the minimum penalty
   magnitude required to deter every profitable deviation identified in the trace.
   This converts a pass/fail verdict into a quantitative deterrence threshold."
  [projection]
  (let [projection' (update projection :spe-config
                            (fn [cfg]
                              (update cfg :utility-spec
                                      (fn [us] (merge us {:type :resolver-reputation-v1})))))
        {:keys [status basis regret-table max-regret threshold checked-nodes requires
                continuation-policy replay-boundary utility-spec
                spe-result strategy-profile
                proper-subgames-checked information-set-nodes-checked not-checkable-nodes
                counterexamples off-path-coverage epsilon-abs epsilon-rel
                class-counts exceed-epsilon-count memoization regret-distribution
                max-deviation-depth mean-regret
                evaluation-mode min-reputation-penalty-for-spe-pass]}
        (subgame-cf/evaluate-subgame-counterfactual projection')
        eq-concept :resolver-reputation-spe
        penalty    (get-in projection' [:spe-config :utility-spec :reputation-slash-penalty] 0)
        slash-detected-count (count (filter #(get-in % [:utility-breakdown :slash-detected?])
                                            regret-table))]
    (cond
      (zero? (long (or proper-subgames-checked 0)))
      (inconclusive eq-concept :absent-evidence
                    (str "no proper subgames found (proper-subgames-checked=0); "
                         "all nodes were information-set or not-checkable"))

      (= status :pass)
      (pass eq-concept basis
            {:spe-result spe-result
             :spe-max-regret max-regret
             :spe-threshold threshold
             :spe-epsilon-abs epsilon-abs
             :spe-epsilon-rel epsilon-rel
             :strategy-profile strategy-profile
             :proper-subgames-checked proper-subgames-checked
             :information-set-nodes-checked information-set-nodes-checked
             :counterexamples counterexamples
             :off-path-coverage off-path-coverage
             :decisions-checked checked-nodes
             :utility-type :resolver-reputation-v1
             :reputation-slash-penalty penalty
             :slash-detected-count slash-detected-count
             :min-reputation-penalty-for-spe-pass min-reputation-penalty-for-spe-pass}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0})

      (= status :fail)
      (fail eq-concept basis
            {:spe-result spe-result
             :spe-max-regret max-regret
             :spe-threshold threshold
             :counterexamples counterexamples
             :profitable-deviation-count (count counterexamples)
             :proper-subgames-checked proper-subgames-checked
             :strategy-profile strategy-profile
             :utility-type :resolver-reputation-v1
             :reputation-slash-penalty penalty
             :slash-detected-count slash-detected-count
             :min-reputation-penalty-for-spe-pass min-reputation-penalty-for-spe-pass}
            {:spe-result #{:spe/pass :spe/epsilon-pass}
             :profitable-deviation-count 0}
            (mapv (fn [ce] {:metric :profitable-deviation
                            :node/id (:node/id ce)
                            :regret (:regret ce)
                            :agent (:agent ce)})
                  counterexamples))

      :else
      (inconclusive eq-concept basis
                    (or (first requires) "counterfactual evidence unavailable")))))

(defn- check-resolver-reputation-profile-matrix
  "Profile-matrix reputation SPE validator.

   Runs the subgame counterfactual evaluator against each profile declared in
   spe-config.utility-profiles (sequence of profile keys or inline maps).

   Each profile is resolved via the reputation-profiles registry and merged into
   the projection's utility-spec before evaluation, producing a per-profile
   comparison table.

   Status semantics:
     :pass        — ALL profiles return :pass
     :fail        — ANY profile returns :fail (worst case wins)
     :inconclusive — no proper subgames found or no profiles declared

   Observed map includes:
     :profile-results          — vector of per-profile result maps
     :min-profile-required     — weakest profile id that yields :pass (nil if none pass)
     :fail-profiles            — vector of profile ids that failed
     :any-pass?                — boolean
     :all-pass?                — boolean

   This validator answers: 'Under which resolver-market assumptions does this
   strategy become incentive-compatible?' rather than a single pass/fail verdict."
  [projection]
  (let [eq-concept :resolver-reputation-profile-matrix
        spe-config (:spe-config projection {})
        profiles   (get spe-config :utility-profiles [])]
    (if (empty? profiles)
      (inconclusive eq-concept :absent-evidence
                    "no utility-profiles declared in spe-config; add :utility-profiles vector")
      (let [{:keys [profile-results min-profile-required any-pass? all-pass? fail-profiles]}
            (subgame-cf/run-profile-matrix projection profiles)
            proper-checked (apply + (map #(:proper-subgames-checked (:profile-spec %) 0)
                                         profile-results))]
        (cond
          (zero? (count (filter #(pos? (or (:proper-subgames-checked %) 0))
                                profile-results)))
          (inconclusive eq-concept :absent-evidence
                        "no proper subgames found across any profile; all nodes were information-set or not-checkable")

          all-pass?
          (pass eq-concept :single-trace-node-counterfactual-proxy
                {:profile-results      profile-results
                 :min-profile-required min-profile-required
                 :fail-profiles        fail-profiles
                 :any-pass?            any-pass?
                 :all-pass?            all-pass?
                 :profile-count        (count profiles)}
                {:all-pass? true})

          any-pass?
          (fail eq-concept :single-trace-node-counterfactual-proxy
                {:profile-results      profile-results
                 :min-profile-required min-profile-required
                 :fail-profiles        fail-profiles
                 :any-pass?            any-pass?
                 :all-pass?            all-pass?
                 :profile-count        (count profiles)
                 :interpretation       (str "Strategy is incentive-compatible only under profiles: "
                                            (pr-str (mapv :profile-id (filter #(= :pass (:status %)) profile-results)))
                                            ". Fails under: " (pr-str fail-profiles))}
                {:all-pass? true}
                (mapv (fn [pr]
                        {:metric :profile-spe-fail
                         :profile-id (:profile-id pr)
                         :max-regret (:max-regret pr)})
                      (filter #(= :fail (:status %)) profile-results)))

          :else
          (fail eq-concept :single-trace-node-counterfactual-proxy
                {:profile-results      profile-results
                 :min-profile-required nil
                 :fail-profiles        fail-profiles
                 :any-pass?            false
                 :all-pass?            false
                 :profile-count        (count profiles)
                 :interpretation       "Strategy fails under all declared profiles."}
                {:all-pass? true}
                (mapv (fn [pr]
                        {:metric :profile-spe-fail
                         :profile-id (:profile-id pr)
                         :max-regret (:max-regret pr)})
                      (filter #(= :fail (:status %)) profile-results))))))))

(defn- check-bayesian-nash-equilibrium
  "Requires population/belief distributions across resolvers. Always
   :inconclusive for single-trace replay."
  [_projection]
  (inconclusive :bayesian-nash-equilibrium :multi-epoch-required
                "requires population data across resolvers; single-trace cannot evaluate"))

;; ---------------------------------------------------------------------------
;; Dispatcher maps
;; ---------------------------------------------------------------------------

(def ^:private mechanism-validators
  {:budget-balance         check-budget-balance
   :incentive-compatibility check-incentive-compatibility
   :individual-rationality check-individual-rationality
   :collusion-resistance   check-collusion-resistance
   :sybil-resistance       check-sybil-resistance
   :force-refund-path-integrity check-force-refund-path-integrity
   :pending-lifecycle-integrity check-pending-lifecycle-integrity
   :stake-flow-conservation check-stake-flow-conservation})

(def ^:private equilibrium-validators
  {:dominant-strategy-equilibrium          check-dominant-strategy-equilibrium
   :nash-equilibrium                        check-nash-equilibrium
   :subgame-perfect-equilibrium             check-subgame-perfect-equilibrium
   :bounded-public-state-epsilon-spe        check-bounded-public-state-epsilon-spe
   :bounded-backward-induction-spe          check-bounded-backward-induction-spe
   :resolver-reputation-spe                 check-resolver-reputation-spe
   :resolver-reputation-profile-matrix      check-resolver-reputation-profile-matrix
   :bayesian-nash-equilibrium               check-bayesian-nash-equilibrium})

;; ---------------------------------------------------------------------------
;; Status roll-up
;; ---------------------------------------------------------------------------

(defn- roll-up-status
  "Compute aggregate status from a collection of validator results.
   :fail  — any hard :fail present
   :inconclusive — no :fail but some :inconclusive or :not-applicable
   :pass  — all results are :pass
   :not-checked — empty results"
  [results]
  (cond
    (empty? results)               :not-checked
    (some #(and (= :fail (:status %)) (= :hard (:severity %))) results) :fail
    ;; Defensive: if a soft :fail exists (future extension), treat as inconclusive.
    (some #(and (= :fail (:status %)) (not= :hard (:severity %))) results) :inconclusive
    (some #(#{:inconclusive :not-applicable} (:status %)) results) :inconclusive
    :else                          :pass))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-mechanism-properties
  "Check all declared :mechanism-properties against the terminal projection.
   Returns a map of {property-kw → result-map}."
  [properties projection]
  (into {} (map (fn [prop]
                  (let [kw  (keyword prop)
                        chk (get mechanism-validators kw)]
                    [kw (if chk
                          (chk projection)
                          (inconclusive kw :absent-evidence
                                        (str "no validator implemented for mechanism property: " (name kw))))]))
                properties)))

(defn evaluate-equilibrium-concepts
  "Check all declared :equilibrium-concept values against the terminal projection.
   Returns a map of {concept-kw → result-map}."
  [concepts projection]
  (into {} (map (fn [concept]
                  (let [kw  (keyword concept)
                        chk (get equilibrium-validators kw)]
                    [kw (if chk
                          (chk projection)
                          (inconclusive kw :absent-evidence
                                        (str "no validator implemented for equilibrium concept: " (name kw))))]))
                concepts)))

(defn evaluate-equilibrium
  "Top-level entry: build terminal projection, run all declared validators.
   Called by scenario.theory/evaluate-theory when the theory block contains
   :mechanism-properties or :equilibrium-concept.

   Returns:
   {:mechanism-results  {property-kw → result-map}
    :mechanism-status   :pass | :fail | :inconclusive | :not-applicable | :not-checked
    :equilibrium-results {concept-kw → result-map}
    :equilibrium-status :pass | :fail | :inconclusive | :not-applicable | :not-checked}"
  [theory result]
  (let [raw-proj     (proj/trace-end-projection result)
        ;; Thread spe-config from the theory block into the projection so that
        ;; evaluate-subgame-counterfactual uses the declared thresholds/epsilon
        ;; values rather than its own defaults (regret-threshold=0, epsilon-abs=0.0).
        projection   (cond-> raw-proj
                       (:spe-config theory) (assoc :spe-config (:spe-config theory)))
        mech-props   (seq (:mechanism-properties theory))
        eq-concepts  (seq (:equilibrium-concept theory))

        mech-results (if (and projection mech-props)
                       (evaluate-mechanism-properties mech-props projection)
                       {})
        eq-results   (if (and projection eq-concepts)
                       (evaluate-equilibrium-concepts eq-concepts projection)
                       {})]
    {:mechanism-results  mech-results
     :mechanism-status   (roll-up-status (vals mech-results))
     :equilibrium-results eq-results
     :equilibrium-status  (roll-up-status (vals eq-results))}))
