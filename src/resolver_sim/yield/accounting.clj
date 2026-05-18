(ns resolver-sim.yield.accounting
  "[SEW-INTEGRATED ACCOUNTING SUBSTRATE]

   Provides reusable arithmetic/reconciliation mechanics for yield-related
   balances and accrual transformations.

   Boundary note:
   - operation semantics and interpretation remain adapter-owned,
   - current integration and field assumptions are aligned to SEW world shape.")

(defn update-position-yield
  "Update unrealized yield for a position based on new index/price.
   Supports both share-based and exchange-rate based accounting."
  [position current-index]
  (let [entry-index (:entry-index position 1.0)
        shares      (:shares position 0)
        principal   (:principal position 0)
        ;; For share-based (like Aave aTokens):
        ;; value = shares * current-index
        ;; yield = value - principal
        current-value (* shares current-index)
        unrealized    (long (Math/floor (double (max 0 (- current-value principal)))))]
    (assoc position :unrealized-yield unrealized)))

(defn realize-yield
  "Move unrealized yield to realized-yield. Usually called during crystallization."
  [position]
  (let [unrealized (:unrealized-yield position 0)]
    (-> position
        (update :realized-yield + unrealized)
        (assoc :unrealized-yield 0))))
