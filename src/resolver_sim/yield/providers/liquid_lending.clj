(ns resolver-sim.yield.providers.liquid-lending
  "Generic liquid-lending yield archetype.

   Behavior class:
   - deterministic accrual (fixed APY)
   - immediate withdrawal
   - configurable liquidity/failure modes

   This is intentionally provider-agnostic and can be profiled as Aave-like,
   Morpho-like (coarse), etc., without encoding protocol-specific internals."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.accounting :as acct]))

(defn- liquidity-mode [world module-id token]
  (get-in world [:yield/risk module-id token :liquidity-mode] :available))

(defn- module-status [world module-id]
  (get-in world [:yield/module-status module-id] :active))

(defn- failure-modes [world module-id token]
  (set (or (get-in world [:yield/risk module-id token :failure-modes])
           #{})))

;; :shortfall blocks new deposits (pool under stress) but NOT withdrawals —
;; withdrawals apply a haircut via apply-liquidity-stress instead.
(defn- deposit-blocked? [m]
  (contains? #{:shortfall :frozen :paused} m))

(defn- withdraw-blocked? [m]
  (contains? #{:frozen :paused} m))

(defn- fail-enabled? [world module-id token mode]
  (contains? (failure-modes world module-id token) mode))

(defn- shortfall-config [world module-id token]
  (let [cfg (get-in world [:yield/risk module-id token :shortfall] {})]
    {:available-ratio (double (or (:available-ratio cfg) 0.5))
     :reason (or (:reason cfg) :liquidity-shortfall)}))

(defn deposit [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (:token op)
        mid    (:module/id module)
        status (module-status world mid)
        mode   (liquidity-mode world mid token)
        index  (get-in world [:yield/indices mid token] 1.0)
        shares (/ amount index)]
    (cond
      (or (= status :paused)
          (fail-enabled? world mid token :provider-paused))
      (throw (ex-info "Liquid-lending provider is paused"
                      {:module/id mid :token token :module-status status}))

      (= status :disabled-for-new-deposits)
      (throw (ex-info "Liquid-lending provider is disabled for new deposits"
                      {:module/id mid :token token :module-status status}))

      (or (deposit-blocked? mode)
          (fail-enabled? world mid token :deposit-fails))
      (throw (ex-info "Liquid-lending deposit unavailable"
                      {:module/id mid :token token :liquidity-mode mode}))

      :else
      (let [pos (model/make-position {:owner/id oid
                                      :module/id mid
                                      :token token
                                      :principal amount
                                      :shares shares
                                      :entry-index index})]
        (assoc-in world [:yield/positions oid] pos)))))

(defn accrue [world module op]
  (let [{:keys [token dt]} op
        mid       (:module/id module)
        old-index (get-in world [:yield/indices mid token] 1.0)
        apy       (get-in world [:yield/rates mid token] 0.04)
        apy       (if (fail-enabled? world mid token :negative-yield)
                    (- (Math/abs (double apy)))
                    apy)
        seconds-per-year 31536000
        new-index (* old-index (+ 1.0 (/ (* apy dt) seconds-per-year)))
        world'    (assoc-in world [:yield/indices mid token] new-index)]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid) (= (:token pos) token) (= (:status pos) :active))
                (let [old-yield (:unrealized-yield pos 0)
                      new-pos   (acct/update-position-yield world pos new-index)
                      yield-delta (- (:unrealized-yield new-pos 0) old-yield)]
                  (-> w
                      (assoc-in [:yield/positions oid] new-pos)
                      (update-in [:total-yield-generated token] (fnil + 0) yield-delta)
                      (update-in [:total-held token] (fnil + 0) yield-delta)))
                w))
            world'
            (:yield/positions world'))))

(defn withdraw [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)
        token   (:token pos)
        status  (module-status world mid)
        mode    (liquidity-mode world mid token)]
    (cond
      (nil? pos)
      world

      ;; Retry/idempotency guard: once a position is crystallized, withdrawing
      ;; again must be a no-op so liquidity stress is never applied twice.
      (not= (:status pos) :active)
      world

      (or (= status :paused)
          (withdraw-blocked? mode)
          (fail-enabled? world mid token :withdraw-fails))
      (throw (ex-info "Liquid-lending withdraw unavailable"
                      {:module/id mid
                       :token token
                       :module-status status
                       :liquidity-mode mode
                       :owner/id oid}))

      :else
      (let [current-index (get-in world [:yield/indices mid token]
                                  (:entry-index pos 1.0))
            updated-pos   (acct/update-position-yield world pos current-index)
            gross-amount  (+ (:principal updated-pos 0)
                             (:unrealized-yield updated-pos 0))
            ;; mark-to-market loss (gross < principal) is modeled as a permanent haircut
            ;; even when liquidity-mode is :available.
            intrinsic-loss (max 0 (- (:principal updated-pos 0) gross-amount))
            {:keys [fulfilled shortfall]}
            (if (pos? intrinsic-loss)
              {:fulfilled gross-amount
               :shortfall {:reason :negative-carry-loss
                           :basis-amount (:principal updated-pos 0)
                           :fulfilled-amount gross-amount
                           :deferred-amount 0
                           :haircut-amount intrinsic-loss}}
              (acct/apply-liquidity-stress world
                                           mid
                                           token
                                           gross-amount))
            realized-yield (max 0
                                (min (:unrealized-yield updated-pos 0)
                                     (- fulfilled (:principal updated-pos 0))))
            crystallized  (-> updated-pos
                              (assoc :status (if shortfall :unwinding :withdrawn))
                              (assoc :realized-yield realized-yield)
                              (assoc :unrealized-yield 0)
                              (assoc :shortfall shortfall))]
        (assoc-in world pos-key crystallized)))))

(defn claim-deferred [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (if (and pos (= (:status pos) :unwinding))
      (assoc-in world pos-key (acct/claim-deferred world mid pos))
      world)))

(defn emergency-unwind [world module op]
  (let [mid   (:module/id module)
        token (:token op)]
    (if (fail-enabled? world mid token :emergency-unwind-fails)
      (throw (ex-info "Liquid-lending emergency unwind unavailable"
                      {:module/id mid :token token}))
      (reduce (fn [w [oid pos]]
                (if (and (= (:module/id pos) mid) (= (:token pos) token) (= (:status pos) :active))
                  (assoc-in w [:yield/positions oid :status] :unwinding)
                  w))
              world
              (:yield/positions world)))))

(defn make-module
  ([module-id] (make-module module-id :yield.provider/liquid-lending))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind :claim-deferred}
    :accounting/type :shares
    :ops {:yield/deposit deposit
          :yield/withdraw withdraw
          :yield/accrue accrue
          :yield/emergency-unwind emergency-unwind
          :yield/claim-deferred claim-deferred}
    :risk/defaults {:liquidity-mode :available
                    :loss-mode :none
                    :rate-mode :deterministic
                    :failure-modes #{}}}))

(def liquid-lending-module
  (make-module :yield.provider/liquid-lending))
