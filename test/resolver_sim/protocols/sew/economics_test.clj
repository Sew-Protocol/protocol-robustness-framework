(ns resolver-sim.protocols.sew.economics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.economics :as sew-econ]))

(deftest sew-economic-policy-helpers
  (testing "SEW-specific fees, bonds, slashes, and escrow caps live in the Sew adapter"
    (is (= 15 (sew-econ/calculate-escrow-fee 1000 150)))
    (is (= {:fee 10 :net 990}
           (sew-econ/calculate-appeal-bond-fee 1000 100)))
    (is (= 25
           (sew-econ/calculate-challenge-bond-amount
            1000 {:challenge-bond-bps 250 :appeal-bond-amount 50})))
    (is (= 50
           (sew-econ/calculate-challenge-bond-amount
            1000 {:challenge-bond-bps 0 :appeal-bond-amount 50})))
    (is (= 30
           (sew-econ/calculate-appeal-bond-amount
            1000 {:appeal-bond-bps 300})))
    (is (= 20 (sew-econ/calculate-bounty 1000 200)))
    (is (= 25 (sew-econ/calculate-slash-amount-from-basis 1000 250)))
    (is (= 25 (sew-econ/calculate-reversal-slash 1000 250)))
    (is (= 1500.0 (sew-econ/calculate-escrow-cap 1000 1.5)))))

(deftest sew-slashing-distribution
  (testing "SEW slash distribution keeps the historical default split"
    (is (= {:insurance 500 :protocol 300 :retained 200}
           (sew-econ/calculate-slashing-distribution 1000 0)))
    (is (= 990
           (let [{:keys [insurance protocol retained]}
                 (sew-econ/calculate-slashing-distribution 1000 10)]
             (+ insurance protocol retained))))))

(deftest sew-slash-allocation-uses-slashable-stake-as-default-weight
  (testing "SEW adapter maps slashable stake into generic allocation weight"
    (let [result (sew-econ/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :resolver-a
                                     :slashable-stake 300
                                     :available-slashable 300}
                                    {:id :resolver-b
                                     :slashable-stake 100
                                     :available-slashable 100}]})]
      (is (= 400 (:total-basis result)))
      (is (= [75 25] (mapv :paid (:allocations result))))
      (is (= [300 100] (mapv :basis-amount (:allocations result))))
      (is (= [3/4 1/4] (mapv :share (:allocations result)))))))

(deftest sew-slash-allocation-preserves-caps-and-legacy-shape
  (testing "SEW adapter applies available-slashable caps and returns historical keys"
    (let [result (sew-econ/calculate-sew-slash-allocation
                  {:slash-obligation 100
                   :liable-parties [{:id :resolver-a
                                     :slashable-stake 300
                                     :available-slashable 70}
                                    {:id :resolver-b
                                     :slashable-stake 100
                                     :available-slashable 100}]})]
      (is (= :slashable-stake (:basis result)))
      (is (= :available-slashable (:cap-field result)))
      (is (= 95 (:recovered-total result)))
      (is (= 5 (:unmet-total result)))
      (is (= [70 25] (mapv :paid (:allocations result))))
      (is (= [5 0] (mapv :unmet (:allocations result)))))))

(deftest sew-resolution-call-site-uses-sew-economics-adapter
  (testing "resolution query path does not call the deprecated payoffs slash wrapper directly"
    (let [source (slurp "src/resolver_sim/protocols/sew/resolution.clj")]
      (is (str/includes? source "sew-econ/calculate-sew-slash-allocation"))
      (is (not (str/includes? source "payoffs/calculate-prorata-slash-allocation"))))))
