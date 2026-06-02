(ns resolver-sim.sim.defection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.defection :as d]
            [resolver-sim.stochastic.rng :as rng]))

(deftest apply-strategy-defection-disabled-when-rate-zero
  (testing ":defection-rate 0 leaves histories unchanged"
    (let [rng (rng/make-rng 1)
          h {"r1" {:strategy :lazy :epoch-history {:epoch-1 {:trials 1 :profit 1.0}}}}
          {:keys [updated-histories defection-events diagnostics]}
          (d/apply-strategy-defection rng h 1 {:defection-rate 0})]
      (is (= h updated-histories))
      (is (empty? defection-events))
      (is (empty? diagnostics)))))

(deftest legacy-binary-mode-keeps-compatibility
  (testing "legacy mode keeps binary honest/malicious behavior"
    (let [rng (rng/make-rng 42)
          histories {"lazy1" {:strategy :lazy
                              :epoch-history {:epoch-1 {:trials 10 :profit 1.0}}}
                     "honest1" {:strategy :honest
                                :epoch-history {:epoch-1 {:trials 10 :profit 100.0}}}}
          {:keys [updated-histories defection-events]}
          (d/apply-strategy-defection rng histories 1 {:defection-rate 1.0})
          lazy-event (first (filter #(= "lazy1" (:id %)) defection-events))]
      (when lazy-event
        (is (= :honest (:to lazy-event)))
        (is (= :lazy (:from lazy-event))))
      (is (= :honest (get-in updated-histories ["lazy1" :strategy]))))))
