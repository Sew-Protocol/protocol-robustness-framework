(ns resolver-sim.stochastic.detection-test
  "Tests for probabilistic detection helpers, including the new-evidence-probability
   gate that controls Track 2 pending-evidence reversal slashing."
  (:require [clojure.test :refer :all]
            [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.rng :as rng]))

(deftest reversal-pending-live?-zero-threshold-disabled
  (is (false? (detection/reversal-pending-live?
                {:rng (rng/make-rng 42)
                 :new-evidence-probability 0.0}
                {:reversal-slashed? true}))
      "reversal-pending-live? should return false when threshold is 0.0"))

(deftest reversal-pending-live?-not-slashed-disabled
  (is (false? (detection/reversal-pending-live?
                {:rng (rng/make-rng 42)
                 :new-evidence-probability 0.05}
                {:reversal-slashed? false}))
      "reversal-pending-live? should return false when not reversal-slashed"))

(deftest reversal-pending-live?-positive-threshold-active
  (let [params  {:rng (rng/make-rng 42)
                 :new-evidence-probability 1.0}
        result  (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (true? result)
        "reversal-pending-live? should return true when threshold=1.0 (always fires)")))

(deftest reversal-pending-live?-probabilistic-roll
  (let [n-trials 1000
        params-fn (fn [seed]
                    {:rng (rng/make-rng seed)
                     :new-evidence-probability 0.5})
        results (for [s (range n-trials)]
                  (detection/reversal-pending-live? (params-fn s) {:reversal-slashed? true}))
        rate (/ (count (filter true? results)) (double n-trials))]
    (is (< 0.40 rate 0.60)
        (str "reversal-pending-live? with 0.5 threshold should fire ~50% over " n-trials " trials; got " rate))))

(deftest reversal-pending-live?-trace-decision-marker
  (let [params {:rng (rng/make-rng 42)
                :oracle-roll-trace-enabled? true
                :new-evidence-probability 1.0
                :scenario-id "detection-test"}
        result (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (true? result)
        "oracle-roll-trace-enabled? should not affect reversal-pending-live? outcome")))
