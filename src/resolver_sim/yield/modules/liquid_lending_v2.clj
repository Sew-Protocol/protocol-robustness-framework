(ns resolver-sim.yield.modules.liquid-lending-v2
  "Liquid-lending yield archetype using the new decision-based accrual engine.

   Replaces the legacy liquid_lending/accrue which used double-based arithmetic
   inline. This version calls accrual/accrual-decision + apply-accrual-decision
   for each position, and partial-fill/calculate-fulfillment for withdrawals.

   Module identity:
     :module/id arbitrary (e.g. :aave-v3-v2)
     :module/type :yield.profile/aave-v3-like-v2 (or :yield.provider/liquid-lending-v2)
     :accounting/type :shares"
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.loss :as loss]
            [resolver-sim.yield.evidence :as ye]
            [resolver-sim.yield.liquidity :as liquidity]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.util.attribution :as attr]))

(defn- normalize-token [token]
  (if (keyword? token) token (keyword (name token))))

(defn- token= [a b]
  (= (normalize-token a) (normalize-token b)))

(defn- get-in-token [world path module-id token & keys]
  (let [tok (normalize-token token)
        v   (or (get-in world (into path [module-id tok]))
                (get-in world (into path [module-id (name tok)])))]
    (if (seq keys)
      (get-in v keys)
      v)))

(defn- resolve-now [world]
  (:block-time world 0))


;; ---------------------------------------------------------------------------
;; deposit-v2
;; ---------------------------------------------------------------------------
(defn deposit-v2
  "Create a yield position using the new position model (ratio-based entry-index)."
  [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (normalize-token (:token op))
        mid    (:module/id module)
        index  (m/ratio (or (get-in-token world [:yield/indices] mid token) 1))
        shares (m/shares-from-principal-and-index (long amount) index)]
    (-> world
        (assoc-in [:yield/positions oid]
                  (pos/make-position {:owner/id oid
                                     :module/id mid
                                     :token token
                                     :principal (long amount)
                                     :shares shares
                                     :entry-index index})))))


;; ---------------------------------------------------------------------------
;; accrue-v2
;; ---------------------------------------------------------------------------
(defn accrue-v2
  "Accrue yield for all positions in this module using the decision-based
   accrual engine. Each position gets a separate accrual-decision that
   handles short circuits, dust accumulation, and exact ratio arithmetic."
  [world module op]
  (let [token (normalize-token (:token op))
        dt    (:dt op)
        mid   (:module/id module)
        now   (resolve-now world)]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid)
                       (token= (:token pos) token)
                       (= (:status pos) :active))
                (let [decision (accrual/accrual-decision
                                w {:module-id mid
                                   :token token
                                   :position-id oid
                                   :now now
                                   :dt dt})]
                  ;; apply-accrual-decision-with-attribution sets *attribution*
                  ;; with accrual evidence and calls risk/capture-if-risk-event
                  (accrual/apply-accrual-decision-with-attribution w decision))
                w))
            world
            (:yield/positions world {}))))


