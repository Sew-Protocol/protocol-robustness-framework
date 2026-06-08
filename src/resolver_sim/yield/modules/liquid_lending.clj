(ns resolver-sim.yield.modules.liquid-lending
  "Generic liquid-lending yield archetype.

   Behavior class:
   - deterministic accrual (fixed APY)
   - immediate withdrawal
   - configurable liquidity/failure modes

   This is intentionally provider-agnostic and can be profiled as Aave-like,
   Morpho-like (coarse), etc., without encoding protocol-specific internals."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.loss :as loss]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.yield.evidence :as ye]
            [resolver-sim.yield.liquidity :as liquidity]
            [resolver-sim.util.attribution :as attr]))

(defn- normalize-token [token]
  (tok/normalize token))

(defn- token= [a b]
  (= (normalize-token a) (normalize-token b)))

(defn- get-in-token [world path module-id token & keys]
  (let [tok (normalize-token token)
        v   (or (get-in world (into path [module-id tok]))
                (get-in world (into path [module-id (name tok)])))]
    (if (seq keys)
      (get-in v keys)
      v)))

(defn- liquidity-mode [world module-id token]
  (let [ms (market-state/get-market-state world module-id token (:block-time world))]
    (if (< (double (:available-ratio ms 1.0)) 1.0)
      :shortfall
      :available)))

(defn- module-status [world module-id]
  ;; We pick an arbitrary token (first in config or nil) for module-level status
  ;; since market-state is token-oriented but status is often module-wide.
  (let [ms (market-state/get-market-state world module-id nil (:block-time world))]
    (:module-state ms)))

