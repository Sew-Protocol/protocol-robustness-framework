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
  (:require [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.util.evidence :as util-evidence]
            [resolver-sim.io.event-evidence :as evidence]))

(def ^:private schema-version (evcfg/schema :partial-fill-decision))

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

(defn- normalize-token [token]
  (tok/normalize token))

(defn- sum-requested
  [requested]
  (reduce + 0 (map long (vals requested))))

(defn- items-from-rows
  "Convert rows vector into payoffs-compatible items with weight and cap."
  [rows]
  (mapv (fn [r]
          {:id (:key r)
           :weight (or (:weight r) (long (:owed r)))
           :cap (let [c (:cap r)]
                  (if (some? c) (min (long (:owed r)) c) (long (:owed r))))})
        rows))

(defn- make-evidence
  [policy available-liquidity total-requested shortage fill-mode & [extra]]
  (merge {:schema-version schema-version
          :available-liquidity available-liquidity
          :total-requested total-requested
          :shortage shortage
          :fill-mode fill-mode
          :rounding-policy (:rounding-policy policy)
          :fill-order (:fill-order policy)}
         (when extra (assoc extra :allocation-rows (:rows extra)))))

(defn- row-evidence
  "Build a row-evidence entry from a row map and a filled-amount lookup.
   Includes fill-ratio (filled/owed as a double) for verifiability."
  [row filled]
  (let [k (:key row)
        owed (long (:owed row))
        f (long (get filled k 0))
        d (max 0 (- owed f))
        cap (:cap row)
        effective-cap (if (some? cap) (min owed cap) owed)]
    {:key k
     :owed owed
     :weight (or (:weight row) owed)
     :cap (when cap (long cap))
     :filled f
     :deferred d
     :fill-ratio (if (pos? owed) (double (/ f owed)) 0.0)
     :cap-hit? (if (some? cap) (>= f effective-cap) false)}))

(defn calculate-fulfillment-pro-rata
  "Pro-rata fill: each claim bucket receives a proportional share of the available
   liquidity. Exact ratios computed, then quantized via configured rounding policy.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation. When absent, weight and cap are both
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [rows (:rows opts)
        total (if rows
                (reduce + 0 (map #(long (:owed %)) rows))
                (sum-requested requested))
        shortage (max 0 (- total available-liquidity))]
    (if (and (zero? shortage) (not rows))
      ;; Full-fill: only when no rows (backward compat) or shortage is truly zero.
      ;; When rows are present with caps, always go through the capped allocator
      ;; to respect per-row caps even when total liquidity is sufficient.
      {:settlement-mode :full-fill
       :requested requested
       :filled requested
       :deferred {}
       :haircut {}
       :unrealized {}
       :policy policy
       :evidence (make-evidence policy available-liquidity total 0 :pro-rata)}
      (if rows
        ;; Decoupled weight/cap path via payoffs allocator with redistribution.
        ;; Effective cap = min(owed, cap) so the allocator enforces both limits.
        (let [items (items-from-rows rows)
              rounding-policy (:rounding-policy policy :floor-and-carry)
              alloc (payoffs/allocate-pro-rata-with-redistribution
                     {:amount available-liquidity
                      :items items
                      :id-fn :id
                      :weight-fn :weight
                      :cap-fn :cap
                      :rounding (if (#{:floor :floor-with-largest-remainder} rounding-policy)
                                  rounding-policy
                                  :floor-with-largest-remainder)})
              filled (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations alloc)))
              row-evidence (mapv #(row-evidence % filled) rows)
              deferred (into {} (map (fn [r] [(:key r) (:deferred r)]) row-evidence))]
          {:settlement-mode :partial-fill
           :requested (into {} (map (fn [r] [(:key r) (long (:owed r))]) rows))
           :filled filled
           :deferred deferred
           :haircut {}
           :unrealized {}
           :policy policy
           :evidence (assoc (make-evidence policy available-liquidity total shortage :pro-rata)
                            :allocation-detail (select-keys alloc [:total-allocated :total-unmet :remainder])
                            :allocation-rows row-evidence
                            :redistribution (:redistribution alloc))})
        ;; Backward compatible path: derive weight/cap from requested
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
                                                                   :carry]))})))))

