(ns resolver-sim.benchmark.report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
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
