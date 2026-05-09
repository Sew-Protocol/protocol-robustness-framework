(ns resolver-sim.scenario.subgame-counterfactual
  "Bounded subgame counterfactual evaluator (Phase 4 v1).

   Uses deterministic, local counterfactuals around strategic decision nodes.
   This v1 evaluator is intentionally bounded:
   - decision nodes are limited to key strategic actions,
   - alternatives are generated from a small fixed action set,
   - utilities are computed from world snapshots in the same replay trace.

   Output is deterministic and suitable as SPE-proxy evidence."
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
        cap  (long (get spe-config :max-alternatives-per-node 3))]
    (->> base
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

(defn- classify-row
  [{:keys [node-type alternatives chosen-utility best-alt-utility]}]
  (cond
    (nil? node-type) :inapplicable-node-type
    (empty? alternatives) :inconclusive-insufficient-alternatives
    (or (nil? chosen-utility) (nil? best-alt-utility)) :inconclusive-undefined-utility
    :else :evaluated))

(defn- node->table-row
  [{:keys [raw-trace terminal-state continuation-policy replay-boundary utility-spec]}
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
        best-alt-utility (if (seq alternatives)
                           (max (or local-alt-utility Long/MIN_VALUE)
                                (or chosen-utility Long/MIN_VALUE))
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
     :information-set info-set
     :classification classification
     :chosen-action action
     :alternatives alternatives
     :continuation-policy continuation-policy
     :replay-boundary replay-boundary
     :utility-spec utility-spec
     :chosen-utility chosen-utility
     :best-alt-utility best-alt-utility
     :local-regret regret
     :deterministic-key (str idx "|" agent "|" action)}))

(defn evaluate-subgame-counterfactual
  "Compute bounded local regret evidence for strategic decision nodes.

   Returns:
   {:status :pass|:fail|:inconclusive
    :basis  kw
    :regret-table [...]
    :max-regret n
    :threshold n
    :checked-nodes n
    :requires [...]}"
  [{:keys [raw-trace decisions terminal-world spe-config]}]
  (let [decision-nodes (->> decisions
                            (sort-by (juxt :seq :agent :action))
                            vec)
        threshold      (long (get spe-config :regret-threshold 0))
        epsilon-abs    (double (get spe-config :epsilon-abs 0.0))
        epsilon-rel    (double (get spe-config :epsilon-rel 0.0))
        continuation-policy (merge default-continuation-policy
                                   (or (:continuation-policy spe-config) {}))
        replay-boundary (merge default-replay-boundary
                               (or (:replay-boundary spe-config) {}))
        utility-spec (merge default-utility-spec
                            (or (:utility-spec spe-config) {}))
        terminal-state (:world (last raw-trace))]
    (cond
      (empty? decision-nodes)
      {:status :inconclusive
       :basis :absent-evidence
       :regret-table []
       :max-regret nil
       :threshold threshold
       :continuation-policy continuation-policy
       :replay-boundary replay-boundary
       :utility-spec utility-spec
       :checked-nodes 0
       :class-counts {:inconclusive-insufficient-alternatives 0
                      :inconclusive-undefined-utility 0
                      :inapplicable-node-type 0
                      :evaluated 0}
       :requires ["no decision nodes available in trace"]}

      (not (:terminal? terminal-world))
      {:status :inconclusive
       :basis :multi-trace-required
       :regret-table []
       :max-regret nil
       :threshold threshold
       :continuation-policy continuation-policy
       :replay-boundary replay-boundary
       :utility-spec utility-spec
       :checked-nodes (count decision-nodes)
       :class-counts {:inconclusive-insufficient-alternatives 0
                      :inconclusive-undefined-utility 0
                      :inapplicable-node-type 0
                      :evaluated 0}
       :requires ["trace ends before terminal settlement; counterfactual SPE proxy unavailable"]}

      :else
      (let [rows       (mapv #(node->table-row {:raw-trace raw-trace
                                                :terminal-state terminal-state
                                                :continuation-policy continuation-policy
                                                :replay-boundary replay-boundary
                                                :utility-spec utility-spec}
                                               %
                                               spe-config)
                             decision-nodes)
            regrets    (keep :local-regret rows)
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
                                        (for [{:keys [local-regret chosen-utility]} rows
                                              :when (some? local-regret)]
                                          (regret-exceeds-epsilon? local-regret chosen-utility epsilon-abs epsilon-rel))))
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
                         [])]
        {:status status
         :basis :single-trace-node-counterfactual-proxy
         :regret-table rows
         :max-regret max-regret
         :mean-regret mean-regret
         :threshold threshold
         :epsilon-abs epsilon-abs
         :epsilon-rel epsilon-rel
         :continuation-policy continuation-policy
         :replay-boundary replay-boundary
         :utility-spec utility-spec
         :class-counts class-counts
         :exceed-epsilon-count exceed-count
         :regret-distribution {:zero (count (filter zero? regrets))
                               :positive (count (filter pos? regrets))}
         :checked-nodes (count rows)
         :requires requires}))))
