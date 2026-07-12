(ns resolver-sim.benchmark.review-formatter-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-formatter :as formatter]))

(def sample-bundle
  {:benchmark {:benchmark/id :benchmark/example-v1
               :benchmark/status :experimental
               :benchmark/protocol :protocol/sew
               :benchmark/scenario-suite :suite/example
               :benchmark/purpose "Exercise a small declared workload."
               :benchmark/experimental-reason "Closed-form allocation coverage is incomplete."}
   :metrics {:total 2
             :passed 2
             :execution-count 2
             :unique-scenario-count 1
             :declared-run-count 2}
   :results [{:scenario/id "example-scenario"
              :simulator/scenario-path "scenarios/edn/S01_example.edn"
              :benchmark/run-index 1
              :outcome :pass
              :metrics {:events-processed 3}
              :scenario/evidence-root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
             {:scenario/id "example-scenario"
              :simulator/scenario-path "scenarios/edn/S01_example.edn"
              :benchmark/run-index 2
              :outcome :pass
              :metrics {:events-processed 3}
              :scenario/evidence-root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
   :claim-results [{:claim/id :claim/example
                    :claim/scope :benchmark
                    :claim/outcome :pass
                    :claim/severity :low
                    :claim/evidence [:paired-results]}]
   :repo {:repo {:commit "abc123" :dirty? false}}
   :run/manifest {:manifest/at "2026-07-13T12:00:00Z"}
   :evidence/hash "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})

(deftest renders-a-scope-aware-summary
  (let [summary (formatter/render-summary sample-bundle)]
    (is (.contains summary "# Benchmark Summary — :benchmark/example-v1"))
    (is (.contains summary "This benchmark is marked **experimental**"))
    (is (.contains summary "2/2 passed"))
    (is (.contains summary "does not by itself establish comprehensive protocol assurance"))))

(deftest renders-executions-and-claims-without-inventing-families
  (let [scenarios (formatter/render-scenario-results sample-bundle)
        claims (formatter/render-claim-results sample-bundle)]
    (is (.contains scenarios "Each row is one **execution**"))
    (is (.contains scenarios "Source directory: `scenarios/edn`"))
    (is (= 2 (count (re-seq #"\| example-scenario \|" scenarios))))
    (is (.contains claims "Claim outcomes are independent from scenario outcomes"))
    (is (.contains claims "`:not-exercised`, `:not-implemented`, and `:inconclusive` are not passes"))))

(deftest writes-the-three-review-files
  (let [dir (doto (java.io.File/createTempFile "benchmark-review-" "")
              (.delete)
              (.mkdirs))
        paths (formatter/write-review! sample-bundle (.getPath dir))]
    (try
      (testing "all reviewer files are emitted"
        (is (.exists (java.io.File. (:summary paths))))
        (is (.exists (java.io.File. (:scenarios paths))))
        (is (.exists (java.io.File. (:claims paths)))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))
