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
  (:require [resolver-sim.scenario.projection :as proj]))

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
  [{:keys [metrics]}]
  (let [npc  (:negative-payoff-count metrics)
        lost (:funds-lost metrics 0)]
    (cond
      ;; Full payoff ledger available
      (some? npc)
      (if (zero? npc)
        (pass :individual-rationality :single-trace-metric-proxy
              {:negative-payoff-count npc}
              "no participant ended with negative net payoff")
        (fail :individual-rationality :single-trace-metric-proxy
              {:negative-payoff-count npc}
              {:negative-payoff-count 0}
              [{:metric :negative-payoff-count :observed npc}]))

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
  [{:keys [metrics]}]
  (let [cnp (:coalition-net-profit metrics)]
    (if (nil? cnp)
      (inconclusive :collusion-resistance :multi-trace-required
                    "coalition-net-profit metric absent; requires multi-epoch batch runner")
      (if (<= cnp 0)
        (pass :collusion-resistance :single-trace-metric-proxy
              {:coalition-net-profit cnp}
              "coalition net profit ≤ 0")
        (fail :collusion-resistance :single-trace-metric-proxy
              {:coalition-net-profit cnp}
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

(defn- get-agent-wealth
  "Approximate wealth of an agent in a given world snapshot.
   Sums: resolver stake + claimable balances + active bond balances."
  [world agent-id]
  (let [stakes    (get world :resolver-stakes {})
        claimable (get world :claimable {})
        bonds     (get world :bond-balances {})
        ;; Resolver stake (if applicable)
        s (get stakes agent-id 0)
        ;; Total claimable across all workflows for this agent
        c (reduce + 0 (for [[_ workflow-claims] claimable]
                        (get workflow-claims agent-id 0)))
        ;; Active bonds held by the protocol for this agent
        b (reduce + 0 (for [[_ workflow-bonds] bonds]
                        (get workflow-bonds agent-id 0)))]
    (+ s c b)))

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

   :inconclusive when no strategic decisions were made."
  [{:keys [raw-trace decisions terminal-world]}]
  (let [;; Strategic actions are points where an agent makes a material choice:
        ;; - raise_dispute:      party contests an escrow outcome (posts implicit claim)
        ;; - escalate_dispute:   party appeals to a higher authority (posts appeal bond)
        ;; - execute_resolution: resolver decides outcome (may lose stake if fraudulent)
        strategic-actions #{"raise_dispute" "escalate_dispute" "execute_resolution"}
        decision-nodes    (filter #(strategic-actions (:action %)) decisions)]
    (cond
      (empty? decision-nodes)
      (inconclusive :subgame-perfect-equilibrium :absent-evidence
                    "no strategic decision nodes (disputes/escalations) in this trace")

      (not (:terminal? terminal-world))
      (inconclusive :subgame-perfect-equilibrium :multi-trace-required
                    "trace ends before settlement; cannot evaluate ex-post regret")

      :else
      (let [terminal-state (:world (last raw-trace))
            violations (keep (fn [decision]
                               ;; :seq equals the array index in raw-trace (events are
                               ;; validated to have contiguous seq values starting at 0)
                               (let [t-idx   (:seq decision)
                                     agent   (:agent decision)
                                     ;; Use resolved address for world-state lookup;
                                     ;; world keys (resolver-stakes, claimable, etc.)
                                     ;; are keyed by address, not agent ID.
                                     addr    (or (:address decision) agent)
                                     action  (:action decision)]
                                 (when (and (strategic-actions action)
                                             (some? t-idx)
                                             (< t-idx (count raw-trace)))
                                   (let [world-at (:world (nth raw-trace t-idx))
                                         w-at     (get-agent-wealth world-at addr)
                                         w-T      (get-agent-wealth terminal-state addr)]
                                     (when (< w-T w-at)
                                       {:index   t-idx
                                        :seq     t-idx
                                        :agent   agent
                                        :action  action
                                        :loss    (- w-at w-T)
                                        :class   :ex-post-regret
                                        :summary (str "Agent " agent " " action " at step " t-idx
                                                      " led to net loss of " (- w-at w-T))})))))
                             decisions)]
        (if (seq violations)
          (fail :subgame-perfect-equilibrium :single-trace-node-proxy
                {:spe-status     :fail
                 :spe-summary    (str "observed " (count violations) " strategic actions led to avoidable net losses")
                 :spe-violations (vec violations)}
                {:spe-status :pass}
                violations)
          (let [checked (count decision-nodes)]
            (pass :subgame-perfect-equilibrium :single-trace-node-proxy
                  {:spe-status     :pass
                   :spe-summary    (str "all " checked " strategic decisions resulted in non-negative terminal payoff contributions")
                   :spe-violations []
                   :decisions-checked checked}
                  {:spe-status :pass})))))))

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
   :sybil-resistance       check-sybil-resistance})

(def ^:private equilibrium-validators
  {:dominant-strategy-equilibrium  check-dominant-strategy-equilibrium
   :nash-equilibrium                check-nash-equilibrium
   :subgame-perfect-equilibrium     check-subgame-perfect-equilibrium
   :bayesian-nash-equilibrium       check-bayesian-nash-equilibrium})

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
    (some #(= :fail (:status %)) results) :fail
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
  (let [projection   (proj/trace-end-projection result)
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
