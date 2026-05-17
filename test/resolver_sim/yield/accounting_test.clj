(ns resolver-sim.yield.accounting-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.modules.aave :as aave]
            [resolver-sim.yield.modules.fixed :as fixed]))

(deftest test-aave-accrual-math
  (testing "Aave index-based accrual"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.0}}
                 :yield/rates   {:aave-v3 {"USDC" 0.10}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC" 
                                           :principal 1000 :shares 1000 :entry-index 1.0 :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (aave/aave-accrue world {:module/id :aave-v3} {:token "USDC" :dt 31536000})]
      (is (== 1.1 (get-in world' [:yield/indices :aave-v3 "USDC"])))
      (is (== 100 (get-in world' [:yield/positions "user1" :unrealized-yield]))))))

(deftest test-aave-withdraw-crystallizes-yield
  (testing "Aave withdraw realizes current unrealized yield before marking withdrawn"
    (let [world {:yield/indices {:aave-v3 {"USDC" 1.1}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token "USDC"
                                             :principal 1000 :shares 1000 :entry-index 1.0
                                             :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (aave/aave-withdraw world {:module/id :aave-v3} {:owner/id "user1"})
          pos    (get-in world' [:yield/positions "user1"])]
      (is (= :withdrawn (:status pos)))
      (is (== 100 (:realized-yield pos)))
      (is (zero? (:unrealized-yield pos))))))

(deftest test-fixed-rate-accrual-math
  (testing "Fixed rate principal-based accrual"
    (let [world {:yield/rates {:fixed-rate {"USDC" 0.05}}
                 :yield/positions {[:sew/escrow "user1"] {:owner/id [:sew/escrow "user1"] :module/id :fixed-rate :token "USDC"
                                           :principal 1000 :shares 1000 :entry-index 1.0 :status :active :unrealized-yield 0 :realized-yield 0}}}
          world' (fixed/fixed-accrue world {:module/id :fixed-rate} {:token "USDC" :dt 31536000})]
      (is (== 50 (get-in world' [:yield/positions [:sew/escrow "user1"] :unrealized-yield]))))))
