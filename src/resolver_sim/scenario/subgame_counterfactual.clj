(ns resolver-sim.scenario.subgame-counterfactual
  "Bounded subgame counterfactual evaluator (Phase 4 v1).

   Uses deterministic, local counterfactuals around strategic decision nodes.
   This v1 evaluator is intentionally bounded:
   - decision nodes are limited to key strategic actions,
   - alternatives are generated from a small fixed action set,
   - utilities are computed from world snapshots in the same replay trace.

   Output is deterministic and suitable as SPE-proxy evidence.

   ## Phase summary

   Phase A — continuation-policy / replay-boundary / utility-spec defaults
   Phase B — minimal information-set model (observable vs hidden state per node)
   Phase C — epsilon semantics + bounded alternatives with timing/exogenous variants
   Phase D — deterministic response-adjustment + compute-bundle-regret multi-step proxy
   Phase E — in-evaluation memoization keyed by stable tuple; per-row memoization-hit?
   Phase F — per-node :spe/checkability classification (proper-subgame vs information-set-node
              vs not-spe-checkable) + top-level coverage counts
   Phase G — declared strategy profile; :governing-policy per row
   Phase H — rich SPE result vocabulary (:spe/pass, :spe/epsilon-pass, etc.)
   Phase I — structured counterexample maps for every profitable deviation
   Phase J — off-path coverage reporting"
  (:require [clojure.string :as str]))

(def ^:private default-continuation-policy
  {:mode :trace-following
   :version "v1"
   :invalid-trace-action :mark-inconclusive})

(def ^:private default-replay-boundary
  {:frozen [:pre-state-snapshot :block-time :available-actors :environment-params]
   :variable [:node-action :downstream-actions :state-evolution]
   :ordering-mode :preserve
   :exogenous-events :fixed})

(def ^:private default-utility-spec
  {:type :terminal-realized-v1
   :version "v1"
   :undefined-policy :inconclusive})

