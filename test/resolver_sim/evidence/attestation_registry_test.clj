(ns resolver-sim.evidence.attestation-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})

(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- claim-subject [] {:type :claim :claim-id :accounting-consistency})

(defn- build-a
  [& {:keys [claim subject attestor signed-at claim-id provenance signing-key-id]
      :or {claim :verified, subject (subject), attestor (attestor)}}]
  (att/build-attestation attestor subject claim
                         (cond-> {}
                           signed-at (assoc :signed-at signed-at)
                           claim-id (assoc :claim-id claim-id)
                           provenance (assoc :provenance provenance)
                           signing-key-id (assoc :signing-key-id signing-key-id))))

;; ── Registration ─────────────────────────────────────────────────────────────

(deftest register-attestation-stores-by-hash
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          id (:attestation/id a)]
      (is (= a (ar/register-attestation! a))
          "register-attestation! returns the attestation")
      (is (= a (ar/find-attestation id))
          "can look up by :attestation/id"))))

(deftest register-attestation-idempotent
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          id (:attestation/id a)]
      (ar/register-attestation! a)
      (ar/register-attestation! a)
      (is (= a (ar/find-attestation id))
          "registering same attestation twice is idempotent"))))

(deftest register-multiple-attestations
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          a2 (build-a :signed-at "2025-01-02T00:00:00Z" :claim :approved)]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (is (= 2 (count (ar/all-attestations)))))))

;; ── Lookup — not found ───────────────────────────────────────────────────────

(deftest find-attestation-returns-nil-for-unknown
  (ar/with-fresh-registry
    (is (nil? (ar/find-attestation "nonexistent-hash")))
    (is (= [] (ar/all-attestations)))))

;; ── Query by attestor ────────────────────────────────────────────────────────

