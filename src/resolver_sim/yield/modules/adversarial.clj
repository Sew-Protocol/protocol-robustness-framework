(ns resolver-sim.yield.modules.adversarial
  "Adversarial yield module implementation."
  (:require [resolver-sim.yield.model :as model]
            [resolver-sim.yield.accounting :as acct]))

(defn- normalize-token [token]
  (cond
    (keyword? token) token
    (string? token)  (keyword token)
    :else            token))

(defn adversarial-deposit [world module op]
  (let [oid    (:owner/id op)
        amount (:amount op)
        token  (normalize-token (:token op))
        pos (model/make-position {:owner/id oid
                                  :module/id (:module/id module)
                                  :token token
                                  :principal amount
                                  :shares amount
                                  :entry-index 1.0})]
    (assoc-in world [:yield/positions oid] pos)))

(defn adversarial-accrue [world module op]
  ;; NOTE: :drain and :bloat intentionally break total-yield-generated accounting.
  ;; This is by design — adversarial scenarios test how the protocol responds to
  ;; external protocol failures, not that the conservation invariant is preserved.
  ;; `update-position-yield` is called first so positions carry :current-index and
  ;; :current-value for shape consistency with non-adversarial modules.
  (let [{:keys [token dt]} op
        mid (:module/id module)
        strategy (get-in world [:yield/adversary mid :strategy] :drain)
        index (or (get-in world [:yield/indices mid token]) 1.0)]
    (reduce (fn [w [oid pos]]
              (if (and (= (:module/id pos) mid)
                       (= (normalize-token (:token pos)) (normalize-token token))
                       (= (:status pos) :active))
                (let [marked (acct/update-position-yield world pos index)]
                  (case strategy
                    :drain (-> w
                               (assoc-in [:yield/positions oid] (update marked :principal (fn [p] (max 0 (- p 1)))))
                               (update-in [:total-held token] dec))
                    :bloat (-> w
                               (assoc-in [:yield/positions oid] (update marked :unrealized-yield + 1000))
                               (update-in [:total-held token] + 1000))
                    w))
                w))
            world
            (:yield/positions world))))

(defn adversarial-withdraw [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)]
    (if (nil? pos)
      world
      (if (get-in world [:yield/adversary (:module/id pos) :block-withdrawals?])
        (throw (ex-info "Adversarial withdrawal blocked" {:owner/id oid}))
        (let [mid   (:module/id pos)
              token (:token pos)
              current-index (or (get-in world [:yield/indices mid token])
                                (:entry-index pos 1.0))
              marked-pos (acct/update-position-yield world pos current-index)]
          (-> world
              (assoc-in pos-key (assoc marked-pos :status :withdrawn))))))))

(defn make-adversarial-module
  "Build a declarative adversarial module record (strategy via world [:yield/adversary ...])."
  ([module-id]
   (make-adversarial-module module-id :adversarial))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :accrue}
    :accounting/type :principal
    :ops {:yield/deposit adversarial-deposit
          :yield/withdraw adversarial-withdraw
          :yield/accrue adversarial-accrue}}))

(def adversarial-yield-module
  (make-adversarial-module :adversarial))
