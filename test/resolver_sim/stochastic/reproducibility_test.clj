(ns resolver-sim.stochastic.reproducibility-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.correlated-failures :as corr]
            [resolver-sim.stochastic.liveness-failures :as liveness]
            [resolver-sim.oracle.detection :as detection]))

(deftest seeded-correlated-failures-are-deterministic
  (testing "shared-bias-effect with same rng seed yields identical decisions"
    (let [rng-a (rng/make-rng 99)
          rng-b (rng/make-rng 99)
          run   (fn [r] (corr/shared-bias-effect 0.4 true 5 0.6 r))]
      (is (= (:decisions (run rng-a))
             (:decisions (run rng-b)))))))

(deftest seeded-boredom-threshold-is-deterministic
  (testing "boredom-threshold with same rng seed yields identical participation"
    (let [rng-a (rng/make-rng 7)
          rng-b (rng/make-rng 7)
          run   (fn [r] (liveness/boredom-threshold 0.2 50 10 0.2 r))]
      (is (= (:will-participate? (run rng-a))
             (:will-participate? (run rng-b)))))))

(deftest seeded-oracle-detection-is-deterministic
  (testing "detect-fraud with :rng in params is reproducible"
    (let [base {:fraud-detection-probability 0.5
                :reversal-detection-probability 0.25
                :timeout-detection-probability 0.02}
          p1 (assoc base :rng (rng/make-rng 12345))
          p2 (assoc base :rng (rng/make-rng 12345))]
      (is (= (detection/detect-fraud p1)
             (detection/detect-fraud p2))))))

(deftest phase-i-oracle-uses-seeded-rng
  (testing "PhaseIOracle detect-fraud? respects :rng in params"
    (let [oracle (detection/->PhaseIOracle)
          params {:fraud-detection-probability 0.99
                  :rng (rng/make-rng 1)}
          params' (assoc params :rng (rng/make-rng 1))]
      (is (= (detection/detect-fraud? oracle nil params)
             (detection/detect-fraud? oracle nil params'))))))
