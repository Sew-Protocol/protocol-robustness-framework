(ns resolver-sim.time.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.time.model :as time.model]))

(deftest test-temporal-model
  (testing "Initialization and advancement using java.time.Instant"
    (let [w0 (time.model/with-time {} {:block-ts (java.time.Instant/ofEpochSecond 1000) :scenario-step 0})
          now0 (time.model/now w0)]
      (is (= 1000 (.getEpochSecond (:block-ts now0))))
      (is (= 0 (:scenario-step now0)))
      (is (nil? (:block-time w0)) "Root :block-time should be removed")))

  (testing "Advancement advances clock and steps atomically"
    (let [w0 (time.model/with-time {} {:block-ts (java.time.Instant/ofEpochSecond 1000) :scenario-step 0})
          w1 (time.model/advance w0 {:seconds 10 :steps 1})
          now1 (time.model/now w1)]
      (is (= 1010 (.getEpochSecond (:block-ts now1))))
      (is (= 1 (:scenario-step now1)))))

  (testing "Same-timestamp events do not advance time"
    (let [w0 (time.model/with-time {} {:block-ts (java.time.Instant/ofEpochSecond 1000) :scenario-step 0})
          w1 (time.model/advance w0 {:seconds 0 :steps 0})
          now1 (time.model/now w1)]
      (is (= 1000 (.getEpochSecond (:block-ts now1))))
      (is (= 0 (:scenario-step now1))))))