(def ^:private strategic-actions
  #{"raise_dispute" "escalate_dispute" "execute_resolution"})

(def ^:private action-alternatives
  {"raise_dispute" ["settle_now" "wait"]
   "escalate_dispute" ["settle_now" "wait"]
   "execute_resolution" ["defer_verdict" "alternate_verdict"]})

(def ^:private node-type-alternatives
  {:challenge-timing ["challenge_now" "challenge_later" "no_challenge"]
   :escalation-timing ["escalate_now" "escalate_later" "no_escalation"]
   :resolver-verdict ["verdict_for_buyer" "verdict_for_seller" "defer_verdict"]})

(def ^:private node-type-by-action
  {"raise_dispute" :challenge-timing
   "escalate_dispute" :escalation-timing
   "execute_resolution" :resolver-verdict})

;; ---------------------------------------------------------------------------
;; Phase F — Subgame boundary classification
;; ---------------------------------------------------------------------------

;; Agent roles that operate on private evidence quality or private beliefs —
;; these nodes are information-set nodes, not proper subgames.
(def ^:private private-evidence-roles
  #{"buyer" "claimant"})

(defn- classify-subgame-node
  "Phase F: classify a decision node as :proper-subgame, :information-set-node,
   or :not-spe-checkable.

   :proper-subgame       — all relevant protocol state is public (live-states,
                           dispute-levels, block-time visible); verdict or
                           escalation action from a known public dispute state.
   :information-set-node — the acting agent has private state that materially
                           affects their decision (e.g. buyer's private evidence
                           quality; buyer escalation decisions).
   :not-spe-checkable    — pre-state unavailable or node is not a strategic
                           decision point."
  [node pre-world]
  (let [action (str (:action node))
        agent  (str (:agent node))]
    (cond
      (nil? pre-world)
      {:checkability :not-spe-checkable
       :spe/checkability :not-spe-checkable
       :checkability-reason "pre-state unavailable; cannot evaluate decision context"}

      (not (contains? node-type-by-action action))
      {:checkability :not-spe-checkable
       :spe/checkability :not-spe-checkable
       :checkability-reason "action is not a recognized strategic decision type"}

      ;; Buyer/claimant raise_dispute or escalate_dispute depend on private
      ;; evidence quality — these are information-set nodes.
      (and (contains? private-evidence-roles agent)
           (contains? #{"raise_dispute" "escalate_dispute"} action))
      {:checkability :information-set-node
       :spe/checkability :information-set-node
       :checkability-reason "buyer private evidence quality not modeled; decision is information-set node"}

      ;; Resolver execute_resolution from a public dispute state is a proper subgame:
      ;; all protocol state (dispute-levels, live-states, available actions) is public.
      (= action "execute_resolution")
      {:checkability :proper-subgame
       :spe/checkability :proper-subgame
       :checkability-reason "all protocol state public; resolver verdict from known public dispute state"}

      ;; Seller escalation from a known dispute state is treated as a proper subgame.
      ;; Assumption: seller delivery state is publicly observable by the time of escalation
      ;; (the dispute was already raised and protocol state is on-chain). This is a modeling
      ;; assumption — if seller private evidence quality were material, this would be an
      ;; information-set node. Mark as :proper-subgame under the public-state assumption.
      :else
      {:checkability :proper-subgame
       :spe/checkability :proper-subgame
       :checkability-reason "protocol dispute state is public; escalation from known dispute state (assumes seller delivery status observable)"})))

;; ---------------------------------------------------------------------------
;; Phase G — Strategy profile definition
;; ---------------------------------------------------------------------------

(def ^:private default-strategy-profile
  {:id         "honest-resolution-v1"
   :buyer      :policy/buyer-rational-v1
   :seller     :policy/seller-rational-v1
   :resolver   :policy/resolver-honest-v1
   :governance :policy/governance-no-intervention-v1})

(defn- governing-policy
  "Phase G: return the declared policy for the acting agent in this node,
   derived from the (possibly overridden) strategy profile."
  [agent strategy-profile]
  (let [role (keyword (str agent))]
    (or (get strategy-profile role)
        (get strategy-profile (keyword (str/replace (str agent) #"^:" "")))
        :policy/unknown)))

(defn- get-agent-wealth [world agent-id]
  (let [stakes    (get world :resolver-stakes {})
        claimable (get world :claimable {})
        bonds     (get world :bond-balances {})
        s (get stakes agent-id 0)
        c (reduce + 0 (for [[_ wf] claimable] (get wf agent-id 0)))
        b (reduce + 0 (for [[_ wf] bonds] (get wf agent-id 0)))]
    (+ s c b)))

(defn- compute-utility
  "Canonical utility interface for Phase A.
   Returns {:defined? bool :value number|nil :utility-type kw :utility-version str}."
  [world agent-id utility-spec]
  (let [u-type (keyword (or (:type utility-spec) :terminal-realized-v1))
        u-ver  (str (or (:version utility-spec) "v1"))]
    (case u-type
      :terminal-realized-v1
      {:defined? true
       :value (get-agent-wealth world agent-id)
       :utility-type u-type
       :utility-version u-ver}

      {:defined? false
       :value nil
       :utility-type u-type
       :utility-version u-ver})))

(defn- build-information-set
  "Phase B minimal information-set model.
   Keeps a deterministic, role-bounded observable view derived from pre-state."
  [pre-world {:keys [agent action seq]}]
  {:agent agent
   :decision-seq seq
   :decision-action action
   :observable-state {:block-time (get pre-world :block-time)
                      :pending-count (get pre-world :pending-count)
                      :live-states (get pre-world :live-states)
                      :dispute-levels (get pre-world :dispute-levels)}
   :hidden-state [:resolver-stakes :bond-balances :claimable]
   :available-actions (vec (get action-alternatives action []))})

(defn- bounded-alternatives
  [node-type action info-set spe-config]
  (let [base (or (seq (get node-type-alternatives node-type))
                 (seq (:available-actions info-set))
                 (seq (get action-alternatives action []))
                 [])
        timing-variants (when (get spe-config :enable-timing-variants? true)
                          ["wait_same_block" "wait_next_block"])
        exogenous-variants (when (get spe-config :enable-exogenous-variants? true)
                             ["hold_exogenous_fixed" "allow_exogenous_shift"])
        enriched (concat base timing-variants exogenous-variants)
        cap  (long (get spe-config :max-alternatives-per-node 3))]
    (->> enriched
         (remove #(= % action))
         distinct
         (take (max 0 cap))
         vec)))

(defn- regret-exceeds-epsilon?
  [regret chosen-utility epsilon-abs epsilon-rel]
  (let [abs-th (double (or epsilon-abs 0.0))
        rel-th (double (or epsilon-rel 0.0))
        rel-v  (if (and (some? chosen-utility) (not (zero? (double chosen-utility))))
                 (/ (double regret) (Math/abs (double chosen-utility)))
                 0.0)]
    (or (> (double regret) abs-th)
        (> rel-v rel-th))))

(defn- response-adjustment
  "Deterministic response-policy adjustment for counterfactual alternatives.
   Phase D foundation: enables explicit mode differences without introducing
   nondeterminism."
  [continuation-policy chosen-utility]
  (case (:mode continuation-policy)
    :policy-response (if (some? chosen-utility) 1 0)
    :trace-following 0
    0))

(defn- compute-bundle-regret
  "Bounded multi-step deviation bundle proxy.
   Uses depth-2 style additive penalty on positive one-step regret as a
   deterministic approximation for compounded strategic deviation effects."
  [local-regret max-depth]
  (let [depth (long (max 1 max-depth))]
    (if (or (nil? local-regret) (<= local-regret 0) (= depth 1))
      local-regret
      (+ local-regret (long (Math/floor (/ local-regret 2.0)))))))

(defn- classify-row
  [{:keys [node-type alternatives chosen-utility best-alt-utility]}]
  (cond
    (nil? node-type) :inapplicable-node-type
    (empty? alternatives) :inconclusive-insufficient-alternatives
    (or (nil? chosen-utility) (nil? best-alt-utility)) :inconclusive-undefined-utility
    :else :evaluated))

(defn- node->table-row
  [{:keys [raw-trace terminal-state continuation-policy replay-boundary utility-spec
           strategy-profile]}
   {:keys [agent address action] :as node}
   spe-config]
  (let [node-seq        (:seq node)
        idx             (long node-seq)
        pre-entry      (when (pos? idx) (nth raw-trace (dec idx) nil))
        chosen-entry   (nth raw-trace idx nil)
        actor          (or address agent)
        node-type      (get node-type-by-action action)
        pre-world      (:world pre-entry)
        chosen-world   (:world chosen-entry)
        ;; Phase F: subgame boundary classification
        subgame-class  (classify-subgame-node node pre-world)
        info-set       (build-information-set pre-world node)
        alternatives   (bounded-alternatives node-type action info-set spe-config)
        pre-utility-r  (when pre-world (compute-utility pre-world actor utility-spec))
        chosen-utility-r (when terminal-state (compute-utility terminal-state actor utility-spec))
        pre-utility    (:value pre-utility-r)
        chosen-utility (:value chosen-utility-r)
        local-alt-utility
        (when chosen-world
          (let [chosen-local (get-agent-wealth chosen-world actor)]
            (if (and (some? pre-utility) (some? chosen-local))
              ;; bounded local replay proxy: if chosen action immediately reduces
              ;; wealth (e.g. bond lock/slash), alternatives "wait/settle" avoid
              ;; that immediate drop in this local subgame snapshot.
              (max pre-utility chosen-local)
              chosen-local)))
        response-delta  (response-adjustment continuation-policy chosen-utility)
        best-alt-utility (if (seq alternatives)
                           (max (or local-alt-utility Long/MIN_VALUE)
                                (+ (long (or chosen-utility Long/MIN_VALUE)) response-delta))
                           chosen-utility)
        classification (classify-row {:node-type node-type
                                      :alternatives alternatives
                                      :chosen-utility chosen-utility
                                      :best-alt-utility best-alt-utility})
        regret (if (and (= :evaluated classification)
                        (some? best-alt-utility)
                        (some? chosen-utility))
                 (max 0 (- best-alt-utility chosen-utility))
                 nil)]
    {:node-index idx
     :agent agent
     :address actor
     :node-type node-type
     ;; Phase F
     :checkability (:checkability subgame-class)
     :spe/checkability (:spe/checkability subgame-class)
     :checkability-reason (:checkability-reason subgame-class)
     :information-set info-set
     :classification classification
     :chosen-action action
     :alternatives alternatives
     :continuation-policy continuation-policy
     :replay-boundary replay-boundary
     :utility-spec utility-spec
     ;; Phase G
     :governing-policy (governing-policy actor strategy-profile)
     :chosen-utility chosen-utility
     :best-alt-utility best-alt-utility
     :local-regret regret
     :bundle-regret nil
     :deterministic-key (str idx "|" agent "|" action)}))

;; ---------------------------------------------------------------------------
;; Phase H — Rich SPE result vocabulary
;; ---------------------------------------------------------------------------

(defn- derive-spe-result
  "Phase H: derive a rich SPE vocabulary keyword from the raw evaluator state.
   Returned alongside the backward-compat :status key.

   :spe/pass                          — all evaluated nodes have regret = 0
   :spe/epsilon-pass                  — all evaluated nodes pass threshold, but some have regret > 0
   :spe/fail-profitable-deviation     — max-regret exceeds threshold or epsilon exceeded
   :spe/not-a-proper-subgame          — all nodes inapplicable (no node-type match)
   :spe/inconclusive-missing-actions  — some nodes lack alternatives
   :spe/inconclusive-missing-utility  — some nodes lack defined utility
   :spe/inconclusive-continuation-undefined — other inconclusive cases"
  [status evaluated-count max-regret threshold exceed-count rows]
  (cond
    (zero? evaluated-count)
    (let [class-issues (mapv :classification rows)]
      (cond
        (every? #(= :inapplicable-node-type %) class-issues)
        :spe/not-a-proper-subgame

        (some #(= :inconclusive-insufficient-alternatives %) class-issues)
        :spe/inconclusive-missing-actions

        (some #(= :inconclusive-undefined-utility %) class-issues)
        :spe/inconclusive-missing-utility

        :else
        :spe/inconclusive-continuation-undefined))

    (= status :pass)
    ;; :spe/epsilon-pass when passes threshold but max-regret > 0 (not perfect)
    (if (and (some? max-regret) (pos? (long max-regret)))
      :spe/epsilon-pass
      :spe/pass)

    (= status :fail)
    :spe/fail-profitable-deviation

    :else
    :spe/inconclusive-continuation-undefined))

;; ---------------------------------------------------------------------------
;; Phase I — Structured counterexample maps
;; ---------------------------------------------------------------------------

(defn- build-counterexample
  "Phase I: build a structured counterexample map for a profitable deviation row."
  [row epsilon-abs epsilon-rel]
  (let [regret   (long (or (:bundle-regret row) 0))
        chosen-u (:chosen-utility row)
        alt-u    (:best-alt-utility row)
        pre-obs  (get-in row [:information-set :observable-state])]
    {:failure/type        :profitable-deviation
     :node/id             (:deterministic-key row)
     :agent               (:agent row)
     :chosen-action       (:chosen-action row)
     :best-alternative    (first (:alternatives row))
     :chosen-utility      chosen-u
     :alternative-utility alt-u
     :regret              regret
     :epsilon-exceeded?   (regret-exceeds-epsilon? regret chosen-u epsilon-abs epsilon-rel)
     :checkability        (:checkability row)
     :pre-state-summary   {:block-time    (:block-time pre-obs)
                           :dispute-level (get (:dispute-levels pre-obs) "unknown")}
     :counterfactual-ref  nil}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-subgame-counterfactual
  "Compute bounded local regret evidence for strategic decision nodes.

   Returns:
   {:status       :pass|:fail|:inconclusive          (backward-compat)
    :spe-result   :spe/pass|:spe/epsilon-pass|...    (Phase H rich vocab)
    :basis        kw
    :regret-table [...]
    :max-regret   n
    :threshold    n
    :checked-nodes n
    :strategy-profile  map                           (Phase G)
    :proper-subgames-checked n                       (Phase F)
    :information-set-nodes-checked n                 (Phase F)
    :not-checkable-nodes n                           (Phase F)
    :off-path-coverage map                           (Phase J)
    :counterexamples [...]                           (Phase I)
    :requires [...]}"
  [{:keys [raw-trace decisions terminal-world spe-config]}]
  (let [decision-nodes (->> decisions
                            (sort-by (juxt :seq :agent :action))
                            vec)
        threshold      (long (get spe-config :regret-threshold 0))
        max-depth      (long (get spe-config :max-deviation-depth 1))
        epsilon-abs    (double (get spe-config :epsilon-abs 0.0))
        epsilon-rel    (double (get spe-config :epsilon-rel 0.0))
        continuation-policy (merge default-continuation-policy
                                   (or (:continuation-policy spe-config) {}))
        replay-boundary (merge default-replay-boundary
                               (or (:replay-boundary spe-config) {}))
        utility-spec (merge default-utility-spec
                            (or (:utility-spec spe-config) {}))
        ;; Phase G: strategy profile
        strategy-profile (merge default-strategy-profile
                                (or (:strategy-profile spe-config) {}))
        terminal-state (:world (last raw-trace))
        ;; Early-exit helper for inconclusive stubs
        stub (fn [basis requires]
               {:status :inconclusive
                :spe-result :spe/inconclusive-continuation-undefined
                :basis basis
                :regret-table []
                :max-regret nil
                :threshold threshold
                :continuation-policy continuation-policy
                :replay-boundary replay-boundary
                :utility-spec utility-spec
                :strategy-profile strategy-profile
                :checked-nodes 0
                :proper-subgames-checked 0
                :information-set-nodes-checked 0
                :not-checkable-nodes 0
                :class-counts {:inconclusive-insufficient-alternatives 0
                               :inconclusive-undefined-utility 0
                               :inapplicable-node-type 0
                               :evaluated 0}
                :counterexamples []
                :off-path-coverage {:nodes-generated 0
                                    :nodes-evaluated 0
                                    :nodes-inconclusive 0
                                    :nodes-not-checkable 0
                                    :proper-subgames-checked 0
                                    :information-set-nodes-checked 0
                                    :max-depth max-depth}
                :requires requires})]
    (cond
      (empty? decision-nodes)
      (stub :absent-evidence ["no decision nodes available in trace"])

      (not (:terminal? terminal-world))
      (assoc (stub :multi-trace-required
                   ["trace ends before terminal settlement; counterfactual SPE proxy unavailable"])
             :checked-nodes (count decision-nodes))

      :else
      (let [memo*      (atom {})
            cache-key  (fn [node]
                         [(:seq node)
                          (:agent node)
                          (:action node)
                          (:mode continuation-policy)
                          (:version continuation-policy)
                          (:type utility-spec)
                          (:version utility-spec)
                          (:id strategy-profile)
                          (long (get spe-config :max-alternatives-per-node 3))
                          (boolean (get spe-config :enable-timing-variants? true))
                          (boolean (get spe-config :enable-exogenous-variants? true))])
            cached-row (fn [node]
                         (let [k (cache-key node)]
                           (if-let [hit (get @memo* k)]
                             (assoc hit :memoization-hit? true)
                             (let [computed (node->table-row {:raw-trace raw-trace
                                                              :terminal-state terminal-state
                                                              :continuation-policy continuation-policy
                                                              :replay-boundary replay-boundary
                                                              :utility-spec utility-spec
                                                              :strategy-profile strategy-profile}
                                                             node
                                                             spe-config)]
                               (swap! memo* assoc k computed)
                               (assoc computed :memoization-hit? false)))))
            rows0      (mapv cached-row decision-nodes)
            rows       (mapv (fn [r]
                               (assoc r :bundle-regret
                                      (compute-bundle-regret (:local-regret r) max-depth)))
                             rows0)
            regrets    (keep :bundle-regret rows)
            max-regret (when (seq regrets) (apply max regrets))
            mean-regret (when (seq regrets) (/ (reduce + 0 regrets) (count regrets)))
            class-counts (reduce (fn [m r]
                                   (update m (:classification r) (fnil inc 0)))
                                 {:inconclusive-insufficient-alternatives 0
                                  :inconclusive-undefined-utility 0
                                  :inapplicable-node-type 0
                                  :evaluated 0}
                                 rows)
            evaluated-count (:evaluated class-counts 0)
            exceed-count (count (filter identity
                                        (for [{:keys [bundle-regret chosen-utility]} rows
                                              :when (some? bundle-regret)]
                                          (regret-exceeds-epsilon? bundle-regret chosen-utility epsilon-abs epsilon-rel))))
            pass?      (and (pos? evaluated-count)
                            (some? max-regret)
                            (<= max-regret threshold)
                            (zero? exceed-count))
            status     (cond
                         (zero? evaluated-count) :inconclusive
                         pass? :pass
                         :else :fail)
            requires   (if (zero? evaluated-count)
                         ["all decision nodes were inapplicable or lacked defined alternatives/utility"]
                         [])
            ;; Phase H: rich SPE result vocabulary
            spe-result (derive-spe-result status evaluated-count max-regret threshold exceed-count rows)
            ;; Phase F: subgame boundary coverage counts
            proper-count     (count (filter #(= :proper-subgame (:checkability %)) rows))
            info-set-count   (count (filter #(= :information-set-node (:checkability %)) rows))
            not-check-count  (count (filter #(= :not-spe-checkable (:checkability %)) rows))
            inconclusive-count (+ (long (:inconclusive-insufficient-alternatives class-counts 0))
                                  (long (:inconclusive-undefined-utility class-counts 0)))
            ;; Phase I: structured counterexamples for profitable deviations
            counterexamples (vec
                             (for [r rows
                                   :let [regret (long (or (:bundle-regret r) 0))]
                                   :when (pos? regret)]
                               (build-counterexample r epsilon-abs epsilon-rel)))
            ;; Phase J: off-path coverage report
            off-path-coverage {:nodes-generated         (count decision-nodes)
                               :nodes-evaluated         evaluated-count
                               :nodes-inconclusive      inconclusive-count
                               :nodes-not-checkable     not-check-count
                               :proper-subgames-checked proper-count
                               :information-set-nodes-checked info-set-count
                               :max-depth               max-depth}]
        {:status status
         :spe-result spe-result
         :basis :single-trace-node-counterfactual-proxy
         :regret-table rows
         :max-regret max-regret
         :mean-regret mean-regret
         :threshold threshold
         :epsilon-abs epsilon-abs
         :epsilon-rel epsilon-rel
         :max-deviation-depth max-depth
         :continuation-policy continuation-policy
         :replay-boundary replay-boundary
         :utility-spec utility-spec
         :strategy-profile strategy-profile
         :proper-subgames-checked proper-count
         :information-set-nodes-checked info-set-count
         :not-checkable-nodes not-check-count
         :class-counts class-counts
         :exceed-epsilon-count exceed-count
         :memoization {:enabled true
                       :entries (count @memo*)
                       :hits (count (filter :memoization-hit? rows0))}
         :regret-distribution {:zero (count (filter zero? regrets))
                               :positive (count (filter pos? regrets))}
         :counterexamples counterexamples
         :off-path-coverage off-path-coverage
         :checked-nodes (count rows)
         :requires requires}))))
