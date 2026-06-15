(ns resolver-sim.economics.payoffs-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]))

(deftest test-prorata-slash-equal-stake
  (testing "equal stake splits evenly"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 50
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 100 :available-slashable 100}
                    {:id :resolver-b :slashable-stake 100 :available-slashable 100}]})]
      (is (= 200 (:total-basis result)))
      (is (= 50 (:recovered-total result)))
      (is (= 0 (:unmet-total result)))
      (is (= 25 (:paid (first (:allocations result)))))
      (is (= 25 (:paid (second (:allocations result)))))
      (is (every? zero? (map :unmet (:allocations result)))))))

(deftest test-prorata-slash-unequal-stake
  (testing "unequal stake allocates proportionally"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 400
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 1000 :available-slashable 1000}
                    {:id :resolver-b :slashable-stake 500  :available-slashable 500}
                    {:id :resolver-c :slashable-stake 500  :available-slashable 500}]})]
      (is (= 2000 (:total-basis result)))
      (is (= 400 (:recovered-total result)))
      (is (= 0 (:unmet-total result)))
      (is (= 200 (:paid (get (:allocations result) 0))))
      (is (= 100 (:paid (get (:allocations result) 1))))
      (is (= 100 (:paid (get (:allocations result) 2)))))))

(deftest test-prorata-slash-capped-party
  (testing "capped party records unmet"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 400
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 1000 :available-slashable 1000}
                    {:id :resolver-b :slashable-stake 500  :available-slashable 60}
                    {:id :resolver-c :slashable-stake 500  :available-slashable 500}]})]
      (is (= 2000 (:total-basis result)))
      (is (= 360 (:recovered-total result)))
      (is (= 40 (:unmet-total result)))
      (is (= 200 (:paid (get (:allocations result) 0))))
      (is (= 60  (:paid (get (:allocations result) 1))))
      (is (= 40  (:unmet (get (:allocations result) 1))))
      (is (= 100 (:paid (get (:allocations result) 2)))))))

(deftest test-prorata-slash-zero-basis-excluded
  (testing "zero-basis party excluded from allocation"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 50
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 100 :available-slashable 100}
                    {:id :resolver-b :slashable-stake 0   :available-slashable 100}]})]
      (is (= 100 (:total-basis result)))
      (is (= 50 (:recovered-total result)))
      (is (= 0 (:unmet-total result)))
      (is (= 50 (:paid (get (:allocations result) 0))))
      (is (= 0  (:paid (get (:allocations result) 1)))))))

(deftest test-prorata-slash-total-basis-zero
  (testing "total basis zero returns structured failure"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 100
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 0 :available-slashable 0}]})]
      (is (= :no-liable-basis (:status result)))
      (is (= 0 (:recovered-total result)))
      (is (= 100 (:unmet-total result))))))

(deftest test-prorata-slash-rounding-determinism
  (testing "rounding distributes remainder deterministically"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 10
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 1 :available-slashable 10}
                    {:id :resolver-b :slashable-stake 1 :available-slashable 10}
                    {:id :resolver-c :slashable-stake 1 :available-slashable 10}]})
          allocs (:allocations result)]
      (is (= 3 (:total-basis result)))
      (is (= 10 (:recovered-total result)))
      ;; 10/3 = 3.33 each, floored to 3, remainder 1 goes to largest fractional
      ;; all equal fractional, tie-break by id gives resolver-a
      (is (= 4 (:paid (get allocs 0))))
      (is (= 3 (:paid (get allocs 1))))
      (is (= 3 (:paid (get allocs 2))))
      ;; repeat should be identical
      (let [result2 (payoffs/calculate-prorata-slash-allocation
                     {:slash-obligation 10
                      :liable-parties
                      [{:id :resolver-a :slashable-stake 1 :available-slashable 10}
                       {:id :resolver-b :slashable-stake 1 :available-slashable 10}
                       {:id :resolver-c :slashable-stake 1 :available-slashable 10}]})]
        (is (= (:allocations result) (:allocations result2)))))))

(deftest test-prorata-slash-conservation
  (testing "conservation: paid + unmet = slash-obligation"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 400
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 1000 :available-slashable 1000}
                    {:id :resolver-b :slashable-stake 500  :available-slashable 60}
                    {:id :resolver-c :slashable-stake 500  :available-slashable 500}]})]
      (is (= (:slash-obligation result)
             (+ (:recovered-total result) (:unmet-total result)))))))

(deftest test-prorata-slash-no-over-slashing
  (testing "no party pays more than available-slashable"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 1000
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 100 :available-slashable 50}
                    {:id :resolver-b :slashable-stake 100 :available-slashable 200}]})
          allocs (:allocations result)]
      (is (= 50 (:paid (get allocs 0))) "A capped at 50")
      (is (= 200 (:paid (get allocs 1))) "B capped at 200")
      (is (<= (:paid (get allocs 0)) 50))
      (is (<= (:paid (get allocs 1)) 200)))))

(deftest test-prorata-slash-uses-custom-cap-field
  (testing "custom cap-field works"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 100
                   :cap-field :custom-cap
                   :liable-parties
                   [{:id :resolver-a :slashable-stake 100 :custom-cap 30}
                    {:id :resolver-b :slashable-stake 100 :custom-cap 200}]})
          allocs (:allocations result)]
      (is (= 30 (:paid (get allocs 0))) "A capped at 30")
      (is (= 50 (:owed (get allocs 1))) "B owes 50")
      (is (= 50 (:paid (get allocs 1))) "B pays 50, no cap hit"))))

(deftest test-prorata-slash-uses-custom-basis
  (testing "custom basis key works"
    (let [result (payoffs/calculate-prorata-slash-allocation
                  {:slash-obligation 100
                   :basis :custom-stake
                   :liable-parties
                   [{:id :resolver-a :custom-stake 300 :available-slashable 100}
                    {:id :resolver-b :custom-stake 100 :available-slashable 100}]})]
      (is (= 400 (:total-basis result)))
      (is (= 75 (:paid (get (:allocations result) 0))))
      (is (= 25 (:paid (get (:allocations result) 1)))))))


(deftest test-allocation-order-stability
  (testing "allocation result is independent of liable-parties order"
    (let [parties [{:id :a :slashable-stake 100 :available-slashable 100}
                   {:id :b :slashable-stake 200 :available-slashable 200}
                   {:id :c :slashable-stake 300 :available-slashable 300}]
          spec {:slash-obligation 100
                :liable-parties parties}
          result1 (payoffs/calculate-prorata-slash-allocation spec)
          result2 (payoffs/calculate-prorata-slash-allocation (assoc spec :liable-parties (reverse parties)))]
      ;; Allocations might be in different order if we just check the list, 
      ;; but the sum/result should be identical for the same party id.
      (is (= (:recovered-total result1) (:recovered-total result2)))
      (is (= (:unmet-total result1) (:unmet-total result2)))
      (is (= (set (:allocations result1)) (set (:allocations result2)))))))

(deftest test-allocation-linear-progression
  (testing "linear progression of total basis"
    (let [base-spec {:slash-obligation 1000
                     :liable-parties
                     [{:id :a :slashable-stake 100 :available-slashable 1000}]}
          results (map (fn [i]
                         (payoffs/calculate-prorata-slash-allocation
                          (assoc-in base-spec [:liable-parties 0 :slashable-stake] (* 100 i))))
                       (range 1 11))]
      (doseq [res results]
        (is (= 1000 (:recovered-total res)))))))
