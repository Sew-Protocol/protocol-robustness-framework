(ns resolver-sim.protocols.sew.claimable-classification
  "Claimable-classification.v2 taxonomy and terminal-world aggregation.

   Distinct from the legacy invariant `:claimable-classification` (recipient/sender
   split on `:claimable`). This namespace describes :claimable-v2 domains, boundary
   invariants, and optional end-of-run observed balances."
  (:require [resolver-sim.protocols.sew.claimable-outcome :as claim-outcome]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.projection :as proj]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.accounting :as yield-acct]))

(def schema-version "claimable-classification.v2")

(def shortfall-policy
  {:mode "partial-liquidity-supported"
   :allocation "fulfilled-plus-deferred"
   :rounding_policy "floor-to-asset-decimals.v1"})

(defn- domain-name
  [domain]
  (if (keyword? domain)
    (if-let [ns (namespace domain)]
      (str ns "/" (name domain))
      (name domain))
    (str domain)))

(defn- class-spec
  [delivery-model source recipient-type risk-class domains & [extra]]
  (merge {:delivery_model delivery-model
           :source source
           :recipient_type recipient-type
           :risk_class risk-class
           :claimable_v2_domains (mapv domain-name domains)}
         extra))

(def v2-classes
  "Canonical fund classes aligned with :claimable-v2 domains and *-boundary invariants."
  {:settlement_principal
   (class-spec "pull" "settlement" "party" "user-withdrawable"
          [:settlement/principal]
          {:boundary_invariant "settlement-principal-boundary"
           :v1_class "escrow_principal"})

   :settlement_yield
   (class-spec "pull" "yield" "party-or-protocol" "yield-derived"
          [:settlement/yield]
          {:boundary_invariant "settlement-yield-boundary"
           :v1_class "escrow_yield"
           :shortfall_outcome "may-be-partially-deferred"})

   :fees_resolver
   (class-spec "pull" "dispute-resolution" "resolver" "service-compensation"
          [:fees/resolver]
          {:boundary_invariant "fee-boundary"
           :v1_class "resolver_payment"})

   :fees_protocol
   (class-spec "pull-or-governance-withdrawal" "fee" "protocol" "protocol-revenue"
          [:fees/protocol]
          {:boundary_invariant "fee-boundary"
           :v1_class "protocol_fee"})

   :yield_protocol_fee
   (class-spec "pull-or-governance-withdrawal" "yield" "protocol" "protocol-revenue"
          [:yield/protocol-fee])

   :yield_resolver_incentive
   (class-spec "pull" "yield" "resolver" "yield-derived-incentive"
          [:yield/resolver-incentive]
          {:v1_class "resolver_payment"})

   :bond_refund
   (class-spec "pull" "appeal-bond" "disputant" "bond-return"
          [:bond/refund]
          {:boundary_invariant "bond-boundary"
           :v1_class "bond_refund"})

   :bond_forfeit
   (class-spec "pull" "appeal-bond" "protocol-or-pool" "bond-forfeit"
          [:bond/forfeit])

   :liability_slash_bounty
   (class-spec "pull" "slash-distribution" "challenger-or-governance" "slash-bounty"
          [:liability/slash-bounty]
          {:boundary_invariant "liability-slash-boundary"})

   :liability_challenge_bounty
   (class-spec "pull" "appeal-challenge" "challenger" "challenge-bounty"
          [:liability/challenge-bounty])

   :reserve_shortfall
   (class-spec "pull" "shortfall-repair" "protocol-or-pool" "shortfall-reserve"
          [:reserve/shortfall])})

(def all-v2-domains
  (vec (sort (mapcat :claimable_v2_domains (vals v2-classes)))))

(defn- workflow-token
  [world workflow-id]
  (get-in world [:escrow-transfers workflow-id :token]))

