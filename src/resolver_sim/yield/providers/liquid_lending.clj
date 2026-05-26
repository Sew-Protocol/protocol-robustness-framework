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

(defn- blocked-mode? [m]
  (contains? #{:shortfall :frozen :paused} m))

(defn- fail-enabled? [world module-id token mode]
  (contains? (failure-modes world module-id token) mode))

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

      (or (blocked-mode? mode)
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
                      new-pos   (acct/update-position-yield pos new-index)
                      yield-delta (- (:unrealized-yield new-pos 0) old-yield)]
                  (-> w
                      (assoc-in [:yield/positions oid] new-pos)
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
    (if (nil? pos)
      world
      (if (or (= status :paused)
              (blocked-mode? mode)
              (fail-enabled? world mid token :withdraw-fails))
        (throw (ex-info "Liquid-lending withdraw unavailable"
                        {:module/id mid
                         :token token
                         :module-status status
                         :liquidity-mode mode
                         :owner/id oid}))
        (let [current-index (get-in world [:yield/indices mid token] (:entry-index pos 1.0))
              crystallized  (acct/realize-yield (acct/update-position-yield pos current-index))]
          (if (fail-enabled? world mid token :partial-liquidity)
            (assoc-in world pos-key
                      (-> crystallized
                          (update :realized-yield #(quot (long %) 2))
                          (assoc :status :unwinding)))
            (assoc-in world pos-key (assoc crystallized :status :withdrawn))))))))

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
    :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind}
    :accounting/type :shares
    :ops {:yield/deposit deposit
          :yield/withdraw withdraw
          :yield/accrue accrue
          :yield/emergency-unwind emergency-unwind}
    :risk/defaults {:liquidity-mode :available
                    :loss-mode :none
                    :rate-mode :deterministic
                    :failure-modes #{}}}))

(def liquid-lending-module
  (make-module :yield.provider/liquid-lending))
