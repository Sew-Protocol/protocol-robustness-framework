(ns resolver-sim.protocols.sew.yield.failure-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.aave :as aave]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]))

(deftest test-aave-deposit-blocked-under-liquidity-shortfall
  (testing "Aave deposit throws when liquidity-mode is shortfall"
    (let [world {:yield/risk {:aave-v3 {"USDC" {:liquidity-mode :shortfall}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}}
          op    {:owner/id "user1" :amount 1000 :token "USDC"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"deposit unavailable"
           (aave/aave-deposit world {:module/id :aave-v3} op))))))

(deftest test-aave-withdraw-blocked-under-liquidity-shortfall
  (testing "Aave withdraw throws when liquidity-mode is shortfall"
    (let [world {:yield/risk {:aave-v3 {"USDC" {:liquidity-mode :shortfall}}}
                 :yield/indices {:aave-v3 {"USDC" 1.1}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                             :principal 1000 :shares 1000 :entry-index 1.0
                                             :status :active :unrealized-yield 0 :realized-yield 0}}}
          op    {:owner/id "user1"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"withdraw unavailable"
           (aave/aave-withdraw world {:module/id :aave-v3} op))))))

(deftest test-aave-emergency-unwind-marks-active-positions
  (testing "Emergency unwind marks matching active positions as :unwinding"
    (let [world {:yield/positions {"u1" {:owner/id "u1" :module/id :aave-v3 :token "USDC" :status :active}
                                   "u2" {:owner/id "u2" :module/id :aave-v3 :token "USDC" :status :withdrawn}
                                   "u3" {:owner/id "u3" :module/id :aave-v3 :token "DAI"  :status :active}}}
          world' (aave/aave-emergency-unwind world {:module/id :aave-v3} {:token "USDC"})]
      (is (= :unwinding (get-in world' [:yield/positions "u1" :status])))
      (is (= :withdrawn (get-in world' [:yield/positions "u2" :status])))
      (is (= :active (get-in world' [:yield/positions "u3" :status]))))))

(deftest test-aave-deposit-blocked-when-module-paused
  (testing "Aave deposit is blocked when module status is paused"
    (let [world {:yield/module-status {:aave-v3 :paused}
                 :yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}}
          op    {:owner/id "user1" :amount 1000 :token "USDC"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"module is paused"
           (aave/aave-deposit world {:module/id :aave-v3} op))))))

(deftest test-aave-deposit-blocked-when-disabled-for-new-deposits
  (testing "Aave deposit is blocked when module status disables new deposits"
    (let [world {:yield/module-status {:aave-v3 :disabled-for-new-deposits}
                 :yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}}
          op    {:owner/id "user1" :amount 1000 :token "USDC"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"disabled for new deposits"
           (aave/aave-deposit world {:module/id :aave-v3} op))))))

(deftest test-replay-yield-config-sets-module-status
  (testing "Scenario yield-config can set module status and still replay non-yield actions"
    (let [scenario {:scenario-id "yield-module-status-config"
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :yield-config {:modules {:aave-v3 {:module-status :disabled-for-new-deposits
                                                       :tokens {"USDC" {:initial-index 1.0 :apy 0.05}}}}}
                    :protocol-params {}
                    :agents [{:id "sender" :address "sender"}
                             {:id "recipient" :address "recipient"}]
                    :events [{:seq 0 :time 1000 :agent "sender" :action "create_escrow"
                              :params {:token "USDC" :to "recipient" :amount 10000}}]}
          result (replay/replay-with-protocol sew/protocol scenario)
          world  (proto/init-world sew/protocol scenario)]
      ;; create_escrow in replay path does not auto-deposit yield unless module is in snapshot,
      ;; and replay trace projection intentionally omits non-EVM world fields,
      ;; so validate status on init-world directly.
      (is (= :pass (:outcome result)))
      (is (= :disabled-for-new-deposits
             (get-in world [:yield/module-status :aave-v3]))))))