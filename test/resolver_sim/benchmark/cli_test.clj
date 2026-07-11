(ns resolver-sim.benchmark.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.cli :as cli]
            [resolver-sim.benchmark.registry :as registry]
            [resolver-sim.hash.canonical :as hc]))

(deftest record-history-best-effort-ignores-write-failures
  (testing "History write failures only emit a warning"
    (let [calls (atom [])]
      (with-redefs [registry/record-entry (fn [entry]
                                            (swap! calls conj entry)
                                            (throw (ex-info "boom" {:entry entry})))]
        (is (nil? (#'cli/record-history-best-effort! {:benchmark {:benchmark/id "bm-1"}})))
        (is (= 1 (count @calls)))))))

(deftest benchmark-index-retains-canonical-benchmark-ids
  (let [entries (:benchmarks (#'cli/load-index))
        replay (first (filter #(= :benchmark/prf-deterministic-replay-v1
                                  (:benchmark/id %))
                              entries))]
    (is replay)
    (is (= "prf-core/prf-deterministic-replay-v1" (:id replay)))
    (is (= :active (:status replay)))
    (is (pos? (:claims replay)))))

(deftest legacy-bundle-hashes-remain-verifiable
  (let [base {:benchmark {:benchmark/id :benchmark/test}
              :metrics {:total 1 :passed 1}}
        legacy (assoc base
                      :run/manifest {:manifest/version "run-manifest.v1"}
                      :benchmark-certification {:certification-hash "later"}
                      :evidence/hash (hc/hash-with-intent {:hash/intent :bundle-root} base))]
    (is (= {:hash-ok? true
            :scheme :legacy-v1
            :computed-hash (:evidence/hash legacy)}
           (#'cli/verify-bundle-hash legacy)))))

(deftest non-interactive-runs-suppress-the-post-run-prompt
  (is (#'cli/interactive-run? true {}))
  (is (not (#'cli/interactive-run? true {:non-interactive true})))
  (is (not (#'cli/interactive-run? false {}))))
