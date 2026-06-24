(ns resolver-sim.evidence.attestation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.attestation :as att]))

(defn- valid-attestor
  []
  {:type :ci-runner :id "github-actions"})

(defn- valid-subject
  []
  {:type :evidence-node :hash "sha256:abcdef1234567890"})

(defn- sample-signing-fn
  [_]
  {:algorithm :ed25519
   :public-key-id "key-001"
   :signature-bytes "deadbeef"})

;; ── build-attestation ───────────────────────────────────────────────────────

(deftest build-attestation-produces-required-fields
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified)]
    (is (some? (:attestation-id result)))
    (is (= {:type :ci-runner :id "github-actions"} (:attestor result)))
    (is (= {:type :evidence-node :hash "sha256:abcdef1234567890"} (:subject result)))
    (is (= :verified (:claim result)))
    (is (some? (:timestamp result)))))

(deftest build-attestation-accepts-override-id
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :certified
                                      {:id "my-custom-id"})]
    (is (= "my-custom-id" (:attestation-id result)))))

(deftest build-attestation-accepts-override-timestamp
  (let [ts "2025-01-15T10:00:00Z"
        result (att/build-attestation (valid-attestor) (valid-subject) :approved
                                      {:timestamp ts})]
    (is (= ts (:timestamp result)))))

(deftest build-attestation-attaches-signature-when-signing-fn-provided
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :reproduced
                                      {:signing-fn sample-signing-fn})]
    (is (some? (:signature result)))
    (is (= :ed25519 (:algorithm (:signature result))))
    (is (= "key-001" (:public-key-id (:signature result))))
    (is (= "deadbeef" (:signature-bytes (:signature result))))))

(deftest build-attestation-no-signature-when-signing-fn-absent
  (let [result (att/build-attestation (valid-attestor) (valid-subject) :verified)]
    (is (nil? (:signature result)))))

(deftest build-attestation-attaches-metadata
  (let [meta {:source "test-suite" :environment "ci"}
        result (att/build-attestation (valid-attestor) (valid-subject) :verified
                                      {:metadata meta})]
    (is (= meta (:metadata result)))))

(deftest build-attestation-signature-does-not-include-itself-or-metadata
  (let [called-with (atom nil)
        signing-fn (fn [data] (reset! called-with data) sample-signing-fn)]
    (att/build-attestation (valid-attestor) (valid-subject) :verified
                           {:signing-fn signing-fn :metadata {:env "test"}})
    (is (nil? (:signature @called-with))
        "signing-fn must not receive :signature")
    (is (nil? (:metadata @called-with))
        "signing-fn must not receive :metadata")))

(deftest build-attestation-supports-all-claim-types
  (doseq [claim [:verified :reproduced :certified :approved :rejected]]
    (let [result (att/build-attestation (valid-attestor) (valid-subject) claim)]
      (is (= claim (:claim result))
          (str "Claim type " (pr-str claim) " is supported")))))

(deftest build-attestation-supports-claim-subject
  (let [subject {:type :claim :claim-id :accounting-consistency}
        result (att/build-attestation (valid-attestor) subject :verified)]
    (is (= subject (:subject result)))))

;; ── validate-attestation-shape ──────────────────────────────────────────────

(deftest validate-valid-attestation-passes
  (let [result (att/validate-attestation-shape
                (att/build-attestation (valid-attestor) (valid-subject) :verified))]
    (is (:valid? result))))

(deftest validate-detects-missing-attestor
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified)
        result (att/validate-attestation-shape (dissoc a :attestor))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-missing-subject
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified)
        result (att/validate-attestation-shape (dissoc a :subject))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-missing-claim
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified)
        result (att/validate-attestation-shape (dissoc a :claim))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-missing-timestamp
  (let [a (att/build-attestation (valid-attestor) (valid-subject) :verified)
        result (att/validate-attestation-shape (dissoc a :timestamp))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/missing-field (:type %)) (:errors result)))))

(deftest validate-detects-attestor-without-type
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor {:id "no-type"}
                 :subject (valid-subject)
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-attestor (:type %)) (:errors result)))))

