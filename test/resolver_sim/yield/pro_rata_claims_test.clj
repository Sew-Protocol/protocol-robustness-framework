(ns resolver-sim.yield.pro-rata-claims-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.pro-rata-claims :as claims]))

(def phase-6-claims
  #{:projection-deterministic
    :projection-canonical-safe
    :allocation-complete
    :non-negative
    :conservation
    :rounding-bounded
    :ordering-independent})

(def representative-fixtures
  [{:slash-obligation 11
    :slash-policy {:policy/id :test-policy}
    :liable-parties [{:id :resolver-a
                      :slashable-stake 3}
                     {:id :resolver-b
                      :slashable-stake 2}
                     {:id :resolver-c
                      :slashable-stake 1}]}
   {:slash-obligation 10
    :liable-parties [{:id :resolver-a
                      :slashable-stake 5}
                     {:id :resolver-b
                      :slashable-stake 3}
                     {:id :resolver-c
                      :slashable-stake 2}]}
   {:slash-obligation 7
    :basis :custom-weight
    :cap-field :custom-cap
    :liable-parties [{:id :resolver-a
                      :custom-weight 4}
                     {:id :resolver-b
                      :custom-weight 2}
                     {:id :resolver-c
                      :custom-weight 1}]}])

(deftest registered-claims-cover-phase-6-contract
  (testing "the passive claim registry exposes the phase 6 claim set"
    (is (= phase-6-claims (set (claims/registered-claim-ids))))))

(deftest all-phase-6-claims-pass-on-representative-fixtures
  (testing "claim evaluators prove direct and projection paths equivalent on representative fixtures"
    (doseq [input representative-fixtures]
      (let [result (claims/evaluate-all {:sew-slash-input input})]
        (is (= phase-6-claims (set (keys result))))
        (is (every? true? (map :holds? (vals result))))
        (is (empty? (mapcat :violations (vals result))))))))
