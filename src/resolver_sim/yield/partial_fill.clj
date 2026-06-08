(ns resolver-sim.yield.partial-fill
  "First-class partial-fill settlement decision model.

   `calculate-fulfillment` returns a structured settlement decision map
   rather than a simple scalar balance update. Supports pro-rata,
   principal-first, and waterfall fill policies with exact ratio arithmetic
   and configurable quantization.

   Default policy:
     {:mode :waterfall
      :fill-order [:principal :realized-yield :deferred-yield]
      :unrealized-yield-treatment :not-claimable
      :residual-treatment :defer
      :post-partial-fill-accrual :accrue-residual-as-unrealized
      :rounding-policy :floor-and-carry}"
  (:require [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.position :as pos]))


(def ^:private schema-version "partial-fill-decision.v1")


(def default-partial-fill-policy
  {:mode :waterfall
   :fill-order [:principal :realized-yield :deferred-yield]
   :unrealized-yield-treatment :not-claimable
   :residual-treatment :defer
   :post-partial-fill-accrual :accrue-residual-as-unrealized
   :rounding-policy :floor-and-carry})


(def default-settlement-decision
  {:settlement-mode :full-fill
   :requested {}
   :filled {}
   :deferred {}
   :haircut {}
   :unrealized {}
   :policy default-partial-fill-policy
   :evidence {:schema-version schema-version}})


(defn- fill-order-set
  "Convert fill-order vector to a set for membership checks."
  [fill-order]
  (set fill-order))


(defn- position-bucket
  "Get a position's value for a given bucket key."
  [pos bucket]
  (case bucket
    :principal        (long (:principal pos 0))
    :realized-yield   (long (:realized-yield pos 0))
    :unrealized-yield (max 0 (long (:unrealized-yield pos 0)))
    :deferred-yield   (long (:deferred-yield pos 0))
    0))


(defn- position-bucket-name
  [bucket]
  (case bucket
    :principal "principal"
    :realized-yield "realized_yield"
    :unrealized-yield "unrealized_yield"
    :deferred-yield "deferred_yield"
    "unknown"))


(defn- sum-requested
  [requested]
  (reduce + 0 (map long (vals requested))))


(defn- make-evidence
  [policy available-liquidity total-requested shortage fill-mode]
  {:schema-version schema-version
   :available-liquidity available-liquidity
   :total-requested total-requested
   :shortage shortage
   :fill-mode fill-mode
   :rounding-policy (:rounding-policy policy)
   :fill-order (:fill-order policy)})


(defn calculate-fulfillment-pro-rata
  "Pro-rata fill: each claim bucket receives a proportional share of the available
   liquidity. Exact ratios computed, then quantized via configured rounding policy."
  [available-liquidity requested policy]
  (let [total (sum-requested requested)
        shortage (max 0 (- total available-liquidity))]
    (if (zero? shortage)
      {:settlement-mode :full-fill
       :requested requested
       :filled requested
       :deferred {}
       :haircut {}
       :unrealized {}
       :policy policy
       :evidence (make-evidence policy available-liquidity total 0 :pro-rata)}
      (let [claims (mapv (fn [[k v]] {:key k :amount (long v)})
                         (seq requested))
            rounding-policy (:rounding-policy policy :floor-and-carry)
            alloc (case rounding-policy
                    :largest-remainder (m/largest-remainder-alloc available-liquidity claims)
                    :principal-protective-floor (m/principal-protective-floor-alloc
                                                 available-liquidity claims
                                                 (fn [c] (= :principal (:key c))))
                    :adversarial-rounding (m/adversarial-rounding available-liquidity claims)
                    (m/floor-and-carry-alloc available-liquidity claims))
            filled (into {} (map (fn [a] [(:key a) (:filled a)]) (:allocations alloc)))
            deferred (into {} (map (fn [[k v]] [k (max 0 (- (long v) (long (get filled k 0))))])
                                   requested))]
        {:settlement-mode :partial-fill
         :requested requested
         :filled filled
         :deferred deferred
         :haircut {}
         :unrealized {}
         :policy policy
         :evidence (assoc (make-evidence policy available-liquidity total shortage :pro-rata)
                          :allocation-detail (select-keys alloc [:total-available-units
                                                                  :total-allocated-units
                                                                  :shortage-units
                                                                  :carry]))}))))


