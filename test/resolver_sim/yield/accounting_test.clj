(ns resolver-sim.yield.accounting-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.modules.aave :as aave]
            [resolver-sim.yield.modules.fixed :as fixed]))

(defn- run-aave-fragmented
  [world module token dt n]
  (let [per-step (quot dt n)
        remainder (- dt (* per-step n))
        world* (nth (iterate #(aave/aave-accrue % module {:token token :dt per-step}) world) n)]
    (if (pos? remainder)
      (aave/aave-accrue world* module {:token token :dt remainder})
      world*)))

(defn- run-fixed-fragmented
  [world module token dt n]
  (let [per-step (quot dt n)
        remainder (- dt (* per-step n))
        world* (nth (iterate #(fixed/fixed-accrue % module {:token token :dt per-step}) world) n)]
    (if (pos? remainder)
      (fixed/fixed-accrue world* module {:token token :dt remainder})
      world*)))

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

(deftest test-aave-accrual-partition-equivalence-bounded-drift
  (testing "One-shot vs fragmented Aave accrual stays within explicit drift budget"
    (let [token "USDC"
          module {:module/id :aave-v3}
          dt 31536000
          n 365
          max-rounding-drift 365
          world {:yield/indices {:aave-v3 {token 1.0}}
                 :yield/rates   {:aave-v3 {token 0.10}}
                 :yield/positions {"user1" {:owner/id "user1" :module/id :aave-v3 :token token
                                             :principal 1000 :shares 1000 :entry-index 1.0
                                             :status :active :unrealized-yield 0 :realized-yield 0}}}
          one-shot    (aave/aave-accrue world module {:token token :dt dt})
          fragmented  (run-aave-fragmented world module token dt n)
          y1          (get-in one-shot [:yield/positions "user1" :unrealized-yield])
          y2          (get-in fragmented [:yield/positions "user1" :unrealized-yield])
          drift       (Math/abs (long (- y1 y2)))]
      (is (<= drift max-rounding-drift)
          (str "Aave accrual drift " drift " exceeded budget " max-rounding-drift)))))

(deftest test-fixed-accrual-partition-equivalence-bounded-drift
  (testing "One-shot vs fragmented fixed accrual stays within explicit drift budget"
    (let [token "USDC"
          module {:module/id :fixed-rate}
          dt 31536000
          n 365
          ;; fixed-accrue rounds each op via integer division, so per-step error <= 1 unit
          max-rounding-drift n
          world {:yield/rates {:fixed-rate {token 0.05}}
                 :yield/positions {[:sew/escrow "user1"] {:owner/id [:sew/escrow "user1"]
                                                           :module/id :fixed-rate :token token
                                                           :principal 1000 :shares 1000 :entry-index 1.0
                                                           :status :active :unrealized-yield 0 :realized-yield 0}}}
          one-shot   (fixed/fixed-accrue world module {:token token :dt dt})
          fragmented (run-fixed-fragmented world module token dt n)
          y1         (get-in one-shot [:yield/positions [:sew/escrow "user1"] :unrealized-yield])
          y2         (get-in fragmented [:yield/positions [:sew/escrow "user1"] :unrealized-yield])
          drift      (Math/abs (long (- y1 y2)))]
      (is (<= drift max-rounding-drift)
          (str "Fixed accrual drift " drift " exceeded budget " max-rounding-drift)))))
