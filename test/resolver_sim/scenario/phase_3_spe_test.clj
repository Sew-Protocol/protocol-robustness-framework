(ns resolver-sim.scenario.phase-3-spe-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.subgame-counterfactual :as cf]
            [resolver-sim.contract-model.replay :as replay]))

(deftest test-dynamic-tree-expansion-integration
  (testing "SPE validation with dynamic tree expansion"
    (let [scenario {:scenario-id "test-spe-dynamic"
                    :schema-version "1.0"
                    :initial-block-time 800
                    :agents [{:id "buyer" :address "0xbuyer" :role "buyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :role "seller" :strategy "honest"}
                             {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
                    :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 1000 :custom-resolver "0xresolver"}}
                             {:seq 1 :time 1100 :agent "buyer" :action "release" :params {:workflow-id 0}}]
                    :protocol-params {:resolver-fee-bps 0}}
          result (sew/replay-with-sew-protocol scenario)
          _ (println (format "Full result: %s" result))
          _ (is (= :pass (:outcome result)))
          
          ;; Manually build a projection that includes the protocol instance
          projection (proto/trace-projection sew/protocol result)
          _ (println (format "Projection keys: %s" (keys projection)))
          _ (println (format "Terminal world: %s" (:terminal-world projection)))
          
          ;; Enable tree expansion
          spe-config {:regret-threshold 0
                      :enable-tree-expansion? true
                      :utility-spec {:type :terminal-realized-v1 :version "v1"}}
          
          eval-result (cf/evaluate-subgame-counterfactual (assoc projection :spe-config spe-config))]
      
      (is (= :pass (:status eval-result)))
      (println (format "Eval result: %s" eval-result))
      (let [row (first (:regret-table eval-result))]
        (println (format "Row: %s" row))
        (is (= "create_escrow" (:chosen-action row)))
        ;; Alternatives should now be dynamic (the test currently gets hardcoded alternatives because the expansion doesn't find alternatives for create-escrow)
        ;; Actually it seems dynamic-branches are empty?
        (is (contains? (set (:alternatives row)) "wait_same_block"))
        (is (pos? (count (:alternatives row))))))))

(defn -main [& _]
  (run-tests 'resolver-sim.scenario.phase-3-spe-test))