(defn- sum-domain-amounts
  [world domain]
  (reduce
   (fn [acc [wf domain-map]]
     (let [addr-map (get domain-map domain {})
           token    (workflow-token world wf)]
       (reduce
        (fn [inner [addr amt]]
          (if (nil? addr)
            inner
            (let [n (long (or amt 0))]
              (if (pos? n)
                (-> inner
                    (update :total (fnil + 0) n)
                    (update-in [:by_token (or token :unknown)] (fnil + 0) n)
                    (update :workflows (fnil conj #{}) wf)
                    (update-in [:by_workflow wf] (fnil + 0) n))
                inner))))
        acc
        addr-map)))
   {:total 0 :by_token {} :workflows #{} :by_workflow {}}
   (or (:claimable-v2 world) {})))

(defn- merge-observation
  [a b]
  {:total (+ (:total a 0) (:total b 0))
   :by_token (merge-with + (:by_token a {}) (:by_token b {}))
   :scenarios_nonzero (+ (:scenarios_nonzero a 0) (:scenarios_nonzero b 0))})

(defn- observation-row
  [{:keys [total by_token workflows]}]
  (let [nz (if (or (pos? total) (seq workflows)) 1 0)]
    (cond-> {:total total
             :total_claimable total
             :scenarios_nonzero nz}
      (seq by_token) (assoc :by_token by_token))))

(defn- public-observation
  [m]
  (cond-> {:total_claimable (long (or (:total_claimable m) (:total m) 0))
           :scenarios_nonzero (long (:scenarios_nonzero m 0))}
    (seq (:by_token m)) (assoc :by_token (:by_token m))))

(defn aggregate-domain-observations
  "Sum terminal-world claimable-v2 balances per domain across `worlds`."
  [worlds]
  (into {}
        (for [domain all-v2-domains
              :let [domain-kw (keyword domain)
                    rows (map #(sum-domain-amounts % domain-kw) worlds)
                    merged (reduce (fn [acc row]
                                     (merge-observation acc (observation-row row)))
                                   {:total 0 :by_token {} :scenarios_nonzero 0}
                                   rows)]]
          [domain (public-observation merged)])))

(defn aggregate-class-observations
  [worlds]
  (into {}
        (for [[class-id {:keys [claimable_v2_domains]}] v2-classes
              :let [domain-keys (map keyword claimable_v2_domains)
                    merged
                    (reduce merge-observation
                            {:total 0 :by_token {} :scenarios_nonzero 0}
                            (for [w worlds]
                              (let [per-domain (map #(sum-domain-amounts w %) domain-keys)
                                    total    (reduce + 0 (map :total per-domain))
                                    by-token (apply merge-with +
                                                    (map :by_token per-domain))
                                    workflows (into #{} (mapcat :workflows per-domain))]
                                (observation-row {:total total
                                                  :by_token by-token
                                                  :workflows workflows}))))]]
          [(name class-id) (public-observation merged)])))

(def boundary-checks
  [{:id "settlement-principal-boundary" :fn inv/settlement-principal-boundary?}
   {:id "settlement-yield-boundary" :fn inv/settlement-yield-boundary?}
   {:id "liability-slash-boundary" :fn inv/liability-slash-boundary?}
   {:id "bond-boundary" :fn inv/bond-boundary?}
   {:id "fee-boundary" :fn inv/fee-boundary?}])

(def ^:private default-highlight-limit 15)
(def ^:private default-workflows-per-highlight 10)

(defn- sum-domain-for-workflow
  [world workflow-id domain]
  (let [addr-map (get-in world [:claimable-v2 workflow-id domain] {})
        token    (workflow-token world workflow-id)]
    (reduce
     (fn [inner [addr amt]]
       (if (nil? addr)
         inner
         (let [n (long (or amt 0))]
           (if (pos? n)
             (-> inner
                 (update :total (fnil + 0) n)
                 (update-in [:by_token (or token :unknown)] (fnil + 0) n)
                 (update-in [:by_address (str addr)] (fnil + 0) n))
             inner))))
     {:total 0 :by_token {} :by_address {}}
     addr-map)))

(defn- workflow-domain-breakdown
  [world workflow-id]
  (into {}
        (for [domain all-v2-domains
              :let [domain-kw (keyword domain)
                    row     (sum-domain-for-workflow world workflow-id domain-kw)
                    total   (:total row 0)]
              :when (pos? total)]
          [domain (cond-> (public-observation row)
                    (seq (:by_address row)) (assoc :by_address (:by_address row)))])))

(defn- workflow-class-breakdown
  [world workflow-id]
  (into {}
        (for [[class-id {:keys [claimable_v2_domains]}] v2-classes
              :let [domain-keys (map keyword claimable_v2_domains)
                    total     (reduce + 0
                                      (map #(long (or (:total (sum-domain-for-workflow
                                                                 world workflow-id %))
                                                      0))
                                           domain-keys))]
              :when (pos? total)]
          [(name class-id) {:total_claimable total}])))

(defn- workflow-total-claimable
  [world workflow-id]
  (reduce + 0 (map :total_claimable (vals (workflow-class-breakdown world workflow-id)))))

(defn- workflow-ids-for-world
  [world]
  (into #{}
        (concat (keys (:claimable-v2 world {}))
                (keys (:live-states world {}))
                (keys (:escrow-transfers world {})))))

(defn- principal-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [parse-num (fn [x]
                           (cond
                             (number? x) (long x)
                             (string? x) (try (long (Double/parseDouble x)) (catch Exception _ 0))
                             :else 0))
               claims (reduce + 0 (vals (get domain-map :settlement/principal {})))
               et     (get-in world [:escrow-transfers wf])
               max    (parse-num (or (:amount-after-fee et) 0))]
         :when (or (pos? claims) et)]
     {:workflow_id wf :claims claims :max max :headroom (- max claims)})))

