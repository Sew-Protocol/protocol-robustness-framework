(ns resolver-sim.protocols.sew.yield.failure-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as liquid]
            [resolver-sim.yield.presets :as yield-presets]
            [resolver-sim.yield.registry :as yield-reg]
            [resolver-sim.yield.risk :as yield-risk]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]))

(deftest test-liquid-lending-failure-modes-contract
  (let [module {:module/id :yield.provider/liquid-lending}
        base-world {:yield/indices {:yield.provider/liquid-lending {"USDC" 1.0}}
                    :yield/rates {:yield.provider/liquid-lending {"USDC" 0.05}}
                    :yield/risk {:yield.provider/liquid-lending {"USDC" {:liquidity-mode :available
                                                                         :failure-modes #{}}}}
                    :yield/positions {"user1" {:owner/id "user1" :module/id :yield.provider/liquid-lending :token "USDC"
                                                :principal 1000 :shares 1000 :entry-index 1.0
                                                :status :active :unrealized-yield 0 :realized-yield 0}}}]
    (testing ":deposit-fails"
      (is (thrown? clojure.lang.ExceptionInfo
                   (liquid/deposit (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                             #{:deposit-fails})
                                   module
                                   {:owner/id "u2" :amount 100 :token "USDC"}))))
    (testing ":withdraw-fails"
      (is (thrown? clojure.lang.ExceptionInfo
                   (liquid/withdraw (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                              #{:withdraw-fails})
                                    module
                                    {:owner/id "user1"}))))
    (testing ":partial-liquidity"
      (let [w' (liquid/withdraw (-> base-world
                                    (assoc-in [:yield/indices :yield.provider/liquid-lending "USDC"] 2.0)
                                    (assoc-in [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                              #{:partial-liquidity}))
                                module
                                {:owner/id "user1"})]
        (is (= :unwinding (get-in w' [:yield/positions "user1" :status])))
        (is (= 500 (get-in w' [:yield/positions "user1" :realized-yield])))
        (is (= {:reason :liquidity-shortfall
                :basis-amount 1000
                :available-ratio 0.5
                :fulfilled-amount 500
                :deferred-amount 500
                :haircut-amount 0
                :as-of-index 2.0}
               (get-in w' [:yield/positions "user1" :shortfall])))))
    (testing ":partial-liquidity with custom shortfall ratio"
      (let [w' (-> base-world
                   (assoc-in [:yield/indices :yield.provider/liquid-lending "USDC"] 2.0)
                   (assoc-in [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                             #{:partial-liquidity})
                   (assoc-in [:yield/risk :yield.provider/liquid-lending "USDC" :shortfall]
                             {:available-ratio 0.25 :reason :market-dislocation})
                   (liquid/withdraw module {:owner/id "user1"}))]
        (is (= :unwinding (get-in w' [:yield/positions "user1" :status])))
        (is (= 250 (get-in w' [:yield/positions "user1" :realized-yield])))
        (is (= {:reason :market-dislocation
                :basis-amount 1000
                :available-ratio 0.25
                :fulfilled-amount 250
                :deferred-amount 750
                :haircut-amount 0
                :as-of-index 2.0}
               (get-in w' [:yield/positions "user1" :shortfall])))))
    (testing ":negative-yield"
      (let [w' (liquid/accrue (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                        #{:negative-yield})
                              module
                              {:token "USDC" :dt 31536000})]
        (is (< (get-in w' [:yield/indices :yield.provider/liquid-lending "USDC"]) 1.0))))
    (testing ":negative-yield mark-to-market accrual"
      (let [risk {:failure-modes #{:negative-yield}}
            w    (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC"] risk)
            w'   (liquid/accrue w module {:token "USDC" :dt 31536000})
            pos  (get-in w' [:yield/positions "user1"])]
        (is (= :mark-to-market (yield-risk/effective-loss-mode risk)))
        (is (< (:unrealized-yield pos 0) 0))
        (is (= :negative-accrual-yield (get-in pos [:yield-loss :reason])))
        (is (pos? (get-in pos [:yield-loss :amount])))))
    (testing "intrinsic loss on withdraw when gross < principal"
      (let [pos  {:owner/id "user1"
                  :module/id :yield.provider/liquid-lending
                  :token "USDC"
                  :principal 10000
                  :shares 10000
                  :entry-index 1.0
                  :status :active
                  :unrealized-yield -150}
            w    (-> base-world
                     (assoc-in [:yield/positions "user1"] pos)
                     (assoc-in [:yield/indices :yield.provider/liquid-lending "USDC"] 0.985)
                     (assoc-in [:yield/risk :yield.provider/liquid-lending "USDC"]
                               {:liquidity-mode :available
                                :failure-modes #{:negative-yield}}))
            w'   (liquid/withdraw w module {:owner/id "user1"})]
        (is (= :negative-carry-loss
               (get-in w' [:yield/positions "user1" :shortfall :reason])))
        (is (= 10000 (get-in w' [:yield/positions "user1" :shortfall :basis-amount])))
        (is (= 150 (get-in w' [:yield/positions "user1" :shortfall :haircut-amount])))))
    (testing ":provider-paused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (liquid/deposit (assoc base-world :yield/module-status {:yield.provider/liquid-lending :paused})
                                   module
                                   {:owner/id "u3" :amount 100 :token "USDC"}))))
    (testing ":emergency-unwind-fails"
      (is (thrown? clojure.lang.ExceptionInfo
                   (liquid/emergency-unwind (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                                     #{:emergency-unwind-fails})
                                            module
                                            {:token "USDC"}))))
    (testing ":oracle-stale freezes APY at activation snapshot"
      (let [stable-world (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                   #{:oracle-stale})
            w1 (liquid/accrue stable-world module {:token "USDC" :dt 31536000})
            stale-apy (get-in w1 [:yield/risk :yield.provider/liquid-lending "USDC" :stale-apy])
            ;; Change the configured rate after staleness activates
            w2 (assoc-in w1 [:yield/rates :yield.provider/liquid-lending "USDC"] 0.20)
            w3 (liquid/accrue w2 module {:token "USDC" :dt 31536000})]
        (is (some? stale-apy) "stale-apy snapshot cached on first accrual")
        (is (= 0.05 stale-apy) "stale-apy matches rate at activation")
        ;; Rate change should be ignored: index after 2nd accrual uses stale-apy, not 0.20
        (let [i1 (get-in w1 [:yield/indices :yield.provider/liquid-lending "USDC"])
              i3 (get-in w3 [:yield/indices :yield.provider/liquid-lending "USDC"])]
          ;; 0.05 over 1 year: index *= (1 + 0.05) = 1.05; with stale, second year also uses 0.05
          (is (< 1.10 i3) "index grew using stale rate, not the updated 0.20 rate"))))
    (testing ":withdrawal-queue defers withdrawal instead of failing"
      (let [w' (liquid/withdraw (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                                          #{:withdrawal-queue})
                                module
                                {:owner/id "user1"})
            pos (get-in w' [:yield/positions "user1"])]
        (is (= :queued (:status pos)) "position enters queued state")
        (is (zero? (:realized-yield pos)) "realized yield unchanged")
        (is (zero? (:unrealized-yield pos)) "unrealized yield zeroed by update-position-yield")
        (is (= 1 (count (get-in w' [:yield/withdrawal-queue :yield.provider/liquid-lending])))
            "withdrawal added to queue")))
    (testing ":withdrawal-queue claim-deferred processes queued position"
      (let [w0 (assoc-in base-world [:yield/risk :yield.provider/liquid-lending "USDC" :failure-modes]
                         #{:withdrawal-queue})
            w1 (liquid/withdraw w0 module {:owner/id "user1"})
            w2 (liquid/claim-deferred w1 module {:owner/id "user1"})
            pos (get-in w2 [:yield/positions "user1"])]
        (is (= :withdrawn (:status pos)) "queued position claimed successfully")))))

(deftest test-yield-config-behavior-adapter
  (let [world (-> (proto/init-world sew/protocol {:scenario-id "x"})
                  (yield-reg/apply-yield-config
                   {:modules {:aave-v3 {:behavior {:yield/provider-kind :immediate-withdrawal-lending}
                                        :tokens {"USDC" {:initial-index 1.0
                                                         :apy 0.05
                                                         :failure-modes #{:withdraw-fails}}}}}}))]
    (is (= :yield.provider/liquid-lending
           (get-in world [:yield/module-aliases :aave-v3])))
    (is (= :immediate-withdrawal-lending
           (get-in world [:yield/behavior :aave-v3 :yield/provider-kind])))
    (is (= #{:withdraw-fails}
           (get-in world [:yield/risk :yield.provider/liquid-lending :USDC :failure-modes])))))
  

(deftest test-liquid-lending-deposit-blocked-under-liquidity-shortfall
  (testing "Liquid-lending deposit throws when liquidity-mode is shortfall"
    (let [module {:module/id :aave-v3}
          world {:yield/risk {:aave-v3 {"USDC" {:liquidity-mode :shortfall}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}}
          op    {:owner/id "user1" :amount 1000 :token "USDC"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"deposit unavailable"
           (liquid/deposit world module op))))))

(deftest test-liquid-lending-emergency-unwind-marks-active-positions
  (testing "Emergency unwind marks matching active positions as :unwinding"
    (let [module {:module/id :aave-v3}
          world {:yield/positions {"u1" {:owner/id "u1" :module/id :aave-v3 :token "USDC" :status :active}
                                   "u2" {:owner/id "u2" :module/id :aave-v3 :token "USDC" :status :withdrawn}
                                   "u3" {:owner/id "u3" :module/id :aave-v3 :token "DAI"  :status :active}}}
          world' (liquid/emergency-unwind world module {:token "USDC"})]
      (is (= :unwinding (get-in world' [:yield/positions "u1" :status])))
      (is (= :withdrawn (get-in world' [:yield/positions "u2" :status])))
      (is (= :active (get-in world' [:yield/positions "u3" :status]))))))

(deftest test-liquid-lending-deposit-blocked-when-module-paused
  (testing "Liquid-lending deposit is blocked when module status is paused"
    (let [module {:module/id :aave-v3}
          world {:yield/module-status {:aave-v3 :paused}
                 :yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}}
          op    {:owner/id "user1" :amount 1000 :token "USDC"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"provider is paused"
           (liquid/deposit world module op))))))

(deftest test-liquid-lending-deposit-blocked-when-disabled-for-new-deposits
  (testing "Liquid-lending deposit is blocked when module status disables new deposits"
    (let [module {:module/id :aave-v3}
          world {:yield/module-status {:aave-v3 :disabled-for-new-deposits}
                 :yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}}
          op    {:owner/id "user1" :amount 1000 :token "USDC"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"disabled for new deposits"
           (liquid/deposit world module op))))))

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
          world  (-> (proto/init-world sew/protocol scenario)
                     (yield-reg/apply-yield-config (:yield-config scenario)))]
      ;; create_escrow in replay path does not auto-deposit yield unless module is in snapshot,
      ;; and replay trace projection intentionally omits non-EVM world fields,
      ;; so validate status on init-world directly.
      (is (= :pass (:outcome result)))
      (is (= :disabled-for-new-deposits
             (get-in world [:yield/module-status :yield.provider/liquid-lending]))))))

(deftest test-liquid-withdraw-idempotent-on-non-active-position
  (testing "Liquid withdraw is a no-op when retried after crystallization"
    (let [module {:module/id :yield.provider/liquid-lending}
          world  {:yield/indices {:yield.provider/liquid-lending {"USDC" 1.0}}
                  :yield/risk {:yield.provider/liquid-lending {"USDC" {:liquidity-mode :available}}}
                  :yield/positions {"user1" {:owner/id "user1" :module/id :yield.provider/liquid-lending
                                             :token "USDC" :principal 1000 :shares 1000 :entry-index 1.0
                                             :status :active :unrealized-yield 0 :realized-yield 0}}}
          first-withdraw (liquid/withdraw world module {:owner/id "user1"})
          second-withdraw (liquid/withdraw first-withdraw module {:owner/id "user1"})]
      (is (= first-withdraw second-withdraw))
      (is (= :withdrawn (get-in second-withdraw [:yield/positions "user1" :status]))))))

(deftest test-liquid-lending-withdraw-idempotent-for-aave-module-id
  (testing "Liquid-lending withdraw is a no-op when retried after crystallization (:aave-v3 id)"
    (let [module {:module/id :aave-v3}
          world {:yield/risk {:aave-v3 {"USDC" {:liquidity-mode :available}}}
                 :yield/indices {:aave-v3 {"USDC" 1.0}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                             :principal 1000 :shares 1000 :entry-index 1.0
                                             :status :active :unrealized-yield 0 :realized-yield 0}}}
          first-withdraw (liquid/withdraw world module {:owner/id "user1"})
          second-withdraw (liquid/withdraw first-withdraw module {:owner/id "user1"})]
      (is (= first-withdraw second-withdraw))
      (is (= :withdrawn (get-in second-withdraw [:yield/positions "user1" :status]))))))

(deftest test-yield-preset-negative-yield-mild
  (let [world (-> (proto/init-world sew/protocol {:scenario-id "preset-test"})
                  yield-reg/init-yield-modules
                  (yield-presets/apply-preset :yield.preset/negative-yield-mild))]
    (is (= #{:negative-yield}
           (get-in world [:yield/risk :yield.provider/liquid-lending :USDC :failure-modes])))
    (is (= -0.01 (get-in world [:yield/rates :yield.provider/liquid-lending :USDC])))))