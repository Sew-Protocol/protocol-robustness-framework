(ns resolver-sim.protocols.sew.invariant-runner-test
  "Smoke tests for the S01–S100 deterministic invariant suite runner."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariant-runner :as runner]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.scenario.runner :as scenario-runner]))

(deftest test-registry-size
  (is (= 115 (count sc/all-scenarios))))

(deftest test-run-all-all-pass
  (let [{:keys [passed total results]} (runner/run-all)]
    (is (= passed total))
    (testing "no invariant violations in any scenario"
      (is (every? #(zero? (:violations %)) results)))))

(deftest test-run-all-shape
  (let [summary (runner/run-all)]
    (is (contains? summary :passed))
    (is (contains? summary :total))
    (is (contains? summary :elapsed-ms))
    (is (contains? summary :results))
    (is (every? #(contains? % :name) (:results summary)))))

(deftest test-print-report-exit-code
  (let [summary (runner/run-all)
        code    (runner/print-report summary)]
    (is (= 0 code))))

(deftest test-scenario-pass-aligned-with-run-all
  (let [{:keys [results]} (runner/run-all)]
    (testing "single-scenario entries (paired registry rows omit :outcome)"
      (is (every? #(= (:pass? %) (scenario-runner/scenario-pass? % {}))
                  (filter #(contains? % :outcome) results))))))
