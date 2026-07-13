(ns resolver-sim.economics.payoffs
  "Protocol-agnostic economic allocation and accounting helpers.

   Layering rule:
   - resolver-sim.economics/* is protocol-agnostic.
   - resolver-sim.protocols.<protocol>/* adapts protocol-specific state and policy
     into generic economics functions.
   - Generic economics must never depend on protocol namespaces."
  (:require [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]))

;; Basis point denominator used by generic integer accounting helpers.
;; Also defined as resolver-sim.yield.exact-math/scaling-factor (same value).
(def basis-point-denominator 10000)

(defn calculate-bps-amount
  "Return `amount * bps / 10000` using integer division.
   Nil-safe: nil amount or bps is treated as 0."
  [amount bps]
  (quot (* (or amount 0) (or bps 0)) basis-point-denominator))

(defn calculate-net-after-bps-fee
  "Return {:fee ... :net ...} for a basis-point fee deducted from `amount`."
  [amount fee-bps]
  (let [fee (calculate-bps-amount amount fee-bps)]
    {:fee fee
     :net (- amount fee)}))

(defn calculate-capacity-limit
  "Return a generic capacity limit from a base amount and scalar multiplier."
  ([base-amount] (calculate-capacity-limit base-amount 1.0))
  ([base-amount multiplier]
   (* base-amount (or multiplier 1.0))))

(defn- non-negative-integer
  [x]
  (let [x (or x 0)]
    (if (integer? x)
      (max 0 (bigint x))
      (throw (ex-info "Expected an integer amount" {:value x})))))

(def default-pro-rata-intent-id :pro-rata/slash-obligation-allocation)

(def default-pro-rata-projection-definition-id :projection/pro-rata-slash-obligation)

(defn make-pro-rata-progress-atom
  "Create caller-owned progress state for one pro-rata allocation.

   Pass the returned atom as `:progress-atom` to `allocate-pro-rata`. The
   allocator updates it atomically through :preparing, :requesting,
   :allocating, and :completed phases. It deliberately does not use a global
   atom, so concurrent allocations remain isolated by default."
  []
  (atom {:status :pending
         :phase :pending
         :current 0
         :total 0}))

(defn- report-pro-rata-progress!
  [progress-atom update]
  (when progress-atom
    (swap! progress-atom merge update)))

(defn- registry-entry
  [entries id]
  (some #(when (= id (:id %)) %) entries))

(defn registered-intent
  "Return a passive intent registry entry by id, or nil."
  [intent-id]
  (registry-entry (:intents registries/intent-registry) intent-id))

(defn registered-projection-definition
  "Return a passive projection definition registry entry by id, or nil."
  [projection-definition-id]
  (registry-entry (:projection-definitions registries/projection-definition-registry)
                  projection-definition-id))

(defn- registered-claim
  [claim-id]
  (registry-entry (:claim-definitions registries/claim-definition-registry) claim-id))

(defn- require-registered
  [kind id value]
  (or value
      (throw (ex-info (str "Unregistered " (name kind))
                      {kind id}))))

(defn- short-hash
  [s]
  (subs s 0 (min 16 (count s))))

(defn- prepared-allocation-frame
  [{:keys [amount items id-fn weight-fn cap-fn rounding remainder-policy ordering-policy]
    :or {id-fn :id
         weight-fn :weight
         cap-fn (constantly nil)
         rounding :floor-with-largest-remainder
         remainder-policy :unallocated
         ordering-policy :input-order}}]
  (let [amount (non-negative-integer amount)
        prepared (mapv (fn [idx item]
                         (let [id (id-fn item)
                               weight (non-negative-integer (weight-fn item))
                               cap-raw (cap-fn item)
                               cap (when (some? cap-raw)
                                     (non-negative-integer cap-raw))]
                           {:idx idx
                            :id id
                            :weight weight
                            :cap cap}))
                       (range) (or items []))
        participants (mapv :id prepared)
        eligible (mapv :id (filter #(pos? (:weight %)) prepared))
        weights (into {} (map (juxt :id :weight) prepared))
        caps (into {} (keep (fn [{:keys [id cap]}]
                              (when (some? cap)
                                [id cap]))
                            prepared))
        total-weight (reduce +' 0 (map :weight prepared))]
    {:participants participants
     :eligible-participants eligible
     :weights weights
     :caps caps
     :total-obligation amount
     :constraints {:unit :wei
                   :rounding rounding
                   :remainder-policy remainder-policy
                   :ordering-policy ordering-policy}
     :items (mapv (fn [{:keys [id weight cap]}]
                    {:id id
                     :weight weight
                     :cap cap})
                  prepared)
     :summary {:participant-count (count participants)
               :eligible-count (count eligible)
               :total-weight total-weight
               :total-obligation amount}}))

(declare allocate-pro-rata)

(defn build-projection-artifact
  "Build a passive projection artifact for the current generic pro-rata input.
   This does not call allocate-pro-rata and does not change allocation behavior."
  ([allocation-input]
   (build-projection-artifact allocation-input {}))
  ([allocation-input {:keys [intent-id projection-definition-id source metadata]
                      :or {intent-id default-pro-rata-intent-id
                           projection-definition-id default-pro-rata-projection-definition-id
                           source {}}}]
   (let [intent (require-registered :intent-id intent-id (registered-intent intent-id))
         projection-definition (require-registered :projection-definition-id
                                                   projection-definition-id
                                                   (registered-projection-definition projection-definition-id))
         claim-ids (mapv :claim-id (:claims projection-definition))
         claims (mapv (fn [claim-id]
                        (let [claim (require-registered :claim-id claim-id (registered-claim claim-id))]
                          {:claim-id claim-id
                           :claim-definition-hash (:canonical-hash claim)
                           :claim-definition-concept-hash (:concept-hash claim)}))
                      claim-ids)
         projection-frame (prepared-allocation-frame allocation-input)
         projection (:summary projection-frame)
         projection-body (dissoc projection-frame :summary)
         projection-definition-hash (:canonical-hash projection-definition)
         projection-concept-hash (:concept-hash projection-definition)
         intent-hash (:canonical-hash intent)
         source-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                          {:intent-id intent-id
                                           :projection-definition-id projection-definition-id
                                           :source source
                                           :projection projection-body})
         artifact-base {:schema-version 1
                        :projection-id (str "projection-pro-rata-" (short-hash source-hash))
                        :projection-type (:projection-type projection-definition)
                        :projection-version (:version projection-definition)
                        :intent {:id (:id intent)
                                 :version (:version intent)
                                 :intent-hash intent-hash}
                        :projection-definition-id (:id projection-definition)
                        :projection-definition-hash projection-definition-hash
                        :projection-concept-hash projection-concept-hash
                        :source (merge {:source-hash source-hash}
                                       source)
                        :projection projection-body
                        :summary projection
                        :claims claims
                        :metadata (or metadata {})}
         projection-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                              artifact-base)
         artifact (assoc artifact-base :projection-hash projection-hash)]
     (hc/validate-canonical-value! artifact)
     artifact)))

(defn- validate-projection-artifact!
  [artifact]
  (hc/validate-canonical-value! artifact)
  (let [expected-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                           (dissoc artifact :projection-hash))]
    (when-not (= expected-hash (:projection-hash artifact))
      (throw (ex-info "Projection artifact hash mismatch"
                      {:expected expected-hash
                       :actual (:projection-hash artifact)}))))
  (require-registered :intent-id
                      (get-in artifact [:intent :id])
                      (registered-intent (get-in artifact [:intent :id])))
  (require-registered :projection-definition-id
                      (:projection-definition-id artifact)
                      (registered-projection-definition (:projection-definition-id artifact)))
  artifact)

(defn calculate-prorata-from-projection
  "Allocate pro-rata from a validated projection artifact.
   This is a shadow path for comparing projection-derived allocation with direct
   allocation; it intentionally returns the same generic shape as allocate-pro-rata."
  [artifact]
  (validate-projection-artifact! artifact)
  (let [{:keys [total-obligation items constraints]} (:projection artifact)
        {:keys [rounding remainder-policy ordering-policy]} constraints]
    (allocate-pro-rata {:amount total-obligation
                        :items items
                        :id-fn :id
                        :weight-fn :weight
                        :cap-fn :cap
                        :rounding rounding
                        :remainder-policy remainder-policy
                        :ordering-policy ordering-policy})))

;; ---------------------------------------------------------------------------
;; Generic Pro-Rata Allocation
;; ---------------------------------------------------------------------------

(defn- pro-rata-requests
  [amount prepared total-weight rounding]
  (let [floors (mapv (fn [{:keys [weight]}]
                       (quot (* amount weight) total-weight))
                     prepared)]
    (case rounding
      :floor
      floors

      :floor-with-largest-remainder
      (let [allocated (reduce +' 0 floors)
            shortage (- amount allocated)
            remainders (mapv (fn [{:keys [idx weight]}]
                               {:idx idx
                                :remainder (mod (* amount weight) total-weight)})
                             prepared)
            remainder-order (->> remainders
                                 (sort-by (juxt (comp - :remainder) :idx))
                                 (map :idx)
                                 (take shortage)
                                 set)]
        (mapv (fn [idx allocated]
                (if (contains? remainder-order idx)
                  (inc allocated)
                  allocated))
              (range) floors)))))

(defn allocate-pro-rata
  "Allocate an integer amount pro-rata across abstract weighted items.

   Inputs are intentionally generic. Protocol-specific namespaces should adapt
   their domain data into {:id ... :weight ... :cap ...} items before calling.

   Supported policies:
   - :rounding :floor (default) leaves integer dust in :remainder
   - :rounding :floor-with-largest-remainder distributes dust by Hare quota
   - :remainder-policy :unallocated reports capped/unallocated amounts; it does not redistribute
   - :ordering-policy :input-order breaks equal-remainder ties by input order"
  [{:keys [amount items id-fn weight-fn cap-fn rounding remainder-policy ordering-policy
           progress-atom]
    :or {id-fn :id
         weight-fn :weight
         cap-fn (constantly nil)
         rounding :floor
         remainder-policy :unallocated
         ordering-policy :input-order}}]
  (when-not (#{:floor :floor-with-largest-remainder} rounding)
    (throw (ex-info "Unsupported pro-rata rounding policy" {:rounding rounding})))
  (when-not (= :unallocated remainder-policy)
    (throw (ex-info "Unsupported pro-rata remainder policy" {:remainder-policy remainder-policy})))
  (when-not (= :input-order ordering-policy)
    (throw (ex-info "Unsupported pro-rata ordering policy" {:ordering-policy ordering-policy})))
  (let [amount (non-negative-integer amount)
        items (vec (or items []))
        total-items (count items)
        _ (report-pro-rata-progress! progress-atom
                                     {:status :running
                                      :phase :preparing
                                      :current 0
                                      :total total-items})
        prepared (mapv (fn [idx item]
                         (let [weight (non-negative-integer (weight-fn item))
                               cap-raw (cap-fn item)
                               cap (when (some? cap-raw)
                                     (non-negative-integer cap-raw))]
                           {:idx idx
                            :item item
                            :id (id-fn item)
                            :weight weight
                            :cap cap}))
                       (range) items)
        total-weight (reduce +' 0 (map :weight prepared))
        _ (report-pro-rata-progress! progress-atom {:phase :requesting})
        requests (if (zero? total-weight)
                   (repeat (count prepared) 0)
                   (pro-rata-requests amount prepared total-weight rounding))
        _ (report-pro-rata-progress! progress-atom {:phase :allocating})
        allocations (mapv (fn [{:keys [id weight cap]} requested]
                            (let [allocated (min requested (or cap requested))
                                  unmet (- requested allocated)]
                              (when progress-atom
                                (swap! progress-atom update :current inc))
                              {:id id
                               :allocated allocated
                               :unmet unmet
                               :weight weight
                               :cap cap}))
                          prepared requests)
        total-allocated (reduce +' 0 (map :allocated allocations))
        total-unmet (reduce +' 0 (map :unmet allocations))
        remainder (- amount total-allocated total-unmet)
        result {:allocations allocations
                :total-requested amount
                :total-allocated total-allocated
                :total-unmet total-unmet
                :remainder remainder
                :policy {:rounding rounding
                         :remainder-policy remainder-policy
                         :ordering-policy ordering-policy
                         :total-weight total-weight}}]
    (report-pro-rata-progress! progress-atom
                               {:status :completed
                                :phase :completed
                                :current total-items})
    result))

(def ^:private max-redistribution-passes 10)

(defn- merge-into-base
  "Merge additional allocations into a base-allocations map (id -> map).
   Sums :allocated for matching ids, adds new entries for unknown ids.
   Returns a vector of merged allocations."
  [base-map additional-allocs]
  (vec (vals (reduce (fn [acc a]
                       (if-let [existing (get acc (:id a))]
                         (update acc (:id a) update :allocated + (:allocated a))
                         (assoc acc (:id a) a)))
                     base-map
                     additional-allocs))))

(defn- initial-cap-analysis
  "Analyze first-pass allocation for caps hit and redistributable excess.
   Returns {:capped-ids set :excess n :base-map {id -> alloc}}."
  [base-result items id-fn]
  (let [base-allocations (:allocations base-result)
        capped-ids (set (keep (fn [a]
                                (when (and (:cap a) (>= (:allocated a) (:cap a)))
                                  (:id a)))
                              base-allocations))
        excess (+ (:total-unmet base-result) (:remainder base-result))]
    {:capped-ids capped-ids
     :excess excess
     :base-map (into {} (map (fn [a] [(:id a) a]) base-allocations))}))

(defn allocate-pro-rata-with-redistribution
  "Like allocate-pro-rata, but when an item hits its cap the excess
   is redistributed to remaining uncapped items iteratively until no
   new caps are hit or the iteration limit is reached.

   Items shape: {:id <kw> :weight <int> :cap <int-or-nil>}
   Nil cap means unlimited (no cap applied).

   When no item has a cap, or no item hits its cap, the result is
   identical to allocate-pro-rata with the same parameters.

   Same return shape as allocate-pro-rata.
   Adds :redistribution metadata with per-pass records."
  [{:keys [amount items id-fn weight-fn cap-fn rounding]
    :or {id-fn :id
         weight-fn :weight
         cap-fn :cap
         rounding :floor-with-largest-remainder}}]
  (let [base-result (allocate-pro-rata {:amount amount
                                        :items items
                                        :id-fn id-fn :weight-fn weight-fn :cap-fn cap-fn
                                        :rounding rounding
                                        :remainder-policy :unallocated})
        {:keys [capped-ids excess base-map]}
        (initial-cap-analysis base-result items id-fn)]
    (if (or (zero? excess) (empty? capped-ids) (= (count capped-ids) (count items)))
      base-result
      (loop [remaining-excess excess
             uncapped-items (remove (fn [item] (contains? capped-ids (id-fn item))) items)
             acc-base-map base-map
             acc-capped-ids capped-ids
             pass-num 1
             pass-records [{:pass 0
                            :capped-ids (vec capped-ids)
                            :excess excess}]]
        (if (>= pass-num max-redistribution-passes)
          (let [all-allocs (vec (vals acc-base-map))
                total-allocated (reduce +' 0 (map :allocated all-allocs))]
            {:allocations all-allocs
             :total-requested amount
             :total-allocated total-allocated
             :total-unmet 0
             :remainder remaining-excess
             :policy (:policy base-result)
             :redistribution {:passes (vec pass-records)
                              :total-passes (inc pass-num)
                              :iteration-limit-reached? true}})
          (let [pass-result (allocate-pro-rata {:amount remaining-excess
                                                :items uncapped-items
                                                :id-fn id-fn :weight-fn weight-fn :cap-fn cap-fn
                                                :rounding rounding
                                                :remainder-policy :unallocated})
                merged (merge-into-base acc-base-map (:allocations pass-result))
                newly-capped (filterv (fn [a] (and (some? (:cap a))
                                                   (>= (:allocated a) (:cap a))))
                                      merged)
                newly-capped-ids (set (map :id newly-capped))]
            (if (empty? newly-capped)
              (let [total-allocated (reduce +' 0 (map :allocated merged))]
                {:allocations merged
                 :total-requested amount
                 :total-allocated total-allocated
                 :total-unmet (:total-unmet pass-result)
                 :remainder (:remainder pass-result)
                 :policy (:policy base-result)
                 :redistribution {:passes (vec pass-records)
                                  :total-passes (inc pass-num)}})
              (let [next-excess (+ (:total-unmet pass-result) (:remainder pass-result))
                    all-capped (into acc-capped-ids newly-capped-ids)
                    next-uncapped (remove (fn [item] (contains? all-capped (id-fn item))) items)]
                (if (or (zero? next-excess) (empty? next-uncapped))
                  (let [total-allocated (reduce +' 0 (map :allocated merged))]
                    {:allocations merged
                     :total-requested amount
                     :total-allocated total-allocated
                     :total-unmet 0
                     :remainder next-excess
                     :policy (:policy base-result)
                     :redistribution {:passes (vec pass-records)
                                      :total-passes (inc pass-num)}})
                  (recur next-excess
                         next-uncapped
                         (into {} (map (fn [a] [(:id a) a]) merged))
                         all-capped
                         (inc pass-num)
                         (conj pass-records {:pass pass-num
                                             :capped-ids (vec newly-capped-ids)
                                             :excess next-excess}))))))))))) (def default-pro-rata-allocation-result-kind :pro-rata-allocation)
(def default-pro-rata-allocation-result-version 1)
(def default-pro-rata-allocation-result-artifact-kind :pro-rata/allocation-result)

(defn build-pro-rata-allocation-result-artifact
  "Build a pro-rata allocation result artifact from an allocation result
   and its provenance context.

   This is the ex-post outcome artifact that captures what was actually
   allocated, complementing the ex-ante projection frame.

   Required inputs:
     :projection-artifact        — the ex-ante projection frame
     :allocation-result          — output from allocate-pro-rata
     :world-before-hash          — hash of the world before execution
     :world-after-hash           — hash of the world after execution
     :action-hash                — hash of the executed action
     :action-hash-at             — hash of the action at execution time

   Optional inputs:
       :shortfall-outcome          — shortfall breakdown from evidence layer
       :claims                     — claim result links
       :invariant-links            — invariant result links
       :evidence-record-hash       — evidence envelope hash (stored in :external-refs, excluded from canonical hash)
       :evidence-group-id          — group-id for cross-layer linking to surrounding evidence
        :attribution                — researcher attribution context (scenario-id, run-id, event-index, event-type)
         :allocation-input           — raw input map (obligation, parties, basis, cap-field, etc.)
        :metadata                   — additional metadata"
  [{:keys [projection-artifact
           allocation-result
           world-before-hash
           world-after-hash
           action-hash
           action-hash-at
           shortfall-outcome
           allocation-input
           claims
           invariant-links
           evidence-record-hash
           evidence-group-id
           attribution
           metadata]}]
  (let [projection-artifact-hash (:projection-hash projection-artifact)
        projection-definition-id (:projection-definition-id projection-artifact)
        projection-definition-hash (:projection-definition-hash projection-artifact)
        projection-concept-hash (:projection-concept-hash projection-artifact)
        provenance (merge
                    {:world-before-hash world-before-hash
                     :world-after-hash world-after-hash
                     :action-hash action-hash
                     :action-hash-at action-hash-at}
                    (when attribution
                      (into {} (remove (comp nil? val)
                                       {:scenario-id (:ctx/scenario-id attribution)
                                        :run-id (:ctx/run-id attribution)
                                        :event-index (:ctx/event-index attribution)
                                        :event-type (:ctx/event-type attribution)}))))
        source (get projection-artifact :source {})
        source-hash (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result}
                                         {:projection-artifact-hash projection-artifact-hash
                                          :allocation-result (dissoc allocation-result :policy)
                                          :provenance provenance
                                          :shortfall-outcome shortfall-outcome
                                          :allocation-input allocation-input})
        external-refs (merge (when evidence-record-hash
                               {:evidence-record-hash evidence-record-hash})
                             (when evidence-group-id
                               {:evidence-group-id evidence-group-id}))
        artifact-base {:schema-version 1
                       :artifact-kind default-pro-rata-allocation-result-artifact-kind
                       :allocation-result-id (str "allocation-pro-rata-"
                                                  (short-hash source-hash))
                       :allocation-result-type default-pro-rata-allocation-result-kind
                       :allocation-result-version default-pro-rata-allocation-result-version
                       :projection-artifact-hash projection-artifact-hash
                       :projection-definition-id projection-definition-id
                       :projection-definition-hash projection-definition-hash
                       :projection-concept-hash projection-concept-hash
                       :source source
                       :provenance provenance
                       :allocation-input allocation-input
                       :allocation-result allocation-result
                       :shortfall-outcome shortfall-outcome
                       :claims (or claims [])
                       :invariant-links (or invariant-links [])
                       :metadata (or metadata {})
                       :external-refs (when (seq external-refs) external-refs)}
        allocation-result-hash (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result}
                                                    artifact-base)
        artifact (assoc artifact-base :allocation-result-hash allocation-result-hash)]
    (hc/validate-canonical-value!
     (:artifact (hc/project-pro-rata-allocation-result
                 artifact {:hash/intent :pro-rata-allocation-result})))
    artifact))

(defn validate-pro-rata-allocation-result-artifact!
  "Validate a pro-rata allocation result artifact by:
   - verifying canonical safety
   - recomputing and verifying allocation-result-hash
   - requiring projection-artifact-hash
   - requiring allocation-result
   - validating allocation totals
   - checking provenance fields
   - rejecting mismatched hash"
  [artifact]
  (hc/validate-canonical-value! artifact)
  (let [expected-hash (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result}
                                           (dissoc artifact :allocation-result-hash))]
    (when-not (= expected-hash (:allocation-result-hash artifact))
      (throw (ex-info "Pro-rata allocation result hash mismatch"
                      {:expected expected-hash
                       :actual (:allocation-result-hash artifact)}))))
  (let [proj-hash (:projection-artifact-hash artifact)]
    (when (nil? proj-hash)
      (throw (ex-info "Missing projection-artifact-hash" {:artifact artifact}))))
  (let [allocation (:allocation-result artifact)]
    (when (nil? allocation)
      (throw (ex-info "Missing allocation-result" {:artifact artifact})))
    (let [total-requested (:total-requested allocation 0)
          total-allocated (:total-allocated allocation 0)
          total-unmet (:total-unmet allocation 0)
          remainder (:remainder allocation 0)]
      (when (not= total-requested (+ total-allocated total-unmet remainder))
        (throw (ex-info "Allocation totals do not sum to total-requested"
                        {:total-requested total-requested
                         :sum (+ total-allocated total-unmet remainder)
                         :total-allocated total-allocated
                         :total-unmet total-unmet
                         :remainder remainder})))))
  (doseq [k [:world-before-hash :world-after-hash :action-hash :action-hash-at]]
    (when (nil? (get-in artifact [:provenance k]))
      (throw (ex-info (str "Missing provenance field: " k)
                      {:field k :artifact artifact}))))
  artifact)

;; ──────────────────────────────────────────────────────────────────────────────
;; Demo Rendering: Pro-Rata Result Tables and Proof Panels
;; ──────────────────────────────────────────────────────────────────────────────

(defn- basis-amount-shaped?
  [allocations]
  (some :basis-amount (take 1 allocations)))

(defn format-pro-rata-result-table
  "Render a pro-rata allocation result as a formatted text table.

   Handles both basis-amount-shaped allocations (with :basis-amount :share :owed :paid)
   and generic allocations (with :weight :cap :allocated :unmet :remainder).

   Input can be:
   - A pro-rata allocation result artifact (with :allocation-result)
   - An allocation-result map directly (with :allocations list)"
  [artifact-or-result]
  (let [allocations (if (:allocation-result artifact-or-result)
                      (:allocations (:allocation-result artifact-or-result))
                      (:allocations artifact-or-result))
        result (if (:allocation-result artifact-or-result)
                 (:allocation-result artifact-or-result)
                 artifact-or-result)
        basis-amount? (basis-amount-shaped? allocations)
        sb (StringBuilder.)]
    (if basis-amount?
      (do
        (.append sb (format "%-20s %8s %8s %8s %8s%n"
                            "Participant" "Basis" "Owed" "Paid" "Unmet"))
        (.append sb (apply str (repeat 52 "-")))
        (doseq [a allocations]
          (.append sb (format "%n%-20s %8s %8s %8s %8s"
                              (str (:id a))
                              (str (:basis-amount a))
                              (str (:owed a))
                              (str (:paid a))
                              (str (:unmet a)))))
        (let [total-owed (apply + (map :owed allocations))
              total-paid (apply + (map :paid allocations))
              total-unmet (apply + (map :unmet allocations))]
          (.append sb (format "%n%n%-20s %8s %8s %8s %8s"
                              "Total" "" (str total-owed) (str total-paid) (str total-unmet)))))
      (do
        (.append sb (format "%-20s %8s %8s %8s %8s %8s%n"
                            "Participant" "Weight" "Cap" "Allocated" "Unmet" "Remainder"))
        (.append sb (apply str (repeat 60 "-")))
        (doseq [a allocations]
          (.append sb (format "%n%-20s %8s %8s %8s %8s %8s"
                              (str (:id a))
                              (str (or (:weight a) "—"))
                              (str (if (some? (:cap a)) (:cap a) "—"))
                              (str (:allocated a))
                              (str (:unmet a))
                              (str (or (:remainder a) "—")))))
        (let [total-allocated (get result :total-allocated)
              total-unmet (get result :total-unmet)
              remainder (get result :remainder)]
          (.append sb (format "%n%n%-20s %8s %8s %8s %8s"
                              "Total" "" "" (str total-allocated) (str total-unmet)))
          (when (and remainder (pos? (or remainder 0)))
            (.append sb (format " %8s" (str remainder)))))))
    (str sb)))

(defn format-proof-panel
  "Render a forensic proof/link panel for a pro-rata allocation result artifact.
   Shows projection frame hash, allocation result hash, world hashes,
   action-hash-at, evidence hash, and claim/invariant links."
  [artifact]
  (let [sb (StringBuilder.)
        result-hash (:allocation-result-hash artifact)
        proj-hash (:projection-artifact-hash artifact)
        prov (:provenance artifact)
        claims (:claims artifact [])
        invariants (:invariant-links artifact [])]
    (.append sb "=== Proof Panel: Pro-Rata Allocation Result ===\n")
    (.append sb (format "  Allocation Result Hash:  %s%n" (or result-hash "—")))
    (.append sb (format "  Projection Frame Hash:   %s%n" (or proj-hash "—")))
    (.append sb (format "  Projection Def ID:       %s%n" (str (:projection-definition-id artifact "—"))))
    (.append sb (format "  Projection Def Hash:     %s%n" (str (:projection-definition-hash artifact "—"))))
    (.append sb (format "  World Before Hash:       %s%n" (or (:world-before-hash prov) "—")))
    (.append sb (format "  World After Hash:        %s%n" (or (:world-after-hash prov) "—")))
    (.append sb (format "  Action Hash:             %s%n" (or (:action-hash prov) "—")))
    (.append sb (format "  Action Hash-At:          %s%n" (or (:action-hash-at prov) "—")))
    (.append sb (format "  Evidence Record Hash:    %s%n" (or (:evidence-record-hash prov) "—")))
    (.append sb (format "  Claim Links:             %d%n" (count claims)))
    (doseq [c claims]
      (.append sb (format "    - %s%n" (str (:claim-id c) " → " (:claim-result-hash c "—")))))
    (.append sb (format "  Invariant Links:         %d%n" (count invariants)))
    (doseq [iv invariants]
      (.append sb (format "    - %s%n" (str iv))))
    (str sb)))
