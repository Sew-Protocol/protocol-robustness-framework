(ns resolver-sim.yield.partial-fill-test
  "Tests for partial-fill settlement decisions: pro-rata, principal-first,
   waterfall modes, recovery, haircut, and multi-escrow isolation."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.position :as pos]))


(def base-position
  (pos/normalize-position
   {:owner/id "user1"
    :module/id :test-mod
    :token "USDC"
    :principal 10000
    :shares 10000
    :entry-index 1
    :realized-yield 500
    :unrealized-yield 300
    :deferred-yield 200
    :haircut-yield 0
    :status :active}))


(deftest test-full-fill-when-sufficient-liquidity
  (testing "Full fill when liquidity covers all claims"
    (let [decision (pf/calculate-fulfillment 20000 base-position)]
      (is (= :full-fill (:settlement-mode decision)))
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 500 (get-in decision [:filled :realized-yield])))
      (is (= 200 (get-in decision [:filled :deferred-yield])))
      (is (= 0 (get-in decision [:deferred :principal] 0)))
      (is (= 0 (get-in decision [:deferred :realized-yield] 0))))))

(deftest test-partial-fill-pro-rata
  (testing "Pro-rata partial fill distributes proportionally"
    (let [policy {:mode :pro-rata
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 5350 base-position policy)
          filled (:filled decision)
          deferred (:deferred decision)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (> (get filled :principal 0) 0) "Principal gets some")
      (is (> (get filled :realized-yield 0) 0) "Realized yield gets some")
      (is (> (get filled :deferred-yield 0) 0) "Deferred yield gets some"))))

(deftest test-partial-fill-principal-first
  (testing "Principal-first fill protects principal"
    (let [policy {:mode :principal-first
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 8000 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 8000 (get-in decision [:filled :principal])))
      (is (= 2000 (get-in decision [:deferred :principal])))
      (is (= 0 (get-in decision [:filled :realized-yield] 0))
          "Yield should get nothing when principal not fully covered"))))

(deftest test-partial-fill-principal-first-with-remainder
  (testing "Principal-first fill with liquidity exceeding principal"
    (let [policy {:mode :principal-first
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10300 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 0 (get-in decision [:deferred :principal] 0)))
      (is (>= (get-in decision [:filled :realized-yield] 0) 0)))))

(deftest test-partial-fill-waterfall
  (testing "Waterfall fill respects fill order"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10200 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 200 (get-in decision [:filled :realized-yield])))
      (is (= 0 (get-in decision [:filled :deferred-yield] 0))))))

(deftest test-waterfall-custom-fill-order
  (testing "Waterfall respects custom fill-order"
    (let [policy {:mode :waterfall
                  :fill-order [:realized-yield :principal :deferred-yield]
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 400 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 400 (get-in decision [:filled :realized-yield])))
      (is (zero? (get-in decision [:filled :principal] 0))))))

(deftest test-partial-fill-followed-by-recovery
  (testing "Post-partial-fill recovery restores claimable status"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable
                  :post-partial-fill-accrual :accrue-residual-as-unrealized}
          decision (pf/calculate-fulfillment 10200 base-position policy)
          updated-pos (pf/post-partial-fill-position base-position decision)]
      (is (:partial-fill-affected? updated-pos))
      (is (= :unwinding (:status updated-pos)))
      (is (< (:principal updated-pos 0) 10000) "Principal reduced by filled amount")
      (is (> (:unrealized-yield updated-pos 0) (:unrealized-yield base-position 0))
          "Deferred amounts accrued as unrealized"))))

(deftest test-partial-fill-followed-by-haircut
  (testing "Post-partial-fill position can track haircut"
    (let [decision {:settlement-mode :partial-fill
                    :filled {:principal 5000}
                    :deferred {:principal 3000}
                    :haircut {:principal 2000}
                    :requested {:principal 10000}
                    :policy {:unrealized-yield-treatment :not-claimable
                             :post-partial-fill-accrual :accrue-residual-as-unrealized}
                    :evidence {}}
          updated-pos (pf/post-partial-fill-position base-position decision)]
      (is (:partial-fill-affected? updated-pos))
      ;; After 5000 fill, the 5000 residual is reclassified: 3000 to unrealized, 2000 to haircut.
      ;; Principal bucket should be 0 to avoid double counting.
      (is (= 0 (:principal updated-pos)))
      (is (= 3300 (:unrealized-yield updated-pos)) "Base 300 + 3000 deferred")
      (is (= 2000 (:haircut-yield updated-pos))))))