(defn- yield-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [claims     (reduce + 0 (vals (get domain-map :settlement/yield {})))
               owner-id   (t/escrow-yield-owner-id wf)
               pos        (get-in world [:yield/positions owner-id])
               shortfall  (:shortfall pos)
               snap       (t/get-snapshot world wf)
               fee-bps    (or (:yield-protocol-fee-bps snap) 0)
               reclaimed  (:reclaimed-amount pos 0)
               pos-yield  (+ (:realized-yield pos 0) (:unrealized-yield pos 0))
               max-yield  (long
                           (cond
                             (pos? reclaimed) claims
                             (= :settled (:status pos)) (max claims pos-yield)
                             (yield-acct/partial-yield-shortfall? pos shortfall)
                             (let [liq (long (:fulfilled-amount shortfall 0))]
                               (- liq (t/compute-fee liq fee-bps)))
                             :else pos-yield))]
         :when (or (pos? claims) pos)]
     {:workflow_id wf :claims claims :max max-yield :headroom (- max-yield claims)})))

(defn- bond-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [parse-num (fn [x]
                     (cond
                       (number? x) x
                       (string? x) (try (Double/parseDouble x) (catch Exception _ 0))
                       :else 0))
               claims (reduce + 0 (vals (get domain-map :bond/refund {})))
               posted (+ (reduce + 0 (map parse-num (vals (get-in world [:bond-balances wf] {}))))
                       (parse-num (get-in world [:bond-posted-by-workflow wf] 0)))]
         :when (or (pos? claims) (pos? posted))]
     {:workflow_id wf :claims claims :max posted :headroom (- posted claims)})))

(defn- fee-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [resolver-fees (get domain-map :fees/resolver {})
               protocol-fees (get domain-map :fees/protocol {})
               claimed       (+ (reduce + 0 (vals resolver-fees))
                                (reduce + 0 (vals protocol-fees)))
               et            (get-in world [:escrow-transfers wf])
               max-fees      (+ (long (or (:initial-fee et) 0))
                                (reduce + 0 (vals (get (:bond-balances world) wf {}))))]
         :when (or (pos? claimed) et)]
     {:workflow_id wf :claims claimed :max max-fees :headroom (- max-fees claimed)})))

(defn- liability-slash-utilization-rows
  [world]
  (let [reserves (long (:retained-slash-reserves world 0))]
    (vec
     (for [[wf domain-map] (get-in world [:claimable-v2] {})
           :let [claims (reduce + 0 (vals (get domain-map :liability/slash-bounty {})))]
           :when (pos? claims)]
       {:workflow_id wf :claims claims :max reserves :headroom (- reserves claims)}))))

(def ^:private utilization-row-fns
  {"settlement-principal-boundary" principal-utilization-rows
   "settlement-yield-boundary"     yield-utilization-rows
   "bond-boundary"                 bond-utilization-rows
   "fee-boundary"                  fee-utilization-rows
   "liability-slash-boundary"      liability-slash-utilization-rows})

