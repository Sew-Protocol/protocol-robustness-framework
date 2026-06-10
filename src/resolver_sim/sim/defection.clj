(ns resolver-sim.sim.defection
  "Agent strategy adaptation model.

   Supports multiple strategy selectors:
   - :binary-payoff        (legacy compatibility selector)
   - :load-optimal         (load-aware strategy targeting)
   - :multi-strategy-payoff (reserved for future implementation)

   Preferred config shape:
     :strategy-adaptation
     {:enabled true
      :rate 0.15
      :selector :load-optimal
      :allowed-targets #{:honest :lazy :malicious}}

   Backward compatibility:
   - :defection-rate and :defection-model are still supported.
   - If neither config shape enables adaptation, histories are unchanged."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.evidence-costs :as ec]))

(defn- epoch-key [epoch] (keyword (str "epoch-" epoch)))

(defn- epoch-profit
  "Return this resolver's profit in the specified epoch, or nil if no entry."
  [resolver epoch]
  (get-in resolver [:epoch-history (epoch-key epoch) :profit]))

(defn resolve-strategy-adaptation-config
  "Resolve strategy adaptation config with provenance.

   Merge order:
   explicit :strategy-adaptation
   -> legacy/top-level params
   -> documented defaults."
  [params]
  (let [defaults         {:rate 0.0
                          :selector :binary-payoff
                          :allowed-targets #{:honest :lazy :malicious}
                          :slash-risk-inhibition 0.7
                          :max-switch-probability 0.8
                          :detection-probability 0.1
                           :slash-multiplier 2.5
                          :blocked-target-policy :inconclusive}
        legacy-rate      (double (get params :defection-rate 0.0))
        legacy-model     (get params :defection-model :binary-payoff)
        nested           (:strategy-adaptation params)
        nested-enabled?  (true? (:enabled nested))
        nested-rate      (double (get nested :rate (:rate defaults)))
        enabled?         (or nested-enabled? (pos? legacy-rate))
        rate             (if nested-enabled? nested-rate legacy-rate)
        selector         (or (:selector nested)
                             (:mode nested)
                             legacy-model
                             :binary-payoff)
        selector         (if (= selector :binary-honest-malicious) :binary-payoff selector)
        declared-space   (or (:strategy-space params)
                             (get-in params [:resolver-strategies :enabled]))
        allowed-targets  (or (:allowed-targets nested)
                             (when (set? declared-space) declared-space)
                             (:allowed-targets defaults))
        slash-risk-inhibition (double (or (:slash-risk-inhibition nested)
                                          (:slash-risk-inhibition params)
                                          (:slash-risk-inhibition defaults)))
        max-switch-probability (double (or (:max-switch-probability nested)
                                           (:max-switch-probability params)
                                           (:max-switch-probability defaults)))
        detection-probability (double (or (:detection-probability nested)
                                          (:slashing-detection-probability params)
                                          (:detection-probability defaults)))
        slash-multiplier (double (or (:slash-multiplier nested)
                                     (:slash-multiplier params)
                                     (:slash-multiplier defaults)))
        effort-budget-per-epoch (double (or (:effort-budget-per-epoch nested)
                                            (:effort-budget-per-epoch params)
                                            (ec/epoch-effort-budget)))
        blocked-target-policy (or (:blocked-target-policy nested)
                                  (:blocked-target-policy params)
                                  (:blocked-target-policy defaults))
        defaults-used (cond-> #{}
                        (nil? (:allowed-targets nested)) (conj :allowed-targets)
                        (nil? (:slash-risk-inhibition nested)) (conj :slash-risk-inhibition)
                        (nil? (:max-switch-probability nested)) (conj :max-switch-probability)
                        (nil? (:detection-probability nested)) (conj :detection-probability)
                        (nil? (:slash-multiplier nested)) (conj :slash-multiplier)
                        (nil? (:blocked-target-policy nested)) (conj :blocked-target-policy))]
    {:enabled?        (and enabled? (pos? rate))
     :rate            rate
     :selector        selector
     :allowed-targets (set allowed-targets)
     :strategy-space  (when (set? declared-space) declared-space)
     :slash-risk-inhibition slash-risk-inhibition
     :max-switch-probability max-switch-probability
     :detection-probability detection-probability
     :slash-multiplier slash-multiplier
     :effort-budget-per-epoch effort-budget-per-epoch
     :blocked-target-policy blocked-target-policy
     :defaults-used defaults-used}))

(defn- strategy-counts [resolver-histories]
  (frequencies (map :strategy (vals resolver-histories))))

(defn- allowed-transition?
  [{:keys [allowed-targets strategy-space]} target]
  (and (contains? allowed-targets target)
       (or (nil? strategy-space)
           (contains? strategy-space target))))

(defn- switch-with-rate?
  [rng rate]
  (< (rng/next-double rng) (min 1.0 (max 0.0 rate))))

(defn- group-mean-profit
  [resolver-histories epoch strategy]
  (let [ek      (epoch-key epoch)
        profits (keep (fn [[_ r]]
                        (when (and (= strategy (:strategy r))
                                   (pos? (get-in r [:epoch-history ek :trials] 0)))
                          (get-in r [:epoch-history ek :profit] 0.0)))
                      resolver-histories)]
    (when (seq profits)
      (double (/ (apply + profits) (count profits))))))

(defn- binary-defect?
  [own-profit other-mean current-strategy params rate max-switch-probability slash-risk-inhibition rng]
  (let [payoff-diff (- other-mean own-profit)]
    (when (pos? payoff-diff)
      (let [raw-p (double (* rate (/ payoff-diff (max 1.0 (Math/abs (double own-profit))))))
            p     (if (= :honest current-strategy)
                    (let [slash-det  (:slashing-detection-probability params 0.1)
                          inhibition slash-risk-inhibition]
                      (* raw-p (- 1.0 (* inhibition slash-det))))
                    raw-p)
            p     (min max-switch-probability (max 0.0 p))]
        (< (rng/next-double rng) p)))))

(defn- fee-profit-estimate
  [params]
  (let [escrow-size      (double (get params :escrow-size 10000))
        resolver-fee-bps (double (get params :resolver-fee-bps 100))]
    (* escrow-size (/ resolver-fee-bps 10000.0))))

(defn- load-optimal-snapshot
  [params cfg rng]
  (let [num-disputes     (long (get params :_epoch-trials
                                    (get params :n-trials-per-epoch
                                         (get params :num-trials-per-epoch 0))))
        effort-budget    (:effort-budget-per-epoch cfg)
        detection-prob   (:detection-probability cfg)
        slash-multiplier (:slash-multiplier cfg)
        fee-profit       (fee-profit-estimate params)
        opt              (ec/optimal-strategy-under-load rng
                                                         num-disputes
                                                         effort-budget
                                                         detection-prob
                                                         slash-multiplier
                                                         fee-profit)]
    {:num-disputes                 num-disputes
     :effort-budget                effort-budget
     :effort-available-per-dispute (ec/effort-available-per-dispute effort-budget (max 1 num-disputes))
     :load-level                   (ec/load-level (max 1 num-disputes) effort-budget)
     :detection-prob               detection-prob
     :slash-multiplier             slash-multiplier
     :fee-profit                   fee-profit
     :optimal-strategy-under-load  opt}))

(defn- event-base
  [epoch id from to selector reason load-snap rate]
  {:event/type                  :resolver.strategy/changed
   :epoch                       epoch
   :resolver-id                 id
   :from                        from
   :to                          to
   :selector                    selector
   :reason                      reason
   :load-level                  (:load-level load-snap)
   :defection-rate              rate
   :optimal-strategy-under-load (:optimal-strategy-under-load load-snap)
   :evidence-cost-snapshot      (dissoc load-snap :optimal-strategy-under-load :load-level)})

(defmulti select-next-strategy
  (fn [selector _ctx _resolver] selector))

(defmethod select-next-strategy :binary-payoff
  [_ {:keys [honest-mean malice-mean params rate rng cfg epoch]} resolver]
  (let [strategy   (:strategy resolver)
        own-profit (epoch-profit resolver epoch)
        trials     (get-in resolver [:epoch-history (epoch-key epoch) :trials] 0)
        other-mean (if (= :honest strategy) malice-mean honest-mean)
        target     (if (= :honest strategy) :malicious :honest)]
    (when (and own-profit other-mean (pos? trials)
               (allowed-transition? cfg target)
               (binary-defect? own-profit
                               other-mean
                               strategy
                               params
                               rate
                               (:max-switch-probability cfg)
                               (:slash-risk-inhibition cfg)
                               rng))
      {:to       target
       :reason   :binary-payoff-differential
       :selector :binary-payoff})))

(defmethod select-next-strategy :load-optimal
  [_ {:keys [rate rng cfg load-snap epoch observed-strategies]} resolver]
  (let [from    (:strategy resolver)
        trials  (get-in resolver [:epoch-history (epoch-key epoch) :trials] 0)
        optimal (:optimal-strategy-under-load load-snap)]
    (cond
      (not (pos? trials))
      {:to from :skip? true}

      (= from optimal)
      {:to from :skip? true}

      (not (allowed-transition? cfg optimal))
      {:to from
       :blocked? true
       :diagnostic {:event/type :resolver.strategy/blocked
                    :epoch epoch
                    :resolver-id (:resolver-id resolver)
                    :from from
                    :target optimal
                    :reason :target-outside-strategy-space
                    :selector :load-optimal
                    :policy (:blocked-target-policy cfg)
                    :load-level (:load-level load-snap)
                    :optimal-strategy-under-load optimal
                    :allowed-targets (:allowed-targets cfg)
                    :observed-strategies observed-strategies
                    :strategy-space (:strategy-space cfg)}}

      (switch-with-rate? rng (min rate (:max-switch-probability cfg)))
      {:to optimal
       :reason :evidence-load
       :selector :load-optimal}

      :else
      {:to from :skip? true})))

(defmethod select-next-strategy :default
  [_ {:keys [epoch selector]} _]
  {:to nil
   :blocked? true
   :diagnostic {:event/type :resolver.strategy/diagnostic
                :epoch epoch
                :reason :unknown-selector
                :selector selector}})

(defn- apply-binary-payoff-defection
  [rng resolver-histories epoch params {:keys [rate] :as cfg}]
  (let [honest-mean (group-mean-profit resolver-histories epoch :honest)
        malice-mean (group-mean-profit resolver-histories epoch :malicious)]
    (reduce-kv
     (fn [acc id resolver]
      (let [strategy   (:strategy resolver)
            decision   (select-next-strategy
                         :binary-payoff
                         {:honest-mean honest-mean
                          :malice-mean malice-mean
                          :params params
                          :rate rate
                          :rng rng
                          :cfg cfg
                          :epoch epoch}
                         resolver)
            target (:to decision)]
        (if (and target (not= strategy target))
           (-> acc
               (assoc-in [:updated-histories id] (assoc resolver :strategy target))
               (update :defection-events conj
                      (merge
                        {:id id}
                        (event-base epoch id strategy target :binary-payoff
                                    (:reason decision) nil rate))))
           (assoc-in acc [:updated-histories id] resolver))))
     {:updated-histories {} :defection-events [] :diagnostics []}
     resolver-histories)))

(defn- apply-load-optimal
  [rng resolver-histories epoch {:keys [rate] :as cfg} params]
  (let [load-snap (load-optimal-snapshot params cfg rng)
        observed-strategies (set (map :strategy (vals resolver-histories)))]
    (reduce-kv
     (fn [acc id resolver]
       (let [from     (:strategy resolver)
             decision (select-next-strategy
                        :load-optimal
                        {:rate rate
                         :rng rng
                         :cfg cfg
                         :load-snap load-snap
                         :observed-strategies observed-strategies
                         :epoch epoch}
                        (assoc resolver :resolver-id id))
             target   (:to decision)]
         (cond
           (:blocked? decision)
           (-> acc
               (assoc-in [:updated-histories id] resolver)
               (cond-> (:diagnostic decision)
                 (update :diagnostics conj (:diagnostic decision))))

           (or (:skip? decision) (= from target))
           (assoc-in acc [:updated-histories id] resolver)

           :else
           (-> acc
               (assoc-in [:updated-histories id] (assoc resolver :strategy target))
               (update :defection-events conj
                       (event-base epoch id from target :load-optimal
                                   (:reason decision) load-snap rate))))))
     {:updated-histories {} :defection-events [] :diagnostics []}
     resolver-histories)))

(defn apply-strategy-defection
  "Apply per-resolver strategy adaptation after epoch `epoch` completes.

   Returns:
   {:updated-histories map
    :defection-events  [{...}]
    :diagnostics       [{...}]}

   Selectors:
   - :binary-payoff
   - :load-optimal
   - :multi-strategy-payoff (returns diagnostic until implemented)"
  [rng resolver-histories epoch params]
  (let [{:keys [enabled? selector] :as cfg} (resolve-strategy-adaptation-config params)]
    (if-not enabled?
      {:updated-histories resolver-histories
       :defection-events  []
       :diagnostics       []
       :resolved-config   cfg}
      (case selector
        :binary-payoff
        (assoc (apply-binary-payoff-defection rng resolver-histories epoch params cfg)
               :resolved-config cfg)

        :load-optimal
        (assoc (apply-load-optimal rng resolver-histories epoch cfg params)
               :resolved-config cfg)

        :multi-strategy-payoff
        {:updated-histories resolver-histories
         :defection-events  []
         :diagnostics       [{:event/type :resolver.strategy/diagnostic
                              :epoch epoch
                              :reason :mode-not-implemented
                              :selector selector}]
         :resolved-config   cfg}

        {:updated-histories resolver-histories
         :defection-events  []
         :diagnostics       [{:event/type :resolver.strategy/diagnostic
                              :epoch epoch
                              :reason :unknown-selector
                              :selector selector}]
         :resolved-config   cfg}))))

(defn defection-summary
  "Produce strategy-general adaptation summary for epoch reports."
  [defection-events initial-histories final-histories diagnostics resolved-config]
  (when (or (seq defection-events) (seq diagnostics))
    {:total-defections     (count defection-events)
     :strategy-transitions (frequencies (map (fn [e] [(:from e) (:to e)]) defection-events))
     :strategy-mix         {:initial (strategy-counts initial-histories)
                            :final   (strategy-counts final-histories)}
     :defector-ids         (mapv #(or (:resolver-id %) (:id %)) defection-events)
     :diagnostics          (vec diagnostics)
     :adaptation/resolved-config (dissoc resolved-config :enabled? :strategy-space)
     :adaptation/defaults-used (:defaults-used resolved-config)}))
