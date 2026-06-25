(ns resolver-sim.evidence.attestation
  "ATTESTATION_SPEC_V1 attestation builder and shape validator.

   Provides:
   - build-attestation  — construct a spec-compliant attestation map
   - validate-attestation-shape — structural validation per spec §9

   Usage:
     (require '[resolver-sim.evidence.attestation :as att])

     (att/build-attestation
       {:type :ci-runner :id \"github-actions\"}
       {:type :evidence-node :hash \"sha256:abc...\"}
       :verified
       {:timestamp \"2026-06-23T12:00:00Z\"
        :signing-fn (fn [data] {:algorithm :ed25519
                                :public-key-id \"key-001\"
                                :signature-bytes \"hex...\"})})"
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.definitions.passive-registries :as registries]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const schema-version 1)

;; ── Attestation Builder ──────────────────────────────────────────────────────

(defn- default-timestamp
  []
  (str (java.time.Instant/now)))

(defn signing-payload
  "Reconstruct the canonical signing payload from an attestation record.

   The signing payload is the exact data structure that was signed:
     {:intent :attestation-record
      :artifact {:schema-version \"...\"
                 :attestation/subject-hash \"...\"
                 :attestation/subject-kind :evidence-node|:claim
                 :attestation/claim-id ...
                 :attestation/claim-result :verified|:reproduced|...
                 :attestation/attestor-id ...
                 :attestation/signing-key-id ...
                 :attestation/signed-at \"...\"
                 :attestation/provenance ...}}

   Self-identifiers (:attestation/id, :attestation/hash), signature,
   and metadata are excluded — they are not part of the attested content.
   The projection function project-attestation-record is the identity
   lens for what constitutes 'attestation content' for hashing and signing."
  [attestation]
  (hc/project-attestation-record attestation :attestation-record))

(defn build-attestation
  "Build a content-addressed attestation record with deterministic identity.

   Arguments:
     attestor  — map {:type ... :id ...}
     subject   — map {:type :evidence-node|:claim :hash ...|:claim-id ...}
     claim     — keyword claim-result, one of :verified :reproduced :certified
                 :approved :rejected
     opts      — optional map with keys:
                  :signed-at      — ISO-8601 UTC string (default: now)
                  :signing-key-id — string identifying the signing key
                  :signing-fn     — (fn [canonical-signing-payload]) returning
                                    {:algorithm :ed25519
                                     :public-key-id \"...\"
                                     :signature-bytes \"...\"}
                  :claim-id       — registered claim definition id (default: nil)
                  :provenance     — map with run-id, scenario-id, etc.
                  :metadata       — optional metadata map (excluded from hash)

   Returns a content-addressed attestation record:
     :schema-version \"attestation.v1\"
     :attestation/id           (= :attestation/hash, content-derived)
     :attestation/hash         sha256 of canonical projection
     :attestation/subject-hash from subject :hash
     :attestation/subject-kind from subject :type
     :attestation/claim-id     registered claim id (if provided)
     :attestation/claim-result :verified|:reproduced|...
     :attestation/attestor-id  from attestor :id
     :attestation/signing-key-id key identifier (if provided)
     :attestation/signed-at    ISO-8601 instant
     :attestation/provenance   context map (if provided)
     :attestation/signature    present if signing-fn provided
     :attestation/metadata     present if metadata provided

   The signing-fn receives the canonical signing payload as returned
   by signing-payload — this is the same data structure that was hashed.
   Use signing-payload to reconstruct what was signed for verification."
  [attestor subject claim & [{:keys [signed-at signing-key-id signing-fn claim-id provenance metadata]}]]
  (let [body (cond-> {:schema-version "attestation.v1"
                       :attestation/subject-hash (or (:hash subject) (:claim-id subject))
                       :attestation/subject-kind (:type subject)
                       :attestation/claim-id claim-id
                       :attestation/claim-result claim
                       :attestation/attestor-id (:id attestor)
                       :attestation/signed-at (or signed-at (default-timestamp))
                       :attestation/provenance provenance}
              signing-key-id (assoc :attestation/signing-key-id signing-key-id))
        projected (signing-payload body)
        body-hash (hc/hash-with-intent {:hash/intent :attestation-record} body)
        artifact (assoc body
                        :attestation/id body-hash
                        :attestation/hash body-hash)
        with-meta (if metadata (assoc artifact :attestation/metadata metadata) artifact)]
    (if signing-fn
      (assoc with-meta :attestation/signature (signing-fn projected))
      with-meta)))

;; ── Shape Validation ─────────────────────────────────────────────────────────

(defn- missing-field-error
  [field]
  {:type :attestation/missing-field
   :field field
   :message (str "Missing required field: " (name field))})

(defn- malformed-signature-error
  [detail]
  {:type :attestation/malformed-signature
   :message "Signature is malformed"
   :detail detail})

(defn- validate-required-fields
  [attestation]
  (let [required #{:attestation-id :attestor :subject :claim :timestamp}
        missing (clojure.set/difference required (set (keys attestation)))]
    (mapv missing-field-error missing)))

(defn- validate-subject
  [subject]
  (cond-> []
    (not (:type subject))
    (conj {:type :attestation/invalid-subject
           :message "Subject missing required :type field"
           :subject subject})
    (not (#{:evidence-node :claim} (:type subject)))
    (conj {:type :attestation/invalid-subject-type
           :message (str "Subject :type must be :evidence-node or :claim, got " (pr-str (:type subject)))
           :subject subject})
    (and (= :evidence-node (:type subject)) (not (:hash subject)))
    (conj {:type :attestation/invalid-subject
           :message "Subject of type :evidence-node missing required :hash"
           :subject subject})
    (and (= :claim (:type subject)) (not (:claim-id subject)))
    (conj {:type :attestation/invalid-subject
           :message "Subject of type :claim missing required :claim-id"
           :subject subject})))

(defn- validate-attestor
  [attestor]
  (cond-> []
    (not (map? attestor))
    (conj {:type :attestation/invalid-attestor
           :message (str "Attestor must be a map, got " (type attestor))
           :attestor attestor})
    (not (:type attestor))
    (conj {:type :attestation/invalid-attestor
           :message "Attestor missing required :type field"
           :attestor attestor})
    (not (:id attestor))
    (conj {:type :attestation/invalid-attestor
           :message "Attestor missing required :id field"
           :attestor attestor})))

(defn- validate-signature
  [signature]
  (when signature
    (cond-> []
      (not (map? signature))
      (conj (malformed-signature-error (str "Expected map, got " (type signature))))
      (and (map? signature) (not (:algorithm signature)))
      (conj (malformed-signature-error "Missing :algorithm"))
      (and (map? signature) (not (:public-key-id signature)))
      (conj (malformed-signature-error "Missing :public-key-id"))
      (and (map? signature) (not (:signature-bytes signature)))
      (conj (malformed-signature-error "Missing :signature-bytes")))))

(defn validate-attestation-shape
  "Validate that an attestation map conforms to ATTESTATION_SPEC_V1 §9.
   Returns {:valid? true} or {:valid? false :errors [...]}.

   Checks:
   - Required fields present (:attestation-id, :attestor, :subject, :claim, :timestamp)
   - Attestor has :type and :id
   - Subject has valid :type and matching id field (:hash or :claim-id)
   - Signature is correctly structured (if present)"
  [attestation]
  (let [required-errors (validate-required-fields attestation)
        attestor-errors (if-let [a (:attestor attestation)]
                          (validate-attestor a)
                          [])
        subject-errors (if-let [s (:subject attestation)]
                         (validate-subject s)
                         [])
        signature-errors (validate-signature (:signature attestation))
        all-errors (vec (concat required-errors attestor-errors subject-errors signature-errors))]
    (if (seq all-errors)
      {:valid? false :errors all-errors}
      {:valid? true})))

;; ── Attestation Verification (Two Layers) ─────────────────────────────────────
;; ATTESTATION_SPEC_V1 §9 and ATTESTOR_REGISTRY_SPEC_V1 §11.
;;
;; Layer 1 — Registry-backed authorization: attestor exists, active, key authorized.
;; Layer 2 — Cryptographic-only: verify-fn checks the signature.
;;
;; The two layers are independent. verify-attestation runs all checks and returns
;; per-check results; a caller can decide which layer matters for their use case.

(defn- attestor-id
  "Extract attestor identifier string from an attestation's :attestor field."
  [attestation]
  (:id (:attestor attestation)))

(defn- signing-key-id
  "Extract the signing key identifier from the attestation's :signature.
   Returns nil if no signature or no public-key-id in signature."
  [attestation]
  (get-in attestation [:signature :public-key-id]))

(defn- data-to-verify
  "Reconstruct the data that was signed for an old-shape attestation.
   Old shape: the attestation minus :signature and :metadata.
   New shape: use signing-payload for the canonical projection."
  [attestation]
  (if (:attestation/subject-kind attestation)
    (signing-payload attestation)
    (dissoc attestation :signature :metadata)))

(defn- check-attestor-exists
  "Check that the attestor is registered."
  [attestation]
  (let [id (attestor-id attestation)
        entry (when id (registries/find-attestor id))]
    {:check :attestor-exists
     :pass? (some? entry)
     :detail (if entry
               {:attestor-id (:id entry) :type (:type entry)}
               {:attestor-id id :reason :not-found})}))

(defn- check-attestor-active
  "Check that the attestor's current status is :active."
  [attestation]
  (let [id (attestor-id attestation)
        entry (when id (registries/find-attestor id))]
    (if entry
      {:check :attestor-active
       :pass? (registries/attestor-active? entry)
       :detail {:attestor-id (:id entry) :status (registries/attestor-status entry)}}
      {:check :attestor-active
       :pass? false
       :detail {:attestor-id id :reason :attestor-not-found}})))

(defn- check-key-authorized
  "Registry-backed authorization: is the signing key active for this attestor?
   Uses the attestor registry exclusively — no cryptographic work done here.
   Authorized means: primary key match, active delegate, or active in key-history.
   If the attestation has no signature, this check passes as :unsigned."
  [attestation]
  (let [id (attestor-id attestation)
        key-id (signing-key-id attestation)
        entry (when id (registries/find-attestor id))]
    (cond
      (nil? entry)
      {:check :key-authorized
       :pass? false
       :detail {:attestor-id id :reason :attestor-not-found}}
      (nil? (:signature attestation))
      {:check :key-authorized
       :pass? :unsigned
       :detail {:reason :no-signature}}
      (nil? key-id)
      {:check :key-authorized
       :pass? false
       :detail {:reason :no-key-id-in-signature}}
      :else
      (let [authorized? (boolean (registries/key-authorized-for-attestor? entry key-id))
            known? (registries/key-known-for-attestor? entry key-id)]
        {:check :key-authorized
         :pass? authorized?
         :detail {:key-id key-id
                  :authorized? authorized?
                  :known? known?
                  :status (cond
                            authorized? :active
                            known? :retired
                            :else :unknown)}}))))

(defn- check-signature
  "Cryptographic signature verification. Purely cryptographic — does not
   check key authorization. Authorization is handled by check-key-authorized
   via the attestor registry. Without verify-fn, reports :unavailable."
  [attestation verify-fn]
  (let [signature (:signature attestation)]
    (cond
      (nil? signature)
      {:check :signature-verified
       :pass? :unsigned
       :detail {:reason :no-signature-present}}

      (nil? verify-fn)
      {:check :signature-verified
       :pass? :unavailable
       :detail {:reason :no-verify-fn-provided}}

      :else
      (let [data (data-to-verify attestation)
            result (try
                     (verify-fn data signature)
                     (catch Exception e
                       {:pass? false :error (.getMessage e)}))
            pass? (if (map? result) (:pass? result) (boolean result))]
        {:check :signature-verified
         :pass? pass?
         :detail (if (map? result) result {:raw-result result})}))))

(defn- check-subject-exists
  "Check that the subject references a known evidence node or claim.
   Requires a subject-resolver function in opts."
  [attestation subject-resolver]
  (let [subject (:subject attestation)]
    (cond
      (nil? subject)
      {:check :subject-exists
       :pass? false
       :detail {:reason :no-subject}}

      (nil? subject-resolver)
      {:check :subject-exists
       :pass? :unavailable
       :detail {:reason :no-subject-resolver-provided}}

      :else
      (let [exists? (try
                      (subject-resolver subject)
                      (catch Exception e
                        (do
                          (.println *err* "subject-resolver failed:" (.getMessage e))
                          nil)))]
        (cond
          (nil? exists?)
          {:check :subject-exists
           :pass? :error
           :detail {:reason :resolver-threw :subject subject}}
          exists?
          {:check :subject-exists
           :pass? true
           :detail {:subject subject}}
          :else
          {:check :subject-exists
           :pass? false
           :detail {:reason :subject-not-found :subject subject}})))))

(defn- check-revocation
  "Check if the attestation has been revoked.
   Requires a revocation-resolver fn in opts.
   Per ATTESTATION_SPEC_V1 §7 and ATTESTOR_REGISTRY_SPEC_V1 §8,
   revocation does not invalidate the cryptographic attestation —
   this check is informational."
  [attestation revocation-resolver]
  (let [id (:attestation-id attestation)]
    (cond
      (nil? id)
      {:check :revocation-status
       :pass? :unavailable
       :detail {:reason :no-attestation-id}}

      (nil? revocation-resolver)
      {:check :revocation-status
       :pass? :unavailable
       :detail {:reason :no-revocation-resolver-provided}}

      :else
      (let [revoked? (try
                       (boolean (revocation-resolver id))
                       (catch Exception e
                         (do (.println *err* "revocation-resolver failed:" (.getMessage e))
                             nil)))]
        (cond
          (nil? revoked?)
          {:check :revocation-status
           :pass? :error
           :detail {:reason :resolver-threw :attestation-id id}}
          revoked?
          {:check :revocation-status
           :pass? true
           :detail {:revoked? true :attestation-id id}}
          :else
          {:check :revocation-status
           :pass? false
           :detail {:revoked? false :attestation-id id}})))))

(defn verify-attestation
  "Verify an attestation. Two independent layers:

     Layer 1 — Registry-backed authorization (mandatory):
       :attestor-exists     — registry: is the attestor registered?
       :attestor-active     — registry: is the attestor status :active?
       :key-authorized      — registry: is the signing key active for this attestor?

     Layer 2 — Cryptographic verification (optional, via :verify-fn):
       :signature-verified  — cryptographic: does the signature match the data?

     Additional checks:
       :subject-exists      — does the subject hash/claim-id resolve?
       :revocation-status   — informational, registry-backed

   Layers are independent. Registry checks do not involve cryptography.
   The verify-fn does pure cryptographic work — it does not consult the registry.
   Unless all applicable checks pass, :valid? is false.
   Checks returning :unavailable, :unsigned, :error do not count as failures.
   The :revocation-status check is informational — revocation does not invalidate
   the cryptographic attestation.

   opts:
     :verify-fn             — (fn [data-to-verify signature-map]) -> boolean or {:pass? bool}
     :subject-resolver      — (fn [subject-map]) -> boolean
     :revocation-resolver   — (fn [attestation-id]) -> boolean; true if revoked"
  [attestation & [{:keys [verify-fn subject-resolver revocation-resolver]}]]
  (let [checks [(check-attestor-exists attestation)
                (check-attestor-active attestation)
                (check-key-authorized attestation)
                (check-signature attestation verify-fn)
                (check-subject-exists attestation subject-resolver)
                (check-revocation attestation revocation-resolver)]
        hard-failures (filterv (fn [c]
                                 (false? (:pass? c)))
                               checks)]
    {:valid? (empty? hard-failures)
     :checks checks}))

(defn verify-attestation-summary
  "Single-keyword summary of attestation verification.
   Returns one of:
     :verified             — all applicable checks pass
     :no-such-attestor     — attestor not in registry
     :attestor-revoked     — attestor status is :revoked or :retired
     :key-not-authorized   — signing key not authorized for attestor
     :signature-mismatch   — cryptographic signature verification failed
     :subject-unknown      — subject does not resolve
     :no-signature         — attestation has no signature"
  [attestation & opts]
  (let [{:keys [valid? checks]} (apply verify-attestation attestation opts)]
    (if valid?
      :verified
      (let [fail->summary {:attestor-exists :no-such-attestor
                           :attestor-active :attestor-revoked
                           :key-authorized :key-not-authorized
                           :signature-verified :signature-mismatch
                           :subject-exists :subject-unknown}
            failures (filterv (fn [c] (false? (:pass? c))) checks)]
         (or (some (fn [c] (get fail->summary (:check c))) failures)
             :verification-failed)))))

;; ── Claim Integration ────────────────────────────────────────────────────────

(defn build-claim-result-attestation
  "Build an attestation for a claim result.

   The attestation references the claim result hash as its subject, linking
   the attestor's verification to the specific claim evaluation outcome.
   The claim-id is passed through for cross-referencing via the registry.

   Arguments:
     attestor     — map {:type ... :id ...}
     claim-result — claim result map with at least :claim-id and
                    :claim-result-hash keys (as produced by
                    claim-result-entry in slashing.clj)
     opts         — optional map with keys:
                     :claim       — claim result for this attestation
                                   (default: :verified)
                     :signed-at, :signing-key-id, :signing-fn
                     :provenance, :metadata

   Returns a content-addressed attestation record with:
     :attestation/subject-kind :claim
     :attestation/subject-hash <claim-result-hash>
     :attestation/claim-id    <claim-id>
     :attestation/claim-result :verified (or as specified)"
  [attestor claim-result & [{:keys [claim signed-at signing-key-id signing-fn
                                    provenance metadata]
                             :or {claim :verified}}]]
  (build-attestation
   attestor
   {:type :claim :hash (:claim-result-hash claim-result)}
   claim
   (cond-> {:claim-id (:claim-id claim-result)}
     signed-at (assoc :signed-at signed-at)
     signing-key-id (assoc :signing-key-id signing-key-id)
     signing-fn (assoc :signing-fn signing-fn)
     provenance (assoc :provenance provenance)
     metadata (assoc :metadata metadata))))


