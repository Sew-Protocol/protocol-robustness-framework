(ns resolver-sim.concepts.benchmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.concepts.benchmark :as benchmark-concepts]
            [resolver-sim.concepts.registry :as concepts-registry]))

(deftest resolve-benchmark-concepts-prefers-local-shadow
  (testing "benchmark-local concepts override global concepts with the same id"
    (let [local-concepts [{:concept/id :allocation/shortfall
                           :concept/title "Local shortfall"
                           :concept/summary "Benchmark-local summary"
                           :concept/stakeholder-language "Benchmark-local language"
                           :concept/why-it-matters "Benchmark-local rationale"
                           :concept/maps-to {:scenarios ["S103_negative-yield-shortfall-cascade"]}}]
          resolved (benchmark-concepts/resolve-benchmark-concepts
                    [:allocation/shortfall :consensus/finality :concept/missing]
                    {:local-concepts local-concepts})]
      (is (= [:concept/missing] (:unknown-concept-ids resolved)))
      (is (= :allocation/shortfall
             (get-in resolved [:local-concepts 0 :concept/id])))
      (is (= "Local shortfall"
             (get-in resolved [:report-concepts 0 :concept/title])))
      (is (= "Consensus finality"
             (get-in resolved [:report-concepts 1 :concept/title]))))))

(deftest missing-related-concepts-detects-unresolved-links
  (testing "related concept validation reports unresolved ids"
    (is (= [{:from :concept/a :to :concept/missing}]
           (concepts-registry/missing-related-concepts
            [{:concept/id :concept/a
              :concept/related [:concept/b :concept/missing]}
             {:concept/id :concept/b}])))))