(defn calculate-fulfillment-principal-first
  "Principal-first fill: principal claims are satisfied in full before any
   yield claims are filled."
  [available-liquidity requested policy]
  (let [principal-requested (long (get requested :principal 0))
        principal-filled (min principal-requested available-liquidity)
        remaining (- available-liquidity principal-filled)
        yield-requested (dissoc requested :principal)
        yield-total (sum-requested yield-requested)
        shortage (max 0 (- (+ principal-requested yield-total) available-liquidity))]
    (if (zero? shortage)
      {:settlement-mode :full-fill
       :requested requested
       :filled requested
       :deferred {}
       :haircut {}
       :unrealized {}
       :policy policy
       :evidence (make-evidence policy available-liquidity (+ principal-requested yield-total) 0 :principal-first)}
      (let [principal-deferred (max 0 (- principal-requested principal-filled))
            filled (cond-> {:principal principal-filled}
                     (pos? remaining)
                     (merge (let [claims (mapv (fn [[k v]] {:key k :amount (long v)})
                                               (seq yield-requested))
                                  rounding-policy (:rounding-policy policy :floor-and-carry)
                                  alloc (case rounding-policy
                                          :largest-remainder (m/largest-remainder-alloc remaining claims)
                                          :principal-protective-floor (m/principal-protective-floor-alloc
                                                                       remaining claims
                                                                       (fn [c] (= :principal (:key c))))
                                          :adversarial-rounding (m/adversarial-rounding remaining claims)
                                          (m/floor-and-carry-alloc remaining claims))]
                              (into {} (map (fn [a] [(:key a) (:filled a)]) (:allocations alloc))))))
            deferred (merge (when (pos? principal-deferred) {:principal principal-deferred})
                            (into {} (map (fn [[k v]] [k (max 0 (- (long v) (long (get filled k 0))))])
                                          yield-requested)))]
        {:settlement-mode :partial-fill
         :requested requested
         :filled filled
         :deferred deferred
         :haircut {}
         :unrealized {}
         :policy policy
         :evidence (make-evidence policy available-liquidity (+ principal-requested yield-total) shortage :principal-first)}))))


(defn calculate-fulfillment-waterfall
  "Waterfall fill: claims are satisfied in strict fill-order priority.
   Each bucket in :fill-order is filled to exhaustion before moving to the next.
   Exact amounts are quantized via configured rounding policy."
  [available-liquidity requested policy]
  (let [fill-order (:fill-order policy [:principal :realized-yield :deferred-yield])
        total (sum-requested requested)
        shortage (max 0 (- total available-liquidity))]
    (if (zero? shortage)
      {:settlement-mode :full-fill
       :requested requested
       :filled requested
       :deferred {}
       :haircut {}
       :unrealized {}
       :policy policy
       :evidence (make-evidence policy available-liquidity total 0 :waterfall)}
      (loop [remaining available-liquidity
             filled {}
             deferred {}
             buckets fill-order]
        (if (or (zero? remaining) (empty? buckets))
          (let [all-keys (keys requested)
                all-deferred (merge deferred
                                    (into {} (for [k all-keys
                                                   :when (not (contains? filled k))]
                                               [k (long (get requested k 0))])))]
            {:settlement-mode :partial-fill
             :requested requested
             :filled filled
             :deferred all-deferred
             :haircut {}
             :unrealized {}
             :policy policy
             :evidence (make-evidence policy available-liquidity total shortage :waterfall)})
          (let [bucket (first buckets)
                bucket-amount (long (get requested bucket 0))
                filled-amount (min bucket-amount remaining)
                new-remaining (- remaining filled-amount)
                deferred-amount (max 0 (- bucket-amount filled-amount))]
            (recur new-remaining
                   (if (pos? filled-amount) (assoc filled bucket filled-amount) filled)
                   (if (pos? deferred-amount) (assoc deferred bucket deferred-amount) deferred)
                   (rest buckets))))))))