(deftest validate-detects-attestor-without-id
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor {:type :ci-runner}
                 :subject (valid-subject)
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-attestor (:type %)) (:errors result)))))

(deftest validate-detects-subject-without-type
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor (valid-attestor)
                 :subject {:hash "sha256:abc"}
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-subject (:type %)) (:errors result)))))

(deftest validate-detects-subject-without-hash-for-evidence-node
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor (valid-attestor)
                 :subject {:type :evidence-node}
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-subject (:type %)) (:errors result)))))

(deftest validate-detects-subject-without-claim-id-for-claim
  (let [result (att/validate-attestation-shape
                {:attestation-id "test"
                 :attestor (valid-attestor)
                 :subject {:type :claim}
                 :claim :verified
                 :timestamp "2026-01-01T00:00:00Z"})]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/invalid-subject (:type %)) (:errors result)))))

(deftest validate-detects-malformed-signature
  (let [result (att/validate-attestation-shape
                (att/build-attestation (valid-attestor) (valid-subject) :verified
                                       {:signing-fn (fn [_] "not-a-map")}))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/malformed-signature (:type %)) (:errors result)))))

(deftest validate-detects-signature-without-algorithm
  (let [result (att/validate-attestation-shape
                (assoc (att/build-attestation (valid-attestor) (valid-subject) :verified)
                       :signature {:public-key-id "key-001" :signature-bytes "abc"}))]
    (is (false? (:valid? result)))
    (is (some #(= :attestation/malformed-signature (:type %)) (:errors result)))))

(deftest validate-passes-unsigned-attestation
  (let [result (att/validate-attestation-shape
                (att/build-attestation (valid-attestor) (valid-subject) :verified))]
    (is (:valid? result))
    (is (nil? (:errors result)))))

(deftest validate-claim-subject-passes
  (let [subject {:type :claim :claim-id :accounting-consistency}
        result (att/validate-attestation-shape
                (att/build-attestation (valid-attestor) subject :verified))]
    (is (:valid? result))))

;; ── verify-attestation ─────────────────────────────────────────────────────

(defn- registry-attestor
  "Build an attestation referencing a real registry attestor.
   Uses :ci-validation which exists in the real registry."
  []
  {:type :ci-runner :id :ci-validation})

(defn- unknown-attestor
  "Build an attestation referencing an attestor not in the registry."
  []
  {:type :ci-runner :id :does-not-exist})

(deftest verify-passes-for-known-active-attestor
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        result (att/verify-attestation attestation)]
    (is (:valid? result))
    (is (some? (:checks result)))))

(deftest verify-detects-unknown-attestor
  (let [attestation (att/build-attestation (unknown-attestor) (valid-subject) :verified)
        result (att/verify-attestation attestation)]
    (is (false? (:valid? result)))
    (is (some #(and (= :attestor-exists (:check %)) (false? (:pass? %)))
              (:checks result)))))

(deftest verify-check-attestor-active-for-known-attestor
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation a)
        active-check (first (filter #(= :attestor-active (:check %)) checks))]
    (is (true? (:pass? active-check)))
    (is (= :active (get-in active-check [:detail :status])))))

(deftest verify-check-key-authorized-for-known-key
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        {:keys [checks]} (att/verify-attestation attestation)
        key-check (first (filter #(= :key-authorized (:check %)) checks))]
    (is (true? (:pass? key-check))
        (str "Key should be authorized, got: " (pr-str key-check)))))

(deftest verify-check-key-not-authorized-for-unknown-key
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "completely-unknown-key"
                                                           :signature-bytes "deadbeef"})})
        {:keys [checks]} (att/verify-attestation attestation)
        key-check (first (filter #(= :key-authorized (:check %)) checks))]
    (is (false? (:pass? key-check))
        (str "Unknown key should not be authorized, got: " (pr-str key-check)))))

(deftest verify-signature-check-reports-unavailable-without-verify-fn
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        {:keys [checks]} (att/verify-attestation attestation)
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (= :unavailable (:pass? sig-check)))))

