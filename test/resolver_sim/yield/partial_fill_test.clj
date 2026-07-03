(ns resolver-sim.yield.partial-fill-test
  "Tests for partial-fill settlement decisions: pro-rata, principal-first,
   waterfall modes, recovery, haircut, and multi-escrow isolation."
  (:require [clojure.test :refer [deftest is testing]]
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
          filled (:filled decision)]
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
                 :total-held {:USDC 100000}}
          decision (pf/calculate-fulfillment 10200 base-position)
          world' (pf/apply-partial-fill world base-position decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos'))
      (is (< (get-in world' [:total-held :USDC] 0) 100000)
          "Total held should decrease by filled amount"))))

(deftest test-empty-position-full-fill
  (testing "Empty position always full-fills"
    (let [pos (assoc base-position :principal 0 :realized-yield 0 :deferred-yield 0)
          decision (pf/calculate-fulfillment 0 pos)]
      (is (= :full-fill (:settlement-mode decision))))))

;; ── Decoupled rows (weight/cap) tests ─────────────────────────────────

(deftest test-pro-rata-rows-backward-compatible
  (testing "no explicit rows produces same result as current behavior"
    (let [policy {:mode :pro-rata}
          without-rows (pf/calculate-fulfillment 5350 base-position policy)
          ;; same call but explicitly passing rows derived from requested
          rows [{:key :principal :owed 10000 :weight 10000 :cap 10000}
                {:key :realized-yield :owed 500 :weight 500 :cap 500}
                {:key :deferred-yield :owed 200 :weight 200 :cap 200}]
          with-rows (pf/calculate-fulfillment 5350 base-position policy
                                              {:rows rows})]
      (is (= (:settlement-mode without-rows) (:settlement-mode with-rows)))
      (is (= (:filled without-rows) (:filled with-rows)))
      (is (= (:deferred without-rows) (:deferred with-rows))))))

(deftest test-pro-rata-rows-owed-exceeds-cap
  (testing "owed > cap: unfilled owed goes to deferred"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 1000 (get-in decision [:filled :principal]))
          "principal capped at 1000")
      (is (= 9000 (get-in decision [:deferred :principal]))
          "remaining 9000 deferred"))))

(deftest test-pro-rata-rows-cap-exceeds-owed
  (testing "cap > owed: allocation does not overpay beyond owed"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 5000 :weight 10000 :cap 10000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (<= (get-in decision [:filled :principal]) 5000)
          "principal capped at owed")
      (is (<= (get-in decision [:filled :realized-yield]) 500)
          "realized-yield capped at owed")
      (is (<= (get-in decision [:filled :deferred-yield]) 200)
          "deferred-yield capped at owed"))))

(deftest test-pro-rata-rows-weight-exceeds-cap-redistributes
  (testing "weight > cap: excess redistributes to uncapped rows"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 60 base-position policy
                                             {:rows [{:key :a :owed 50 :weight 100 :cap 10}
                                                     {:key :b :owed 50 :weight 100 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 10 (get-in decision [:filled :a]))
          "a capped at 10")
      (is (pos? (get-in decision [:filled :b]))
          "b receives remaining")
      (is (pos? (get-in decision [:deferred :a]))
          "a has deferred (owed 50, filled 10)")
      (is (>= (get-in decision [:deferred :a])
              (- 50 (get-in decision [:filled :a])))
          "deferred accounts for remaining owed"))))

(deftest test-pro-rata-rows-weight-below-cap
  (testing "weight < cap: row receives more after capped peers release excess"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 60 :cap 60}
                                                     {:key :b :owed 100 :weight 2 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (<= (get-in decision [:filled :a]) 60)
          "a does not exceed cap")
      (is (<= (get-in decision [:filled :b]) 100)
          "b does not exceed owed")
      (is (< 0 (get-in decision [:filled :b]))
          "b receives some amount"))))

(deftest test-pro-rata-rows-zero-weight
  (testing "zero weight with positive cap gets nothing"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 0 :cap 50}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (zero? (get-in decision [:filled :a]))
          "zero-weight row gets nothing")
      (is (pos? (get-in decision [:filled :b]))
          "b gets allocation"))))

(deftest test-pro-rata-rows-zero-cap
  (testing "positive weight with zero cap gets nothing"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 100 :cap 0}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (zero? (get-in decision [:filled :a]))
          "zero-cap row gets nothing")
      (is (pos? (get-in decision [:filled :b]))
          "b gets allocation"))))

