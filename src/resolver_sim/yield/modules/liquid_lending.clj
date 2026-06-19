(ns resolver-sim.yield.modules.liquid-lending
  "Liquid-lending yield archetype using the new decision-based accrual engine.

   Replaces the legacy liquid_lending/accrue which used double-based arithmetic
   inline. This version calls accrual/accrual-decision + apply-accrual-decision
   for each position, and partial-fill/calculate-fulfillment for withdrawals.

   Module identity:
     :module/id arbitrary (e.g. :aave-v3)
     :module/type :yield.profile/aave-v3-like (or :yield.provider/liquid-lending)
     :accounting/type :shares"
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.yield.evidence :as ye]
            [resolver-sim.time.context :as time-ctx]))

(defn- normalize-token [token]
  (tok/normalize token))

(defn- resolve-now [world]
  (time-ctx/block-ts world))

(defn- token= [a b]
  (= (normalize-token a) (normalize-token b)))

(defn- get-in-token [world path module-id token & keys]
  (let [tok (normalize-token token)
        v   (or (get-in world (into path [module-id tok]))
                (get-in world (into path [module-id (name tok)])))]
    (if (seq keys)
      (get-in v keys)
      v)))


;; ---------------------------------------------------------------------------
;; deposit
;; ---------------------------------------------------------------------------
(defn deposit
  "Create a yield position using the new position model (ratio-based entry-index).
   Does NOT update :total-held — create-escrow already called add-held for the
   escrow amount.  Updating :total-held here would double-count."
  [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (normalize-token (:token op))
        mid    (:module/id module)
        index  (m/ratio (or (get-in-token world [:yield/indices] mid token) 1))
        shares (m/shares-from-principal-and-index (long amount) index)]
    (assoc-in world [:yield/positions oid]
              (pos/make-position {:owner/id oid
                                 :module/id mid
                                 :token token
                                 :principal (long amount)
                                 :shares shares
                                 :entry-index index}))))


;; ---------------------------------------------------------------------------
;; accrue
;; ---------------------------------------------------------------------------
(defn accrue
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
;; withdraw
;; ---------------------------------------------------------------------------
(defn withdraw
  "Withdraw from a yield position. Crystallizes yield first via the decision
   engine, then uses partial-fill/calculate-fulfillment to handle shortfalls."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (cond
      (nil? pos)                      world
      (not= (:status pos) :active)    world
      (not= (:module/id pos) mid)     world
      :else
      (let [token   (normalize-token (:token pos))
            now     (resolve-now world)]
      ;; Step 1: Accrue to crystallize final yield
      (let [accrual-decision (accrual/accrual-decision
                              world {:module-id mid
                                     :token token
                                     :position-id oid
                                     :now now
                                     :dt 0})
            world-after-accrue (accrual/apply-accrual-decision world accrual-decision)
            pos-after-accrue (get-in world-after-accrue pos-key)

            ;; Step 2: Determine available liquidity from market state,
            ;; which resolves the liquidity-schedule, shortfall-model,
            ;; and risk config into a composite available-ratio.
            base-recoverable (or (get-in world-after-accrue [:total-held token])
                                 (get-in world-after-accrue [:yield/held-balances (name token)])
                                 0)
            market-state (market-state/get-market-state world-after-accrue mid token now)
            available-ratio (:available-ratio market-state 1.0)
            shortfall-model (:shortfall-model market-state)
            withdrawal-policy (:withdrawal-policy market-state)
            recoverable (long (* base-recoverable available-ratio))
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

            ;; Step 4: Build :shortfall (based on requested vs filled, not gross value).
            ;; When shortfall-model specifies recoverable=false, all unfilled
            ;; amounts become permanent haircuts (recognized losses) rather
            ;; than deferred (future recoverable).
            shortfall (when (pos? (- basis-total fulfilled-total))
                        (let [sf-reason (or (:type shortfall-model) :liquidity-shortfall)
                              recoverable? (:recoverable shortfall-model true)]
                          {:reason sf-reason
                           :basis-amount basis-total
                           :available-ratio (if (pos? gross-amount)
                                              (/ (rationalize fulfilled-total)
                                                 (rationalize gross-amount))
                                              1)
                           :fulfilled-amount fulfilled-total
                           :deferred-amount (if recoverable? deferred-total 0)
                           :haircut-amount (if recoverable? haircut-total
                                               (+ deferred-total haircut-total))
                           :as-of-index (:current-index pos-after-accrue)}))

            ;; Step 5: Update position status
            realized-yield (max 0
                                (min (:unrealized-yield pos-after-accrue 0)
                                     (- fulfilled-total (:principal pos-after-accrue 0))))
            updated-pos (-> pos-after-accrue
                            (assoc :partial-fill-affected? (boolean shortfall))
                            (assoc :status (if shortfall :unwinding :withdrawn))
                            (assoc :realized-yield realized-yield)
                            (assoc :unrealized-yield 0)
                            (assoc :shortfall shortfall))
            
            world-final (assoc-in world-after-accrue pos-key updated-pos)]
            
        (cond-> world-final
            shortfall
              (ye/emit-shortfall-event :yield.shortfall/deferred-created oid
                {:deferred-amount deferred-total
                  :haircut-amount haircut-total
                  :fulfilled-amount fulfilled-total
                  :basis-amount basis-total
                  :available-ratio (:available-ratio shortfall 1.0)
                  :shortfall-kind (name (or (:reason shortfall) :unknown))}))
        )))))



;; ---------------------------------------------------------------------------
;; emergency-unwind
;; ---------------------------------------------------------------------------
(defn emergency-unwind
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
;; claim-deferred
;; ---------------------------------------------------------------------------
(defn claim-deferred
  "Attempt to reclaim deferred yield from an unwinding position."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (cond
      (nil? pos)                       world
      (not= (:module/id pos) mid)      world
      (= (:status pos) :unwinding)
      (let [old-pos pos
            new-pos (acct/claim-deferred world mid pos)
            reclaimed (:reclaimed-amount new-pos 0)]
        (if (pos? reclaimed)
          (let [world-final (assoc-in world pos-key new-pos)]
            (ye/emit-shortfall-event world-final :yield.shortfall/deferred-reclaimed oid
              { :reclaimed-amount reclaimed
                :deferred-before (get-in old-pos [:shortfall :deferred-amount] 0)})
            world-final)
          world))

      (= (:status pos) :queued)
      (let [claimed-pos (-> pos
                            (assoc :status :withdrawn)
                            (assoc :shortfall nil)
                            (assoc :realized-yield (:unrealized-yield pos 0))
                            (assoc :unrealized-yield 0))]
        (assoc-in world pos-key claimed-pos))

      :else world)))


;; ---------------------------------------------------------------------------
;; Module constructor
;; ---------------------------------------------------------------------------
(defn make-liquid-lending-module
  "Build a declarative module record using the v2 (decision-based) ops.

   `module-id` — dispatch key (e.g. :aave-v3).
   `module-type` — profile label (e.g. :yield.profile/aave-v3-like)."
  ([module-id]
   (make-liquid-lending-module module-id :yield.provider/liquid-lending))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind :claim-deferred}
    :accounting/type :shares
    :ops {:yield/deposit deposit
          :yield/withdraw withdraw
          :yield/accrue accrue
          :yield/emergency-unwind emergency-unwind
          :yield/claim-deferred claim-deferred}}))


(def liquid-lending-module
  (make-liquid-lending-module :yield.provider/liquid-lending))
