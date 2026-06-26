(ns resolver-sim.run.bundle-root-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.run.bundle-root :as br]))

(def sample-request
  "A representative :scenario-run/request map."
  {:runner/backend :local-current
   :runner-selection {:mode :pinned
                      :runner-id :runner/local-bb}
   :suite/key :sew-invariants
   :protocol/default-id "sew-v1"
   :evidence/profile :standard
   :output/profile :full
   :entries []})

(def sample-result
  "A representative :scenario-run/result map."
  {:status :pass
   :suite/key :sew-invariants
   :totals {:passed 3 :failed 0 :total 3}
   :results [{:scenario-id "S01" :pass? true :outcome :pass
              :checks [] :violations {} :dispatcher-id :protocol/sew-v1
              :expected-fail? false :scenario-path nil}
             {:scenario-id "S02" :pass? true :outcome :pass
              :checks [] :violations {} :dispatcher-id :protocol/sew-v1
              :expected-fail? false :scenario-path nil}
             {:scenario-id "S03" :pass? false :outcome :fail
              :checks [] :violations {} :dispatcher-id :protocol/sew-v1
              :expected-fail? true :scenario-path nil}]
   :diagnostics {:elapsed-ms 150 :suite-id :sew-invariants}})

(deftest build-bundle-root-has-required-top-level-keys
  (let [bundle (br/build-bundle-root sample-request sample-result)]
    (is (= "bundle-root.v1" (:bundle/schema-version bundle)))
    (is (some? (:bundle/id bundle)))
    (is (some? (:bundle/hash bundle)))
    (is (= (:bundle/id bundle) (:bundle/hash bundle)))
    (is (map? (:run/request bundle)))
    (is (map? (:registry/snapshot bundle)))
    (is (map? (:run/environment bundle)))
    (is (map? (:execution/summary bundle)))
    (is (string? (:overview/hash bundle)))
    (is (map? (:overview bundle)))))

(deftest build-bundle-root-run-request-keys
  (let [bundle (br/build-bundle-root sample-request sample-result)
        req (:run/request bundle)]
    (is (= :pinned (get-in req [:runner-selection :mode])))
    (is (= :runner/local-bb (get-in req [:runner-selection :runner-id])))
    (is (= :sew-invariants (:suite/key req)))
    (is (= "sew-v1" (:protocol/default-id req)))))

(deftest build-bundle-root-execution-summary
  (let [bundle (br/build-bundle-root sample-request sample-result)]
    (is (= {:passed 3 :failed 0 :total 3} (:totals (:execution/summary bundle))))
    (is (= :pass (:status (:execution/summary bundle))))))

(deftest build-bundle-root-registry-snapshot-contains-expected-keys
  (let [snap (:registry/snapshot (br/build-bundle-root sample-request sample-result))]
    (is (string? (:registry-hash snap)))
    (is (string? (:scenario-suite-hash snap)))
    (is (string? (:dispatcher-registry-hash snap)))
    (is (string? (:evidence-policy-hash snap)))
    (is (string? (:execution-registry-hash snap)))
    (is (string? (:claim-definition-registry-hash snap)))))

(deftest build-bundle-root-overview-hash-is-stable
  (let [b1 (br/build-bundle-root sample-request sample-result)
        b2 (br/build-bundle-root sample-request sample-result)]
    (is (= (:overview/hash b1) (:overview/hash b2)))
    (is (= (:bundle/hash b1) (:bundle/hash b2)))))

(deftest build-bundle-root-overview-hash-changes-when-results-differ
  (let [different-result (assoc-in sample-result [:results 0 :pass?] false)
        b1 (br/build-bundle-root sample-request sample-result)
        b2 (br/build-bundle-root sample-request different-result)]
    (is (not= (:overview/hash b1) (:overview/hash b2)))
    (is (not= (:bundle/hash b1) (:bundle/hash b2)))))

(deftest bundle-root-is-runnable
  (let [bundle (br/build-bundle-root sample-request sample-result)
        check (br/runnable? bundle)]
    (is (:runnable? check))))

(deftest bundle-root-runnable-fails-without-run-request
  (let [bundle (dissoc (br/build-bundle-root sample-request sample-result) :run/request)
        check (br/runnable? bundle)]
    (is (not (:runnable? check)))
    (is (some #(= :missing-run-request (:code %)) (:errors check)))))