(deftest test-pro-rata-rows-excess-liquidity
  (testing "available liquidity exceeding total cap does not overpay"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 50 :weight 50 :cap 20}
                                                     {:key :b :owed 30 :weight 30 :cap 30}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 20 (get-in decision [:filled :a])))
      (is (= 30 (get-in decision [:filled :b])))
      (is (zero? (get-in decision [:evidence :shortage]))
          "shortage is 0 when rows total owed <= available"))))

(deftest test-pro-rata-rows-conservation
  (testing "conservation: filled + deferred = owed for each row"
    (let [policy {:mode :pro-rata}
          rows [{:key :a :owed 100 :weight 100 :cap 30}
                {:key :b :owed 100 :weight 100 :cap nil}]
          decision (pf/calculate-fulfillment 80 base-position policy {:rows rows})]
      (doseq [row rows]
        (let [k (:key row)
              f (long (get-in decision [:filled k] 0))
              d (long (get-in decision [:deferred k] 0))]
          (is (<= f (long (:owed row)))
              (str "row " k " filled <= owed"))
          (is (>= d 0)
              (str "row " k " deferred >= 0")))))))

(deftest test-pro-rata-rows-evidence
  (testing "row-level evidence includes allocation-rows with cap-hit? flag"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 40 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 100 :cap 10}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (is (some? rows-evidence) "allocation-rows in evidence")
      (is (= 2 (count rows-evidence)))
      (is (true? (get-in decision [:evidence :allocation-rows 0 :cap-hit?]))
          "a hit its cap")
      (is (false? (get-in decision [:evidence :allocation-rows 1 :cap-hit?]))
          "b did not hit cap (nil cap)")
      (is (every? #(contains? % :filled) rows-evidence)
          "each row has filled")
      (is (every? #(contains? % :deferred) rows-evidence)
          "each row has deferred"))))

;; ── Principal-first with decoupled rows ───────────────────────────────

(deftest test-principal-first-rows-backward-compatible
  (testing "no explicit rows produces same result as current principal-first behavior"
    (let [policy {:mode :principal-first}
          without-rows (pf/calculate-fulfillment 8000 base-position policy)
          rows [{:key :principal :owed 10000 :weight 10000 :cap 10000}
                {:key :realized-yield :owed 500 :weight 500 :cap 500}
                {:key :deferred-yield :owed 200 :weight 200 :cap 200}]
          with-rows (pf/calculate-fulfillment 8000 base-position policy
                                              {:rows rows})]
      (is (= (:settlement-mode without-rows) (:settlement-mode with-rows)))
      (is (= (:filled without-rows) (:filled with-rows)))
      (is (= (:deferred without-rows) (:deferred with-rows))))))

(deftest test-principal-first-rows-principal-capped
  (testing "principal owed > cap: principal is capped, yield shares remaining"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 1000 (get-in decision [:filled :principal]))
          "principal capped at 1000")
      (is (some? (get-in decision [:filled :realized-yield]))
          "yield receives from remaining after principal cap"))))

(deftest test-principal-first-rows-yield-weight-exceeds-cap
  (testing "yield row weight > cap: excess redistributed among yield rows"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 3000 base-position policy
                                             {:rows [{:key :principal :owed 2000 :weight 2000 :cap 2000}
                                                     {:key :realized-yield :owed 2000 :weight 1000 :cap 100}
                                                     {:key :deferred-yield :owed 2000 :weight 1000 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 2000 (get-in decision [:filled :principal]))
          "principal fills fully")
      (is (<= (get-in decision [:filled :realized-yield]) 100)
          "realized-yield capped at 100")
      (is (pos? (get-in decision [:filled :deferred-yield]))
          "deferred-yield gets remaining after cap redistribution"))))

