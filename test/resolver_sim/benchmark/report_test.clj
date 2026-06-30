(ns resolver-sim.benchmark.report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.report :as rpt]))

(defn- temp-evidence-file
  "Create a temporary EDN file with the given evidence map, returning the path."
  [evidence]
  (let [f (doto (java.io.File/createTempFile "bundle-" ".edn")
            .deleteOnExit)]
    (spit f (pr-str evidence))
    (.getAbsolutePath f)))

(defn- make-evidence
  "Build a minimal evidence map from a benchmark pack file path."
  [benchmark-path scenario-results & {:keys [claim-results metrics reproduce env inv-summary]
                                      :or {claim-results []
                                            metrics {:total 0 :passed 0}
                                            reproduce {:command "bb benchmark:reproduce"}
                                            env {:os-name "Linux" :os-version "test" :java-version "test"}
                                            inv-summary {:per-invariant {} :total-checks 0 :passed-checks 0 :all-pass? true}}}]
  {:benchmark (edn/read-string (slurp benchmark-path))
   :environment env
   :results scenario-results
   :claim-results claim-results
   :metrics metrics
   :reproduce reproduce
   :invariant-summary inv-summary})

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
        classification (rpt/classify-result 1 1 (:scoring/rules scoring) claim-results manifest)]
    (is (= :critical (:claim/severity failed-critical)))
    (is (= :fail (:claim/outcome failed-critical)))
    (is (= "Critical claim failed — semantic claim violation detected"
           (:classification-label classification)))))

