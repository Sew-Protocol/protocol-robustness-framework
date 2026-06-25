(ns resolver-sim.sensitivity.sentinel-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-bundle :as ab]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]))

;; ── Level ordering ──────────────────────────────────────────────────────────

(deftest levels-ordered-low-to-high
  (is (= 0 (sentinel/level-index :sensitivity/public)))
  (is (= 1 (sentinel/level-index :sensitivity/internal)))
  (is (= 2 (sentinel/level-index :sensitivity/private)))
  (is (= 3 (sentinel/level-index :sensitivity/embargoed)))
  (is (= 4 (sentinel/level-index :sensitivity/critical-private))))

(deftest level-unknown-defaults-to-highest
  (is (= 5 (sentinel/level-index :sensitivity/unknown))))

(deftest level-inequality
  (is (true? (sentinel/level>= :sensitivity/private :sensitivity/public)))
  (is (false? (sentinel/level>= :sensitivity/public :sensitivity/private)))
  (is (true? (sentinel/level>= :sensitivity/critical-private :sensitivity/private))))

;; ── Disclosure matrix ───────────────────────────────────────────────────────

(deftest public-allowed-on-all-sinks
  (doseq [sink sentinel/all-sinks]
    (is (true? (sentinel/disclosure-allowed? :sensitivity/public sink))
        (str "public allowed on " sink))))

(deftest internal-blocked-on-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/internal sink))
        (str "internal blocked on " sink))))

(deftest internal-allowed-on-safe-sinks
  (doseq [sink sentinel/safe-sinks]
    (is (true? (sentinel/disclosure-allowed? :sensitivity/internal sink))
        (str "internal allowed on " sink))))

(deftest private-blocked-on-all-non-safe-sinks
  (doseq [sink (clojure.set/difference sentinel/all-sinks sentinel/safe-sinks)]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/private sink))
        (str "private blocked on " sink))))

(deftest embargoed-blocked-on-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/embargoed sink))
        (str "embargoed blocked on " sink))))

(deftest critical-private-blocked-on-public-sinks
  (doseq [sink sentinel/public-sinks]
    (is (false? (sentinel/disclosure-allowed? :sensitivity/critical-private sink))
        (str "critical-private blocked on " sink))))

(deftest unknown-level-defaults-to-blocked
  (is (false? (sentinel/disclosure-allowed? :sensitivity/unknown :public-bundle)))
  (is (true? (sentinel/disclosure-allowed? :sensitivity/unknown :local))))

(deftest unknown-sink-defaults-to-blocked
  (is (false? (sentinel/disclosure-allowed? :sensitivity/public :unknown-sink))))

;; ── Classification: attestations ────────────────────────────────────────────

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(deftest classify-attestation-is-internal
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= :sensitivity/internal (sentinel/classify a)))))

(deftest classify-attestation-with-claim-id-is-private
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"
                                  :claim-id :claim/consistency})]
    (is (= :sensitivity/private (sentinel/classify a)))))

