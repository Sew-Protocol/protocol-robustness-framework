(ns resolver-sim.benchmark.report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.report :as rpt]))

(deftest scenario-outcome-prefers-public-scenario-id
  (let [results [{:scenario/id "malicious-resolver-verdict-v1"
                  :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                  :outcome :pass
                  :halt-reason nil
                  :scenario/evidence-root "abc"}]]
    (is (= {:outcome :pass
            :halt-reason nil
            :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
            :scenario/evidence-root "abc"}
           (rpt/scenario->outcome "malicious-resolver-verdict-v1" results)))))

(deftest scenario-outcome-resolves-reference-validation-file-alias
  (let [results [{:file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                  :outcome :pass
                  :halt-reason nil
                  :scenario/evidence-root "root"}]]
    (is (= {:outcome :pass
            :halt-reason nil
            :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
            :scenario/evidence-root "root"}
           (rpt/scenario->outcome "malicious-resolver-verdict-v1" results)))))

(deftest build-report-resolves-prf-dimensions
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "malicious-resolver-verdict-v1"
                             :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "dispute-flooding-v1"
                             :file "scenarios/S62_resolver-throughput-exhaustion.json"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "autopush-settlement-v1"
                             :file "scenarios/S05_pending-settlement-execute.json"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root "root-3"}]
                  :metrics {:total 3 :passed 3}
                  :reproduce {:command "bb benchmark:reproduce /tmp/prf.edn"}
                  :invariant-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}
        _ (spit evidence-path (pr-str evidence))
        report (rpt/build-report (.getAbsolutePath evidence-path)
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")]
    (testing "dimensions resolve against public IDs"
      (is (= 3 (count (:dimensions report))))
      (is (every? :pass-condition-met? (:dimensions report)))
      (is (= ["malicious-resolver-verdict-v1"
              "dispute-flooding-v1"
              "autopush-settlement-v1"]
             (mapv :scenario/id (:dimensions report)))))))

(deftest severity-weighted-classification-uses-scoring-severity
  (let [manifest (edn/read-string (slurp "benchmarks/packs/sew/escrow-dispute-v1.edn"))
        scoring (edn/read-string (slurp "benchmarks/scoring/severity-weighted-v1.edn"))
        claim-results (benchmark-claims/evaluate-manifest-claims
                       manifest
                       [{:scenario/id "scenario-1"
                         :file "scenario-1.edn"
                         :outcome :pass
                         :scenario/evidence-root (apply str (repeat 64 "a"))
                         :invariant-results [{:id :conservation-of-funds
                                              :result :fail}]}])
        failed-critical (first (filter #(= :claim/no-unauthorized-release (:claim/id %))
                                       claim-results))
        classification (rpt/classify-result 1 1 (:scoring/rules scoring) claim-results)]
    (is (= :critical (:claim/severity failed-critical)))
    (is (= :fail (:claim/outcome failed-critical)))
    (is (= "At least one critical-severity claim fails"
           (:classification-label classification)))))

(deftest build-report-dimension-fails-when-scenario-claim-fails
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "malicious-resolver-verdict-v1"
                             :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "dispute-flooding-v1"
                             :file "scenarios/S62_resolver-throughput-exhaustion.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "autopush-settlement-v1"
                             :file "scenarios/S05_pending-settlement-execute.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-3"}]
                  :claim-results [{:claim/id :evidence-root-present
                                   :claim/scope :scenario
                                   :scenario/id "malicious-resolver-verdict-v1"
                                   :claim/outcome :fail
                                   :claim/severity :low}]
                  :metrics {:total 3 :passed 3}
                  :reproduce {:command "bb benchmark:reproduce /tmp/prf.edn"}
                  :invariant-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}
        _ (spit evidence-path (pr-str evidence))
        report (rpt/build-report (.getAbsolutePath evidence-path)
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")
        dimension (first (:dimensions report))]
    (is (:scenario/pass? dimension))
    (is (= false (:claims/pass? dimension)))
    (is (:invariants/pass? dimension))
    (is (= false (:dimension/pass? dimension)))
    (is (= false (:pass-condition-met? dimension)))))

(deftest build-report-dimension-fails-when-invariant-fails
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "malicious-resolver-verdict-v1"
                             :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :fail}]
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "dispute-flooding-v1"
                             :file "scenarios/S62_resolver-throughput-exhaustion.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "autopush-settlement-v1"
                             :file "scenarios/S05_pending-settlement-execute.json"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-3"}]
                  :claim-results []
                  :metrics {:total 3 :passed 3}
                  :reproduce {:command "bb benchmark:reproduce /tmp/prf.edn"}
                  :invariant-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}
        _ (spit evidence-path (pr-str evidence))
        report (rpt/build-report (.getAbsolutePath evidence-path)
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")
        dimension (first (:dimensions report))]
    (is (:scenario/pass? dimension))
    (is (nil? (:claims/pass? dimension)))
    (is (= false (:invariants/pass? dimension)))
    (is (= false (:dimension/pass? dimension)))
    (is (= false (:pass-condition-met? dimension)))))
