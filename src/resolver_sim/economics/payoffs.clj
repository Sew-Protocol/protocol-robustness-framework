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
(def basis-point-denominator 10000)

(defn calculate-bps-amount
  "Return `amount * bps / 10000` using integer division."
  [amount bps]
  (quot (* amount bps) basis-point-denominator))

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
                           :claim-definition-hash (:canonical-hash claim)}))
                      claim-ids)
         projection-frame (prepared-allocation-frame allocation-input)
         projection (:summary projection-frame)
         projection-body (dissoc projection-frame :summary)
         projection-definition-hash (:canonical-hash projection-definition)
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
  [{:keys [amount items id-fn weight-fn cap-fn rounding remainder-policy ordering-policy]
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
                       (range) (or items []))
        total-weight (reduce +' 0 (map :weight prepared))
        requests (if (zero? total-weight)
                   (repeat (count prepared) 0)
                   (pro-rata-requests amount prepared total-weight rounding))
        allocations (mapv (fn [{:keys [id weight cap]} requested]
                            (let [allocated (min requested (or cap requested))
                                  unmet (- requested allocated)]
                              {:id id
                               :allocated allocated
                               :unmet unmet
                               :weight weight
                               :cap cap}))
                          prepared requests)
        total-allocated (reduce +' 0 (map :allocated allocations))
        total-unmet (reduce +' 0 (map :unmet allocations))
        remainder (- amount total-allocated total-unmet)]
    {:allocations allocations
     :total-requested amount
     :total-allocated total-allocated
     :total-unmet total-unmet
     :remainder remainder
     :policy {:rounding rounding
              :remainder-policy remainder-policy
              :ordering-policy ordering-policy
              :total-weight total-weight}}))