(deftest test-multi-escrow-same-module-shortfall-isolation
  (testing "Shortfall in one escrow does not affect another in same module"
    (let [pos1 (assoc base-position :owner/id "user1")
          pos2 (assoc base-position :owner/id "user2")
          decision1 (pf/calculate-fulfillment 5350 pos1)
          decision2 (pf/calculate-fulfillment 20000 pos2)]
      (is (= :partial-fill (:settlement-mode decision1)))
      (is (= :full-fill (:settlement-mode decision2))
          "Position 2 with sufficient liquidity should fill completely"))))

(deftest test-separate-module-unaffected
  (testing "Shortfall in one module does not affect a different module"
    (let [pos-mod1 (assoc base-position :module/id :mod1)
          pos-mod2 (assoc base-position :module/id :mod2)
          decision1 (pf/calculate-fulfillment 5350 pos-mod1)
          decision2 (pf/calculate-fulfillment 20000 pos-mod2)]
      (is (= :partial-fill (:settlement-mode decision1)))
      (is (= :full-fill (:settlement-mode decision2))))))

(deftest test-unrealized-yield-not-claimable-by-default
  (testing "Unrealized yield is excluded from requested by default"
    (let [decision (pf/calculate-fulfillment 20000 base-position)]
      (is (not (contains? (:requested decision) :unrealized-yield))
          "Unrealized yield should not be in requested by default"))))

(deftest test-unrealized-yield-claimable-when-configured
  (testing "Unrealized yield can be included when configured"
    (let [policy {:mode :waterfall
                  :unrealized-yield-treatment :claimable}
          decision (pf/calculate-fulfillment 20000 base-position policy)]
      (is (contains? (:requested decision) :unrealized-yield)
          "Unrealized yield should be in requested when claimable"))))

(deftest test-zero-requested-buckets-excluded
  (testing "Buckets with zero value are excluded from requested"
    (let [pos (assoc base-position :realized-yield 0 :deferred-yield 0)
          decision (pf/calculate-fulfillment 20000 pos)]
      (is (not (contains? (:requested decision) :realized-yield)))
      (is (not (contains? (:requested decision) :deferred-yield))))))

(deftest test-partial-fill-preserves-total-accounting
  (testing "Filled + deferred = requested for each bucket under waterfall"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10200 base-position policy)]
      (doseq [k (keys (:requested decision))]
        (is (= (long (get-in decision [:requested k] 0))
               (+ (long (get-in decision [:filled k] 0))
                  (long (get-in decision [:deferred k] 0))))
            (str "Bucket " k " should conserve: filled + deferred = requested"))))))

(deftest test-largest-remainder-policy
  (testing "Largest-remainder rounding policy for partial fill"
    (let [policy {:mode :pro-rata
                  :rounding-policy :largest-remainder
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 100 [(assoc base-position
                                                    :principal 33 :realized-yield 33 :deferred-yield 34)] policy)
          total-filled (reduce + 0 (vals (:filled decision)))]
      (is (<= total-filled 100)
          "Never exceeds available liquidity"))))

(deftest test-principal-protective-floor-policy
  (testing "Principal-protective-floor rounding preserves principal"
    (let [policy {:mode :principal-first
                  :rounding-policy :principal-protective-floor
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10000 base-position policy)]
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 0 (get-in decision [:deferred :principal] 0))))))

(deftest test-partial-fill-predicate
  (testing "partial-fill? predicate"
    (is (pf/partial-fill? {:settlement-mode :partial-fill}))
    (is (not (pf/partial-fill? {:settlement-mode :full-fill})))))

(deftest test-apply-partial-fill-world-mutation
  (testing "apply-partial-fill updates world state correctly"
    (let [world {:yield/positions {"user1" base-position}
                 :total-held {"USDC" 100000}}
          decision (pf/calculate-fulfillment 10200 base-position)
          world' (pf/apply-partial-fill world base-position decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos'))
      (is (< (get-in world' [:total-held "USDC"] 0) 100000)
          "Total held should decrease by filled amount"))))

(deftest test-empty-position-full-fill
  (testing "Empty position always full-fills"
    (let [pos (assoc base-position :principal 0 :realized-yield 0 :deferred-yield 0)
          decision (pf/calculate-fulfillment 0 pos)]
      (is (= :full-fill (:settlement-mode decision))))))