(deftest classify-attestation-on-claim-subject-is-private
  (let [claim-subject {:type :claim :claim-id :consistency}
        a (att/build-attestation (attestor) claim-subject :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (= :sensitivity/private (sentinel/classify a)))))

;; ── Classification: evidence nodes ──────────────────────────────────────────

(deftest classify-evidence-node-fail-is-internal
  (let [node {:node-hash "sha256:n1" :result {:status :fail}}]
    (is (= :sensitivity/internal (sentinel/classify node)))))

(deftest classify-evidence-node-fail-with-details-is-private
  (let [node {:node-hash "sha256:n1"
              :result {:status :fail
                       :failure-details [{:message "exploit path found"}]}}]
    (is (= :sensitivity/private (sentinel/classify node)))))

(deftest classify-evidence-node-with-attestations-is-internal
  (let [node {:node-hash "sha256:n1"
              :result {:status :pass}
              :attestations ["att-hash-1"]}]
    (is (= :sensitivity/internal (sentinel/classify node)))))

;; ── Classification: claim results ──────────────────────────────────────────

(deftest classify-claim-result-fail-is-internal
  (let [cr {:claim-id :conservation :holds? false :status :fail}]
    (is (= :sensitivity/internal (sentinel/classify cr)))))

(deftest classify-claim-result-pass-is-critical-private
  (let [cr {:claim-id :conservation :holds? true :status :pass}]
    (is (= :sensitivity/critical-private (sentinel/classify cr)))))

;; ── Classification: bundles ─────────────────────────────────────────────────

(deftest classify-bundle-is-internal
  (let [bundle (ab/build-attestation-bundle
                {:attestations []
                 :registries {:attestors registries/attestor-registry
                              :claim-definitions registries/claim-definition-registry
                              :hash-intents hc/hash-intents}})]
    (is (= :sensitivity/internal (sentinel/classify bundle)))))

;; ── Classification: default ─────────────────────────────────────────────────

(deftest classify-unknown-is-critical-private
  (is (= :sensitivity/critical-private (sentinel/classify {})))
  (is (= :sensitivity/critical-private (sentinel/classify "string")))
  (is (= :sensitivity/critical-private (sentinel/classify nil))))

;; ── Sentinel report ────────────────────────────────────────────────────────

(deftest sentinel-report-has-required-fields
  (let [r (sentinel/sentinel-report {:attestation/id "test"} :public-bundle)]
    (is (= "sensitivity-sentinel.v1" (:sentinel/version r)))
    (is (string? (:sentinel/policy-hash r)))
    (is (string? (:sentinel/evaluated-at r)))
    (is (some? (:sentinel/decision r)))
    (is (some? (:sentinel/level r)))
    (is (vector? (:sentinel/reasons r)))
    (is (vector? (:sentinel/allowed-sinks r)))))

(deftest sentinel-report-deterministic
  (let [a {:attestation/id "test-hash"}
        r1 (sentinel/sentinel-report a :public-bundle)
        r2 (sentinel/sentinel-report a :public-bundle)]
    (is (= (:sentinel/decision r1) (:sentinel/decision r2)))
    (is (= (:sentinel/level r1) (:sentinel/level r2)))
    (is (= (:sentinel/reasons r1) (:sentinel/reasons r2)))
    (is (= (:sentinel/policy-hash r1) (:sentinel/policy-hash r2)))))

(deftest sentinel-report-blocked-on-public-sink-for-attestation
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})
        r (sentinel/sentinel-report a :public-bundle)]
    (is (= :blocked (:sentinel/decision r)))
    (is (= :sensitivity/internal (:sentinel/level r)))))

(deftest sentinel-report-allowed-on-safe-sink-for-attestation
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})
        r (sentinel/sentinel-report a :local)]
    (is (= :allowed (:sentinel/decision r)))))

(deftest sentinel-report-redaction-required-for-private
  (let [r (sentinel/sentinel-report {:attestation/id "t" :attestation/claim-id :c} :local)]
    (is (true? (:sentinel/redaction-required? r)))))

(deftest sentinel-report-override-single-for-private
  (let [r (sentinel/sentinel-report {:attestation/id "t"} :public-bundle)]
    (is (= :single (get-in r [:sentinel/override-required? :mode])))))

(deftest sentinel-report-override-multi-party-for-critical-private
  (let [r (sentinel/sentinel-report {} :public-bundle)]
    (is (= :multi-party-approval (get-in r [:sentinel/override-required? :mode])))))

;; ── Assertion functions ─────────────────────────────────────────────────────

(deftest assert-allowed-passes
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})
        result (sentinel/assert-disclosure-allowed! a {:sink :local})]
    (is (map? result))
    (is (= :allowed (:sentinel/decision result)))))

(deftest assert-blocked-throws
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (thrown? Exception
                 (sentinel/assert-disclosure-allowed! a {:sink :public-bundle})))))

(deftest assert-export-allowed
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (thrown? Exception
                 (sentinel/assert-export-allowed! a {:sink :ipfs})))
    (is (map? (sentinel/assert-export-allowed! a {:sink :local})))))

(deftest assert-publish-allowed
  (let [node {:node-hash "sha256:n" :result {:status :pass}}]
    (is (thrown? Exception
                 (sentinel/assert-publish-allowed! node {:sink :nostr-public-relay})))))

(deftest assert-attestation-allowed
  (let [a (att/build-attestation (attestor) (subject) :verified
                                 {:signed-at "2025-01-01T00:00:00Z"})]
    (is (thrown? Exception
                 (sentinel/assert-attestation-allowed! a {:sink :on-chain-registry})))))