(deftest verify-signature-check-passes-with-verify-fn
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:verify-fn (fn [_data _sig] true)})
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (true? (:pass? sig-check)))))

(deftest verify-signature-check-fails-with-verify-fn-returning-false
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified
                                           {:signing-fn (fn [_]
                                                          {:algorithm :ed25519
                                                           :public-key-id "ci-validation-placeholder"
                                                           :signature-bytes "deadbeef"})})
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:verify-fn (fn [_data _sig] false)})
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (false? (:pass? sig-check)))))

(deftest verify-signature-for-unsigned-attestation
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation)
        sig-check (first (filter #(= :signature-verified (:check %)) checks))]
    (is (= :unsigned (:pass? sig-check)))))

(deftest verify-subject-check-reports-unavailable-without-resolver
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation)
        subj-check (first (filter #(= :subject-exists (:check %)) checks))]
    (is (= :unavailable (:pass? subj-check)))))

(deftest verify-subject-check-passes-with-resolver-returning-true
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:subject-resolver (fn [_] true)})
        subj-check (first (filter #(= :subject-exists (:check %)) checks))]
    (is (true? (:pass? subj-check)))))

(deftest verify-subject-check-fails-with-resolver-returning-false
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation
                                                 {:subject-resolver (fn [_] false)})
        subj-check (first (filter #(= :subject-exists (:check %)) checks))]
    (is (false? (:pass? subj-check)))))

(deftest verify-all-checks-present
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation)]
    (is (= 6 (count checks)))
    (is (= #{:attestor-exists :attestor-active :key-authorized
             :signature-verified :subject-exists :revocation-status}
           (set (map :check checks))))))

(deftest verify-revocation-check-unavailable-without-resolver
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation)
        rev-check (first (filter #(= :revocation-status (:check %)) checks))]
    (is (= :unavailable (:pass? rev-check)))))

(deftest verify-revocation-check-reports-revoked
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation
                          {:revocation-resolver (fn [_] true)})
        rev-check (first (filter #(= :revocation-status (:check %)) checks))]
    (is (true? (:pass? rev-check)))
    (is (true? (get-in rev-check [:detail :revoked?])))))

(deftest verify-revocation-check-reports-not-revoked
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [checks]} (att/verify-attestation attestation
                          {:revocation-resolver (fn [_] false)})
        rev-check (first (filter #(= :revocation-status (:check %)) checks))]
    (is (false? (:pass? rev-check)))
    (is (false? (get-in rev-check [:detail :revoked?])))))

(deftest verify-does-not-fail-on-revoked-attestation
  (let [attestation (att/build-attestation (registry-attestor) (valid-subject) :verified)
        {:keys [valid?]} (att/verify-attestation attestation
                           {:revocation-resolver (fn [_] true)})]
    (is valid?
        "Revocation is informational — it must not make verification fail")))

;; ── verify-attestation-summary ─────────────────────────────────────────────

(deftest summary-returns-verified-for-valid-attestation
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified)]
    (is (= :verified (att/verify-attestation-summary a)))))

(deftest summary-returns-no-such-attestor
  (let [a (att/build-attestation (unknown-attestor) (valid-subject) :verified)]
    (is (= :no-such-attestor (att/verify-attestation-summary a)))))

(deftest summary-returns-key-not-authorized
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified
                                 {:signing-fn (fn [_]
                                                {:public-key-id "completely-unknown-key"
                                                 :signature-bytes "x"})})]
    (is (= :key-not-authorized (att/verify-attestation-summary a)))))

(deftest summary-returns-signature-mismatch
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified
                                 {:signing-fn (fn [_]
                                                {:algorithm :ed25519
                                                 :public-key-id "ci-validation-placeholder"
                                                 :signature-bytes "deadbeef"})})]
    (is (= :signature-mismatch (att/verify-attestation-summary a
                                                               {:verify-fn (fn [_ _] false)})))))

(deftest summary-returns-subject-unknown
  (let [a (att/build-attestation (registry-attestor) (valid-subject) :verified)]
    (is (= :subject-unknown (att/verify-attestation-summary a
                                                            {:subject-resolver (fn [_] false)})))))
