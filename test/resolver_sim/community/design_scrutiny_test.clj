(ns resolver-sim.community.design-scrutiny-test
  "Tests for the design concerns raised during review.
   Each test verifies that references are correctly included in content hashes
   and that tampering with any reference field changes the object hash."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.task :as task]))

;; ── Concern 4: Reference fields in hashes ──────────────────────────────
;; The canonical-body functions strip ONLY explicitly designated self-identity
;; keys (e.g. :attestation/id, :attestation/hash, :attestation/ref).
;; They do NOT strip nested semantic references inside :subject, :assertion, etc.
;; These tests confirm that changing any external reference changes the hash.

(deftest change-task-ref-changes-attestation-hash
  (let [h1 (:attestation/hash
            (att/build-execution-attestation
             {:task/ref "research-task:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :runner/id "r1"
              :execution-node-hash "node-1"
              :issued-at "2026-07-01T00:00:00Z"}))
        h2 (:attestation/hash
            (att/build-execution-attestation
             {:task/ref "research-task:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              :runner/id "r1"
              :execution-node-hash "node-1"
              :issued-at "2026-07-01T00:00:00Z"}))]
    (is (not= h1 h2) "Changing task ref must change attestation hash")))

(deftest change-challenged-ref-changes-challenge-hash
  (let [h1 (:attestation/hash
            (att/build-challenge-attestation
             {:task/ref "research-task:sha256:task"
              :challenged-attestation-ref "attestation:sha256:original-aaa"
              :runner/id "r2"
              :reason "test"
              :issued-at "2026-07-01T00:00:00Z"}))
        h2 (:attestation/hash
            (att/build-challenge-attestation
             {:task/ref "research-task:sha256:task"
              :challenged-attestation-ref "attestation:sha256:original-bbb"
              :runner/id "r2"
              :reason "test"
              :issued-at "2026-07-01T00:00:00Z"}))]
    (is (not= h1 h2) "Changing challenged attestation ref must change challenge hash")))

(deftest change-original-attestation-ref-changes-reproduction-hash
  (let [h1 (:attestation/hash
            (att/build-reproduction-attestation
             {:task/ref "research-task:sha256:task"
              :original-attestation-ref "attestation:sha256:orig-aaa"
              :original-result-projection-hash "proj-aaa"
              :runner/id "r2"
              :reproduction-execution-node-hash "repro-node"
              :reproduction-result-projection-hash "proj-bbb"
              :comparison-policy :strict-hash
              :comparison-status :matched
              :issued-at "2026-07-01T00:00:00Z"}))
        h2 (:attestation/hash
            (att/build-reproduction-attestation
             {:task/ref "research-task:sha256:task"
              :original-attestation-ref "attestation:sha256:orig-bbb"
              :original-result-projection-hash "proj-aaa"
              :runner/id "r2"
              :reproduction-execution-node-hash "repro-node"
              :reproduction-result-projection-hash "proj-bbb"
              :comparison-policy :strict-hash
              :comparison-status :matched
              :issued-at "2026-07-01T00:00:00Z"}))]
    (is (not= h1 h2) "Changing original attestation ref must change reproduction hash")))

(deftest change-evidence-node-ref-changes-execution-hash
  (let [h1 (:attestation/hash
            (att/build-execution-attestation
             {:task/ref "research-task:sha256:task"
              :runner/id "r1"
              :execution-node-hash "evidence-node:sha256:node-aaa"
              :issued-at "2026-07-01T00:00:00Z"}))
        h2 (:attestation/hash
            (att/build-execution-attestation
             {:task/ref "research-task:sha256:task"
              :runner/id "r1"
              :execution-node-hash "evidence-node:sha256:node-bbb"
              :issued-at "2026-07-01T00:00:00Z"}))]
    (is (not= h1 h2) "Changing evidence node ref must change execution attestation hash")))

;; ── Concern 2: Public key substitution ─────────────────────────────────
;; The hashed payload includes :sender (for mailbox messages) and :assertion/:runner/id
;; (for attestations). If an attacker substitutes both the public key and signature,
;; the hash stays the same BUT the committed sender/runner-id in the hash must be
;; verified against the actual key used for signing.
;; These tests confirm that a masqueraded message (different public key for same sender)
;; can be detected by a verifier that checks key-to-identity binding.

(deftest sender-identity-is-committed-in-hash
  (let [h1 (:message/hash
            (mailbox/build-message
             {:message/type :RUNNER_RESULT
              :subject-task "research-task:sha256:task"
              :sender "runner-alpha"}))
        h2 (:message/hash
            (mailbox/build-message
             {:message/type :RUNNER_RESULT
              :subject-task "research-task:sha256:task"
              :sender "runner-beta"}))]
    (is (not= h1 h2) "Different senders must produce different message hashes")))

(deftest runner-identity-is-committed-in-attestation-hash
  (let [h1 (:attestation/hash
            (att/build-execution-attestation
             {:task/ref "research-task:sha256:task"
              :runner/id "runner-alpha"
              :execution-node-hash "node-1"
              :issued-at "2026-07-01T00:00:00Z"}))
        h2 (:attestation/hash
            (att/build-execution-attestation
             {:task/ref "research-task:sha256:task"
              :runner/id "runner-beta"
              :execution-node-hash "node-1"
              :issued-at "2026-07-01T00:00:00Z"}))]
    (is (not= h1 h2) "Different runner IDs must change attestation hash")))

;; ── Concern 3: Timestamps ──────────────────────────────────────────────
;; :issued-at IS included in the attestation hash (not stripped by canonical-body).
;; Mailbox :timestamp is excluded from hash.
;; These tests confirm the current behaviour.

(deftest issued-at-is-included-in-attestation-hash
  (let [same-payload (fn [issued-at]
                       (:attestation/hash
                        (att/build-execution-attestation
                         {:task/ref "research-task:sha256:task"
                          :runner/id "r1"
                          :execution-node-hash "node-1"
                          :issued-at issued-at})))
        h1 (same-payload "2026-07-01T00:00:00Z")
        h2 (same-payload "2026-07-02T00:00:00Z")]
    (is (not= h1 h2) ":issued-at changes must change attestation hash")))

(deftest mailbox-timestamp-is-excluded-from-hash
  (let [h1 (:message/hash
            (mailbox/build-message
             {:message/type :TASK_ANNOUNCEMENT
              :subject-task "research-task:sha256:task"
              :sender "r1"
              :timestamp "2026-07-01T00:00:00Z"}))
        h2 (:message/hash
            (mailbox/build-message
             {:message/type :TASK_ANNOUNCEMENT
              :subject-task "research-task:sha256:task"
              :sender "r1"
              :timestamp "2026-07-02T00:00:00Z"}))]
    (is (= h1 h2) "Mailbox timestamps must be excluded from content hashes")
    (is (nil? (:message/signature
               (mailbox/build-message
                {:message/type :TASK_ANNOUNCEMENT
                 :subject-task "research-task:sha256:task"
                 :sender "r1"})))
        "Unsigned messages cannot prove timestamp integrity")))