(defn calculate-fulfillment-principal-first
  "Principal-first fill: principal claims are satisfied in full before any
   yield claims are filled.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation. When absent, weight and cap are both
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [rows (:rows opts)]
    (if rows
      ;; Decoupled rows path
      (let [principal-row (first (filter #(= :principal (:key %)) rows))
            yield-rows (remove #(= :principal (:key %)) rows)
            principal-owed (long (if principal-row (:owed principal-row) 0))
            principal-cap (:cap principal-row)
            principal-effective-cap (if (some? principal-cap)
                                      (min principal-owed principal-cap)
                                      principal-owed)
            principal-filled (min principal-effective-cap available-liquidity)
            principal-deferred (max 0 (- principal-owed principal-filled))
            remaining (- available-liquidity principal-filled)
            yield-total (reduce + 0 (map #(long (:owed %)) yield-rows))
            total (+ principal-owed yield-total)
            shortage (max 0 (- total available-liquidity))]
        ;; When rows are present, always use the capped path
        ;; (full-fill shortcut would ignore per-row caps)
        (let [yield-items (items-from-rows yield-rows)
              rounding-policy (:rounding-policy policy :floor-and-carry)
              yield-alloc (when (and (pos? remaining) (seq yield-items))
                            (payoffs/allocate-pro-rata-with-redistribution
                             {:amount remaining
                              :items yield-items
                              :id-fn :id :weight-fn :weight :cap-fn :cap
                              :rounding (if (#{:floor :floor-with-largest-remainder} rounding-policy)
                                          rounding-policy
                                          :floor-with-largest-remainder)}))
              yield-filled (when yield-alloc
                             (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations yield-alloc))))
              filled (cond-> {}
                       (pos? principal-filled)
                       (assoc :principal principal-filled)
                       yield-filled
                       (merge yield-filled))
              row-evidence (mapv #(row-evidence % filled) rows)
              deferred (into {} (map (fn [r] [(:key r) (:deferred r)]) row-evidence))]
          {:settlement-mode :partial-fill
           :requested (into {} (map (fn [r] [(:key r) (long (:owed r))]) rows))
           :filled filled
           :deferred deferred
           :haircut {}
           :unrealized {}
           :policy policy
           :evidence (assoc (make-evidence policy available-liquidity total shortage :principal-first)
                            :allocation-rows row-evidence
                            :allocation-detail (when yield-alloc
                                                 (select-keys yield-alloc [:total-allocated :total-unmet :remainder]))
                            :redistribution (:redistribution yield-alloc))}))
      ;; Backward compatible path (no explicit rows)
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
             :evidence (make-evidence policy available-liquidity (+ principal-requested yield-total) shortage :principal-first)}))))))