(deftest find-by-attestor
  (ar/with-fresh-registry
    (let [attestor-a {:type :ci-runner :id :attestor-a}
          attestor-b {:type :ci-runner :id :attestor-b}
          a1 (build-a :attestor attestor-a :claim :verified
                      :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :attestor attestor-a :claim :approved
                      :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :attestor attestor-b :claim :verified
                      :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [from-a (ar/find-attestations-by-attestor :attestor-a)
            from-b (ar/find-attestations-by-attestor :attestor-b)]
        (is (= 2 (count from-a)))
        (is (= #{:verified :approved} (set (map :attestation/claim-result from-a))))
        (is (= 1 (count from-b)))
        (is (= :verified (:attestation/claim-result (first from-b))))))))

(deftest find-by-attestor-returns-empty-for-unknown
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")]
      (ar/register-attestation! a)
      (is (= [] (ar/find-attestations-by-attestor :nonexistent))))))

;; ── Query by subject ─────────────────────────────────────────────────────────

(deftest find-by-subject
  (ar/with-fresh-registry
    (let [subj-a {:type :evidence-node :hash "sha256:aaa"}
          subj-b {:type :evidence-node :hash "sha256:bbb"}
          a1 (build-a :subject subj-a :claim :verified
                      :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :subject subj-a :claim :approved
                      :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :subject subj-b :claim :verified
                      :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [for-a (ar/find-attestations-by-subject "sha256:aaa")
            for-b (ar/find-attestations-by-subject "sha256:bbb")]
        (is (= 2 (count for-a)))
        (is (= 1 (count for-b)))
        (is (= "sha256:bbb" (:attestation/subject-hash (first for-b))))))))

(deftest find-by-subject-returns-empty-for-unknown
  (ar/with-fresh-registry
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")]
      (ar/register-attestation! a)
      (is (= [] (ar/find-attestations-by-subject "sha256:nonexistent"))))))

(deftest find-by-subject-claim-type
  (ar/with-fresh-registry
    (let [a (build-a :subject (claim-subject) :claim :verified
                     :signed-at "2025-01-01T00:00:00Z")]
      (ar/register-attestation! a)
      (let [found (ar/find-attestations-by-subject :accounting-consistency)]
        (is (= 1 (count found)))
        (is (= :claim (:attestation/subject-kind (first found))))))))

;; ── Query by claim result ────────────────────────────────────────────────────

(deftest find-by-claim-result
  (ar/with-fresh-registry
    (let [a1 (build-a :claim :verified :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :claim :approved :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :claim :verified :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [verified (ar/find-attestations-by-claim-result :verified)
            approved (ar/find-attestations-by-claim-result :approved)]
        (is (= 2 (count verified)))
        (is (= 1 (count approved)))
        (is (= 0 (count (ar/find-attestations-by-claim-result :reproduced))))))))

(deftest find-by-claim-result-returns-empty-for-unused
  (ar/with-fresh-registry
    (is (= [] (ar/find-attestations-by-claim-result :rejected)))))

;; ── Query by claim id ────────────────────────────────────────────────────────

(deftest find-by-claim-id
  (ar/with-fresh-registry
    (let [a1 (build-a :claim-id :claim/consistency :claim :verified
                      :signed-at "2025-01-01T00:00:00Z")
          a2 (build-a :claim-id :claim/consistency :claim :approved
                      :signed-at "2025-01-02T00:00:00Z")
          a3 (build-a :claim-id :claim/other :claim :verified
                      :signed-at "2025-01-03T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [consistency (ar/find-attestations-by-claim-id :claim/consistency)
            other (ar/find-attestations-by-claim-id :claim/other)]
        (is (= 2 (count consistency)))
        (is (= 1 (count other)))
        (is (= [] (ar/find-attestations-by-claim-id :claim/absent)))))))

;; ── all-attestations sorting ─────────────────────────────────────────────────

(deftest all-attestations-sorted-by-signed-at
  (ar/with-fresh-registry
    (let [a1 (build-a :signed-at "2025-03-01T00:00:00Z")
          a2 (build-a :signed-at "2025-01-01T00:00:00Z")
          a3 (build-a :signed-at "2025-02-01T00:00:00Z")]
      (ar/register-attestation! a1)
      (ar/register-attestation! a2)
      (ar/register-attestation! a3)
      (let [all (ar/all-attestations)]
        (is (= 3 (count all)))
        (is (= ["2025-01-01T00:00:00Z"
                "2025-02-01T00:00:00Z"
                "2025-03-01T00:00:00Z"]
               (mapv :attestation/signed-at all)))))))

;; ── clear-attestations! ──────────────────────────────────────────────────────

(deftest clear-attestations-empties-registry
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :signed-at "2025-02-01T00:00:00Z"))
    (is (= 2 (count (ar/all-attestations))))
    (ar/clear-attestations!)
    (is (= [] (ar/all-attestations)))
    (is (nil? (ar/find-attestation (str (java.util.UUID/randomUUID)))))))

;; ── Registry status ──────────────────────────────────────────────────────────

(deftest registry-status-empty
  (ar/with-fresh-registry
    (let [s (ar/registry-status)]
      (is (= 0 (:count s)))
      (is (:empty? s)))))

(deftest registry-status-with-data
  (ar/with-fresh-registry
    (ar/register-attestation! (build-a :claim :verified :signed-at "2025-01-01T00:00:00Z"))
    (ar/register-attestation! (build-a :claim :approved :signed-at "2025-02-01T00:00:00Z"
                                       :attestor {:type :ci-runner :id :other-attestor}))
    (let [s (ar/registry-status)]
      (is (= 2 (:count s)))
      (is (false? (:empty? s)))
      (is (= #{:ci-validation :other-attestor} (set (:attestors s))))
      (is (= {:verified 1, :approved 1} (:claim-results s))))))

;; ── Edge: with-fresh-registry restores outer state ──────────────────────────

(deftest with-fresh-registry-restores-outer
  (let [outer (build-a :signed-at "2025-01-01T00:00:00Z")]
    (ar/register-attestation! outer)
    (is (= 1 (count (ar/all-attestations))))
    (ar/with-fresh-registry
      (is (= [] (ar/all-attestations)) "within with-fresh-registry, registry is empty")
      (ar/register-attestation! (build-a :signed-at "2025-02-01T00:00:00Z"))
      (is (= 1 (count (ar/all-attestations))) "within with-fresh-registry, can add"))
    (is (= 1 (count (ar/all-attestations)))
        "after with-fresh-registry, outer state is restored")
    (is (= outer (ar/find-attestation (:attestation/id outer)))
        "after with-fresh-registry, outer attestation is still accessible")))
