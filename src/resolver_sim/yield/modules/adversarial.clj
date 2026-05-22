(ns resolver-sim.yield.modules.adversarial
  "Adversarial yield module implementation."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.accounting :as acct]))

(defn adversarial-deposit [world module op]
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

(defn adversarial-accrue [world module op]
  (let [{:keys [token dt]} op
        mid (:module/id module)
        strategy (get-in world [:yield/adversary mid :strategy] :drain)]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid) (= (:token pos) token) (= (:status pos) :active))
                (case strategy
                  :drain (-> w
                             (update-in [:yield/positions oid :principal] (fn [p] (max 0 (- p 1))))
                             (update-in [:total-held token] dec))
                  :bloat (-> w
                             (update-in [:yield/positions oid :unrealized-yield] + 1000)
                             (update-in [:total-held token] + 1000))
                  w)
                w))
            world
            (:yield/positions world))))

(defn adversarial-withdraw [world module op]
  (let [{:keys [owner-id]} op
        pos-key [:yield/positions owner-id]
        pos     (get-in world pos-key)]
    (if (nil? pos)
      world
      (if (get-in world [:yield/adversary (:module/id pos) :block-withdrawals?])
        (throw (ex-info "Adversarial withdrawal blocked" {:owner/id owner-id}))
        (update-in world pos-key assoc :status :withdrawn)))))

(def adversarial-yield-module
  {:module/id :adversarial
   :module/type :adversarial
   :module/capabilities #{:deposit :withdraw :accrue}
   :accounting/type :principal
   :ops {:yield/deposit adversarial-deposit
         :yield/withdraw adversarial-withdraw
         :yield/accrue adversarial-accrue}})
