(ns resolver-sim.stochastic.params-test
  (:require [clojure.test :refer :all]
            [resolver-sim.stochastic.params :as params]))

(deftest from-snap-preserves-zero-and-false
  (testing "from-snap should preserve zero and false values when keys are present"
    (let [snap {:resolver-fee-bps 0
                :has-kleros? false
                :p-l1-reversal 0}
          out (params/from-snap snap)]
      (is (= 0 (:fee-bps out)))
      (is (= false (:has-kleros? out)))
      (is (= 0 (:p-l1-reversal out))))))