(ns resolver-sim.contract-model.replay-temporal-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.io.trace-export :as trace-export]))

(defn- temporal-step-context
  "Execution context with temporal enforcement enabled (direct process-step tests)."
  [scenario]
  (assoc (proto/build-execution-context sew/protocol (:agents scenario) {})
         :replay-flags {:temporal-enabled? true}))

(deftest advance-world-time-helper
  (testing "advances only when event-time is in the future"
    (let [w {:block-time 1000}
          f #'resolver-sim.contract-model.replay/advance-world-time
          same (f w 1000)
          fut  (f w 1015)]
      (is (= w (:world same)))
      (is (false? (:advanced? same)))
      (is (= 0 (:delta-ms same)))
      (is (= 1015 (get-in fut [:world :block-time])))
      (is (true? (:advanced? fut)))
      (is (= 15 (:delta-ms fut))))))

(deftest temporal-rule-regression-rejected
  (testing "process-step rejects regressive time via temporal rule evaluation"
    (let [scenario {:scenario-id "time-regression-check"
                    :schema-version "1.1"
                    :title "Time regression"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (assoc (proto/init-world sew/protocol scenario)
                          :block-time 2000)
          context  (temporal-step-context scenario)
          event    {:seq 0 :time 1999 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :time-regression (get-in step [:trace-entry :error])))
      (is (= :non-regressive-time (get-in step [:trace-entry :temporal-rule-id])))
      (is (= 2000 (get-in step [:world :block-time]))))))

(deftest temporal-rule-invalid-time-rejected
  (testing "process-step rejects missing/invalid event-time with explicit rule metadata"
    (let [scenario {:scenario-id "invalid-time-check"
                    :schema-version "1.1"
                    :title "Invalid time"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (assoc (proto/init-world sew/protocol scenario)
                          :block-time 2000)
          context  (temporal-step-context scenario)
          event    {:seq 0 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :invalid-event-time (get-in step [:trace-entry :error])))
      (is (= :missing-event-time (get-in step [:trace-entry :temporal-rule-id])))
      (is (= 2000 (get-in step [:world :block-time]))))))

(deftest temporal-rule-context-extension
  (testing "process-step applies optional context-provided temporal rules"
    (let [scenario {:scenario-id "context-rule-check"
                    :schema-version "1.1"
                    :title "Context rule"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (assoc (proto/init-world sew/protocol scenario)
                          :block-time 2000)
          context  (assoc (temporal-step-context scenario)
                          :temporal-rules
                          [{:id :custom-no-set-paused
                            :check (fn [{:keys [event]}]
                                     (if (= "set-paused" (:action event))
                                       {:ok? false :error :custom-time-rule}
                                       {:ok? true}))}])
          event    {:seq 0 :time 2001 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :custom-time-rule (get-in step [:trace-entry :error])))
      (is (= :custom-no-set-paused (get-in step [:trace-entry :temporal-rule-id]))))))

(deftest temporal-rule-order-first-failure-wins
  (testing "when multiple temporal rules fail, first failing rule is emitted"
    (let [scenario {:scenario-id "context-rule-order"
                    :schema-version "1.1"
                    :title "Context rule order"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (assoc (proto/init-world sew/protocol scenario) :block-time 2000)
          context  (assoc (temporal-step-context scenario)
                          :temporal-rules
                          [{:id :rule-first
                            :check (fn [_] {:ok? false :error :first-error})}
                           {:id :rule-second
                            :check (fn [_] {:ok? false :error :second-error})}])
          event    {:seq 0 :time 2001 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :first-error (get-in step [:trace-entry :error])))
      (is (= :rule-first (get-in step [:trace-entry :temporal-rule-id]))))))

(deftest sew-appeal-window-rule-triggers-via-replay
  (testing "SEW temporal rule rejects execute_pending_settlement before appeal deadline"
    (let [scenario {:scenario-id "sew-appeal-window-rule"
                    :id "S-Temporal-SEW-Appeal-Window"
                    :schema-version "1.1"
                    :title "SEW appeal window temporal rule"
                    :purpose :adversarial-robustness
                    :expectations {:terminal [{:name :trace-produced :equals true}]}
                    :initial-block-time 1000
                    :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :strategy "honest"}
                             {:id "l0resolver" :address "0xl0" :role "resolver"}
                             {:id "keeper" :address "0xkeeper" :role "keeper"}]
                    :protocol-params {:resolver-fee-bps 150
                                      :resolution-module "0xkleros-proxy"
                                      :escalation-resolvers {"0" "0xl0"}
                                      :appeal-window-duration 60
                                      :max-dispute-duration 2592000}
                    :options {:flags {:temporal-enabled? true}}
                    :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 6000}}
                             {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                              :params {:workflow-id 0}}
                             {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
                             ;; before appeal window expires (1120 + 60 = 1180)
                             {:seq 3 :time 1130 :agent "keeper" :action "execute_pending_settlement"
                              :params {:workflow-id 0}}]}
          result (sew/replay-with-sew-protocol scenario)
          entry  (some #(when (= 3 (:seq %)) %) (:trace result))]
      (is (= :rejected (:result entry)))
      (is (= :appeal-window-not-expired (:error entry)))
      (is (= :sew/appeal-window-open (:temporal-rule-id entry))))))

(deftest temporal-rule-metadata-propagates-to-trace-artifact
  (testing "exported trace artifact preserves temporal rule id on rejected step"
    (let [scenario {:scenario-id "sew-appeal-window-artifact"
                    :id "S-Temporal-SEW-Appeal-Artifact"
                    :schema-version "1.1"
                    :title "SEW appeal window artifact propagation"
                    :purpose :adversarial-robustness
                    :expectations {:terminal [{:name :trace-produced :equals true}]}
                    :initial-block-time 1000
                    :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :strategy "honest"}
                             {:id "l0resolver" :address "0xl0" :role "resolver"}
                             {:id "keeper" :address "0xkeeper" :role "keeper"}]
                    :protocol-params {:resolver-fee-bps 150
                                      :resolution-module "0xkleros-proxy"
                                      :escalation-resolvers {"0" "0xl0"}
                                      :appeal-window-duration 60
                                      :max-dispute-duration 2592000}
                    :options {:flags {:temporal-enabled? true}}
                    :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 6000}}
                             {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                              :params {:workflow-id 0}}
                             {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
                             {:seq 3 :time 1130 :agent "keeper" :action "execute_pending_settlement"
                              :params {:workflow-id 0}}]}
          result  (sew/replay-with-sew-protocol scenario)
          fixture (trace-export/export-trace-fixture result scenario)
          step    (some #(when (= 3 (:seq %)) %) (:steps fixture))]
      (is (= true (get-in step [:expected :reverted])))
      (is (= "appeal-window-not-expired" (get-in step [:expected :error])))
      (is (= "appeal-window-open" (get-in step [:attributes :temporal_rule_id]))))))
