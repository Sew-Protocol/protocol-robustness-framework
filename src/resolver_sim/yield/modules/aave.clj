(ns resolver-sim.yield.modules.aave
  "[REFERENCE MODULE / Sew-INTEGRATED]

   Aave-inspired yield module used as the current reference module.
   The interface pattern is reusable; concrete world-field coupling and
   policy assumptions are currently tuned to Sew integration."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.accounting :as acct]))

(defn- liquidity-mode
  [world module-id token]
  (get-in world [:yield/risk module-id token :liquidity-mode] :available))

(defn- module-status
  [world module-id]
  (get-in world [:yield/module-status module-id] :active))

(defn- blocked-mode? [m]
  (contains? #{:shortfall :frozen :paused} m))

(defn aave-deposit [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (:token op)
        status (module-status world (:module/id module))
        mode   (liquidity-mode world (:module/id module) token)
        index    (get-in world [:yield/indices (:module/id module) token] 1.0)
        shares   (/ amount index)]
    (cond
      (= status :paused)
      (throw (ex-info "Aave module is paused"
                      {:module/id (:module/id module)
                       :module-status status
                       :token token}))

      (= status :disabled-for-new-deposits)
      (throw (ex-info "Aave module is disabled for new deposits"
                      {:module/id (:module/id module)
                       :module-status status
                       :token token}))

      (blocked-mode? mode)
      (throw (ex-info "Aave deposit unavailable under current liquidity mode"
                      {:module/id (:module/id module)
                       :token token
                       :liquidity-mode mode}))

      :else
      (let [pos (model/make-position {:owner/id oid
                                      :module/id (:module/id module)
                                      :token token
                                      :principal amount
                                      :shares shares
                                      :entry-index index})]
        (assoc-in world [:yield/positions oid] pos)))))


(defn aave-accrue [world module op]
  (let [{:keys [token dt]} op
        mid       (:module/id module)
        old-index (get-in world [:yield/indices mid token] 1.0)
        apy       (get-in world [:yield/rates mid token] 0.04)
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
                      (update-in [:total-held token] (fnil + 0) yield-delta)))
                w))
            world'
            (:yield/positions world'))))

(defn aave-withdraw [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)
        token   (:token pos)
        status  (module-status world mid)
        mode    (liquidity-mode world mid token)]
    (if (nil? pos)
      world
      (if (or (= status :paused) (blocked-mode? mode))
        (throw (ex-info "Aave withdraw unavailable under current liquidity mode"
                        {:module/id mid
                         :token token
                         :module-status status
                         :liquidity-mode mode
                         :owner/id oid}))
        (let [current-index (get-in world [:yield/indices mid token] (:entry-index pos 1.0))
              updated-pos   (acct/update-position-yield world pos current-index)
              gross-amount  (+ (:principal updated-pos 0) (:unrealized-yield updated-pos 0))
              {:keys [fulfilled shortfall]} (acct/apply-liquidity-stress world mid token gross-amount)
              crystallized  (-> updated-pos
                                (assoc :status (if shortfall :unwinding :withdrawn))
                                (assoc :realized-yield fulfilled)
                                (assoc :unrealized-yield 0)
                                (assoc :shortfall shortfall))]
          (assoc-in world pos-key crystallized))))))

(defn aave-claim-deferred [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (if (and pos (= (:status pos) :unwinding))
      (assoc-in world pos-key (acct/claim-deferred world mid pos))
      world)))

(defn aave-emergency-unwind [world module op]
  (let [mid   (:module/id module)
        token (:token op)]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid) (= (:token pos) token) (= (:status pos) :active))
                (assoc-in w [:yield/positions oid :status] :unwinding)
                w))
            world
            (:yield/positions world))))

(def aave-v3-module
  {:module/id :aave-v3
   :module/type :aave-v3
   :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind :claim-deferred}
   :accounting/type :shares
   :ops {:yield/deposit aave-deposit
         :yield/withdraw aave-withdraw
         :yield/accrue aave-accrue
         :yield/emergency-unwind aave-emergency-unwind
         :yield/claim-deferred aave-claim-deferred}
   :risk/defaults {:liquidity-mode :available
                   :loss-mode :none
                   :rate-mode :external}})