(defn- workflow-boundary-snapshot
  [world workflow-id]
  (into {}
        (for [{:keys [id]} boundary-checks
              :let [rows (get utilization-row-fns id (fn [_] []))
                    row  (first (filter #(= workflow-id (:workflow_id %)) (rows world)))]
              :when row]
          [id (select-keys row [:claims :max :headroom])])))

(defn workflow-rows-for-world
  "Per-workflow claimable and boundary snapshot (single terminal world)."
  [world & {:keys [limit]}]
  (let [lim (or limit default-workflows-per-highlight)]
    (->> (workflow-ids-for-world world)
         (map (fn [wf]
                (let [domains (workflow-domain-breakdown world wf)
                      total   (workflow-total-claimable world wf)
                      bounds  (workflow-boundary-snapshot world wf)]
                  (when (or (pos? total) (seq domains) (seq bounds))
                    {:workflow_id wf
                     :token (some-> (workflow-token world wf) name)
                     :terminal_state (some-> (get-in world [:live-states wf]) name)
                     :total_claimable total
                     :by_domain domains
                     :by_class (workflow-class-breakdown world wf)
                     :boundary_headroom bounds}))))
         (remove nil?)
         (sort-by :total_claimable >)
         (take lim)
         vec)))

(defn boundary-headroom-for-world
  [world]
  (into {}
        (for [{:keys [id], check-fn :fn} boundary-checks
              :let [rows-fn   (get utilization-row-fns id (fn [_] []))
                    util      (rows-fn world)
                    result    (check-fn world)
                    headrooms (map :headroom util)
                    min-h     (when (seq headrooms) (apply min headrooms))]]
          [id {:min_headroom      min-h
               :workflows_tracked (count util)
               :workflows_at_cap  (count (filter zero? headrooms))
               :holds             (:holds? result)
               :violations        (:violations result)}])))

(defn boundary-observations
  "Per-world boundary holds? counts across `worlds`."
  [worlds]
  (into {}
        (for [{:keys [id fn]} boundary-checks]
          (let [results (map #(fn %) worlds)
                failures (count (remove :holds? results))]
            [id {:all_hold (zero? failures)
                 :failure_count failures
                 :worlds_checked (count worlds)}]))))

(defn- aggregate-boundary-headroom
  [worlds]
  (into {}
        (for [{:keys [id]} boundary-checks
              :let [per-world      (map boundary-headroom-for-world worlds)
                    rows-by-id     (map #(get % id) per-world)
                    min-headrooms  (keep :min_headroom rows-by-id)
                    all-violations (mapcat :violations rows-by-id)]]
          [id {:min_headroom_across_worlds (when (seq min-headrooms)
                                             (apply min min-headrooms))
               :worlds_at_cap  (reduce + 0 (map :workflows_at_cap rows-by-id))
               :worlds_tracked (reduce + 0 (map :workflows_tracked rows-by-id))
               :all_hold       (every? :holds rows-by-id)
               :sample_violations (vec (take 10 all-violations))}])))

(defn- sum-by-token
  [token-maps]
  (apply merge-with + {} token-maps))

(defn aggregate-funds-ledger
  "Sum global custody buckets across terminal worlds (suite-level diagnostic)."
  [worlds]
  (when (seq worlds)
    (let [views (map proj/funds-ledger-view worlds)]
      {:aggregation "sum-across-terminal-worlds"
       :worlds (count worlds)
       :claimable_total (reduce + 0 (map #(get-in % [:global :claimable-total] 0) views))
       :bond_locked_total (reduce + 0 (map #(get-in % [:global :bond-locked-total] 0) views))
       :withdrawn_note "Per-token held/released/refunded/withdrawn summed across scenarios — not one physical ledger."
       :by_token (sum-by-token (map :by-token views))})))

(defn- total-claimable-all-classes
  [world]
  (reduce + 0
          (map :total_claimable (vals (aggregate-class-observations [world])))))

(defn- compact-by-class
  [class-obs]
  (into {}
        (for [[k v] class-obs
              :when (pos? (:total_claimable v 0))]
          [k v])))

(defn scenario-highlight
  [{:keys [scenario-id outcome world]} & {:keys [workflow-limit]}]
  (let [class-obs (aggregate-class-observations [world])
        ledger    (proj/funds-ledger-view world)
        bounds    (boundary-headroom-for-world world)]
    {:scenario_id (str scenario-id)
     :outcome (some-> outcome name)
     :total_claimable (total-claimable-all-classes world)
     :by_class (compact-by-class class-obs)
     :workflows (workflow-rows-for-world world :limit workflow-limit)
     :funds_ledger {:claimable_total (get-in ledger [:global :claimable-total] 0)
                    :bond_locked_total (get-in ledger [:global :bond-locked-total] 0)
                    :conservation_holds (get-in ledger [:conservation :holds?])}
     :boundaries_all_hold (every? :holds (vals bounds))}))

(defn scenario-highlights
  "Top scenarios by terminal claimable balance (for researcher drill-down)."
  [contexts & {:keys [limit workflow-limit]}]
  (let [lim (or limit default-highlight-limit)]
    (->> contexts
         (map #(scenario-highlight % :workflow-limit workflow-limit))
         (sort-by :total_claimable >)
         (filter #(or (pos? (:total_claimable % 0))
                      (seq (:workflows %))))
         (take lim)
         vec)))

(defn escrow-yield-outcome-frequencies
  [worlds]
  (frequencies
   (mapcat
    (fn [w]
      (map :outcome (vals (claim-outcome/outcomes-by-workflow w))))
    worlds)))

(defn terminal-observations
  "Aggregate claimable balances and boundary status from terminal replay worlds."
  [worlds & {:keys [scope scenarios_passed contexts highlight-limit workflow-limit
                   aggregation aggregation_note]}]
  (when (seq worlds)
    (cond-> {:scope (or scope "terminal-worlds")
             :scenario_count (count worlds)
             :scenarios_passed scenarios_passed
             :aggregation (or aggregation "sum-across-terminal-worlds")
             :aggregation_note
             (or aggregation_note
                 (str "Totals sum claimable-v2 balances at each scenario's terminal "
                      "world; scenarios_nonzero counts scenarios with any balance in class."))
             :by_class (aggregate-class-observations worlds)
             :by_domain (aggregate-domain-observations worlds)
             :boundaries (boundary-observations worlds)
             :boundary_headroom (aggregate-boundary-headroom worlds)
             :funds_ledger (aggregate-funds-ledger worlds)
             :escrow_yield_outcome_counts
             (into {} (map (fn [[k v]] [(name k) v]) (escrow-yield-outcome-frequencies worlds)))}
      (seq contexts)
      (assoc :scenario_highlights
             (scenario-highlights contexts
                                  :limit highlight-limit
                                  :workflow-limit workflow-limit)))))

(defn taxonomy-document
  "Static v2 classification (no replay)."
  []
  {:schema_version schema-version
   :shortfall_policy shortfall-policy
   :classes (update-vals v2-classes
                          (fn [m]
                            (update m :claimable_v2_domains vec)))})

(defn build-document
  "Full v2 artifact: taxonomy plus optional terminal observations."
  [& {:keys [worlds contexts scope scenarios-passed observations-status provenance
             highlight-limit workflow-limit aggregation aggregation-note]}]
  (cond-> (taxonomy-document)
    provenance (assoc :provenance provenance)

    (seq worlds)
    (assoc :terminal_observations
           (terminal-observations worlds
                                  :scope scope
                                  :scenarios_passed scenarios-passed
                                  :contexts contexts
                                  :highlight-limit highlight-limit
                                  :workflow-limit workflow-limit
                                  :aggregation aggregation
                                  :aggregation_note aggregation-note))

    observations-status
    (assoc :observations_status observations-status)))

(defn terminal-context-from-replay-result
  [result]
  (when-let [world (proj/terminal-world-from-result result)]
    {:scenario-id (or (:scenario-id result) "unknown")
     :outcome (:outcome result)
     :world world}))

(defn- summary-entries
  [summary]
  (or (:results summary) (:entries summary) []))

(defn terminal-contexts-from-summary
  "Replay summary entries with terminal world snapshots."
  [summary]
  (vec
   (keep
    (fn [entry]
      (when-let [res (:replay-result entry)]
        (when-let [world (proj/terminal-world-from-result res)]
          {:scenario-id (or (:scenario-id res) (:scenario-id entry) (:name entry))
           :outcome (:outcome res)
           :world world})))
    (summary-entries summary))))

(defn terminal-worlds-from-summary
  "Extract terminal worlds from a scenario runner summary (`:entries`)."
  [summary]
  (mapv :world (terminal-contexts-from-summary summary)))
