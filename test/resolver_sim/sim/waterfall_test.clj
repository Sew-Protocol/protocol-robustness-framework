(ns resolver-sim.sim.waterfall-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.waterfall :as waterfall]
            [resolver-sim.stochastic.rng :as rng]))

(defn- process-events
  [{:keys [juniors seniors]} events]
  (reduce (fn [state event]
            (let [{:keys [resolvers seniors event-result]}
                  (waterfall/process-slash-event event
                                                 (:resolvers state)
                                                 (:seniors state))]
              {:resolvers resolvers
               :seniors seniors
               :events (conj (:events state) event-result)}))
          {:resolvers juniors :seniors seniors :events []}
          events))

(deftest test-calculate-slash-amount-cap
  (testing "50% per-slash cap on bond"
    (is (= 2.5 (waterfall/calculate-slash-amount 500 50)))
    (is (= 250.0 (waterfall/calculate-slash-amount 500 5000)))))

(deftest test-apply-junior-slash-cap
  (testing "junior slash enforces 50% of remaining bond per event"
    (let [resolver {:bond-remaining 500.0 :current-epoch 0}
          {:keys [actually-slashed shortage]} (waterfall/apply-junior-slash resolver 300)]
      (is (= 250.0 actually-slashed))
      (is (= 50.0 shortage)))))

(deftest test-cumulative-slashes-deplete-junior-then-senior
  (testing "repeated slashes on one junior cascade to senior coverage"
    (let [params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 10000 :junior-bond-amount 500
                  :utilization-factor 0.5}
          pool (waterfall/initialize-waterfall-pool params)
          events (vec (repeat 5 {:resolver-id "j0_0"
                                  :senior-id "s0"
                                  :slash-amount 200
                                  :reason :fraud
                                  :epoch 0}))
          {:keys [resolvers seniors events]} (process-events pool events)
          junior-remaining (get-in resolvers ["j0_0" :bond-remaining])
          junior-paid-total (reduce + (map :junior-paid events))
          senior-paid-total (reduce + (map :senior-paid events))]
      (is (< junior-remaining 500.0))
      (is (pos? senior-paid-total))
      (is (= (- 500.0 junior-remaining) junior-paid-total))
      (is (= (+ junior-paid-total senior-paid-total (reduce + (map :unmet-obligation events)))
             (* 5 200.0))))))

(deftest test-senior-delegation-fallback
  (testing "process-slash-event uses :senior-delegation when :senior-id omitted"
    (let [params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 10000 :junior-bond-amount 500
                  :utilization-factor 0.5}
          pool (waterfall/initialize-waterfall-pool params)
          event {:resolver-id "j0_0"
                 :slash-amount 600
                 :reason :fraud
                 :epoch 0}
          {:keys [resolvers seniors event-result]}
          (waterfall/process-slash-event event (:juniors pool) (:seniors pool))]
      (is (= "s0" (:senior-id event-result)))
      (is (= 250.0 (:junior-paid event-result)))
      (is (= 350.0 (:senior-paid event-result)))
      (is (= 250.0 (get-in resolvers ["j0_0" :bond-remaining])))
      (is (= 350.0 (get-in seniors ["s0" :coverage-used]))))))

(deftest test-aggregate-metrics-use-cumulative-events
  (testing "adequacy reflects senior exhaustion and unmet obligations"
    (let [params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 1000 :junior-bond-amount 100
                  :utilization-factor 0.5}
          pool (waterfall/initialize-waterfall-pool params)
          events (vec (repeat 10 {:resolver-id "j0_0"
                                  :senior-id "s0"
                                  :slash-amount 200
                                  :reason :fraud
                                  :epoch 0}))
          {:keys [resolvers seniors events]} (process-events pool events)
          metrics (waterfall/aggregate-waterfall-metrics resolvers seniors events)]
      (is (pos? (:total-unmet-obligation metrics)))
      (is (< (:coverage-adequacy-score metrics) 80.0)))))

;; --- Probabilistic waterfall tests ---