(defn calculate-fulfillment
  "Calculate the structured settlement decision for a withdrawal against
   available liquidity.

   Args:
     available-liquidity - total base units available for withdrawal
     position            - the yield position map
     policy              - (optional) partial-fill policy map, merged with defaults
     opts                - (optional) additional options {:available-ratio ...}

   Returns a structured settlement decision:
     {:settlement-mode :partial-fill | :full-fill
      :requested        {bucket amount ...}
      :filled           {bucket amount ...}
      :deferred         {bucket amount ...}
      :haircut           {bucket amount ...}
      :unrealized        {bucket amount ...}
      :policy            {...}
      :evidence          {...}}

   Settlement modes:
     :pro-rata       — proportional allocation across all buckets
     :principal-first — principal first, then pro-rata on yield
     :waterfall      — strict priority order (:fill-order)

   The :unrealized-yield-treatment policy controls whether unrealized yield
   is included in requested (:claimable) or excluded (:not-claimable)."
  ([available-liquidity position]
   (calculate-fulfillment available-liquidity position default-partial-fill-policy))
  ([available-liquidity position policy]
   (calculate-fulfillment available-liquidity position policy {}))
  ([available-liquidity position policy opts]
   (let [policy (merge default-partial-fill-policy policy)
         available (max 0 (long available-liquidity))
         include-unrealized? (= :claimable (:unrealized-yield-treatment policy))
         requested (cond-> {:principal (pos/claimable-principal position)
                            :realized-yield (pos/claimable-realized-yield position)
                            :deferred-yield (long (:deferred-yield position 0))}
                     include-unrealized?
                     (assoc :unrealized-yield (pos/claimable-unrealized-yield position)))
         requested (into {} (remove (fn [[_ v]] (zero? v)) requested))
         mode (:mode policy :waterfall)]
     (if (empty? requested)
       {:settlement-mode :full-fill
        :requested {}
        :filled {}
        :deferred {}
        :haircut {}
        :unrealized {}
        :policy policy
        :evidence {:schema-version schema-version
                   :available-liquidity available
                   :total-requested 0
                   :shortage 0
                   :fill-mode mode}}
       (case mode
         :pro-rata        (calculate-fulfillment-pro-rata available requested policy)
         :principal-first (calculate-fulfillment-principal-first available requested policy)
         :waterfall       (calculate-fulfillment-waterfall available requested policy))))))


(defn partial-fill?
  "True if the settlement decision represents a partial fill."
  [decision]
  (= :partial-fill (:settlement-mode decision)))


(defn post-partial-fill-position
  "Update a position after a partial-fill settlement decision has been applied.

   Returns an updated position map with:
   - :partial-fill-affected? set to true
   - :status set to :unwinding (unless already terminal)
   - Claimed buckets subtracted from respective fields
   - Residual entitlement preserved as deferred/haircut

   Fix: Ensures that deferred amounts are moved out of their source buckets
   to avoid double-counting when :accrue-residual-as-unrealized is active."
  [position decision]
  (let [filled (:filled decision)
        deferred (:deferred decision)
        haircut (:haircut decision)
        policy (:policy decision)
        post-accrual (get policy :post-partial-fill-accrual :accrue-residual-as-unrealized)
        ;; Subtract both filled and deferred/haircut from original buckets
        ;; to ensure the bucket state correctly reflects the settlement.
        p-delta (+ (long (get filled :principal 0))
                   (long (get deferred :principal 0))
                   (long (get haircut :principal 0)))
        r-delta (+ (long (get filled :realized-yield 0))
                   (long (get deferred :realized-yield 0))
                   (long (get haircut :realized-yield 0)))
        d-delta (+ (long (get filled :deferred-yield 0))
                   (long (get deferred :deferred-yield 0))
                   (long (get haircut :deferred-yield 0)))]
    (-> position
        (pos/normalize-position)
        (update :principal - p-delta)
        (update :realized-yield - r-delta)
        (update :deferred-yield - d-delta)
        (assoc :partial-fill-affected? true)
        (assoc :status :unwinding)
        (cond->
          (= post-accrual :accrue-residual-as-unrealized)
          (update :unrealized-yield + (long (get deferred :principal 0))
                                       (long (get deferred :realized-yield 0))
                                       (long (get deferred :deferred-yield 0)))

          (not= post-accrual :accrue-residual-as-unrealized)
          (-> (update :principal-impairment + (long (get deferred :principal 0)))
              (update :deferred-yield + (long (get deferred :realized-yield 0))
                                         (long (get deferred :deferred-yield 0))))

          (some pos? (vals haircut))
          (update :haircut-yield + (reduce + 0 (vals haircut)))))))


(defn apply-partial-fill
  "Apply a partial-fill settlement to the world state, updating both the
   position and any relevant world-level accounting.

   Returns updated world."
  [world position decision]
    (let [owner-id (or (:owner/id position) (-> (pos/position-identity position) second))
        updated-pos (post-partial-fill-position position decision)
        filled-total (reduce + 0 (vals (:filled decision)))
        token (or (:token position) (get-in position [:position/id 3]))]
    (-> world
        (assoc-in [:yield/positions owner-id] updated-pos)
        (update-in [:total-held (name token)] #(- (or % 0) filled-total)))))
