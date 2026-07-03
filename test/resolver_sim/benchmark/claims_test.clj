(ns resolver-sim.benchmark.claims-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.claims :as claims]))

(def deterministic-replay-manifest
  (edn/read-string (slurp "benchmarks/packs/prf-core/deterministic-replay-v1.edn")))

(deftest prf-replay-claims-have-registered-evaluators
  (testing "all declared PRF replay claims resolve to benchmark evaluators"
    (doseq [claim-id [:claim/replay-identical-results
                      :claim/hash-consistency-across-runs
                      :claim/no-nondeterminism]]
      (is (= :benchmark (:scope (claims/evaluator-resolver claim-id)))
          (str "missing benchmark evaluator for " claim-id)))))

(deftest prf-replay-claims-pass-for-consistent-results
  (let [results [{:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}
                 {:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 2
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}
                 {:scenario/id "scenario-b"
                  :simulator/scenario-path "scenarios/b.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :fail
                  :halt-reason :halted
                  :invariant-results [{:id :inv/b :result :fail}]
                  :scenario/evidence-root (apply str (repeat 64 "b"))}
                 {:scenario/id "scenario-b"
                  :simulator/scenario-path "scenarios/b.edn"
                  :benchmark/run-index 2
                  :benchmark/run-count 2
                  :outcome :fail
                  :halt-reason :halted
                  :invariant-results [{:id :inv/b :result :fail}]
                  :scenario/evidence-root (apply str (repeat 64 "b"))}]
        claim-results (claims/evaluate-manifest-claims deterministic-replay-manifest results)]
    (testing "all three replay claims pass when duplicate scenario entries agree"
      (is (= {:claim/replay-identical-results :pass
              :claim/hash-consistency-across-runs :pass
              :claim/no-nondeterminism :pass}
             (into {}
                   (map (juxt :claim/id :claim/outcome))
                   claim-results))))))

(deftest prf-replay-claims-fail-for-conflicting-results
  (let [results [{:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}
                 {:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 2
                  :benchmark/run-count 2
                  :outcome :fail
                  :halt-reason :halted
                  :invariant-results [{:id :inv/a :result :fail}]
                  :scenario/evidence-root (apply str (repeat 64 "b"))}]
        claim-results (claims/evaluate-manifest-claims deterministic-replay-manifest results)
        by-id (into {} (map (juxt :claim/id identity)) claim-results)]
    (testing "conflicting duplicate scenario entries fail the replay claims"
      (is (= :fail (get-in by-id [:claim/replay-identical-results :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/hash-consistency-across-runs :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/no-nondeterminism :claim/outcome])))
      (is (= [:replay-results-mismatch]
             (:claim/evidence (get by-id :claim/replay-identical-results))))
      (is (= [:evidence-root-mismatch]
             (:claim/evidence (get by-id :claim/hash-consistency-across-runs))))
      (is (= [:nondeterministic-replay]
             (:claim/evidence (get by-id :claim/no-nondeterminism)))))))

(deftest prf-replay-claims-fail-for-incomplete-run-pairing
  (let [results [{:scenario/id "scenario-a"
                  :simulator/scenario-path "scenarios/a.edn"
                  :benchmark/run-index 1
                  :benchmark/run-count 2
                  :outcome :pass
                  :halt-reason nil
                  :invariant-results [{:id :inv/a :result :pass}]
                  :scenario/evidence-root (apply str (repeat 64 "a"))}]
        claim-results (claims/evaluate-manifest-claims deterministic-replay-manifest results)
        by-id (into {} (map (juxt :claim/id identity)) claim-results)]
    (testing "single-run evidence is not enough for replay claims"
      (is (= :fail (get-in by-id [:claim/replay-identical-results :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/hash-consistency-across-runs :claim/outcome])))
      (is (= :fail (get-in by-id [:claim/no-nondeterminism :claim/outcome])))
      (is (= [:insufficient-replay-runs]
             (:claim/evidence (get by-id :claim/replay-identical-results)))))))