(defn- waterfall-allocate-rows
  "Core waterfall-with-rows allocation.
   Processes buckets in fill-order, allocating to each bucket's rows
   respecting per-row caps, with remaining liquidity flowing to the next bucket.
   Returns {:filled {} :row-evidence [] :redistributions [{:bucket k :redistribution m}]}."
  [available-liquidity rows fill-order policy]
  (let [rounding-policy (:rounding-policy policy :floor-and-carry)
        bucket->rows (group-by :key rows)]
    (loop [remaining available-liquidity
           filled {}
           all-row-evidence []
           all-redistributions []
           buckets fill-order]
      (if (empty? buckets)
        {:filled filled
         :row-evidence (vec all-row-evidence)
         :redistributions all-redistributions}
        (if (zero? remaining)
           ;; Remaining buckets' rows included as zero-filled evidence
          (let [remaining-rows (mapcat #(get bucket->rows % []) buckets)
                zero-evidence (mapv (fn [r] (row-evidence r {})) remaining-rows)]
            {:filled filled
             :row-evidence (vec (into all-row-evidence zero-evidence))
             :redistributions all-redistributions})
           ;; Else branch: process the current bucket
          (let [bucket (first buckets)
                bucket-rows (get bucket->rows bucket [])
                bucket-total (reduce + 0
                                     (map (fn [r]
                                            (let [c (:cap r)]
                                              (if (some? c)
                                                (min (long (:owed r)) c)
                                                (long (:owed r)))))
                                          bucket-rows))]
            (if (zero? bucket-total)
              (recur remaining filled all-row-evidence all-redistributions (rest buckets))
              (let [bucket-items (items-from-rows bucket-rows)
                    bucket-alloc (payoffs/allocate-pro-rata-with-redistribution
                                  {:amount (min remaining bucket-total)
                                   :items bucket-items
                                   :id-fn :id :weight-fn :weight :cap-fn :cap
                                   :rounding (if (#{:floor :floor-with-largest-remainder} rounding-policy)
                                               rounding-policy
                                               :floor-with-largest-remainder)})
                    bucket-filled (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations bucket-alloc)))
                    bucket-filled-total (reduce + 0 (vals bucket-filled))
                    bucket-row-evidence (mapv #(row-evidence % bucket-filled) bucket-rows)]
                (recur (- remaining bucket-filled-total)
                       (merge filled bucket-filled)
                       (into all-row-evidence bucket-row-evidence)
                       (conj all-redistributions {:bucket bucket
                                                  :redistribution (:redistribution bucket-alloc)})
                       (rest buckets))))))))))

(defn calculate-fulfillment-waterfall
  "Waterfall fill: claims are satisfied in strict fill-order priority.
   Each bucket in :fill-order is filled to exhaustion before moving to the next.
   Exact amounts are quantized via configured rounding policy.

   Optional opts:
     :rows — vector of {:key k :owed v :weight w :cap c} for decoupled
             weight/cap allocation per bucket. When absent, weight and cap are
             derived from the requested amount (existing behavior)."
  [available-liquidity requested policy & [opts]]
  (let [fill-order (:fill-order policy [:principal :realized-yield :deferred-yield])
        rows (:rows opts)
        total (if rows
                (reduce + 0 (map #(long (:owed %)) rows))
                (sum-requested requested))
        shortage (max 0 (- total available-liquidity))]
    (if rows
      ;; Rows-with-caps path: always go through the capped allocator
      ;; to respect per-row caps even when total liquidity is sufficient
      (let [result (waterfall-allocate-rows available-liquidity rows fill-order policy)
            row-evidence (:row-evidence result)
            filled (:filled result)
            deferred (into {} (map (fn [r] [(:key r) (:deferred r)]) row-evidence))
            processed-keys (set (keys filled))
            all-row-keys (set (map :key rows))
            unprocessed (clojure.set/difference all-row-keys processed-keys)
            deferred (reduce (fn [acc r]
                               (assoc acc (:key r) (long (:owed r))))
                             deferred
                             (filter #(contains? unprocessed (:key %)) rows))
            settlement-mode (if (zero? shortage) :full-fill :partial-fill)]
        {:settlement-mode settlement-mode
         :requested (into {} (map (fn [r] [(:key r) (long (:owed r))]) rows))
         :filled filled
         :deferred deferred
         :haircut {}
         :unrealized {}
         :policy policy
         :evidence (merge (make-evidence policy available-liquidity total shortage :waterfall)
                          {:allocation-rows row-evidence
                           :bucket-redistributions (:redistributions result)})})
      ;; Backward compatible path (no explicit rows)
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
                     (rest buckets)))))))))

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
         :pro-rata        (calculate-fulfillment-pro-rata available requested policy
                                                          (select-keys opts [:rows]))
         :principal-first (calculate-fulfillment-principal-first available requested policy
                                                                 (select-keys opts [:rows]))
         :waterfall       (calculate-fulfillment-waterfall available requested policy
                                                           (select-keys opts [:rows])))))))

(defn partial-fill?
  "True if the settlement decision represents a partial fill."
  [decision]
  (= :partial-fill (:settlement-mode decision)))

