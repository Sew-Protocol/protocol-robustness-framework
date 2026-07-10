(ns resolver-sim.community.core-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [resolver-sim.community.task :as task]
            [resolver-sim.community.finding :as finding]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.graph :as graph]
            [resolver-sim.community.report :as report]))

(defn temp-dir-fixture
  [f]
  (let [d (str (java.nio.file.Files/createTempDirectory "community-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [mailbox/*mailbox-dir* (str d "/mailbox")]
      (f))))

(use-fixtures :each temp-dir-fixture)

(deftest equivalent-task-hashes-match
  (let [spec {:title "Test" :benchmark/id :test}
        t1 (task/build-task spec)
        t2 (task/build-task spec)]
    (is (= (:task/hash t1) (:task/hash t2)))))

(deftest equivalent-attestation-hashes-match
  (let [a1 (att/build-execution-attestation
            {:task/ref "research-task:sha256:abc123" :runner/id "runner-1"
             :execution-node-hash "node-hash-1" :issued-at "2026-07-01T00:00:00Z"})
        a2 (att/build-execution-attestation
            {:task/ref "research-task:sha256:abc123" :runner/id "runner-1"
             :execution-node-hash "node-hash-1" :issued-at "2026-07-01T00:00:00Z"})]
    (is (= (:attestation/hash a1) (:attestation/hash a2)))))

(deftest changing-subject-changes-attestation-hash
  (let [a1 (att/build-execution-attestation
            {:task/ref "research-task:sha256:abc123" :runner/id "runner-1" :execution-node-hash "node-A"})
        a2 (att/build-execution-attestation
            {:task/ref "research-task:sha256:def456" :runner/id "runner-1" :execution-node-hash "node-A"})]
    (is (not= (:attestation/hash a1) (:attestation/hash a2)))))

(deftest changing-predicate-changes-attestation-hash
  (let [a1 (att/build-execution-attestation
            {:task/ref "research-task:sha256:abc123" :runner/id "runner-1" :execution-node-hash "node-A"})
        a2 (att/build-reproduction-attestation
            {:task/ref "research-task:sha256:abc123" :original-attestation-ref "attestation:sha256:orig"
             :original-result-projection-hash "proj-A" :runner/id "runner-2"
             :reproduction-execution-node-hash "node-B" :reproduction-result-projection-hash "proj-B"
             :comparison-policy :strict-hash :comparison-status :matched})]
    (is (not= (:attestation/hash a1) (:attestation/hash a2)))))

(deftest tampered-message-is-detected
  (let [m (mailbox/build-message
           {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})
        tampered (assoc m :sender "different-runner")]
    (is (not (:hash-valid? (mailbox/verify-message tampered))))))

(deftest clean-process-resolves-task
  (let [t (task/build-task {:title "Test Task" :benchmark/id :test})]
    (is (task/valid-task? t))
    (is (task/task-ref? (:task/ref t)))))

(deftest missing-references-fail-explicitly
  (is (thrown? AssertionError (task/build-task {:title nil})))
  (is (thrown? AssertionError (finding/build-finding {:task/reference "invalid-ref"}))))

(deftest bare-references-are-rejected
  (is (not (task/task-ref? "bare-id")))
  (is (not (task/task-ref? "sha256:abc")))
  (is (task/task-ref? "research-task:sha256:abc123def456abc123def456abc123def456abc123def456abc123def456abc1")))

(deftest message-hashes-are-deterministic
  (let [m1 (mailbox/build-message
            {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})
        m2 (mailbox/build-message
            {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})]
    (is (= (:message/hash m1) (:message/hash m2)))))

(deftest duplicate-messages-are-detected
  (let [m (mailbox/build-message
           {:message/type :TASK_ANNOUNCEMENT :subject-task "research-task:sha256:abc123" :sender "runner-1"})]
    (is (= :published (mailbox/publish! m)))
    (is (= :duplicate (mailbox/publish! m)))))

(deftest task-correlation-is-deterministic
  (let [task-ref "research-task:sha256:abc123"
        m1 (mailbox/build-message {:message/type :TASK_ANNOUNCEMENT :subject-task task-ref :sender "r1"})
        m2 (mailbox/build-message {:message/type :RUNNER_RESULT :subject-task task-ref :sender "r2"})
        m3 (mailbox/build-message {:message/type :CHALLENGE :subject-task task-ref :sender "r3"})]
    (mailbox/publish! m1)
    (mailbox/publish! m2)
    (mailbox/publish! m3)
    (let [for-task (mailbox/messages-for-task task-ref)]
      (is (= 3 (count for-task)))
      (is (= :challenged (mailbox/task-status task-ref))))))

(deftest graph-traversal-reaches-all-records
  (let [t (task/build-task {:title "Graph Test" :benchmark/id :test})
        m (mailbox/build-message
           {:message/type :TASK_ANNOUNCEMENT :subject-task (:task/ref t) :sender "runner-1"})
        a (att/build-execution-attestation
           {:task/ref (:task/ref t) :runner/id "runner-1" :execution-node-hash "node-test"})
        g (graph/build-task-graph-projection {:task t :messages [m] :attestations [a]})]
    (is (= 3 (:node-count (:summary g))))
    (is (= 2 (:edge-count (:summary g))))
    (is (= :announced (:task-status (:summary g))))))

(deftest report-is-side-effect-free
  (let [t (task/build-task {:title "Report Test" :benchmark/id :test})
        a (att/build-execution-attestation
           {:task/ref (:task/ref t) :runner/id "runner-1" :execution-node-hash "node-test"})
        r (report/build-report {:task t :attestations [a]})]
    (is (= (:schema-version r) "community-report.v0"))
    (is (= (:task-status r) :unknown))
    (is (string? (get-in r [:original-run :attestation-hash])))))

(deftest valid-signed-execution-attestation-passes
  (let [a (att/build-execution-attestation
           {:task/ref "research-task:sha256:abc123" :runner/id "runner-1" :execution-node-hash "node-test"})]
    (is (att/valid-attestation? a))))

(deftest wrong-subject-hash-fails
  (let [a (att/build-execution-attestation
           {:task/ref "research-task:sha256:abc123" :runner/id "runner-1" :execution-node-hash "node-test"})
        tampered (assoc-in a [:subject :hash] "wrong-hash")]
    (is (not (att/valid-attestation? tampered)))))

(deftest challenge-does-not-mutate-original
  (let [orig (att/build-execution-attestation
              {:task/ref "research-task:sha256:abc123" :runner/id "runner-1" :execution-node-hash "node-test"})
        challenge (att/build-challenge-attestation
                   {:task/ref "research-task:sha256:abc123"
                    :challenged-attestation-ref (:attestation/ref orig)
                    :runner/id "runner-2" :reason "Evidence cannot be resolved"
                    :challenge-type :evidence-unresolvable})]
    (is (att/valid-attestation? orig))
    (is (att/valid-attestation? challenge))
    (is (not= (:attestation/hash orig) (:attestation/hash challenge)))))

(deftest unresolved-challenge-produces-inconclusive-status
  (let [task-ref "research-task:sha256:challenge-test"
        m (mailbox/build-message
           {:message/type :CHALLENGE :subject-task task-ref :sender "challenger"})]
    (mailbox/publish! m)
    (is (= :challenged (mailbox/task-status task-ref)))))
