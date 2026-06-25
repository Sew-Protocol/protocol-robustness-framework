(ns resolver-sim.evidence.attestation-bundle-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-bundle :as ab]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.util UUID]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim claim-id]
      :or {signed-at "2025-01-01T00:00:00Z" claim :verified}}]
  (att/build-attestation (attestor) (subject) claim
                         (cond-> {:signed-at signed-at}
                           claim-id (assoc :claim-id claim-id))))

;; ── build-attestation-bundle ─────────────────────────────────────────────────

(deftest bundle-has-required-fields
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= "attestation-bundle.v1" (:bundle/version bundle)))
    (is (= :attestation-verification-package (:bundle/kind bundle)))
    (is (some? (:bundle/root-hash bundle)))))

(deftest bundle-includes-entrypoints
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= 1 (count (:bundle/entrypoints bundle))))
    (is (= (:attestation/id a)
           (:attestation/hash (first (:bundle/entrypoints bundle)))))))

(deftest bundle-includes-objects
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= 1 (count (:bundle/objects bundle))))
    (is (= :attestation-record (:object/kind (first (:bundle/objects bundle)))))
    (is (= (:attestation/id a) (:object/hash (first (:bundle/objects bundle)))))))

(deftest bundle-includes-registries
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (contains? (:bundle/registries bundle) :attestors))
    (is (contains? (:bundle/registries bundle) :claim-definitions))
    (is (contains? (:bundle/registries bundle) :hash-intents))))

(deftest bundle-root-hash-is-deterministic
  (let [a (build-a)
        opts {:attestations [a]
              :registries {:attestors registries/attestor-registry
                           :claim-definitions registries/claim-definition-registry
                           :hash-intents hc/hash-intents}}
        b1 (ab/build-attestation-bundle opts)
        b2 (ab/build-attestation-bundle opts)]
    (is (= (:bundle/root-hash b1) (:bundle/root-hash b2)))))

(deftest bundle-root-hash-excludes-self
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (nil? (:bundle/root-hash (dissoc bundle :bundle/root-hash))))))

(deftest bundle-sensitivity-defaults-to-blocked
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= :blocked (get-in bundle [:bundle/sensitivity :sentinel/decision])))))

(deftest bundle-with-sensitivity-report
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:sentinel/decision :allowed
                                      :sentinel/report-hash "sha256:report"}})]
    (is (= :allowed (get-in bundle [:bundle/sensitivity :sentinel/decision])))))

(deftest bundle-with-claim-results
  (let [a (build-a)
        claim-result {:claim-id :conservation
                      :claim-result-hash "sha256:claim"
                      :holds? true :status :pass}
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :claim-results [claim-result]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= 2 (count (:bundle/objects bundle))))
    (is (true? (:subject-content-included? (:bundle/verification-profile bundle))))))

;; ── verify-attestation-bundle (in-memory structural checks) ─────────────────

(deftest verify-detects-version-mismatch
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:sentinel/decision :allowed
                                      :sentinel/report-hash "sha256:r"}})
        tampered (assoc bundle :bundle/version "wrong-version")
        result (ab/verify-attestation-bundle tampered)]
    (is (false? (:valid? result)))
    (is (= :invalid (:bundle/status result)))
    (is (some #(= :fail (:check/status %)) (:checks result)))))

(deftest verify-detects-root-hash-mismatch
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:sentinel/decision :allowed
                                      :sentinel/report-hash "sha256:r"}})
        tampered (assoc bundle :bundle/root-hash "tampered")
        result (ab/verify-attestation-bundle tampered)]
    (is (false? (:valid? result)))
    (is (= :invalid (:bundle/status result)))))

(deftest verify-detects-blocked-sensitivity
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:sentinel/decision :blocked
                                      :sentinel/report-hash "sha256:blocked"}})
        result (ab/verify-attestation-bundle bundle)]
    (is (= :blocked-by-sensitivity-policy (:bundle/status result)))
    (is (false? (:valid? result)))))

(deftest verify-returns-summary
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:sentinel/decision :allowed
                                      :sentinel/report-hash "sha256:ok"}})
        result (ab/verify-attestation-bundle bundle)]
    (is (map? (:summary result)))
    (is (number? (get-in result [:summary :total-checks])))))

;; ── Bundle status levels ────────────────────────────────────────────────────

(deftest status-hash-linked
  (testing "warnings from missing files produce hash-linked status"
    (let [a (build-a :signed-at "2025-01-01T00:00:00Z")
          bundle (ab/build-attestation-bundle
                  {:attestations [a]
                   :registries {:attestors registries/attestor-registry
                                :claim-definitions registries/claim-definition-registry
                                :hash-intents hc/hash-intents}
                   :sensitivity-report {:sentinel/decision :allowed
                                        :sentinel/report-hash "sha256:ok"}})
          result (ab/verify-attestation-bundle bundle)]
      (is (= :hash-linked (:bundle/status result))
          "in-memory bundles have file-not-found warnings -> :hash-linked")
      (is (true? (:valid? result))))))

(deftest status-invalid-on-version-mismatch
  (let [a (build-a)
        bundle (assoc (ab/build-attestation-bundle
                       {:attestations [a]
                        :registries {:attestors registries/attestor-registry
                                     :claim-definitions registries/claim-definition-registry
                                     :hash-intents hc/hash-intents}
                        :sensitivity-report {:sentinel/decision :allowed
                                             :sentinel/report-hash "sha256:r"}})
                      :bundle/version "bad")
        result (ab/verify-attestation-bundle bundle)]
    (is (= :invalid (:bundle/status result)))))

(deftest status-blocked-on-sentinel
  (let [a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})
        result (ab/verify-attestation-bundle bundle)]
    (is (= :blocked-by-sensitivity-policy (:bundle/status result)))))

;; ── I/O tests ────────────────────────────────────────────────────────────────

(deftest write-and-read-bundle
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-test-"
                     (java.util.UUID/randomUUID))
        a (build-a)
        bundle (ab/build-attestation-bundle
                {:attestations [a]
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}
                 :sensitivity-report {:sentinel/decision :allowed
                                      :sentinel/report-hash "sha256:r"}
                 :options {:bundle-dir tmp-dir}})]
    (ab/write-attestation-bundle! bundle {:attestations [a]})
    (let [read-back (ab/read-attestation-bundle tmp-dir)]
      (is (= (:bundle/version bundle) (:bundle/version read-back)))
      (is (= (:bundle/root-hash bundle) (:bundle/root-hash read-back)))
      (io/delete-file (io/file tmp-dir) true))))

(deftest read-throws-on-missing-manifest
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/ab-test-"
                     (java.util.UUID/randomUUID))]
    (.mkdirs (io/file tmp-dir))
    (is (thrown? Exception (ab/read-attestation-bundle tmp-dir)))
    (io/delete-file (io/file tmp-dir) true)))