(defn decision-artifact
  "Build a stable first-class artifact for a partial-fill settlement decision.
   The artifact is content-addressed so downstream consumers can link the same
   decision across world state, snapshots, and evidence."
  ([position decision]
   (decision-artifact position decision {}))
  ([position decision {:keys [decision-source]
                       :or {decision-source :yield-withdraw}}]
   (let [owner-id (or (:owner/id position) (-> (pos/position-identity position) second))
         token (normalize-token (or (:token position) (get-in position [:position/id 3])))
         base {:schema-version schema-version
               :artifact/kind :yield/partial-fill-decision
               :decision/source decision-source
               :position/id owner-id
               :module/id (:module/id position)
               :token token
               :settlement-mode (:settlement-mode decision)
               :requested (:requested decision)
               :filled (:filled decision)
               :deferred (:deferred decision)
               :haircut (:haircut decision)
               :unrealized (:unrealized decision)
               :policy (:policy decision)
               :evidence (:evidence decision)}
         decision-hash (str "sha256:"
                            (hc/hash-with-intent {:hash/intent :evidence-record}
                                                 base))]
     (assoc base
            :decision/id (str "partial-fill-" (subs decision-hash 7 (min (count decision-hash) 23)))
            :decision/hash decision-hash))))

(defn attach-decision-artifact
  "Attach a partial-fill decision artifact to world state under a stable map."
  [world artifact]
  (assoc-in world [:yield/partial-fill-decisions (:decision/id artifact)] artifact))

(defn- sum-long-values
  [m]
  (reduce + 0 (map long (vals (or m {})))))

(defn- positive-requested-claims
  [decision]
  (->> (:requested decision)
       (filter (fn [[_ v]] (pos? (long v))))
       (into {})))

(defn- check-result
  [check-id status details]
  {:check/id check-id
   :status status
   :details details})

