(ns resolver-sim.yield.modules.fixed
  "Fixed-rate yield module implementation."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.market-state :as market-state]))

(defn- normalize-token [token]
  (tok/normalize token))

(defn fixed-deposit [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (:token op)
        pos (model/make-position {:owner/id oid
                                  :module/id (:module/id module)
                                  :token token
                                  :principal amount
                                  :shares amount
                                  :entry-index 1.0})]
    (assoc-in world [:yield/positions oid] pos)))

(defn fixed-accrue [world module op]
  (let [{:keys [token dt]} op
        mid (:module/id module)
        ms (market-state/get-market-state world mid token (:block-time world))
        apy (:apy ms 0.05)
        seconds-per-year 31536000
        old-index (or (get-in world [:yield/indices mid token]) 1.0)
        new-index (if-let [fixed-index (:index ms)]
                    fixed-index
                    (+ old-index (/ (* apy dt) seconds-per-year)))
        world' (assoc-in world [:yield/indices mid token] new-index)]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid)
                       (= (normalize-token (:token pos)) (normalize-token token))
                       (= (:status pos) :active))
                (let [old-yield (:unrealized-yield pos 0)
                      updated   (acct/update-position-yield world pos new-index)
                      yield-delta (- (:unrealized-yield updated 0) old-yield)]
                  (-> w
                      (assoc-in [:yield/positions oid] updated)
                      (update-in [:total-yield-generated token] (fnil + 0) yield-delta)
                      (update-in [:total-held token] (fnil + 0) yield-delta)))
                w))
            world'
            (:yield/positions world))))

(defn fixed-withdraw [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)]
    (if (nil? pos)
      world
      (let [mid   (:module/id pos)
            token (:token pos)
            current-index (or (get-in world [:yield/indices mid token])
                              (:entry-index pos 1.0))
            marked-pos (acct/update-position-yield world pos current-index)]
        (-> world
            (assoc-in pos-key (assoc marked-pos :status :withdrawn)))))))

(defn make-fixed-module
  "Build a declarative fixed-rate module record (rates via world/config)."
  ([module-id]
   (make-fixed-module module-id :fixed-rate))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :accrue}
    :accounting/type :principal
    :ops {:yield/deposit fixed-deposit
          :yield/withdraw fixed-withdraw
          :yield/accrue fixed-accrue}}))

(def fixed-rate-module
  (make-fixed-module :fixed-rate))