(deftest build-report-dimension-fails-when-scenario-claim-fails
  (let [evidence-path (doto (java.io.File/createTempFile "prf-bundle-" ".edn")
                        .deleteOnExit)
        evidence {:benchmark (edn/read-string (slurp "benchmarks/packs/prf-core/protocol-robustness-v0.edn"))
                  :environment {:os-name "Linux" :os-version "test" :java-version "test"}
                  :results [{:scenario/id "malicious-resolver-verdict-v1"
                             :file "scenarios/edn/S25_profit-maximizer-slash-lifecycle.edn"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-1"}
                            {:scenario/id "dispute-flooding-v1"
                             :file "scenarios/edn/S62_resolver-throughput-exhaustion.edn"
                             :outcome :pass
                             :halt-reason nil
                             :invariant-results [{:id :conservation-of-funds :result :pass}]
                             :scenario/evidence-root "root-2"}
                            {:scenario/id "autopush-settlement-v1"
                             :file "scenarios/edn/S05_pending-settlement-execute.edn"
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

;; ── Shortfall allocation report tests ──────────────────────────────────────────

(deftest build-report-shortfall-allocation-v0-creates-report
  (let [ev (make-evidence "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                          [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                            :file "scenarios/S-DR-043-payout-shortfall-deferred.json"
                            :outcome :pass
                            :scenario/evidence-root "aabbcc"}
                           {:scenario/id "S103_negative-yield-shortfall-cascade"
                            :file "scenarios/S103_negative-yield-shortfall-cascade.json"
                            :outcome :pass
                            :scenario/evidence-root "ddeeff"}
                           {:scenario/id "S104_resolver-stake-shortfall"
                            :file "scenarios/S104_resolver-stake-shortfall.json"
                            :outcome :fail
                            :scenario/evidence-root "gghhii"}]
                          :metrics {:total 3 :passed 2})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/shortfall-allocation-v0.edn"
                                 "benchmarks/scoring/shortfall-allocation-v0.edn")]
    (testing "report structure"
      (is (= :benchmark/prf-shortfall-allocation-v0 (:benchmark/id report)))
      (is (= 3 (:total-scenarios report)))
      (is (= 2 (:passed-scenarios report)))
      (is (= false (:all-pass? report))))
    (testing "dimension structure"
      (is (= 3 (count (:dimensions report))))
      (is (= "S-DR-043-payout-shortfall-deferred"
             (get-in report [:dimensions 0 :scenario/id])))
      (is (= :allocation/partial-fill
             (get-in report [:dimensions 0 :dimension])))
      (is (= false (get-in report [:dimensions 2 :scenario/pass?]))))
    (testing "scoring classification"
      (let [cls (:scoring/classification report)]
        (is (string? (:classification-label cls)))
        (is (re-find #"(?i)replay" (:classification-label cls)))))))

(deftest build-report-shortfall-allocation-v0-all-pass
  (let [ev (make-evidence "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                          [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                            :file "scenarios/S-DR-043-payout-shortfall-deferred.json"
                            :outcome :pass
                            :scenario/evidence-root "aabbcc"}
                           {:scenario/id "S103_negative-yield-shortfall-cascade"
                            :file "scenarios/S103_negative-yield-shortfall-cascade.json"
                            :outcome :pass
                            :scenario/evidence-root "ddeeff"}
                           {:scenario/id "S104_resolver-stake-shortfall"
                            :file "scenarios/S104_resolver-stake-shortfall.json"
                            :outcome :pass
                            :scenario/evidence-root "gghhii"}]
                          :metrics {:total 3 :passed 3})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/shortfall-allocation-v0.edn"
                                 "benchmarks/scoring/shortfall-allocation-v0.edn")]
    (is (:all-pass? report))
    (is (every? :pass-condition-met? (:dimensions report)))))

;; ── Severity-weighted Sew pack report tests ────────────────────────────────────

(deftest build-report-severity-weighted-sew-classifies-correctly
  (let [ev (make-evidence "benchmarks/packs/sew/escrow-dispute-v1.edn"
                          []
                          :metrics {:total 44 :passed 44}
                          :claim-results
                          [{:claim/id :evidence-root-present
                            :claim/scope :scenario
                            :claim/outcome :pass
                            :claim/severity :low}
                           {:claim/id :claim/no-unauthorized-release
                            :claim/scope :scenario
                            :claim/outcome :fail
                            :claim/severity :critical}])
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/severity-weighted-v1.edn")]
    (testing "classification with critical failure"
      (let [cls (:scoring/classification report)]
        (is (re-find #"(?i)critical" (:classification-label cls)))))))

(deftest build-report-severity-weighted-all-claims-pass
  (let [ev (make-evidence "benchmarks/packs/sew/escrow-dispute-v1.edn"
                          []
                          :metrics {:total 44 :passed 44}
                          :claim-results
                          [{:claim/id :evidence-root-present
                            :claim/scope :scenario
                            :claim/outcome :pass
                            :claim/severity :low}
                           {:claim/id :claim/funds-conserved
                            :claim/scope :scenario
                            :claim/outcome :pass
                            :claim/severity :critical}])
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/severity-weighted-v1.edn")]
    (testing "classification all pass"
      (let [cls (:scoring/classification report)]
        (is (re-find #"(?i)pass" (:classification-label cls)))
        (is (re-find #"(?i)mechanical" (:classification-label cls)))))))

;; ── Missing path tests ─────────────────────────────────────────────────────────

(deftest resolve-report-throws-on-unknown-benchmark-id
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)
        report (rpt/build-report evidence-path
                                 "benchmarks/concepts/protocol-robustness-v0.edn"
                                 "benchmarks/scoring/robustness-dimensions-v0.edn")]
    (is (some? report))))

(deftest resolve-report-resolves-from-evidence-bundle
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          [{:scenario/id "malicious-resolver-verdict-v1"
                            :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
                            :outcome :pass
                            :scenario/evidence-root "abc"}
                           {:scenario/id "dispute-flooding-v1"
                            :file "scenarios/S62_resolver-throughput-exhaustion.json"
                            :outcome :pass
                            :scenario/evidence-root "def"}
                           {:scenario/id "autopush-settlement-v1"
                            :file "scenarios/S05_pending-settlement-execute.json"
                            :outcome :pass
                            :scenario/evidence-root "ghi"}]
                          :metrics {:total 3 :passed 3})
        evidence-path (temp-evidence-file ev)
        report (rpt/resolve-report evidence-path)]
    (is (= :benchmark/prf-protocol-robustness-v0 (:benchmark/id report)))
    (is (:all-pass? report))
    (is (= 3 (count (:dimensions report))))))

(deftest resolve-report-resolves-shortfall-from-bundle
  (let [ev (make-evidence "benchmarks/packs/prf-core/shortfall-allocation-v0.edn"
                          [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                            :file "scenarios/S-DR-043-payout-shortfall-deferred.json"
                            :outcome :pass
                            :scenario/evidence-root "abc"}]
                          :metrics {:total 1 :passed 1})
        evidence-path (temp-evidence-file ev)
        report (rpt/resolve-report evidence-path)]
    (is (= :benchmark/prf-shortfall-allocation-v0 (:benchmark/id report)))
    (is (:all-pass? report))))

(deftest build-report-missing-scoring-path-throws
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)]
    (is (thrown? Exception
                 (rpt/build-report evidence-path
                                   "benchmarks/concepts/protocol-robustness-v0.edn"
                                   "benchmarks/scoring/nonexistent-file.edn")))))

(deftest build-report-missing-concepts-path-throws
  (let [ev (make-evidence "benchmarks/packs/prf-core/protocol-robustness-v0.edn"
                          []
                          :metrics {:total 0 :passed 0})
        evidence-path (temp-evidence-file ev)]
    (is (thrown? Exception
                 (rpt/build-report evidence-path
                                   "benchmarks/concepts/nonexistent-file.edn"
                                   "benchmarks/scoring/robustness-dimensions-v0.edn")))))
