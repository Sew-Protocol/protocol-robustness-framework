(ns resolver-sim.evidence.attestation-provenance-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation-provenance :as ap]))

;; ── provenance-entry ─────────────────────────────────────────────────────────

(deftest provenance-entry-requires-trigger
  (let [p (ap/provenance-entry :claim-evaluation)]
    (is (= ap/schema-version (:provenance/schema-version p)))
    (is (= :claim-evaluation (:provenance/trigger p)))
    (is (string? (:provenance/generated-at p)))
    (is (not= "" (:provenance/generated-at p)))))

(deftest provenance-entry-accepts-all-optional-keys
  (let [p (ap/provenance-entry :replay-complete
                               :run-id "run-123"
                               :scenario-id "S01"
                               :step 42
                               :vcs-sha "abc123def456"
                               :producer "test-emitter.v1"
                               :generated-at "2025-01-01T00:00:00Z")]
    (is (= "run-123" (:provenance/run-id p)))
    (is (= "S01" (:provenance/scenario-id p)))
    (is (= 42 (:provenance/step p)))
    (is (= "abc123def456" (:provenance/vcs-sha p)))
    (is (= "test-emitter.v1" (:provenance/producer p)))
    (is (= "2025-01-01T00:00:00Z" (:provenance/generated-at p)))))

(deftest provenance-entry-accepts-claims-context
  (let [ctx {:claim-id :conservation
             :claim-definition-hash "sha256:def"
             :claim-result-hash "sha256:abc"}
        p (ap/provenance-entry :claim-evaluation
                               :claims-context ctx)]
    (is (= ctx (:provenance/claims-context p)))))

(deftest provenance-entry-defaults-generated-at-to-now
  (let [before (str (java.time.Instant/now))
        _ (Thread/sleep 1)
        p (ap/provenance-entry :manual)
        after (str (java.time.Instant/now))]
    (is (string? (:provenance/generated-at p)))
    (is (<= (compare before (:provenance/generated-at p)) 0))
    (is (<= (compare (:provenance/generated-at p) after) 0))))

;; ── provenance-for-claim ─────────────────────────────────────────────────────

(deftest provenance-for-claim-includes-claim-context
  (let [claim-result {:claim-id :conservation
                      :claim-definition-hash "sha256:def-hash"
                      :claim-result-hash "sha256:abc-hash"
                      :holds? true
                      :status :pass}
        p (ap/provenance-for-claim :claim-evaluation claim-result
                                   :run-id "run-claim-test"
                                   :scenario-id "S01")
        ctx (:provenance/claims-context p)]
    (is (= :conservation (:provenance/claim-id ctx)))
    (is (= "sha256:def-hash" (:provenance/claim-definition-hash ctx)))
    (is (= "sha256:abc-hash" (:provenance/claim-result-hash ctx)))
    (is (= true (:provenance/claim-holds? ctx)))
    (is (= :pass (:provenance/claim-status ctx)))
    (is (= "run-claim-test" (:provenance/run-id p)))
    (is (= "S01" (:provenance/scenario-id p)))))

(deftest provenance-for-claim-preserves-general-fields
  (let [claim-result {:claim-id :non-negative
                      :claim-definition-hash "sha256:xyz"
                      :claim-result-hash "sha256:789"
                      :holds? false
                      :status :fail}
        p (ap/provenance-for-claim :claim-evaluation claim-result
                                   :run-id "run-test"
                                   :vcs-sha "abc123")]
    (is (= :claim-evaluation (:provenance/trigger p)))
    (is (= "run-test" (:provenance/run-id p)))
    (is (= "abc123" (:provenance/vcs-sha p)))))

;; ── validate-provenance ──────────────────────────────────────────────────────

(deftest validate-valid-provenance-passes
  (let [p (ap/provenance-entry :claim-evaluation
                               :run-id "r1"
                               :scenario-id "S01")]
    (is (:valid? (ap/validate-provenance p)))))

(deftest validate-detects-missing-required-keys
  (let [result (ap/validate-provenance {})]
    (is (false? (:valid? result)))
    (is (some #(re-find #"Missing required key" %) (:errors result)))))

(deftest validate-detects-missing-schema-version
  (let [p {:provenance/trigger :manual
           :provenance/generated-at "2025-01-01T00:00:00Z"}
        result (ap/validate-provenance p)]
    (is (false? (:valid? result)))
    (is (some #(re-find #":provenance/schema-version" %) (:errors result)))))

(deftest validate-detects-unknown-keys
  (let [p {:provenance/schema-version ap/schema-version
           :provenance/trigger :manual
           :provenance/generated-at "2025-01-01T00:00:00Z"
           :provenance/unknown-key "oops"}
        result (ap/validate-provenance p)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"Unknown key" %) (:errors result)))))

(deftest validate-detects-invalid-trigger
  (let [p {:provenance/schema-version ap/schema-version
           :provenance/trigger :not-a-real-trigger
           :provenance/generated-at "2025-01-01T00:00:00Z"}
        result (ap/validate-provenance p)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"Invalid trigger" %) (:errors result)))))

(deftest validate-detects-wrong-schema-version
  (let [p {:provenance/schema-version "old-version"
           :provenance/trigger :manual
           :provenance/generated-at "2025-01-01T00:00:00Z"}
        result (ap/validate-provenance p)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"Schema version mismatch" %) (:errors result)))))

(deftest validate-detects-empty-generated-at
  (let [p {:provenance/schema-version ap/schema-version
           :provenance/trigger :manual
           :provenance/generated-at ""}
        result (ap/validate-provenance p)]
    (is (false? (:valid? result)))
    (is (some #(re-find #"generated-at" %) (:errors result)))))

;; ── Integration: provenance can be used with build-attestation ──────────────

(deftest provenance-entry-compatible-with-build-attestation
  (let [provenance (ap/provenance-entry :claim-evaluation
                                        :run-id "run-integration"
                                        :scenario-id "S01"
                                        :step 10)
        result (ap/validate-provenance provenance)]
    (is (:valid? result))
    (is (= :claim-evaluation (:provenance/trigger provenance)))
    (is (= "run-integration" (:provenance/run-id provenance)))
    (is (= "S01" (:provenance/scenario-id provenance)))
    (is (= 10 (:provenance/step provenance)))))

(deftest provenance-for-claim-produces-valid-provenance
  (let [claim-result {:claim-id :conservation
                      :claim-definition-hash "sha256:def"
                      :claim-result-hash "sha256:abc"
                      :holds? true
                      :status :pass}
        p (ap/provenance-for-claim :claim-evaluation claim-result
                                   :run-id "run-valid"
                                   :vcs-sha "abc123")
        validation (ap/validate-provenance p)]
    (is (:valid? validation))))
