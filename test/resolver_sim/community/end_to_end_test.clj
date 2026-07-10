(ns resolver-sim.community.end-to-end-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.community.task :as task]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.graph :as graph]
            [resolver-sim.community.report :as report]))

(defn- with-temp-dirs
  [f]
  (let [artifact-dir (str (java.nio.file.Files/createTempDirectory
                           "e2e-artifacts" (make-array java.nio.file.attribute.FileAttribute 0)))
        mailbox-dir (str (java.nio.file.Files/createTempDirectory
                          "e2e-mailbox" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (binding [mailbox/*mailbox-dir* mailbox-dir]
      (f artifact-dir mailbox-dir))))

(def task-spec
  {:title "PRF-DR3 Deterministic Replay"
   :task/type :benchmark-execution
   :benchmark/id :benchmark/prf-deterministic-replay-v1
   :suite/id :suite/prf-replay-v1
   :claim-ids [:claim/replay-identical-results :claim/hash-consistency-across-runs :claim/no-nondeterminism]
   :acceptance-criteria ["All runs produce identical bundle roots" "Evidence hashes are consistent across runs"]})

(defn shared-task-ref
  "Build the task once and return its ref, so all steps use the same ref."
  []
  (:task/ref (delay (task/build-task task-spec))))

(deftest positive-fixture-complete-workflow
  (with-temp-dirs
    (fn [artifact-dir mailbox-dir]
      (let [task-ref (:task/ref (task/build-task task-spec))]
        (testing "Register task"
          (let [t (task/build-task task-spec)]
            (is (task/valid-task? t))
            (is (task/task-ref? (:task/ref t)))
            (mailbox/publish!
             (mailbox/build-message
              {:message/type :TASK_ANNOUNCEMENT :subject-task (:task/ref t) :sender "researcher-1"}))))
        (testing "Runner executes"
          (let [a (att/build-execution-attestation
                   {:task/ref task-ref :runner/id "runner-alpha"
                    :execution-node-hash "sha256:exec-node-alpha"
                    :result-projection-hash "sha256:result-alpha"})]
            (att/persist-attestation! a artifact-dir)
            (mailbox/publish!
             (mailbox/build-message
              {:message/type :RUNNER_RESULT :subject-task task-ref :sender "runner-alpha"
               :attestation-ref (:attestation/ref a)}))))
        (testing "Reproducer agrees"
          (let [a (att/build-reproduction-attestation
                   {:task/ref task-ref :original-attestation-ref "attestation:sha256:exec-node-alpha"
                    :original-result-projection-hash "sha256:result-alpha"
                    :runner/id "runner-beta" :reproduction-execution-node-hash "sha256:exec-node-beta"
                    :reproduction-result-projection-hash "sha256:result-alpha"
                    :comparison-policy :strict-hash :comparison-status :matched})]
            (att/persist-attestation! a artifact-dir)
            (mailbox/publish!
             (mailbox/build-message
              {:message/type :AGREEMENT :subject-task task-ref :sender "runner-beta"
               :attestation-ref (:attestation/ref a)}))))
        (testing "Mailbox integration"
          (let [msgs (mailbox/messages-for-task task-ref)]
            (is (= 3 (count msgs)))
            (is (= :agreed (mailbox/task-status task-ref)))))
        (testing "Evidence graph"
          (let [msgs (mailbox/messages-for-task task-ref)
                attestation-refs (keep :attestation-ref msgs)
                attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) attestation-refs)
                g (graph/build-task-graph-projection {:task (task/build-task task-spec) :messages msgs :attestations attestations})]
            (is (= 6 (get-in g [:summary :node-count])))
            (is (= :agreed (get-in g [:summary :task-status])))))
        (testing "Report generation"
          (let [msgs (mailbox/messages-for-task task-ref)
                attestation-refs (keep :attestation-ref msgs)
                attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) attestation-refs)
                r (report/build-report {:task (task/build-task task-spec) :attestations attestations :messages msgs})]
            (is (string? (get-in r [:task :id])))
            (is (some? (:original-run r)))
            (is (some? (:reproduction-run r)))
            (is (= :agreed (:task-status r)))))))))

(deftest negative-fixture-challenge-on-mismatch
  (with-temp-dirs
    (fn [artifact-dir mailbox-dir]
      (let [task-ref (:task/ref (task/build-task task-spec))]
        (testing "Original runner executes"
          (let [a (att/build-execution-attestation
                   {:task/ref task-ref :runner/id "runner-alpha"
                    :execution-node-hash "sha256:exec-alpha"
                    :result-projection-hash "sha256:result-alpha"})]
            (att/persist-attestation! a artifact-dir)
            (mailbox/publish!
             (mailbox/build-message
              {:message/type :RUNNER_RESULT :subject-task task-ref :sender "runner-alpha"
               :attestation-ref (:attestation/ref a)}))))
        (testing "Challenger detects mismatch"
          (let [challenge (att/build-challenge-attestation
                           {:task/ref task-ref :challenged-attestation-ref "attestation:sha256:exec-alpha"
                            :runner/id "runner-beta" :reason "Stable result differs from claimed output"
                            :challenge-type :result-mismatch})]
            (att/persist-attestation! challenge artifact-dir)
            (mailbox/publish!
             (mailbox/build-message
              {:message/type :CHALLENGE :subject-task task-ref :sender "runner-beta"
               :attestation-ref (:attestation/ref challenge)}))))
        (testing "Original result preserved, status is challenged"
          (let [msgs (mailbox/messages-for-task task-ref)]
            (is (some #(= :RUNNER_RESULT (:message/type %)) msgs) "Original result preserved")
            (is (some #(= :CHALLENGE (:message/type %)) msgs) "Challenge published")
            (is (= :challenged (mailbox/task-status task-ref)))))
        (testing "Report shows unresolved challenge"
          (let [msgs (mailbox/messages-for-task task-ref)
                attestation-refs (keep :attestation-ref msgs)
                attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) attestation-refs)
                r (report/build-report {:task (task/build-task task-spec) :messages msgs :attestations attestations})]
            (is (= :challenged (:task-status r)))
            (is (seq (:challenges r)) "No challenges in report")
            (is (seq (:unresolved-references r)) "No unresolved references in report")))))))