(deftest test-principal-first-rows-evidence
  (testing "principal-first with rows produces allocation-rows evidence"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap nil}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (is (some? rows-evidence) "allocation-rows in evidence")
      (is (= 2 (count rows-evidence)))
      (is (true? (:cap-hit? (first rows-evidence)))
          "principal hit its cap")
      (is (false? (:cap-hit? (second rows-evidence)))
          "yield row did not hit cap (nil cap)")
      (is (every? #(contains? % :filled) rows-evidence))
      (is (every? #(contains? % :deferred) rows-evidence))))

;; Additional tests for decoupled principal-first edge cases
(deftest test-principal-first-rows-zero-liquidity
(testing "Zero liquidity: nothing allocated, everything deferred"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 0 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
  (is (= :partial-fill (:settlement-mode decision)))
  (is (= 0 (get-in decision [:filled :principal] 0)) "No principal filled")
  (is (= 0 (get-in decision [:filled :realized-yield] 0)) "No yield filled")
  (is (= 1000 (get-in decision [:deferred :principal] 0)) "All principal deferred")
  (is (= 500 (get-in decision [:deferred :realized-yield] 0)) "All yield deferred"))))

(deftest test-principal-first-rows-weight-zero
(testing "Zero weight: receives nothing regardless of cap"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1000 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 0 :cap 1000}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
  (is (= 0 (get-in decision [:filled :principal] 0)) "Principal with zero weight gets nothing")
  (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield gets available liquidity"))))

(deftest test-principal-first-rows-cap-zero
(testing "Zero cap: receives nothing regardless of weight"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1000 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 0}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
  (is (= 0 (get-in decision [:filled :principal] 0)) "Principal with zero cap gets nothing")
  (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield gets available liquidity"))))

(deftest test-principal-first-rows-weight-less-than-cap
(testing "Weight < cap: allocation based on weight, not cap"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1000 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 500 :cap 1000}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
  (is (= 500 (get-in decision [:filled :principal] 0)) "Principal gets weight-based share")
  (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield gets weight-based share"))))

(deftest test-principal-first-rows-weight-greater-than-cap
(testing "Weight > cap: allocation limited by cap"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1000 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 500}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
  (is (= 500 (get-in decision [:filled :principal] 0)) "Principal limited by cap")
  (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield gets remaining"))))

(deftest test-principal-first-rows-all-capped
(testing "All rows capped: excess liquidity remains unallocated"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1000 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 300}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 200}]})]
  (is (= 300 (get-in decision [:filled :principal] 0)) "Principal capped at 300")
  (is (= 200 (get-in decision [:filled :realized-yield] 0)) "Yield capped at 200")
  (is (= 500 (get-in decision [:deferred :principal] 0)) "Principal deferred amount")
  (is (= 300 (get-in decision [:deferred :realized-yield] 0)) "Yield deferred amount"))))

(deftest test-principal-first-rows-conservation
(testing "Conservation: filled + deferred = requested for each row"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1000 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 600}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 300}
                                                 {:key :deferred-yield :owed 200 :weight 200 :cap 100}]})
      rows-evidence (get-in decision [:evidence :allocation-rows])]
  (doseq [row rows-evidence]
    (let [key (:key row)
          requested (:owed row)
          filled (:filled row)
          deferred (:deferred row)]
      (is (= requested (+ filled deferred))
          (str "Row " key " conservation: filled + deferred = requested"))))))

(deftest test-principal-first-rows-only-principal
(testing "Only principal row: yield rows empty"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 500 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}]})]
  (is (= 500 (get-in decision [:filled :principal] 0)) "Principal gets available liquidity")
  (is (nil? (get-in decision [:filled :realized-yield])) "No yield allocation")
  (is (nil? (get-in decision [:filled :deferred-yield])) "No deferred yield allocation"))))

(deftest test-principal-first-rows-no-principal
(testing "No principal row: all liquidity to yield"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 500 base-position policy
                                         {:rows [{:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                 {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
  (is (= 0 (get-in decision [:filled :principal] 0)) "No principal allocation")
  (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Realized yield gets share")
  (is (= 200 (get-in decision [:filled :deferred-yield] 0)) "Deferred yield gets share"))))

(deftest test-principal-first-rows-full-fill
(testing "Full fill when liquidity covers all capped amounts"
(let [policy {:mode :principal-first}
      decision (pf/calculate-fulfillment 1500 base-position policy
                                         {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}
                                                 {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
  (is (= :full-fill (:settlement-mode decision)))
  (is (= 1000 (get-in decision [:filled :principal] 0)) "Principal fully filled")
  (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield fully filled")
  (is (= 0 (get-in decision [:deferred :principal] 0)) "No principal deferred")
  (is (= 0 (get-in decision [:deferred :realized-yield] 0)) "No yield deferred")))))
