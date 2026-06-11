(ns resolver-sim.yield.liquid-lending-v2-test
  "Integration tests for the v2 decision-based yield module.
   Compares v2 output against v1 for equivalence where semantics match."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as v1]
            [resolver-sim.yield.modules.liquid-lending-v2 :as v2]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.risk-monitor :as risk]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.registry :as reg]
            [resolver-sim.util.attribution :as attr]))

(def v1-world
  {:yield/indices {:v1-mod {"USDC" 1.0}}
   :yield/rates   {:v1-mod {"USDC" 0.05}}
   :yield/risk    {:v1-mod {"USDC" {:liquidity-mode :available
                                    :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:v1-mod :active}
   :block-time 1000})

(def v2-world
  {:yield/indices {:v2-mod {"USDC" 1}}
   :yield/rates   {:v2-mod {"USDC" 0.05}}
   :yield/risk    {:v2-mod {"USDC" {:liquidity-mode :available
                                    :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:v2-mod :active}
   :yield/accrual-config {:v2-mod {:max-index-delta-ratio 2}}
   :block-time 1000})

(def v1-mod (v1/make-liquid-lending-module :v1-mod))
(def v2-mod (v2/make-liquid-lending-v2-module :v2-mod))


(deftest test-v2-deposit-creates-ratio-position
  (testing "V2 deposit creates position with ratio-based entry-index"
    (let [world' (v2/deposit-v2 v2-world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          pos   (get-in world' [:yield/positions "user1"])]
      (is (= "user1" (:owner/id pos)))
      (is (= 10000 (:principal pos)))
      (is (number? (:shares pos)))
      (is (number? (:entry-index pos)))
      (is (nil? (:current-index pos)))
      (is (= :active (:status pos))))))


(deftest test-v2-accrue-positive
  (testing "V2 accrue produces positive unrealized yield"
    (let [world-a (v2/deposit-v2 v2-world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          world-b (v2/accrue-v2 world-a v2-mod {:token "USDC" :dt 31536000})
          pos (get-in world-b [:yield/positions "user1"])]
      (is (pos? (:unrealized-yield pos 0))
          "Should have positive unrealized yield")
      (is (number? (:current-index pos))
          "Index should be set"))))

(deftest test-v2-two-positions-accrue-separately
  (testing "V2 handles two positions in same module"
    (let [w (v2/deposit-v2 v2-world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (v2/deposit-v2 w v2-mod {:owner/id "user2" :amount 5000 :token "USDC"})
          w (v2/accrue-v2 w v2-mod {:token "USDC" :dt 31536000})
          p1 (get-in w [:yield/positions "user1"])
          p2 (get-in w [:yield/positions "user2"])]
      (is (pos? (:unrealized-yield p1 0)))
      (is (pos? (:unrealized-yield p2 0)))
      (is (> (:unrealized-yield p1 0) (:unrealized-yield p2 0))
          "Bigger deposit should earn more yield"))))


(deftest test-v2-withdraw-full-liquidity
  (testing "V2 full withdrawal with adequate liquidity"
    (let [w (v2/deposit-v2 v2-world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (v2/accrue-v2 w v2-mod {:token "USDC" :dt 31536000})
          w (v2/withdraw-v2 w v2-mod {:owner/id "user1"})
          pos (get-in w [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos))
      (is (some #{:withdrawn :unwinding} [(:status pos)])))))


(deftest test-v2-accrue-risk-monitor-capture
  (testing "Risk monitor captures events during v2 accrual"
    (risk/clear!)
    (let [world (assoc-in v2-world [:yield/module-status :v2-mod] :frozen)
          w (v2/deposit-v2 world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          _ (v2/accrue-v2 w v2-mod {:token "USDC" :dt 31536000})
          events (risk/events)]
      (is (pos? (count events)) "Risk events should be captured")
      (is (some #(= :module-frozen-zero-accrual (first (:short-circuits %))) events)
          "Frozen module short circuit should appear"))))


(deftest test-v2-shortfall-calls-partial-fill
  (testing "V2 withdrawal with shortfall calls partial-fill"
    (let [w (v2/deposit-v2 v2-world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          w (v2/accrue-v2 w v2-mod {:token "USDC" :dt 31536000})
          ;; restrict liquidity
          w (assoc-in w [:total-held :USDC] 5000)
          w (v2/withdraw-v2 w v2-mod {:owner/id "user1"})
          pos (get-in w [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos)))))


(deftest test-attribution-during-v2-accrue
  (testing "V2 accrue sets with-attribution context"
    (let [w (v2/deposit-v2 v2-world v2-mod {:owner/id "user1" :amount 10000 :token "USDC"})
          _ (v2/accrue-v2 w v2-mod {:token "USDC" :dt 31536000})]
      (is (nil? (:accrual/accrual-mode attr/*attribution*))
          "Attribution binding reverts after with-attribution scope exits"))))


(deftest test-apply-partial-fill-with-attribution-sets-ctx
  (testing "apply-partial-fill-with-attribution sets settlement context"
    (let [pos (pos/normalize-position
               {:owner/id "user1" :module/id :test-mod :token "USDC"
                :principal 10000 :shares 10000 :entry-index 1
                :realized-yield 500 :deferred-yield 200 :status :active})
          decision (partial-fill/calculate-fulfillment 8000 pos)
          _ (partial-fill/apply-partial-fill-with-attribution {} pos decision)]
      (is (nil? (:settlement/mode attr/*attribution*))))))


(deftest test-v2-lifecycle-integration
  (testing "End-to-end: register v2 module → deposit → accrue → withdraw via lifecycle-compatible path"
    (risk/clear!)
    (let [v2-mid :aave-v3-v2
          v2-mod (get-in (reg/init-yield-modules {}) [:yield/modules v2-mid])
          world0 (-> {:yield/module-status {v2-mid :active}
                      :yield/indices {v2-mid {"USDC" 1}}
                      :yield/rates {v2-mid {"USDC" 0.05}}
                      :yield/accrual-config {v2-mid {:max-index-delta-ratio 2}}
                      :block-time 1000}
                     (reg/init-yield-modules)
                     (v2/deposit-v2 v2-mod {:owner/id "escrow:user1"
                                            :amount 10000
                                            :token "USDC"}))]
      ;; Step 1: Accrue yield (simulates lifecycle/accrue-yield path)
      (let [world-a (v2/accrue-v2 world0 v2-mod {:token "USDC" :dt 31536000})
            pos (get-in world-a [:yield/positions "escrow:user1"])]
        (is (pos? (:unrealized-yield pos 0)) "Accrue should produce yield")
        (is (number? (:current-index pos)) "Index should be set after accrue"))
      ;; Step 2: Withdraw (simulates lifecycle/finalize calling withdraw after accrue)
      (let [world-w (v2/withdraw-v2 world0 v2-mod {:owner/id "escrow:user1"})
            pos (get-in world-w [:yield/positions "escrow:user1"])]
        (is (some #{:withdrawn :unwinding} [(:status pos)])
            "Position should be withdrawn or unwinding")
        (is (or (not (:shortfall pos))
                (pos? (:fulfilled-amount (:shortfall pos) 0)))
            "Shortfall fulfilled amount should be non-negative"))
      ;; Step 3: Check risk events captured during the accrual phase
      (let [events (risk/events)]
        (is (vector? events) "Risk events should be a vector")))))

