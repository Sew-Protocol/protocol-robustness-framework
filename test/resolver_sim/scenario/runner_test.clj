(ns resolver-sim.scenario.runner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.scenario.normalize :as norm]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.sim.fixtures :as fixtures]))

(deftest scenario-pass-respects-fixture-checks
  (testing "threshold and golden failures fail the entry"
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass :expected-fail? false
           :checks {:thresholds {:ok? false} :golden {:ok? true}}}
          {})))
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass :expected-fail? false
           :checks {:thresholds {:ok? true} :golden {:ok? false}}}
          {})))
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass :expected-fail? false
           :expected-halt-reason :invariant-violation
           :halt-reason :open-entities-at-end
           :checks {}}
          {})))))

(deftest scenario-pass-respects-expectations
  (testing "failed expectations fail even when outcome is pass"
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass
           :expected-fail? false
           :checks {:expectations {:ok? false :violations [{:type :metric-violation}]}}}
          {})))))

(deftest build-entry-reuses-replay-expectations
  (let [path "scenarios/S108_negative-yield-mild.json"
        scenario (-> path sc/load-scenario-file norm/normalize-scenario)
        replay   (sew/replay-with-sew-protocol scenario)
        entry    (runner/build-entry-result
                  {:name          (:scenario-id scenario)
                   :replay-result replay
                   :scenario      scenario})]
    (is (= (:expectations replay) (:expectations (:checks entry)))
        "should not re-evaluate when replay already has expectations")))

(deftest yield-suite-summary-shape
  (let [summary ((requiring-resolve 'resolver-sim.io.scenario-runner/run-paths)
                 ["scenarios/S108_negative-yield-mild.json"]
                 {:suite-id :yield-scenarios})]
    (is (= 1 (:total summary)))
    (is (contains? summary :passed))
    (is (= :yield-scenarios (:suite-id summary)))))

(deftest report-surfaces-expectation-violations
  (let [lines (report/format-check-failures
               {:checks {:expectations {:ok? false
                                        :violations [{:type :metric-violation
                                                      :name :yield/escrow-principal
                                                      :op :>
                                                      :expected 100
                                                      :actual 99}]}}})]
    (is (pos? (count lines)))
    (is (str/includes? (first lines) "expectation:"))
    (is (str/includes? (first lines) "yield/escrow-principal"))))

(deftest runner-opts-theory-defaults
  (testing "scenario with :theory evaluates theory by default"
    (is (true? (:evaluate-theory? (runner/runner-opts-for-scenario {:theory {:claim-id :x}})))))
  (testing "scenario without :theory skips theory by default"
    (is (false? (:evaluate-theory? (runner/runner-opts-for-scenario {})))))
  (testing "suite opts can suppress theory on a theory scenario"
    (is (false? (:evaluate-theory? (runner/runner-opts-for-scenario
                                    {:theory {:claim-id :x}}
                                    {:evaluate-theory? false})))))
  (testing "suite opts can force theory evaluation flag (no-op without :theory block)"
    (is (true? (:evaluate-theory? (runner/runner-opts-for-scenario
                                   {}
                                   {:evaluate-theory? true}))))))

(deftest build-entry-theory-eval-once
  (let [path "scenarios/S108_negative-yield-mild.json"
        scenario (-> path sc/load-scenario-file norm/normalize-scenario)
        replay   (sew/replay-with-sew-protocol scenario)
        forced-off (runner/build-entry-result
                    {:name (:scenario-id scenario) :replay-result replay :scenario scenario}
                    (runner/runner-opts-for-scenario scenario {:evaluate-theory? false}))]
    (is (nil? (get-in forced-off [:checks :theory]))
        "suppress via runner-opts must skip theory check")))

(deftest theory-check-present-when-scenario-declares-theory
  (let [scenario (fixtures/compose-suite :traces/spe-reg-v4-fail-slashed-resolver-bounded)
        replay   (sew/replay-with-sew-protocol scenario)
        opts     (runner/runner-opts-for-scenario scenario)
        entry    (runner/build-entry-result
                  {:name (:scenario-id scenario) :replay-result replay :scenario scenario}
                  opts)]
    (is (true? (:evaluate-theory? opts)))
    (is (contains? (:checks entry) :theory))
    (is (nil? (get-in (runner/build-entry-result
                       {:name (:scenario-id scenario) :replay-result replay :scenario scenario}
                       (assoc opts :evaluate-theory? false))
                      [:checks :theory])))))
