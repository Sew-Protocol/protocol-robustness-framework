(ns resolver-sim.yield.modules.fixed
  "Fixed-rate yield module implementation."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.accounting :as acct]))

(defn fixed-deposit [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (:token op)
        pos (model/make-position {:owner/id oid
                                  :module/id (:module/id module)
                                  :token token
                                  :principal amount
                                  :shares amount ; 1:1 shares for fixed rate
                                  :entry-index 1.0})]
    (assoc-in world [:yield/positions oid] pos)))

(defn fixed-accrue [world module op]
  (let [{:keys [token dt]} op
        mid (:module/id module)
        apy (get-in world [:yield/rates mid token] 0.05)
        seconds-per-year 31536000]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid) (= (:token pos) token) (= (:status pos) :active))
                (let [val (* (:principal pos) apy dt)
                      yield-delta (long (quot (long val) seconds-per-year))]
                  (-> w
                      (update-in [:yield/positions oid :unrealized-yield] + yield-delta)
                      (update-in [:total-yield-generated token] (fnil + 0) yield-delta)
                      (update-in [:total-held token] (fnil + 0) yield-delta)))
                (do (println "NO MATCH:" (:module/id pos) mid (:token pos) token) w)))
            world
            (:yield/positions world))))

(defn fixed-withdraw [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)]
    (if (nil? pos)
      world
      (update-in world pos-key assoc :status :withdrawn))))

(def fixed-rate-module
  {:module/id :fixed-rate
   :module/type :fixed-rate
   :module/capabilities #{:deposit :withdraw :accrue}
   :accounting/type :principal
   :ops {:yield/deposit fixed-deposit
         :yield/withdraw fixed-withdraw
         :yield/accrue fixed-accrue}})