(defn partial-fill-closed-form-checks
  "Research-grade closed-form criteria for a partial-fill decision.

   Checks:
   - :partial-fill/conservation
   - :partial-fill/capacity-bound
   - :partial-fill/per-claim-bound
   - :partial-fill/per-claim-conservation
   - :partial-fill/claim-key-consistency
   - :partial-fill/pro-rata-cross-product
   - :partial-fill/principal-first-priority
   - :partial-fill/waterfall-priority
   - :partial-fill/rounding-residual-bounded

   These checks operate on the structured decision returned by
   calculate-fulfillment*. They intentionally stay local to the decision
   map and do not infer broader replay semantics."
  [decision]
  (let [requested (:requested decision)
        filled (:filled decision)
        deferred (:deferred decision)
        haircut (:haircut decision)
        policy (:policy decision)
        mode (:mode policy)
        available (long (get-in decision [:evidence :available-liquidity] 0))
        total-requested (sum-long-values requested)
        total-filled (sum-long-values filled)
        total-deferred (sum-long-values deferred)
        total-haircut (sum-long-values haircut)
        positive-claims (positive-requested-claims decision)
        eligible-claim-count (count positive-claims)
        residual (- available total-filled)
        conservation-ok? (= total-requested (+ total-filled total-deferred total-haircut))
        capacity-ok? (<= total-filled available)
        per-claim-violations
        (->> positive-claims
             (keep (fn [[k claim]]
                     (let [f (long (get filled k 0))]
                       (when (> f (long claim))
                         {:claim k :claim-amount (long claim) :filled f}))))
             vec)
        per-claim-conservation-violations
        (->> (set (concat (keys requested) (keys filled) (keys deferred) (keys haircut)))
             (keep (fn [k]
                     (let [r (long (get requested k 0))
                           f (long (get filled k 0))
                           d (long (get deferred k 0))
                           h (long (get haircut k 0))
                           total (+ f d h)]
                       (when (not= r total)
                         {:claim k
                          :requested r
                          :filled f
                          :deferred d
                          :haircut h
                          :recovered-sum total}))))
             vec)
        pro-rata-pairs
        (when (= :pro-rata mode)
          (->> positive-claims
               keys
               sort
               vec))
        pro-rata-violations
        (if (= :pro-rata mode)
          (->> (for [i (range (count pro-rata-pairs))
                     j (range (inc i) (count pro-rata-pairs))]
                 (let [ki (nth pro-rata-pairs i)
                       kj (nth pro-rata-pairs j)
                       claim-i (long (get positive-claims ki 0))
                       claim-j (long (get positive-claims kj 0))
                       filled-i (long (get filled ki 0))
                       filled-j (long (get filled kj 0))]
                   (when (and (pos? claim-i)
                              (pos? claim-j)
                              (not= (* filled-i claim-j)
                                    (* filled-j claim-i)))
                     {:left ki
                      :right kj
                      :left-cross (* filled-i claim-j)
                      :right-cross (* filled-j claim-i)})))
               (remove nil?)
               vec)
          [])
        rounding-policy (:rounding-policy policy :floor-and-carry)
        rounding-applicable? (#{:floor-and-carry :floor :largest-remainder :principal-protective-floor} rounding-policy)
        residual-ok? (case rounding-policy
                       (:floor-and-carry :floor :principal-protective-floor)
                       (and (<= 0 residual)
                            (< residual (max 1 eligible-claim-count)))
                       :largest-remainder
                       (zero? residual)
                       false)
        claim-key-consistency-violations
        (->> (set (concat (keys filled) (keys deferred) (keys haircut)))
             (keep (fn [k]
                     (when (not (contains? requested k))
                       {:key k
                        :source (cond (contains? filled k) :filled
                                      (contains? deferred k) :deferred
                                      :else :haircut)})))
             vec)
        principal-first-violations
        (when (= :principal-first mode)
          (let [principal-requested (long (get requested :principal 0))
                principal-filled (long (get filled :principal 0))
                yield-keys (remove #{:principal} (keys filled))]
            (->> yield-keys
                 (keep (fn [k]
                         (let [yf (long (get filled k 0))]
                           (when (and (pos? principal-requested)
                                      (pos? yf)
                                      (< principal-filled principal-requested))
                             {:claim k
                              :yield-filled yf
                              :principal-requested principal-requested
                              :principal-filled principal-filled}))))
                 vec)))
        waterfall-violations
        (when (= :waterfall mode)
          (let [fill-order (:fill-order policy [:principal :realized-yield :deferred-yield])]
            (->> (for [i (range (count fill-order))
                       j (range (inc i) (count fill-order))]
                   [i j])
                 (keep (fn [[i j]]
                         (let [higher (nth fill-order i)
                               lower (nth fill-order j)
                               higher-requested (long (get requested higher 0))
                               higher-filled (long (get filled higher 0))
                               lower-filled (long (get filled lower 0))]
                           (when (and (pos? higher-requested)
                                      (< higher-filled higher-requested)
                                      (pos? lower-filled))
                             {:higher-bucket higher
                              :higher-requested higher-requested
                              :higher-filled higher-filled
                              :lower-bucket lower
                              :lower-filled lower-filled}))))
                 vec)))]
    (let [conservation-ch (future
                            (check-result :partial-fill/conservation
                                          (if conservation-ok? :pass :fail)
                                          {:total-requested total-requested
                                           :total-filled total-filled
                                           :total-deferred total-deferred
                                           :total-haircut total-haircut}))
          capacity-ch (future
                        (check-result :partial-fill/capacity-bound
                                      (if capacity-ok? :pass :fail)
                                      {:available-liquidity available
                                       :total-filled total-filled}))
          per-claim-ch (future
                         (check-result :partial-fill/per-claim-bound
                                       (if (empty? per-claim-violations) :pass :fail)
                                       {:violations per-claim-violations}))
          cross-product-ch (future
                             (if (= :pro-rata mode)
                               (check-result :partial-fill/pro-rata-cross-product
                                             (if (empty? pro-rata-violations) :pass :fail)
                                             {:violations pro-rata-violations})
                               (check-result :partial-fill/pro-rata-cross-product
                                             :not-applicable
                                             {:mode mode})))
          residual-ch (future
                        (if rounding-applicable?
                          (check-result :partial-fill/rounding-residual-bounded
                                        (if residual-ok? :pass :fail)
                                        {:available-liquidity available
                                         :total-filled total-filled
                                         :residual residual
                                         :eligible-claim-count eligible-claim-count
                                         :rounding-policy rounding-policy})
                          (check-result :partial-fill/rounding-residual-bounded
                                        :not-applicable
                                        {:mode mode
                                         :rounding-policy rounding-policy
                                         :reason "no defined residual bound for this rounding policy"})))
          per-claim-conservation-ch (future
                                      (check-result :partial-fill/per-claim-conservation
                                                    (if (empty? per-claim-conservation-violations) :pass :fail)
                                                    {:violations per-claim-conservation-violations}))
          claim-key-ch (future
                         (check-result :partial-fill/claim-key-consistency
                                       (if (empty? claim-key-consistency-violations) :pass :fail)
                                       {:violations claim-key-consistency-violations}))
          principal-first-ch (future
                               (if (= :principal-first mode)
                                 (check-result :partial-fill/principal-first-priority
                                               (if (empty? principal-first-violations) :pass :fail)
                                               {:violations principal-first-violations})
                                 (check-result :partial-fill/principal-first-priority
                                               :not-applicable
                                               {:mode mode})))
          waterfall-ch (future
                         (if (= :waterfall mode)
                           (check-result :partial-fill/waterfall-priority
                                         (if (empty? waterfall-violations) :pass :fail)
                                         {:violations waterfall-violations})
                           (check-result :partial-fill/waterfall-priority
                                         :not-applicable
                                         {:mode mode})))]
      (mapv deref [conservation-ch capacity-ch per-claim-ch per-claim-conservation-ch
                   claim-key-ch cross-product-ch principal-first-ch waterfall-ch
                   residual-ch]))))

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
        raw-token (or (:token position) (get-in position [:position/id 3]))
        tok (normalize-token raw-token)]
    (-> world
        (assoc-in [:yield/positions owner-id] updated-pos)
        (update-in [:total-held tok] #(- (or % 0) filled-total)))))

(defn apply-partial-fill-with-attribution
  "Apply a partial-fill settlement to world state, wrapping the mutation in
   `with-attribution` so that downstream logging and risk monitoring can see
   the settlement details.

   Attribution context keys:
     :settlement/mode        — :full-fill or :partial-fill
     :settlement/filled      — total filled base units
     :settlement/deferred    — total deferred base units
     :settlement/haircut     — total haircut base units
     :settlement/shortage    — shortfall amount
     :settlement/module-id   — module-id
     :settlement/token       — token
     :settlement/position-id — owner-id"
  [world position decision]
  (let [ctx {:settlement/mode (:settlement-mode decision)
             :settlement/filled (reduce + 0 (vals (:filled decision)))
             :settlement/deferred (reduce + 0 (vals (:deferred decision)))
             :settlement/haircut (reduce + 0 (vals (:haircut decision)))
             :settlement/shortage (get-in decision [:evidence :shortage] 0)
             :settlement/module-id (:module/id position)
             :settlement/token (:token position)
             :settlement/position-id (:owner/id position)}]
    (attr/with-attribution ctx
      (let [world' (apply-partial-fill world position decision)]
        (evidence/capture-event-evidence!
         :settlement-fill
         {:settlement/before (select-keys world [:total-held :yield/positions])}
         {:settlement/after (select-keys world' [:total-held :yield/positions])}
         {:settlement/decision decision}
         nil
         {:world-before world
          :world-after world'})
        world'))))

(defn batch-partial-fill
  "Process multiple partial-fill settlements in parallel compute, serial apply.

   Args:
     world   — current world state
     inputs  — collection of
               {:available-liquidity <long>
                :position            <position map>
                :policy              <optional policy>
                :opts                <optional opts>}

   Returns updated world after all settlements applied.

   Parallel pattern:
   1. snapshot world
   2. parallel pure compute — calculate-fulfillment per input
   3. collect deterministic ordered decisions
   4. serial apply — apply-partial-fill-with-attribution per decision
   5. serial evidence capture (inside apply step)"
  [world inputs]
  (let [inputs (vec inputs)
        ;; 1: snapshot world (implicit — world is captured by closure)
        ;; 2: parallel pure compute — each fulfillment is independent
        decisions (util-evidence/contextual-pmap
                   (fn [{:keys [available-liquidity position policy opts]}]
                     (let [policy' (if (some? policy)
                                     (merge default-partial-fill-policy policy)
                                     default-partial-fill-policy)]
                       (calculate-fulfillment available-liquidity position policy' opts)))
                   inputs)
        ;; 3-4: collect deterministic ordered, serial apply to world
        pairs (map vector inputs decisions)]
    (reduce (fn [w [input decision]]
              (apply-partial-fill-with-attribution
               w (:position input) decision))
            world
            pairs)))
