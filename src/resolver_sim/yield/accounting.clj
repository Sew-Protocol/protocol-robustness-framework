(ns resolver-sim.yield.accounting
  "Standardized accounting substrate for yield-bearing assets.

   Provides reusable arithmetic and reconciliation mechanics for yield-related
   balances and accrual transformations.

   This substrate is designed to be portable across different protocol simulations."
  (:require [resolver-sim.yield.risk :as risk]))

(def ^:private default-asset-decimals 18)

(def liquidity-modes
  "Liquidity-mode keywords that block new deposits."
  #{:shortfall :frozen :paused})

(defn- normalize-token [token]
  (cond
    (keyword? token) token
    (string? token)  (keyword token)
    :else            token))

(defn- risk-map [world module-id token]
  (let [tok (normalize-token token)]
    (or (get-in world [:yield/risk module-id tok])
        (get-in world [:yield/risk module-id (name tok)])
        {})))

(defn token-decimals
  "Resolve token decimals from world metadata.
   Falls back to 18 when unspecified."
  [world token]
  (let [tok (normalize-token token)]
    (long (or (get-in world [:token/decimals tok])
              (get-in world [:token/decimals (name tok)])
              (get-in world [:yield/token-decimals tok])
              (get-in world [:yield/token-decimals (name tok)])
              default-asset-decimals))))

(defn floor-to-asset-decimals
  "Floor numeric amount to token precision (base units).

   Amounts are already in base units (integer token atoms). The `decimals`
   argument is reserved for future fractional-base-unit support; today it is
   unused but kept so all materialization boundaries share one rounding API."
  [amount _decimals]
  (long (Math/floor (double (max 0 amount)))))

(defn floor-to-asset-decimals-signed
  "Floor numeric amount to token precision while preserving sign.

   See `floor-to-asset-decimals` — `decimals` is reserved and currently unused."
  [amount _decimals]
  (long (Math/floor (double amount))))

(defn position-current-value
  "Redeemable position value in underlying token units.

   `current-share-price` is the generic scalar multiplier stored in world paths
   as `:yield/indices` — it may represent an Aave liquidity index, an ERC-4626
   share price, or another module exchange rate. Semantics are module-specific;
   the math is always `shares × price`.

   Shares are minted at deposit as `principal / entry-index` (see liquid-lending
   deposit); `entry-index` is not re-applied here."
  [position current-share-price]
  (* (:shares position 0) (double current-share-price)))

(defn- require-world-for-yield-update!
  [world position current-index]
  (when (nil? world)
    (throw (ex-info "world is required for yield risk/loss-mode evaluation"
                    {:fn 'update-position-yield
                     :position position
                     :current-index current-index}))))

(defn update-position-yield
  "Update unrealized yield from shares and the current share price / index.

   Invariant (share-price model, Model A):
     shares            = principal / entry-index   (at deposit)
     current-value     = shares × current-share-price
     unrealized-yield  = current-value − principal (floored; signed under :mark-to-market)

   The `current-index` argument is the current share price / exchange rate /
   liquidity index for this module — one scalar multiplier, module-specific name.

   `world` is required so loss-mode and token decimals resolve correctly."
  ([position current-index]
   (throw (ex-info "world is required"
                   {:fn 'update-position-yield
                    :position position
                    :current-index current-index})))
  ([world position current-index]
   (require-world-for-yield-update! world position current-index)
   (let [principal           (:principal position 0)
         token               (:token position)
         module-id           (:module/id position)
         decimals            (token-decimals world token)
         risk                (risk-map world module-id token)
         loss-mode           (risk/effective-loss-mode risk)
         current-share-price (double current-index)
         current-value       (long (Math/floor (position-current-value position current-share-price)))
         pnl                 (- current-value principal)
         unrealized          (if (= loss-mode :mark-to-market)
                               (floor-to-asset-decimals-signed pnl decimals)
                               (floor-to-asset-decimals pnl decimals))]
     (assoc position
            :current-index current-index
            :current-value current-value
            :unrealized-yield unrealized))))

(defn realize-yield
  "Move unrealized yield to realized-yield. Usually called during crystallization."
  ([position]
   (realize-yield nil position))
  ([_world position]
   (let [unrealized (:unrealized-yield position 0)]
     (-> position
         (update :realized-yield + unrealized)
         (assoc :unrealized-yield 0)))))

(defn apply-liquidity-stress
  "Calculates potential shortfall and haircuts based on world risk parameters.
   Returns a map with :fulfilled and :shortfall (if any)."
  [world module-id token amount]
  (let [risk (risk-map world module-id token)
        mode (risk/effective-liquidity-mode risk)]
    (case mode
      :available {:fulfilled amount :shortfall nil}
      :shortfall (let [ratio (double (get-in risk [:shortfall :available-ratio] 0.5))
                       fulfilled (long (Math/floor (* amount ratio)))
                       deferred  (- amount fulfilled)]
                   {:fulfilled fulfilled
                    :shortfall (merge {:reason (or (get-in risk [:shortfall :reason])
                                                   :liquidity-shortfall)
                                       :basis-amount amount
                                       :available-ratio ratio
                                       :fulfilled-amount fulfilled
                                       :deferred-amount deferred
                                       :haircut-amount 0}
                                      (select-keys (:shortfall risk {}) [:reason]))})
      :haircut   (let [ratio (double (get-in risk [:haircut :loss-ratio] 0.1))
                       loss  (long (Math/floor (* amount ratio)))
                       fulfilled (- amount loss)]
                   {:fulfilled fulfilled
                    :shortfall {:reason :permanent-loss
                                :basis-amount amount
                                :fulfilled-amount fulfilled
                                :deferred-amount 0
                                :haircut-amount loss}})
      ;; Default to hard block if mode is unrecognized/extreme (frozen, paused)
      {:fulfilled 0 :shortfall {:reason mode
                                :basis-amount amount
                                :fulfilled-amount 0
                                :deferred-amount amount
                                :haircut-amount 0}})))

(defn partial-yield-shortfall?
  "True when shortfall stress applied only to the yield leg (partial-liquidity).

   Identified by basis-amount strictly below position principal."
  [position shortfall]
  (and position shortfall
       (let [basis (long (or (:basis-amount shortfall) 0))
             principal (long (or (:principal position) 0))]
         (and (pos? principal) (< basis principal)))))

(defn apply-liquidity-stress-for-withdraw
  "Apply liquidity stress at withdrawal.

   Under :partial-liquidity, stress applies to the yield leg only; principal
   remains immediately available. Returns total :fulfilled (principal + liquid yield)."
  [world module-id token gross-amount principal]
  (let [risk (risk-map world module-id token)
        failure-modes (risk/normalize-failure-modes (:failure-modes risk))]
    (if (contains? failure-modes :partial-liquidity)
      (let [yield-portion (max 0 (- gross-amount principal))
            {:keys [fulfilled shortfall]}
            (apply-liquidity-stress world module-id token yield-portion)]
        {:fulfilled (+ principal fulfilled)
         :shortfall shortfall})
      (apply-liquidity-stress world module-id token gross-amount))))

(defn claim-deferred
  "Attempts to reclaim deferred funds from a position in :unwinding status.
   If liquidity-mode is :available, transitions position to :withdrawn and returns reclaimed amount."
  [world module-id position]
  (let [token (:token position)
        risk  (risk-map world module-id token)
        mode  (or (:liquidity-mode risk) :available)
        shortfall (:shortfall position)]
    (if (and (= mode :available) shortfall)
      (let [reclaimed (:deferred-amount shortfall 0)]
        (-> position
            (assoc :status :withdrawn)
            (assoc :shortfall nil)
            (update :realized-yield + reclaimed)
            (assoc :reclaimed-amount reclaimed)))
      position)))
