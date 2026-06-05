(ns resolver-sim.core-tests
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.types :as types]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng :as rng]))

(deftest fee-calculation-test
  (testing "Fee calculation"
    (is (== (econ/calculate-fee 1000 150) 15))
    (is (== (econ/calculate-fee 10000 100) 100))
    (is (== (econ/calculate-fee 0 150) 0))))

(deftest bond-calculation-test
  (testing "Bond calculation"
    (is (== (econ/calculate-bond 1000 700) 70))
    (is (== (econ/calculate-bond 10000 500) 500))))

(deftest slashing-loss-test
  (testing "Slashing calculation"
    (is (== (econ/calculate-slashing-loss 100 2.5) 250))
    (is (== (econ/calculate-slashing-loss 0 2.5) 0))))

(deftest honest-ev-test
  (testing "Honest resolver EV is positive"
    (let [fee 100
          appeal-prob 0.1
          ev (econ/honest-expected-value fee appeal-prob)]
      (is (>= ev 0)))))

(deftest malice-ev-test
  (testing "Malicious resolver EV calculation"
    (let [fee 100
          slashing 250
          detection 0.1
          ev (econ/malicious-expected-value fee slashing detection)]
      ; EV = 100 - 250*0.1 = 75
      (is (== ev 75)))))

(deftest rng-determinism-test
  (testing "Same seed produces same sequence"
    (let [rng1 (rng/make-rng 42)
          rng2 (rng/make-rng 42)
          v1 (rng/next-double rng1)
          v2 (rng/next-double rng2)]
      (is (== v1 v2)))))

(deftest rng-split-determinism-test
  (testing "RNG splits are deterministic"
    (let [rng1 (rng/make-rng 42)
          [a b] (rng/split-rng rng1)
          
          rng2 (rng/make-rng 42)
          [c d] (rng/split-rng rng2)]
      ; Same seed + split = same sub-seeds
      (is (== (rng/next-double a) (rng/next-double c))))))

(deftest dispute-resolution-test
  (testing "Dispute resolution produces valid output"
    (let [rng (rng/make-rng 42)
          result (dispute/resolve-dispute
                  rng 10000 150 700 2.5 :honest
                  0.05 0.40 0.10)]
      (is (contains? result :dispute-correct?))
      (is (contains? result :appeal-triggered?))
      (is (contains? result :slashed?))
      (is (number? (:profit-honest result))))))

(deftest honest-vs-malice-test
  (testing "Honest profit >= malice profit in expectation"
    (let [rng (rng/make-rng 42)
          ; Run multiple disputes to get average
          honest-results
          (repeatedly 100 #(dispute/resolve-dispute
                           rng 10000 150 700 2.5 :honest
                           0.05 0.40 0.10))
          
          rng2 (rng/make-rng 43)
          malice-results
          (repeatedly 100 #(dispute/resolve-dispute
                           rng2 10000 150 700 2.5 :malicious
                           0.05 0.40 0.10))
          
          honest-mean (/ (reduce + (map :profit-honest honest-results)) 100)
          malice-mean (/ (reduce + (map :profit-malice malice-results)) 100)]
      
      ; Honest should be better
      (is (> honest-mean malice-mean)))))

(deftest params-validation-test
  (testing "Valid scenario passes validation"
    (let [valid {:description "test"
                 :scenario-id "test-1"
                 :rng-seed 42
                 :escrow-distribution {:type :lognormal}
                 :strategy-mix {:honest 0.75 :lazy 0.15 :malicious 0.08 :collusive 0.02}
                 :resolver-fee-bps 150
                 :appeal-bond-bps 700
                 :slash-multiplier 2.5
                 :appeal-probability-if-correct 0.05
                 :appeal-probability-if-wrong 0.40
                 :slashing-detection-probability 0.10
                 :n-trials 1000
                 :n-seeds 1
                 :parallelism :auto}]
      (let [out (types/validate-scenario valid)]
        (is (= valid (dissoc out :oracle-effective)))
        (is (map? (:oracle-effective out))))))
  
  (testing "Invalid fee-bps fails"
    (let [invalid {:resolver-fee-bps -100}]
      (is (thrown? Exception (types/validate-scenario invalid))))))
