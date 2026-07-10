(ns resolver-sim.benchmark.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.cli :as cli]
            [resolver-sim.benchmark.registry :as registry]))

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
    (is (= "prf-core/prf-deterministic-replay-v1" (:id replay)))))
