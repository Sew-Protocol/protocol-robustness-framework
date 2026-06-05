(ns resolver-sim.protocols.yield-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.protocols.registry :as preg]))

(def base-scenario
  {:scenario-id "yield-test-inline"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "vault" :address "0xVault"}]
   :protocol-params {:yield-profile "aave-v3" :default-owner-id "vault"}
   :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"}}}}}
   :options {:minimal true}
   :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
             :params {:token "USDC" :amount 10000}}
            {:seq 1 :time 87400 :agent "vault" :action "yield_accrue"
             :params {:token "USDC" :dt 86400}}]})

(deftest yield-protocol-satisfies-adapter
  (is (satisfies? proto/SimulationAdapter yp/protocol))
  (is (not (satisfies? proto/EconomicModel yp/protocol))))

(deftest registry-resolves-yield-v1
  (is (= yp/protocol (preg/get-protocol "yield-v1"))))

(deftest simple-replay-deposit-accrue
  (let [result (replay/replay-yield-scenario base-scenario)]
    (is (= :pass (:outcome result)))
    (is (pos? (get-in result [:metrics :yield/position-unrealized])))))

(deftest y01-long-accrue-expectations
  (let [scenario (assoc base-scenario
                        :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
                                  :params {:token "USDC" :amount 10000}}
                                 {:seq 1 :time 31537000 :agent "vault" :action "yield_accrue"
                                  :params {:token "USDC" :dt 31536000}}]
                        :expectations {:metrics [{:name :yield/position-principal :op := :value 10000}
                                                 {:name :yield/position-unrealized :op :> :value 400}]})
        result (replay/replay-yield-scenario scenario)]
    (is (= :pass (:outcome result)))))

(deftest y02-negative-yield-step
  (let [scenario (assoc base-scenario
                        :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
                                  :params {:token "USDC" :amount 10000}}
                                 {:seq 1 :time 31537000 :agent "vault" :action "yield_accrue"
                                  :params {:token "USDC" :dt 31536000}}
                                 {:seq 2 :time 31537001 :agent "vault" :action "set-yield-risk"
                                  :params {:module-id "aave-v3" :token "USDC" :apy -0.05
                                           :failure-modes ["negative-yield"]}}
                                 {:seq 3 :time 63073001 :agent "vault" :action "yield_accrue"
                                  :params {:token "USDC" :dt 31536000}}]
                        :expectations {:step-terminal [{:seq 3
                                                        :path ["yield-positions" "vault" "unrealized-yield"]
                                                        :op :<
                                                        :value 0}]})
        result (replay/replay-yield-scenario scenario)]
    (is (= :pass (:outcome result)))))
