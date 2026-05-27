(ns resolver-sim.yield.accounting
  "[Sew-INTEGRATED ACCOUNTING SUBSTRATE]

   Provides reusable arithmetic/reconciliation mechanics for yield-related
   balances and accrual transformations.

   Boundary note:
   - operation semantics and interpretation remain adapter-owned,
   - current integration and field assumptions are aligned to Sew world shape.")

(def ^:private default-asset-decimals 18)

(defn token-decimals
  "Resolve token decimals from world metadata.
   Falls back to 18 when unspecified."
  [world token]
  (long (or (get-in world [:token/decimals token])
            (get-in world [:yield/token-decimals token])
            default-asset-decimals)))

(defn floor-to-asset-decimals
  "Floor numeric amount to token precision (base units).
   For integer-denominated base units this is a no-op, but centralizing this
   function guarantees one deterministic rounding policy for all materialization
   boundaries."
  [amount _decimals]
  (long (Math/floor (double (max 0 amount)))))

(defn update-position-yield
  "Update unrealized yield for a position based on new index/price.
   Supports both share-based and exchange-rate based accounting."
  ([position current-index]
   (update-position-yield nil position current-index))
  ([world position current-index]
  (let [entry-index (:entry-index position 1.0)
        shares      (:shares position 0)
        principal   (:principal position 0)
        token       (:token position)
        decimals    (token-decimals world token)
        ;; For share-based (like Aave aTokens):
        ;; value = shares * current-index
        ;; yield = value - principal
        current-value (* shares current-index)
        unrealized    (floor-to-asset-decimals (max 0 (- current-value principal)) decimals)]
    (assoc position :unrealized-yield unrealized))))

(defn realize-yield
  "Move unrealized yield to realized-yield. Usually called during crystallization."
  ([position]
   (realize-yield nil position))
  ([world position]
  (let [token       (:token position)
        unrealized  (:unrealized-yield position 0)]
    (-> position
        (update :realized-yield + unrealized)
        (assoc :unrealized-yield 0)))))

(defn apply-liquidity-stress
  "Calculates potential shortfall and haircuts based on world risk parameters.
   Returns a map with :fulfilled and :shortfall (if any)."
  [world module-id token amount]
  (let [risk (get-in world [:yield/risk module-id token] {})
        mode (:liquidity-mode risk :available)]
    (case mode
      :available {:fulfilled amount :shortfall nil}
      :shortfall (let [ratio (double (get-in risk [:shortfall :available-ratio] 0.5))
                       fulfilled (long (Math/floor (* amount ratio)))
                       deferred  (- amount fulfilled)]
                   {:fulfilled fulfilled
                    :shortfall {:reason :liquidity-shortfall
                                :available-ratio ratio
                                :fulfilled-amount fulfilled
                                :deferred-amount deferred
                                :haircut-amount 0}})
      :haircut   (let [ratio (double (get-in risk [:haircut :loss-ratio] 0.1))
                       loss  (long (Math/floor (* amount ratio)))
                       fulfilled (- amount loss)]
                   {:fulfilled fulfilled
                    :shortfall {:reason :permanent-loss
                                :fulfilled-amount fulfilled
                                :deferred-amount 0
                                :haircut-amount loss}})
      ;; Default to hard block if mode is unrecognized/extreme (frozen, paused)
      {:fulfilled 0 :shortfall {:reason mode :fulfilled-amount 0 :deferred-amount amount :haircut-amount 0}})))

(defn claim-deferred
  "Attempts to reclaim deferred funds from a position in :unwinding status.
   If liquidity-mode is :available, transitions position to :withdrawn and returns reclaimed amount."
  [world module-id position]
  (let [token (:token position)
        risk  (get-in world [:yield/risk module-id token] {})
        mode  (:liquidity-mode risk :available)
        shortfall (:shortfall position)]
    (if (and (= mode :available) shortfall)
      (let [reclaimed (:deferred-amount shortfall 0)]
        (-> position
            (assoc :status :withdrawn)
            (assoc :shortfall nil)
            (update :realized-yield + reclaimed)
            (assoc :reclaimed-amount reclaimed)))
      position)))
