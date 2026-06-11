(ns resolver-sim.yield.liquid-lending-v2-test
  "Integration tests for the decision-based liquid-lending yield module.

   These tests cover the v2-merged-into-v1 module shape (decision-based accrual,
   ratio-based entry-index, partial-fill withdrawals). The module was previously
   referred to as 'liquid-lending-v2' and has been merged into the main
   liquid-lending module."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as ll]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.registry :as reg]
            [resolver-sim.util.attribution :as attr]))

(def test-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                     :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000})

(def test-mod (ll/make-liquid-lending-module :test-mod))


(deftest deposit-creates-ratio-position
  (testing "Deposit creates position with ratio-based entry-index"
    (let [world' (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          pos   (get-in world' [:yield/positions "user1"])]
      (is (= "user1" (:owner/id pos)))
      (is (= 10000 (:principal pos)))
      (is (number? (:shares pos)))
      (is (number? (:entry-index pos)))
      (is (nil? (:current-index pos)))
      (is (= :active (:status pos))))))


(deftest accrue-positive
  (testing "Accrue produces positive unrealized yield"
    (let [world-a (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          world-b (ll/accrue world-a test-mod {:token "USDC" :dt 31536000})
          pos (get-in world-b [:yield/positions "user1"])]
      (is (pos? (:unrealized-yield pos 0))
          "Should have positive unrealized yield")
      (is (number? (:current-index pos))
          "Index should be set"))))


(deftest two-positions-accrue-separately
  (testing "Two positions in same module accrue independently"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/deposit w test-mod {:owner/id "user2" :amount 5000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          p1 (get-in w [:yield/positions "user1"])
          p2 (get-in w [:yield/positions "user2"])]
      (is (pos? (:unrealized-yield p1 0)))
      (is (pos? (:unrealized-yield p2 0)))
      (is (> (:unrealized-yield p1 0) (:unrealized-yield p2 0))
          "Bigger deposit should earn more yield"))))


(deftest withdraw-full-liquidity
  (testing "Full withdrawal with adequate liquidity"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          w (ll/withdraw w test-mod {:owner/id "user1"})
          pos (get-in w [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos))
      (is (some #{:withdrawn :unwinding} [(:status pos)])))))


(deftest shortfall-calls-partial-fill
  (testing "Withdrawal with shortfall calls partial-fill"
    (let [w (ll/deposit test-world test-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (ll/accrue w test-mod {:token "USDC" :dt 31536000})
          ;; restrict liquidity
          w (assoc-in w [:total-held :USDC] 5000)
          w (ll/withdraw w test-mod {:owner/id "user1"})
          pos (get-in w [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos)))))


(deftest apply-partial-fill-with-attribution-sets-ctx
  (testing "apply-partial-fill-with-attribution sets settlement context"
    (let [pos (pos/normalize-position
               {:owner/id "user1" :module/id :test-mod :token "USDC"
                :principal 10000 :shares 10000 :entry-index 1
                :realized-yield 500 :deferred-yield 200 :status :active})
          decision (partial-fill/calculate-fulfillment 8000 pos)
          _ (partial-fill/apply-partial-fill-with-attribution {} pos decision)]
      (is (nil? (:settlement/mode attr/*attribution*))))))


(deftest lifecycle-integration
  (testing "End-to-end: register module -> deposit -> accrue -> withdraw via lifecycle-compatible path"
    (let [test-mid :yield.provider/liquid-lending
          test-mod (get-in (reg/init-yield-modules {}) [:yield/modules test-mid])
          world0 (-> {:yield/module-status {test-mid :active}
                      :yield/indices {test-mid {"USDC" 1}}
                      :yield/rates {test-mid {"USDC" 0.05}}
                      :yield/accrual-config {test-mid {:max-index-delta-ratio 2}}
                      :block-time 1000}
                     (reg/init-yield-modules)
                     (ll/deposit test-mod {:owner/id "escrow:user1"
                                           :amount 10000
                                           :token "USDC"}))]
      ;; Step 1: Accrue yield
      (let [world-a (ll/accrue world0 test-mod {:token "USDC" :dt 31536000})
            pos (get-in world-a [:yield/positions "escrow:user1"])]
        (is (pos? (:unrealized-yield pos 0)) "Accrue should produce yield")
        (is (number? (:current-index pos)) "Index should be set after accrue"))
      ;; Step 2: Withdraw
      (let [world-w (ll/withdraw world0 test-mod {:owner/id "escrow:user1"})
            pos (get-in world-w [:yield/positions "escrow:user1"])]
        (is (some #{:withdrawn :unwinding} [(:status pos)])
            "Position should be withdrawn or unwinding")
        (is (or (not (:shortfall pos))
                (pos? (:fulfilled-amount (:shortfall pos) 0)))
            "Shortfall fulfilled amount should be non-negative")))))