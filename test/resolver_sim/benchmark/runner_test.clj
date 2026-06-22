(ns resolver-sim.benchmark.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.sharing :as sharing]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [buddy.core.keys :as keys]
            [buddy.core.codecs :as codecs]))

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
  (testing "Can run a benchmark and generate evidence"
    (let [manifest-path "benchmarks/dispute-liveness.edn"
          evidence (runner/run-benchmark manifest-path)]
      (is (contains? evidence :benchmark))
      (is (contains? evidence :repo))
      (is (contains? evidence :evidence/hash))
      (is (vector? (:results evidence))))))

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