(defn- failure-modes [world module-id token]
  (set (or (get-in-token world [:yield/risk] module-id token :failure-modes)
           #{})))

;; :shortfall blocks new deposits (pool under stress) but NOT withdrawals —
;; withdrawals apply a haircut via apply-liquidity-stress instead.
(defn- deposit-blocked? [m]
  (contains? acct/liquidity-modes m))

(defn- withdraw-blocked? [m]
  (contains? #{:frozen :paused} m))

(defn- fail-enabled? [world module-id token mode]
  (contains? (failure-modes world module-id token) mode))

(defn- shortfall-config [world module-id token]
  (let [cfg (or (get-in-token world [:yield/risk] module-id token :shortfall) {})]
    {:available-ratio (double (or (:available-ratio cfg) 0.5))
     :reason (or (:reason cfg) :liquidity-shortfall)}))

(defn deposit [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (normalize-token (:token op))
        mid    (:module/id module)
        status (module-status world mid)
        mode   (liquidity-mode world mid token)
        index  (or (get-in-token world [:yield/indices] mid token) 1.0)
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
      (let [;; Model A: shares = principal / entry share price at deposit.
            ;; `index` is the module liquidity index / share price at entry.
            pos (model/make-position {:owner/id oid
                                      :module/id mid
                                      :token token
                                      :principal amount
                                      :shares shares
                                      :entry-index index})]
        (assoc-in world [:yield/positions oid] pos)))))

(defn accrue [world module op]
  (let [token     (normalize-token (:token op))
        dt        (:dt op)
        mid       (:module/id module)
        ms        (market-state/get-market-state world mid (or token :global) (:block-time world))
        stale?    (fail-enabled? world mid token :oracle-stale)
        old-index (or (get-in-token world [:yield/indices] mid token) 1.0)
        apy       (:apy ms 0.04)
        ;; :oracle-stale: use cached stale-apy, snapshotting on first accrual
        stale-apy (when stale? (get-in-token world [:yield/risk] mid token :stale-apy))
        apy       (if stale? (or stale-apy (double apy)) apy)
        apy       (if (fail-enabled? world mid token :negative-yield)
                    (- (Math/abs (double apy)))
                    apy)
        seconds-per-year 31536000
        ;; Precedence: index-schedule > apy growth
        new-index (if-let [fixed-index (:index ms)]
                    fixed-index
                    (* old-index (+ 1.0 (/ (* apy dt) seconds-per-year))))
        world'    (-> world
                      (assoc-in [:yield/indices mid token] new-index)
                      (assoc-in [:yield/indices mid (name token)] new-index)
                      ;; Cache stale-apy snapshot on first accrual under :oracle-stale
                      (cond-> (and stale? (not stale-apy))
                        (assoc-in [:yield/risk mid (name token) :stale-apy] (double apy))))]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid) (token= (:token pos) token) (= (:status pos) :active))
                (attr/with-attribution {:yield/position-id oid
                                        :yield/module-id mid
                                        :yield/token token}
                  (let [old-yield (:unrealized-yield pos 0)
                        updated   (acct/update-position-yield world pos new-index)
                        new-pos   (loss/annotate-accrual-loss w updated new-index)
                        yield-delta (- (:unrealized-yield new-pos 0) old-yield)]
                    (-> w
                        (assoc-in [:yield/positions oid] new-pos)
                        (update-in [:total-yield-generated token] (fnil + 0) yield-delta)
                        (update-in [:total-held token] (fnil + 0) yield-delta))))
                w))
            world'
            (:yield/positions world'))))

(defn withdraw [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)
        token   (normalize-token (:token pos))
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

        ;; :withdrawal-queue — defer withdrawal, claim later via claim-deferred
        (fail-enabled? world mid token :withdrawal-queue)
        (let [current-index (or (get-in-token world [:yield/indices] mid token)
                                (:entry-index pos 1.0))
              updated-pos   (acct/update-position-yield world pos current-index)
              queued-pos    (assoc updated-pos :status :queued)
              queued-entry  {:owner/id oid :token token :principal (:principal updated-pos)
                             :unrealized-yield (:unrealized-yield updated-pos 0)
                             :queued-at (:block-time world 0)}]
          (-> world
              (assoc-in pos-key queued-pos)
              (update-in [:yield/withdrawal-queue mid] (fnil conj []) queued-entry)))

        :else
        (let [current-index (or (get-in-token world [:yield/indices] mid token)
                                (:entry-index pos 1.0))
              updated-pos   (acct/update-position-yield world pos current-index)
              gross-amount  (+ (:principal updated-pos 0)
                               (:unrealized-yield updated-pos 0))
              ;; mark-to-market loss (gross < principal) is modeled as a permanent haircut
              ;; even when liquidity-mode is :available.
              intrinsic-loss (max 0 (- (:principal updated-pos 0) gross-amount))
              shortfall-result
              (if (pos? intrinsic-loss)
                {:fulfilled gross-amount
                 :shortfall (loss/intrinsic-carry-shortfall (:principal updated-pos 0)
                                                            gross-amount
                                                            current-index)}
                (let [res (liquidity/apply-withdrawal-policy world mid token gross-amount
                                                               (:principal updated-pos 0))]
                  (if (nil? res)
                    {:fulfilled gross-amount :shortfall nil}
                    res)))
              {:keys [fulfilled shortfall]} shortfall-result
              shortfall' (when shortfall
                           (assoc shortfall :as-of-index current-index))
              realized-yield (max 0
                                  (min (:unrealized-yield updated-pos 0)
                                       (- fulfilled (:principal updated-pos 0))))
              crystallized  (-> updated-pos
                                (dissoc :yield-loss)
                                (assoc :status (if shortfall :unwinding :withdrawn))
                                (assoc :realized-yield realized-yield)
                                (assoc :unrealized-yield 0)
                                (assoc :shortfall shortfall'))]
          (cond-> (assoc-in world pos-key crystallized)
            shortfall'
              (ye/emit-shortfall-event :yield.shortfall/deferred-created oid
                {:deferred-amount (:deferred-amount shortfall 0)
                  :haircut-amount (:haircut-amount shortfall 0)
                  :fulfilled-amount (:fulfilled-amount shortfall 0)
                  :basis-amount (:basis-amount shortfall 0)
                  :available-ratio (:available-ratio shortfall 1.0)
                  :shortfall-kind (name (or (:reason shortfall) :unknown))}))
          ))))

(defn claim-deferred [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)
        status  (:status pos)]
    (cond
      ;; :withdrawal-queue positions are deferred, claim them normally
      (= status :queued)
      (let [current-index (or (get-in-token world [:yield/indices] mid (:token pos))
                              (:entry-index pos 1.0))
            claimed-pos   (-> pos
                              (assoc :status :withdrawn)
                              (assoc :realized-yield (:unrealized-yield pos 0))
                              (assoc :unrealized-yield 0))]
        (assoc-in world pos-key claimed-pos))

      (= status :unwinding)
      
      (let [old-pos pos
          new-pos (acct/claim-deferred world mid pos)
          reclaimed (:reclaimed-amount new-pos 0)]
        (cond-> (assoc-in world pos-key new-pos)
          (pos? reclaimed)
          (ye/emit-shortfall-event :yield.shortfall/deferred-reclaimed oid
            { :reclaimed-amount reclaimed
              :deferred-before (get-in old-pos [:shortfall :deferred-amount] 0)})))








      :else world)))

(defn emergency-unwind [world module op]
  (let [mid   (:module/id module)
        token (:token op)]
    (if (fail-enabled? world mid token :emergency-unwind-fails)
      (throw (ex-info "Liquid-lending emergency unwind unavailable"
                      {:module/id mid :token token}))
      (reduce (fn [w [oid pos]]
                (if (and (= (:module/id pos) mid) (token= (:token pos) token) (= (:status pos) :active))
                  (assoc-in w [:yield/positions oid :status] :unwinding)
                  w))
              world
              (:yield/positions world)))))

(defn make-liquid-lending-module
  "Build a declarative liquid-lending module record (shared ops; risk via world/config).

   `module-id` — registry/dispatch key (e.g. :aave-v3).
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
