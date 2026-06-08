(ns resolver-sim.yield.exact-math
  "Exact integer and ratio arithmetic for yield accrual, partial-fill ratios,
   shortfall ratios, and rounding-drift analysis.

   Principles:
   - Economic amounts, APYs, indices, available ratios, and fill ratios are
     represented as exact ratios internally.
   - Externally settled token amounts are integer base units.
   - Quantization rounds down (floor) and preserves exact fractional remainders
     for carry-forward across accrual intervals.
   - All arithmetic is deterministic and reproducible.")


(def seconds-per-year 31536000)

(def scaling-factor 10000)


(defn bps->ratio
  "Convert basis points to an exact ratio. 10% = 1000 bps = 1000/10000 = 1/10."
  [bps]
  (/ (long bps) scaling-factor))


(defn ratio->bps
  "Convert a ratio back to approximate bps (truncated)."
  [r]
  (long (Math/floor (* (double r) scaling-factor))))


(defn index-growth-factor
  "Compute the exact multiplicative factor for index growth over dt seconds
   given `apy-bps` (integer basis points).

   factor = 1 + (apy-bps/10000) * (dt / seconds-per-year)
          = (10000 * seconds-per-year + apy-bps * dt) / (10000 * seconds-per-year)

   Returns a ratio."
  [apy-bps dt]
  (let [bps (long apy-bps)
        dt  (long dt)]
    (/ (+ (* scaling-factor seconds-per-year) (* bps dt))
       (* scaling-factor seconds-per-year))))


(defn ratio
  "Coerce a value to a Clojure ratio. Accepts long, ratio, double, or nil.
   nil defaults to 1/1. Uses a denominator of 1 and numerator of the value
   cast via bigint to force Ratio representation, ensuring numerator/denominator
   always work. For integer zero, returns 0/1 (a true Ratio rather than 0N)."
  [x]
  (cond
    (ratio? x)                     x
    (integer? x)                   (clojure.lang.Ratio. (biginteger (long x)) (biginteger 1))
    (float? x)                     (rationalize (double x))
    (instance? Number x)           (rationalize (double x))
    (nil? x)                       (clojure.lang.Ratio. (biginteger 1) (biginteger 1))
    :else                          (throw (ex-info "Cannot coerce to ratio"
                                                  {:value x :type (type x)}))))


(defn next-index
  "Compute the next index after accrual: new-index = index * growth-factor.

   All arguments and result are exact ratios."
  [index apy-bps dt]
  (* (ratio index) (index-growth-factor apy-bps dt)))


(defn index-from-bps-and-dt
  "Convenience: compute next-index starting from 1.0 with given bps and dt."
  [apy-bps dt]
  (next-index 1 apy-bps dt))


(defn current-value-exact
  "Compute the exact current value of a position in underlying token units
   (as a ratio): shares × current-index.

   `shares` and `current-index` should be ratios."
  [shares current-index]
  (* (ratio shares) (ratio current-index)))


(defn principal-from-shares-and-index
  "Reverse-calculate principal from shares and entry-index.
   principal = shares × entry-index (exact ratio)."
  [shares entry-index]
  (* (ratio shares) (ratio entry-index)))


(defn shares-from-principal-and-index
  "Calculate shares from principal and entry-index.
   shares = principal / entry-index (exact ratio)."
  [principal entry-index]
  (/ (ratio principal) (ratio entry-index)))


(defn- ratio-num
  "Safe numerator accessor: returns the numerator of a Ratio, or the integer
   itself for non-Ratio numbers."
  [r]
  (if (ratio? r) (.numerator ^clojure.lang.Ratio r) (biginteger r)))

(defn- ratio-den
  "Safe denominator accessor: returns the denominator of a Ratio, or 1N for
   non-Ratio numbers."
  [r]
  (if (ratio? r) (.denominator ^clojure.lang.Ratio r) 1))

(defn quantize-base-units
  "Floor a ratio to integer base units and return [base-units remainder].

   base-units = floor(ratio)
   remainder  = ratio - base-units  (always 0 <= remainder < 1)

   Returns [long remainder-ratio]."
  [r]
  (let [r (ratio r)
        n (ratio-num r)
        d (ratio-den r)
        units (long (quot n d))
        rem (- r units)]
    [units rem]))


(defn quantize-with-carry
  "Quantize a ratio into base units, accumulating the fractional remainder
   with a prior carry.

   Returns {:units long :carry ratio} where carry is the new accumulated
   fractional remainder to carry forward to the next interval.

   The prior-carry is added to the value before quantization, so sub-unit
   accrual accumulates across intervals until it crosses one claimable unit."
  [value prior-carry]
  (let [total (+ (ratio value) (ratio prior-carry))
        [units rem] (quantize-base-units total)]
    {:units units
     :carry rem
     :exact-total total}))


(defn floor-and-carry-alloc
  "Allocate `total-available` across `claims` using floor-and-carry policy.

   Each claim is a map with at least `:amount` (ratio or long).
   Returns claims with `:filled` (long base units) and allocator-level
   `:carry` (exact remainder preserved for next round).

   Never allocates more than total-available in base units."
  [total-available claims]
  (let [available (ratio total-available)
        total-amount (reduce + 0 (map (comp ratio :amount) claims))]
    (if (zero? (double total-amount))
      {:allocations (mapv #(assoc % :filled 0 :ideal-exact 0 :remainder-exact 0) claims)
       :total-available-units (first (quantize-base-units available))
       :total-allocated-units 0
       :shortage-units 0
       :carry 0}
      (let [ideal (mapv (fn [claim] (* available (/ (ratio (:amount claim)) total-amount)))
                        claims)
            [units rems] (reduce (fn [[us rs] ideal-i]
                                   (let [[u r] (quantize-base-units ideal-i)]
                                     [(conj us u) (conj rs r)]))
                                 [[] []]
                                 ideal)
            sum-units (reduce + 0 units)
            available-units (first (quantize-base-units available))
            shortage (- available-units sum-units)
            carry (reduce + 0 rems)]
        {:allocations (mapv (fn [claim u r ideal-i]
                              (assoc claim :filled u :ideal-exact ideal-i :remainder-exact r))
                            claims units rems ideal)
         :total-available-units available-units
         :total-allocated-units sum-units
         :shortage-units shortage
         :carry (ratio carry)}))))


(defn largest-remainder-alloc
  "Allocate `total-available` across `claims` using largest-remainder method.

   Each claim gets its floor allocation, then remaining units are distributed
   one at a time to claims with the largest fractional remainders.

   Guarantees: sum(filled) = floor(total-available) in base units.
   Never allocates more than total-available."
  [total-available claims]
  (let [total (ratio total-available)
        total-units (first (quantize-base-units total))
        n (count claims)
        claims-total (reduce + 0 (map (comp ratio :amount) claims))
        ideal (mapv (fn [c] (* total (/ (ratio (:amount c)) (ratio (or (:basis c) claims-total)))))
                    claims)
        indexed (map-indexed (fn [i c] (assoc c :idx i :ideal (nth ideal i))) claims)
        [units rems] (reduce (fn [[us rs] idx-i]
                               (let [[u r] (quantize-base-units (:ideal idx-i))]
                                 [(conj us u) (conj rs r)]))
                             [[] []]
                             indexed)
        sum-units (reduce + 0 units)
        shortage (- total-units sum-units)
        indices-by-rem (->> (range n)
                            (sort-by #(- (nth rems %)))
                            (take shortage))
        final-units (reduce (fn [us i] (update us i inc))
                            (vec units)
                            indices-by-rem)]
    {:allocations (mapv (fn [claim u r ideal-v]
                          (assoc claim :filled u :remainder-exact r :ideal-exact ideal-v))
                        claims final-units rems ideal)
     :total-available-units total-units
     :total-allocated-units (reduce + 0 final-units)
     :shortage-units (- total-units (reduce + 0 final-units))
     :carry 0}))


(defn principal-protective-floor-alloc
  "Allocate `total-available` across claims, protecting principal claims first.

   Principal claims receive their full floor allocation before any yield
   claims are filled, capped at total-available. When multiple principal
   claims exceed available liquidity, they are pro-rated via floor-and-carry.
   Within yield claims, largest-remainder applies.

   `principal-pred` is a fn on claim returning truthy for principal claims."
  [total-available claims principal-pred]
  (let [total (ratio total-available)
        total-units (first (quantize-base-units total))
        principal-claims (filterv principal-pred claims)
        yield-claims (filterv (complement principal-pred) claims)
        principal-requested (reduce + 0 (map (comp first quantize-base-units ratio :amount) principal-claims))
        principal-allocated (min total-units principal-requested)
        available-for-yield (max 0 (- total-units principal-allocated))
        principal-alloc (if (seq principal-claims)
                          (if (<= principal-requested total-units)
                            (mapv (fn [c] (assoc c :filled (first (quantize-base-units (ratio (:amount c))))
                                                   :remainder-exact 0 :ideal-exact (ratio (:amount c))))
                                  principal-claims)
                            (:allocations (floor-and-carry-alloc total-units principal-claims)))
                          [])
        yield-alloc (if (and (seq yield-claims) (pos? available-for-yield))
                      (:allocations (largest-remainder-alloc available-for-yield yield-claims))
                      (mapv #(assoc % :filled 0 :remainder-exact 0 :ideal-exact 0) yield-claims))]
    {:allocations (into (vec principal-alloc) (vec yield-alloc))
     :total-available-units total-units
     :total-allocated-units (+ (reduce + 0 (map :filled principal-alloc))
                               (reduce + 0 (map :filled yield-alloc)))
     :shortage-units (- total-units (+ (reduce + 0 (map :filled principal-alloc))
                                       (reduce + 0 (map :filled yield-alloc))))
     :carry 0}))


(defn adversarial-rounding
  "Round in the worst-case direction for testing rounding-debt conservation.
   All fractional remainders round toward the adversary (up), producing a
   positive rounding debt that must equal the sum of fractional remainders.

   This is only for test purposes; it violates the 'never exceeds available'
   constraint by design."
  [total-available claims]
  (let [total (ratio total-available)
        total-units (first (quantize-base-units total))
        claims-total (reduce + 0 (map (comp ratio :amount) claims))
        ideal (mapv (fn [c] (* total (/ (ratio (:amount c)) (ratio (or (:basis c) claims-total)))))
                    claims)
        filled (mapv (fn [i] (long (Math/ceil (double i)))) ideal)
        sum-filled (reduce + 0 filled)
        rounding-debt (- sum-filled total-units)]
    {:allocations (mapv (fn [claim f i]
                          (assoc claim :filled f :ideal-exact i :remainder-exact (- (ratio f) i)))
                        claims filled ideal)
     :total-available-units total-units
     :total-allocated-units sum-filled
     :shortage-units (- total-units sum-filled)
     :rounding-debt rounding-debt
     :carry 0}))


(defn apy-degradation
  "Apply stale-oracle APY degradation.

   Given base-apy-bps and stale-seconds, compute the effective APY:
   - If base is positive: decay toward a configurable floor (default 0)
   - If base is negative: do not make it less conservative (return base unchanged)

   Degradation factor: max(0, 1 - stale-seconds / max-stale-seconds)
   Effective = floor-apy-bps + (base-apy-bps - floor-apy-bps) * degradation

   All values are exact integer bps; result is long bps."
  [base-apy-bps stale-seconds max-stale-seconds floor-apy-bps]
  (let [base  (long base-apy-bps)
        stale (long stale-seconds)
        max-s (long max-stale-seconds)
        floor (long (or floor-apy-bps 0))]
    (if (<= base 0)
      base
      (let [degradation (max 0 (/ (- max-s stale) max-s))
            effective (+ floor (long (* (- base floor) (double degradation))))]
        (max floor (min base effective))))))


(defn shortfall-ratio-exact
  "Compute the exact shortfall ratio: available / needed, capped at 1.0."
  [available needed]
  (if (zero? needed)
    1
    (min 1 (/ (ratio available) (ratio needed)))))


(defn split-amount-exact
  "Split an amount into [realized unrealized deferred haircut] based on
   ratios. Returns a map with exact ratio values."
  [total realized-ratio unrealized-ratio deferred-ratio haircut-ratio]
  (let [t (ratio total)]
    {:realized   (* t (ratio realized-ratio))
     :unrealized (* t (ratio unrealized-ratio))
     :deferred   (* t (ratio deferred-ratio))
     :haircut    (* t (ratio haircut-ratio))}))


(defn ratio->json
  "Serialize a ratio for JSON output as {:num \"...\" :den \"...\"}."
  [r]
  (let [r (ratio r)]
    {:num (str (numerator r))
     :den (str (denominator r))}))

