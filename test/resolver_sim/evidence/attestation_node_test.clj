(ns resolver-sim.evidence.attestation-node-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-node :as an]
            [resolver-sim.hash.canonical :as hc]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-attestation
  [& {:keys [signed-at claim-id signing-key-id provenance metadata claim]
      :or {claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {}
                           signed-at (assoc :signed-at signed-at)
                           claim-id (assoc :claim-id claim-id)
                           signing-key-id (assoc :signing-key-id signing-key-id)
                           signing-key-id (assoc :signing-fn (fn [_]
                                                               {:algorithm :ed25519
                                                                :public-key-id signing-key-id
                                                                :signature-bytes "deadbeef"}))
                           provenance (assoc :provenance provenance)
                           metadata (assoc :metadata metadata))))

;; ── build-attestation-node ───────────────────────────────────────────────────

(deftest build-node-produces-required-fields
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)]
    (is (some? (:node-hash node)))
    (is (map? (:result node)))
    (is (true? (:attestations/reference node)))))

(deftest build-node-captures-attestation-id
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)]
    (is (= (:attestation/id a)
           (get-in node [:result :attestation-node/attestation-id])))))

(deftest build-node-captures-attestor-id
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)]
    (is (= :ci-validation
           (get-in node [:result :attestation-node/attestor-id])))))

(deftest build-node-captures-subject
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)]
    (is (= :evidence-node (get-in node [:result :attestation-node/subject-kind])))
    (is (= "sha256:abc" (get-in node [:result :attestation-node/subject-hash])))))

(deftest build-node-captures-claim
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z" :claim :reproduced
                              :claim-id :claim/reproduced)
        node (an/build-attestation-node a)]
    (is (= :reproduced (get-in node [:result :attestation-node/claim-result])))
    (is (= :claim/reproduced (get-in node [:result :attestation-node/claim-id])))))

(deftest build-node-detects-signed-status
  (let [unsigned (build-attestation :signed-at "2025-01-01T00:00:00Z")
        signed (build-attestation :signed-at "2025-01-01T00:00:00Z"
                                   :signing-key-id "key-001")
        node-unsigned (an/build-attestation-node unsigned)
        node-signed (an/build-attestation-node signed)]
    (is (false? (get-in node-unsigned [:result :attestation-node/signed?])))
    (is (true? (get-in node-signed [:result :attestation-node/signed?])))))

(deftest build-node-captures-provenance
  (let [prov {:provenance/run-id "run-123" :provenance/trigger :claim-evaluation}
        a (build-attestation :signed-at "2025-01-01T00:00:00Z" :provenance prov)
        node (an/build-attestation-node a)]
    (is (= prov (get-in node [:result :attestation-node/provenance])))))

(deftest build-node-hash-is-content-addressed
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)]
    (is (string? (:node-hash node)))
    (is (= 64 (count (:node-hash node)))
        "sha256 hex strings are 64 characters")))

(deftest build-node-deterministic-hash
  (let [opts {:signed-at "2025-01-01T00:00:00Z" :claim-id :claim/consistency
              :signing-key-id "key-001" :provenance {:run-id "r1"}}
        a1 (build-attestation opts)
        a2 (build-attestation opts)
        n1 (an/build-attestation-node a1)
        n2 (an/build-attestation-node a2)]
    (is (= (:node-hash n1) (:node-hash n2)))))

(deftest build-node-different-claims-different-hash
  (testing "different claim results produce different node hashes"
    (let [a1 (build-attestation :signed-at "2025-01-01T00:00:00Z" :claim :verified)
          a2 (build-attestation :signed-at "2025-01-01T00:00:00Z" :claim :approved)
          n1 (an/build-attestation-node a1)
          n2 (an/build-attestation-node a2)]
      (is (not= (:attestation/id a1) (:attestation/id a2))
          "different claims produce different attestation hashes")
      (is (not= (:node-hash n1) (:node-hash n2))
          "different attestations produce different node hashes"))))

(deftest build-node-different-subjects-different-hash
  (testing "different subject hashes produce different node hashes"
    (let [subject-a {:type :evidence-node :hash "sha256:aaa"}
          subject-b {:type :evidence-node :hash "sha256:bbb"}
          a1 (att/build-attestation (attestor) subject-a :verified
                                    {:signed-at "2025-01-01T00:00:00Z"})
          a2 (att/build-attestation (attestor) subject-b :verified
                                    {:signed-at "2025-01-01T00:00:00Z"})
          n1 (an/build-attestation-node a1)
          n2 (an/build-attestation-node a2)]
      (is (not= (:node-hash n1) (:node-hash n2))
          "different subjects produce different node hashes"))))

(deftest build-node-metadata-excluded-from-hash
  (testing "metadata is excluded from the attestation hash by design"
    (let [a1 (build-attestation :signed-at "2025-01-01T00:00:00Z" :metadata {:env "test"})
          a2 (build-attestation :signed-at "2025-01-01T00:00:00Z" :metadata {:env "prod"})
          n1 (an/build-attestation-node a1)
          n2 (an/build-attestation-node a2)]
      ;; Metadata is excluded from attestation body before hashing, so attestation IDs match
      (is (= (:attestation/id a1) (:attestation/id a2))
          "metadata must not change attestation hash")
      ;; Same attestation ID -> same node hash
      (is (= (:node-hash n1) (:node-hash n2))
          "same attestation -> same node hash"))))

;; ── Hash verification ───────────────────────────────────────────────────────

(deftest build-node-hash-matches-recomputed
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)
        recomputed (hc/hash-with-intent {:hash/intent :evidence-record} (:result node))]
    (is (= recomputed (:node-hash node)))))

;; ── Edge cases ───────────────────────────────────────────────────────────────

(deftest build-node-unsigned-attestation
  (let [a (build-attestation :signed-at "2025-01-01T00:00:00Z")
        node (an/build-attestation-node a)]
    (is (some? (:node-hash node)))
    (is (= :ci-validation (get-in node [:result :attestation-node/attestor-id])))))
