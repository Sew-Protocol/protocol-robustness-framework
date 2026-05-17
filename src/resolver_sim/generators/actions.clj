(ns resolver-sim.generators.actions
  "State-aware action generation using protocol dispatch as the source of truth."
  (:require [resolver-sim.protocols.protocol :as engine]))

(def ^:private buyer-id "buyer")
(def ^:private seller-id "seller")
(def ^:private resolver-id "resolver")

(defn- candidate-events
  "Build candidate events for the current world.
   Candidates are validated by protocol dispatch before use."
  [world seq time]
  (let [escrows (:escrow-transfers world)
        pending-settlements (:pending-settlements world)
        pending-ids  (for [[wf et] escrows :when (= :pending (:escrow-state et))] wf)
        disputed-ids (for [[wf et] escrows :when (= :disputed (:escrow-state et))] wf)
        settlement-ids (for [[wf ps] pending-settlements :when (:exists ps)] wf)
        workflow-ids (keys escrows)]
    (vec
     (concat
      [{:seq seq :time time :agent buyer-id :action "create_escrow"
        :params {:token "0xUSDC" :to "0xSeller" :amount 1000 :custom-resolver "0xResolver"}}]
      [{:seq seq :time time :agent resolver-id :action "register_stake"
        :params {:amount 1000}}]
      (for [wf pending-ids]
        {:seq seq :time time :agent buyer-id :action "raise_dispute"
         :params {:workflow-id wf}})
      (for [wf workflow-ids]
        {:seq seq :time time :agent buyer-id :action "agree_cancel"
         :params {:workflow-id wf}})
      (for [wf workflow-ids]
        {:seq seq :time time :agent seller-id :action "agree_cancel"
         :params {:workflow-id wf}})
      (for [wf disputed-ids]
        {:seq seq :time time :agent resolver-id :action "execute_resolution"
         :params {:workflow-id wf :is-release true}})
      (for [wf settlement-ids]
        {:seq seq :time time :agent buyer-id :action "escalate_dispute"
         :params {:workflow-id wf}})
      (for [wf disputed-ids]
        {:seq seq :time time :agent resolver-id :action "auto_cancel_disputed"
         :params {:workflow-id wf}})
      (for [wf settlement-ids]
        {:seq seq :time time :agent resolver-id :action "execute_pending_settlement"
         :params {:workflow-id wf}})
      [{:seq seq :time time :agent resolver-id :action "withdraw_stake"
        :params {:amount 100}}]))))

(defn valid-next-actions
  "Return protocol-valid next events for this world at seq/time.
   Uses protocol dispatch to validate candidate actions and avoid shadow logic."
  [protocol context world seq time]
  (->> (candidate-events world seq time)
       (filter (fn [ev]
                 (let [r (engine/dispatch-action protocol context world ev)]
                   (:ok r))))
       vec))
