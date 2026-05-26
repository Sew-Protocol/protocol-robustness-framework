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
        decimals    (token-decimals world token)
        unrealized  (floor-to-asset-decimals (:unrealized-yield position 0) decimals)]
    (-> position
        (update :realized-yield + unrealized)
        (assoc :unrealized-yield 0)))))