;; ---------------------------------------------------------------------------
;; withdraw-v2
;; ---------------------------------------------------------------------------
(defn withdraw-v2
  "Withdraw from a yield position. Crystallizes yield first via the decision
   engine, then uses partial-fill/calculate-fulfillment to handle shortfalls.

   Sets :shortfall on the position in the format expected by lifecycle/finalize,
   which handles the actual :total-held adjustment.  Does NOT modify :total-held
   directly — that would cause a double-subtraction when finalize runs.

   When liquidity is adequate, the full position value is withdrawn.
   When liquidity is constrained, only a partial fill is executed and the
   residual is classified as deferred/haircut."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)
        token   (normalize-token (:token pos))
        now     (resolve-now world)]
    (cond
      (nil? pos)                      world
      (not= (:status pos) :active)    world
      :else
      ;; Step 1: Accrue to crystallize final yield
      (let [accrual-decision (accrual/accrual-decision
                              world {:module-id mid
                                     :token token
                                     :position-id oid
                                     :now now
                                     :dt 0})
            world-after-accrue (accrual/apply-accrual-decision world accrual-decision)
            pos-after-accrue (get-in world-after-accrue pos-key)

            ;; Step 2: Determine available liquidity
            recoverable (or (get-in world-after-accrue [:total-held token])
                            (get-in world-after-accrue [:yield/held-balances (name token)])
                            0)
            gross-amount (+ (:principal pos-after-accrue 0)
                            (:unrealized-yield pos-after-accrue 0))

            ;; Step 3: Calculate fulfillment via the partial-fill engine
            settlement (partial-fill/calculate-fulfillment
                        (max 0 (long recoverable)) pos-after-accrue)
            filled (get settlement :filled {})
            deferred-map (get settlement :deferred {})
            haircut-map (get settlement :haircut {})

            fulfilled-total (reduce + 0 (vals filled))
            deferred-total (reduce + 0 (vals deferred-map))
            haircut-total (reduce + 0 (vals haircut-map))
            basis-total (reduce + 0 (vals (:requested settlement {})))

            ;; Step 4: Build :shortfall in the legacy format expected by finalize
            shortfall (when (pos? (- gross-amount fulfilled-total))
                        {:reason :liquidity-shortfall
                         :basis-amount basis-total
                         :available-ratio (if (pos? gross-amount)
                                            (/ (rationalize fulfilled-total)
                                               (rationalize gross-amount))
                                            1)
                         :fulfilled-amount fulfilled-total
                         :deferred-amount deferred-total
                         :haircut-amount haircut-total
                         :as-of-index (:current-index pos-after-accrue)})

            ;; Step 5: Update position status (like v1 does)
            ;; Do NOT touch :total-held — that's finalize's job
            realized-yield (max 0
                                (min (:unrealized-yield pos-after-accrue 0)
                                     (- fulfilled-total (:principal pos-after-accrue 0))))
            updated-pos (-> pos-after-accrue
                            (assoc :partial-fill-affected? (boolean shortfall))
                            (assoc :status (if shortfall :unwinding :withdrawn))
                            (assoc :realized-yield realized-yield)
                            (assoc :unrealized-yield 0)
                            (assoc :shortfall shortfall))]
        (assoc-in world-after-accrue pos-key updated-pos)))))


;; ---------------------------------------------------------------------------
;; emergency-unwind-v2
;; ---------------------------------------------------------------------------
(defn emergency-unwind-v2
  "Mark all active positions in the module as :unwinding."
  [world module op]
  (let [mid   (:module/id module)
        token (normalize-token (:token op))]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid)
                       (token= (:token pos) token)
                       (= (:status pos) :active))
                (assoc-in w [:yield/positions oid :status] :unwinding)
                w))
            world
            (:yield/positions world {}))))


;; ---------------------------------------------------------------------------
;; claim-deferred-v2
;; ---------------------------------------------------------------------------
(defn claim-deferred-v2
  "Attempt to reclaim deferred yield from an unwinding position."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (cond
      (= (:status pos) :unwinding)
      (let [updated-pos (acct/claim-deferred world mid pos)]
        (assoc-in world pos-key updated-pos))

      (= (:status pos) :queued)
      (let [current-index (m/ratio (or (get-in world [:yield/indices mid (:token pos)]) 1))
            claimed-pos (-> pos
                            (assoc :status :withdrawn)
                            (assoc :realized-yield (:unrealized-yield pos 0))
                            (assoc :unrealized-yield 0))]
        (assoc-in world pos-key claimed-pos))

      :else world)))


;; ---------------------------------------------------------------------------
;; Module constructor
;; ---------------------------------------------------------------------------
(defn make-liquid-lending-v2-module
  "Build a declarative module record using the v2 (decision-based) ops.

   `module-id` — dispatch key (e.g. :aave-v3-v2).
   `module-type` — profile label (e.g. :yield.profile/aave-v3-like-v2)."
  ([module-id]
   (make-liquid-lending-v2-module module-id :yield.provider/liquid-lending-v2))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind :claim-deferred}
    :accounting/type :shares
    :ops {:yield/deposit deposit-v2
          :yield/withdraw withdraw-v2
          :yield/accrue accrue-v2
          :yield/emergency-unwind emergency-unwind-v2
          :yield/claim-deferred claim-deferred-v2}}))


(def liquid-lending-v2-module
  (make-liquid-lending-v2-module :yield.provider/liquid-lending-v2))
