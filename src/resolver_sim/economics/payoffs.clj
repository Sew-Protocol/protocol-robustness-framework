(ns resolver-sim.economics.payoffs
  "Protocol-agnostic economic allocation and accounting helpers.

   Layering rule:
   - resolver-sim.economics/* is protocol-agnostic.
   - resolver-sim.protocols.<protocol>/* adapts protocol-specific state and policy
     into generic economics functions.
   - Generic economics must never depend on protocol namespaces.")

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

;; ---------------------------------------------------------------------------
;; Generic Pro-Rata Allocation
;; ---------------------------------------------------------------------------

(defn- non-negative-integer
  [x]
  (let [x (or x 0)]
    (if (integer? x)
      (max 0 (bigint x))
      (throw (ex-info "Expected an integer amount" {:value x})))))

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
