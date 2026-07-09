(ns resolver-sim.benchmark.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.sharing :as sharing]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as sew-inv]
            [resolver-sim.scenario.suites :as suites]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(deftest test-hashing-determinism
  (testing "Identical data produces identical hashes"
    (let [data {:a 1 :b [1 2 3] :c {:d "foo"}}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} data)]
      (is (= h1 h2))))

  (testing "Map key order does not affect hash"
    (let [data1 {:a 1 :b 2}
          data2 {:b 2 :a 1}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data1)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} data2)]
      (is (= h1 h2)))))

(deftest test-suite-resolution
  (testing "Pack suite keywords resolve to scenario paths"
    (is (= 44 (count (suites/suite-paths :suite/sew-dispute-safety-v1)))
        ":suite/sew-dispute-safety-v1 should resolve to 44 dispute-resolution scenarios")
    (is (= 15 (count (suites/suite-paths :suite/sew-yield-safety-v1)))
        ":suite/sew-yield-safety-v1 should resolve to 15 yield scenarios")
    (is (= 44 (count (suites/suite-paths :suite/prf-replay-v1)))
        ":suite/prf-replay-v1 should resolve to 44 core replay scenarios")
    (is (nil? (suites/suite-paths :suite/non-existent))
        "Unknown suite keyword should return nil")))

(deftest test-pack-manifest-loading
  (testing "Canonical pack manifests load and reference registered suites"
    (doseq [[path expected-suite expected-count]
            [["benchmarks/packs/sew/escrow-dispute-v1.edn"
              :suite/sew-dispute-safety-v1 44]
             ["benchmarks/packs/sew/dispute-liveness-v1.edn"
              :suite/sew-dispute-safety-v1 44]
             ["benchmarks/packs/sew/yield-shortfall-v1.edn"
              :suite/sew-yield-safety-v1 15]
             ["benchmarks/packs/sew/resolver-slashing-v1.edn"
              :suite/sew-dispute-safety-v1 44]
             ["benchmarks/packs/prf-core/deterministic-replay-v1.edn"
              :suite/prf-replay-v1 44]]]
      (let [manifest (edn/read-string (slurp path))
            suite-kw (:benchmark/scenario-suite manifest)
            paths (suites/suite-paths suite-kw)]
        (is (= expected-suite suite-kw)
            (str (:benchmark/id manifest) " references " expected-suite))
        (is (= expected-count (count paths))
            (str (:benchmark/id manifest) " resolves to " expected-count " scenarios"))))))

(deftest test-hash-stability
  (testing "Hashing is stable across different instances of same data"
    (let [data {:repo {:commit "abc"}}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} (into {} data))]
      (is (= h1 h2)))))

(deftest test-repo-metadata
  (testing "Can extract git metadata"
    (let [meta (repo/metadata)]
      (is (contains? meta :repo))
      (is (string? (get-in meta [:repo :commit])))
      (is (boolean? (get-in meta [:repo :dirty?]))))))

(deftest test-benchmark-run-basic
  (testing "Can run a benchmark (old format) and generate evidence"
    (let [manifest-path "benchmarks/dispute-liveness.edn"
          evidence (runner/run-benchmark manifest-path)]
      (is (contains? evidence :benchmark))
      (is (contains? evidence :repo))
      (is (contains? evidence :evidence/hash))
      (is (vector? (:results evidence))))))

(deftest test-benchmark-run-new-format
  (testing "Can run a benchmark (new pack format) and generate evidence"
    (let [manifest-path "benchmarks/packs/sew/escrow-dispute-v1.edn"
          evidence (runner/run-benchmark manifest-path)]
      (is (contains? evidence :benchmark) "Evidence should contain :benchmark")
      (is (contains? evidence :repo) "Evidence should contain :repo")
      (is (contains? evidence :evidence/hash) "Evidence should contain :evidence/hash")
      (is (contains? evidence :benchmark-certification) "Evidence should contain :benchmark-certification")
      (is (vector? (:results evidence)) "Results should be a vector")
      (is (contains? evidence :metrics) "Evidence should contain :metrics")
      ;; Verify the evidence shape matches BENCHMARK_RESULT_SPEC_V1
      (is (string? (:evidence/hash evidence)) "Hash should be a string")
      (is (pos? (count (:evidence/hash evidence))) "Hash should be non-empty")
      ;; :repo should contain git metadata
      (is (contains? (:repo evidence) :repo) ":repo should contain nested :repo metadata"))))

