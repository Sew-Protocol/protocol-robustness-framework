(ns resolver-sim.time.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.time.context :as ctx]))

(deftest test-temporal-context-derivation
  (testing "Creation from legacy world (no :context/time)"
    (let [w {:block-time 1000}
          c (ctx/temporal-context w)]
      (is (= 1000 (:block-ts c)))
      (is (= 0 (:step c)))
      (is (= :legacy (:clock/source c)))))

  (testing "Creation from rich world (with :context/time)"
    (let [w {:context/time {:block-ts 2000 :step 5 :clock/source :scenario}}]
      (is (= 2000 (ctx/block-ts w)))
      (is (= 5 (ctx/step w)))
      (is (= :scenario (:clock/source (ctx/temporal-context w))))))

  (testing "Synchronization during update"
    (let [w {:block-time 1000}
          w' (ctx/with-temporal-context w {:block-ts 2000 :step 1})]
      (is (= 2000 (:block-time w')) "Legacy key should be updated")
      (is (= 2000 (get-in w' [:context/time :block-ts])))
      (is (= 1 (get-in w' [:context/time :step]))))))

(deftest test-advance-time
  (testing "Relative advancement"
    (let [w {:block-time 1000}
          w' (ctx/advance-time w {:seconds 60})]
      (is (= 1060 (ctx/block-ts w')))
      (is (= 1 (ctx/step w')) "Step should increment by 1 default")))

  (testing "Absolute advancement"
    (let [w {:block-time 1000}
          w' (ctx/advance-time w {:to 2000 :steps 10})]
      (is (= 2000 (ctx/block-ts w')))
      (is (= 10 (ctx/step w')))))

  (testing "Atomic step preservation"
    (let [w {:context/time {:block-ts 1000 :step 42}}
          w' (ctx/advance-time w {:seconds 0})]
      (is (= 1000 (ctx/block-ts w')))
      (is (= 43 (ctx/step w'))))))