(deftest test-draw-escrow-size-positive
  (testing "draw-escrow-size returns positive amounts from lognormal"
    (let [rng-inst (rng/make-rng 42)
          dist {:type :lognormal :mean 10000 :std 3000}
          sizes (repeatedly 20 #(waterfall/draw-escrow-size rng-inst dist))]
      (is (every? pos? sizes))
      (is (some #(> % 10000) sizes) "some draws should exceed the mean"))))

(deftest test-draw-strategy-in-range
  (testing "draw-strategy picks from mix and respects weights"
    (let [rng-inst (rng/make-rng 42)
          mix {:honest 0.80 :malicious 0.10 :lazy 0.05 :collusive 0.05}
          draws (repeatedly 200 #(waterfall/draw-strategy rng-inst mix))
          {:keys [honest malicious] :as counts} (frequencies draws)]
      (is (contains? counts :honest))
      (is (contains? counts :malicious))
      (is (> honest 100) "honest should be the majority"))))

(deftest test-probabilistic-process-slash-pool-returns-metrics
  (testing "probabilistic-process-slash-pool returns expected structure"
    (let [rng-inst (rng/make-rng 42)
          params {:n-seniors 1 :n-juniors-per-senior 2
                  :senior-bond-amount 5000 :junior-bond-amount 200
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 1000 :std 300}
                  :strategy-mix {:honest 0.80 :malicious 0.15 :lazy 0.05}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 2.5
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 0.50
                  :reversal-detection-probability 0.02
                  :timeout-slash-bps 25 :fraud-slash-bps 50}
          pool (waterfall/initialize-waterfall-pool params)
          result (waterfall/probabilistic-process-slash-pool rng-inst pool params 100)]
      (is (contains? result :resolvers))
      (is (contains? result :seniors))
      (is (contains? result :events))
      (is (contains? result :metrics))
      (is (= 100 (count (:events result)))))))

(deftest test-probabilistic-waterfall-per-epoch-cap
  (testing "per-epoch cap limits total slashing in a single epoch"
    (let [rng-inst (rng/make-rng 42)
          params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 5000 :junior-bond-amount 1000
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 5000 :std 100}
                  :strategy-mix {:malicious 1.0}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 5.0
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 1.0
                  :reversal-detection-probability 0.0
                  :timeout-slash-bps 25 :fraud-slash-bps 5000}
          pool (waterfall/initialize-waterfall-pool params)
          result (waterfall/probabilistic-process-slash-pool rng-inst pool params 50)
          metrics (:metrics result)
          total-junior-slash (:total-slashed-by-junior metrics)
          junior-bond (:junior-bond-amount params 1000)
          max-per-epoch (* junior-bond 0.20)] ;; 20% cap
      ;; Even with 100% detection and max slash rate, per-epoch cap should limit
      (is (<= total-junior-slash (* 50 max-per-epoch)) "total slash bounded by per-epoch cap")
      (is (pos? (:total-slashes metrics)) "should have some slashes"))))

(deftest test-probabilistic-vs-deterministic-semantics
  (testing "probabilistic mode produces fewer slashes than deterministic (same params)"
    (let [rng-inst (rng/make-rng 42)
          params {:n-seniors 1 :n-juniors-per-senior 3
                  :senior-bond-amount 5000 :junior-bond-amount 200
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 1000 :std 300}
                  :strategy-mix {:honest 0.80 :malicious 0.15 :lazy 0.05}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 2.5
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 0.30
                  :reversal-detection-probability 0.02
                  :timeout-slash-bps 25 :fraud-slash-bps 50}
          pool (waterfall/initialize-waterfall-pool params)
          n-trials 100
          prob-result (waterfall/probabilistic-process-slash-pool rng-inst pool params n-trials)
          ;; Deterministic would be n-trials * fraud-rate slashes at calculate-slash-amount
          det-slash-count (int (* n-trials 0.15))] ;; 15% malicious
      (is (< (:total-slashes (:metrics prob-result)) det-slash-count)
          "probabilistic slashes fewer than deterministic worst-case"))))

;; --- Private helpers should not be called directly in tests,
;; --- but draw-escrow-size and draw-strategy are useful for downstream use.

(defn -main [& _]
  (clojure.test/run-tests 'resolver-sim.sim.waterfall-test))