(deftest test-suite-resolution-in-adapter
  (testing "SewAdapter resolves :benchmark/scenario-suite keyword"
    (let [manifest (edn/read-string (slurp "benchmarks/packs/sew/escrow-dispute-v1.edn"))
          adapter runner/default-adapter
          scenarios (adapter/load-scenarios adapter manifest)]
      (is (= 44 (count scenarios))
          "Adapter should resolve :suite/sew-dispute-safety-v1 to 44 scenarios")
      (is (every? #(instance? java.io.File %) scenarios)
          "All scenarios should be java.io.File objects")))

  (testing "SewAdapter falls back to :scenario-suites (old format)"
    (let [old-manifest {:scenario-suites ["scenarios"]}
          adapter runner/default-adapter
          scenarios (adapter/load-scenarios adapter old-manifest)]
      (is (pos? (count scenarios))
          "Old format should still resolve scenarios via directory walking"))))

(deftest test-evidence-shape
  (testing "Evidence bundle matches BENCHMARK_RESULT_SPEC_V1 shape"
    (let [evidence (runner/run-benchmark "benchmarks/packs/sew/escrow-dispute-v1.edn")]
      ;; Core shape
      (is (contains? evidence :benchmark) ":benchmark key present")
      (is (contains? evidence :repo) ":repo key present")
      (is (contains? evidence :environment) ":environment key present")
      (is (contains? evidence :results) ":results key present")
      (is (contains? evidence :metrics) ":metrics key present")
      (is (contains? evidence :evidence/hash) ":evidence/hash key present")
      (is (contains? evidence :benchmark-certification) ":benchmark-certification key present")

      ;; Environment shape
      (is (contains? (:environment evidence) :os-name) ":environment :os-name")
      (is (contains? (:environment evidence) :java-version) ":environment :java-version")

      ;; Metrics shape
      (is (contains? (:metrics evidence) :total) ":metrics :total")
      (is (contains? (:metrics evidence) :passed) ":metrics :passed")

      ;; Benchmark shape
      (is (map? (:benchmark evidence)) ":benchmark is a map")

      ;; Results vector
      (is (every? #(contains? % :file) (:results evidence))
          "Each result should have :file")
      (is (every? #(contains? % :outcome) (:results evidence))
          "Each result should have :outcome"))))

(deftest test-deterministic-replay-benchmark-produces-claim-results
  (testing "PRF deterministic replay benchmark executes duplicate runs and resolves replay claims"
    (with-redefs [repo/metadata (fn [] {:repo {:commit "test-commit"
                                               :dirty? false}})
                  sew/replay-with-sew-protocol (fn [_scenario _opts]
                                                 {:events-processed 3
                                                  :outcome :pass
                                                  :halt-reason nil
                                                  :metrics {:invariant-results {}}
                                                  :world {:status :ok}})
                  sew-inv/check-all (fn [_world] {:results {}})]
      (let [evidence (runner/run-benchmark "benchmarks/packs/prf-core/deterministic-replay-v1.edn")
            claim-results (:claim-results evidence)
            claim-outcomes (into {} (map (juxt :claim/id :claim/outcome)) claim-results)]
        (is (= 88 (count (:results evidence)))
            "Deterministic replay benchmark should execute 44 scenarios twice")
        (is (= #{1 2} (into #{} (map :benchmark/run-index) (:results evidence))))
        (is (= #{2} (into #{} (map :benchmark/run-count) (:results evidence))))
        (is (= 88 (get-in evidence [:metrics :execution-count])))
        (is (= 44 (get-in evidence [:metrics :unique-scenario-count])))
        (is (= 2 (get-in evidence [:metrics :declared-run-count])))
        (is (= 88 (get-in evidence [:run/manifest :execution-count])))
        (is (= 44 (get-in evidence [:run/manifest :unique-scenario-count])))
        (is (= 2 (get-in evidence [:run/manifest :declared-run-count])))
        (is (= :pass (get claim-outcomes :claim/replay-identical-results)))
        (is (= :pass (get claim-outcomes :claim/hash-consistency-across-runs)))
        (is (= :pass (get claim-outcomes :claim/no-nondeterminism)))
        (is (not-any? #(= :inconclusive (:claim/outcome %)) claim-results)
            "Replay claims should now resolve to concrete outcomes")))))

(deftest test-malformed-manifest
  (testing "Throws on missing manifest"
    (is (thrown? Exception (runner/load-manifest "non-existent.edn")))))

(deftest test-hash-stability
  (testing "Hashing is stable across different instances of same data"
    (let [data {:repo {:commit "abc"}}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} (into {} data))]
      (is (= h1 h2)))))

(deftest test-share-summary
  (testing "Share summary generation"
    (let [evidence {:benchmark {:benchmark/id "test-bm"}
                    :repo {:repo {:commit "def"}}
                    :metrics {:passed 5 :total 5}
                    :evidence/hash "hash123"}
          summary (sharing/share-summary evidence)]
      (is (str/includes? summary "test-bm"))
      (is (str/includes? summary "def"))
      (is (str/includes? summary "PASS")))))

(deftest test-attestation
  (testing "Attestation structure"
    (let [evidence {:benchmark {:benchmark/id "test-bm" :commit "abc"}
                    :repo {:repo {:commit "def"}}
                    :evidence/hash "hash123"}
          ;; Mock slurp and signing/sign-hash
          _ (with-redefs [clojure.core/slurp (fn [_] (pr-str evidence))
                          signing/sign-hash (fn [_ _ _] "sig123")]
              (let [att (sharing/attest "evidence.edn" "my-key" "pass")]
                (is (= "test-bm" (:benchmark/id att)))
                (is (= "hash123" (:evidence/hash att)))
                (is (= "sig123" (:signature att)))))])))
