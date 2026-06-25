(ns resolver-sim.sensitivity.sentinel
  "Sensitivity sentinel: classification and enforcement layer for artifact
   disclosure boundaries.

   Implements SENSITIVITY_SENTINEL_SPEC_V1.

   Usage:
     (require '[resolver-sim.sensitivity.sentinel :as sentinel])

     ;; Classify an artifact
     (sentinel/classify artifact)

     ;; Check if disclosure is allowed
     (sentinel/disclosure-allowed? artifact :public-bundle)

     ;; Assert (throws on block)
     (sentinel/assert-export-allowed! artifact {:sink :ipfs})

     ;; Full sentinel report
     (sentinel/sentinel-report artifact :ipfs)"
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.time Instant]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const sentinel-version "sensitivity-sentinel.v1")

;; ── Sensitivity Levels (ordered: low → high) ────────────────────────────────

(def levels
  "Ordered vector of sensitivity levels (lowest to highest)."
  [:sensitivity/public
   :sensitivity/internal
   :sensitivity/private
   :sensitivity/embargoed
   :sensitivity/critical-private])

(def level-set (set levels))

(def level-order
  "Map from level keyword to position in the severity ordering."
  (into {} (map-indexed (fn [i l] [l i]) levels)))

(defn level-index
  [level]
  (get level-order level (count levels)))

(defn level>= 
  "True if a is at least as sensitive as b (or higher)."
  [a b]
  (>= (level-index a) (level-index b)))

;; ── Sink Classes ────────────────────────────────────────────────────────────

(def safe-sinks
  #{:local :sealed-log :private-encrypted-bundle :sealed-private-workspace})

(def low-risk-sinks
  #{:encrypted-bundle})

(def medium-risk-sinks
  #{:public-bundle :public-ci-artifact})

(def high-risk-sinks
  #{:ipfs :nostr-public-relay :on-chain-registry :git-commit})

(def public-sinks
  "Sinks that result in public disclosure."
  (set/union medium-risk-sinks high-risk-sinks))

(def all-sinks
  (set/union safe-sinks low-risk-sinks medium-risk-sinks high-risk-sinks))

;; ── Reason Codes ────────────────────────────────────────────────────────────

(def reason-code-set
  #{:contains-live-vulnerability
    :potential-live-vulnerability
    :contains-reproducible-exploit-path
    :contains-protocol-identifier
    :contains-counterparty-identifier
    :contains-private-researcher-identity
    :contains-unredacted-scenario
    :contains-claim-result
    :contains-attestation
    :contains-unpublished-evidence
    :contains-linkable-subject-hash
    :contains-public-sink-reference
    :contains-timing-metadata})

;; ── Disclosure Matrix ───────────────────────────────────────────────────────

(defn disclosure-allowed?
  "Check if an artifact at the given sensitivity level may be sent to a sink.

   Returns true if the movement is allowed by the disclosure matrix, false
   if blocked.

   Arguments:
     level — sensitivity level keyword (e.g. :sensitivity/private)
     sink  — sink keyword (e.g. :ipfs)

   When level is unknown, defaults to :sensitivity/critical-private (blocked).
   When sink is unknown, defaults to blocked (conservative)."
  [level sink]
  (let [effective-level (if (contains? level-set level) level :sensitivity/critical-private)]
    (cond
      ;; Safe sinks: all levels allowed
      (contains? safe-sinks sink) true
      ;; Low-risk sinks: public, internal allowed
      (contains? low-risk-sinks sink)
      (contains? #{:sensitivity/public :sensitivity/internal} effective-level)
      ;; Public sinks (medium + high risk): only public allowed
      (contains? public-sinks sink)
      (= :sensitivity/public effective-level)
      ;; Unknown sink: blocked
      :else false)))

;; ── Classification ───────────────────────────────────────────────────────────

(defn classify
  "Classify an artifact and return its sensitivity level.

   Examines artifact content and structure using structural heuristics:

   - Evidence nodes with :result :status :fail → at least :internal
   - Evidence nodes with failure details → at least :private
   - Attestations → at least :internal
   - Attestations with claim results → at least :private
   - Claim results with :holds? false → at least :internal
   - Artifacts with provenance + scenario-id → at least :internal
   - Unredacted scenario content → at least :private
   - Unknown structure → :critical-private (conservative default)

   Arguments:
     artifact — any artifact map (attestation, evidence node, claim result, bundle)

   Returns a sensitivity level keyword."
  [artifact]
  (let [;; Evidence node: fail status
        result-status (get-in artifact [:result :status])
        failure-details (get-in artifact [:result :failure-details])
        ;; Attestation presence
        is-attestation (some? (:attestation/id artifact))
        claim-result (:attestation/claim-result artifact)
        claim-id (:attestation/claim-id artifact)
        ;; Claim result
        holds? (:holds? artifact)
        ;; Evidence presence
        has-attestations (seq (:attestations artifact))
        ;; Provenance
        provenance (:attestation/provenance artifact)
        scenario-id (or (:scenario-id provenance)
                        (:scenario-id artifact))
        ;; Subject
        subject-kind (:attestation/subject-kind artifact)
        ;; Bundle
        bundle-kind (:bundle/kind artifact)]
    (cond
      ;; Critical: evidence node with failure details (reproducible issues)
      (and (some? result-status) (seq failure-details))
      :sensitivity/private

      ;; Critical: attestation with claim-id (references a claim definition)
      (and is-attestation claim-id)
      :sensitivity/private

      ;; Critical: attestation on claim subject (references a claim result)
      (and is-attestation (= :claim subject-kind))
      :sensitivity/private

      ;; High: attestation (credible attributable statement)
      is-attestation :sensitivity/internal

      ;; High: evidence node with fail status
      (= :fail result-status) :sensitivity/internal

      ;; Medium: evidence with attestations attached
      has-attestations :sensitivity/internal

      ;; Medium: claim result with failed claim
      (false? holds?) :sensitivity/internal

      ;; Medium: artifact with scenario provenance
      scenario-id :sensitivity/internal

      ;; Bundle: classify as bundle
      bundle-kind :sensitivity/internal

      ;; Default: conservative
      :else :sensitivity/critical-private)))

;; ── Sentinel Report ─────────────────────────────────────────────────────────

(defn- compute-policy-hash
  []
  (hc/hash-with-intent {:hash/intent :evidence-record}
                       {:version sentinel-version
                        :levels levels
                        :sinks (vec (sort all-sinks))
                        :disclosure-rules "level>=:public required for public-sinks"}))

(defn- default-reasons
  [level]
  (case level
    :sensitivity/public []
    :sensitivity/internal [:contains-unpublished-evidence]
    :sensitivity/private [:contains-unpublished-evidence :contains-attestation]
    :sensitivity/embargoed [:contains-unpublished-evidence :contains-attestation
                            :contains-timing-metadata]
    :sensitivity/critical-private [:contains-unpublished-evidence :contains-attestation
                                    :contains-reproducible-exploit-path]
    [:contains-unpublished-evidence]))

(defn sentinel-report
  "Produce a full sentinel report for an artifact and requested sink.

   Arguments:
     artifact — artifact map to classify
     sink     — requested sink keyword

   Returns the sentinel report map."
  [artifact sink]
  (let [level (classify artifact)
        allowed? (disclosure-allowed? level sink)
        decision (if allowed? :allowed :blocked)
        reasons (default-reasons level)
        allowed-sinks (vec (sort (filter #(disclosure-allowed? level %) all-sinks)))
        input-kind (cond (:attestation/id artifact) :attestation-record
                         (:node-hash artifact) :evidence-node
                         (:holds? artifact) :claim-result
                         (:bundle/kind artifact) :bundle
                         :else :unknown)
        input-hash (or (:attestation/id artifact)
                       (:node-hash artifact)
                       (:bundle/root-hash artifact)
                       (hc/hash-with-intent {:hash/intent :evidence-record} artifact))]
    {:sentinel/version sentinel-version
     :sentinel/policy-hash (compute-policy-hash)
     :sentinel/evaluated-at (str (Instant/now))
     :sentinel/input-kind input-kind
     :sentinel/input-hash input-hash
     :sentinel/requested-sink sink
     :sentinel/decision decision
     :sentinel/level level
     :sentinel/reasons reasons
     :sentinel/allowed-sinks allowed-sinks
     :sentinel/redaction-required? (level>= level :sensitivity/private)
     :sentinel/override-required?
     {:required? (and (not allowed?)
                      (level>= level :sensitivity/private))
      :mode (if (level>= level :sensitivity/critical-private)
              :multi-party-approval
              :single)}}))

;; ── Assertion Functions ──────────────────────────────────────────────────────

(defn assert-disclosure-allowed!
  "Assert that an artifact may be sent to a sink.

   Classifies the artifact, checks the sink against the disclosure matrix,
   and returns the sentinel report if allowed. Throws if blocked.

   Arguments:
     artifact — artifact map
     opts     — map with :sink key (required)

   Returns the sentinel report on success.

   Throws ex-info with :sentinel/blocked on failure."
  [artifact & [{:keys [sink]}]]
  (let [report (sentinel-report artifact sink)]
    (if (= :allowed (:sentinel/decision report))
      report
      (throw (ex-info (str "Disclosure blocked by sensitivity sentinel: "
                           (:sentinel/level report) " → " sink)
                      {:sentinel/blocked true
                       :sentinel/report report})))))

(defn assert-export-allowed!
  "Assert that an artifact may be exported to a sink.
   Delegates to assert-disclosure-allowed!."
  [artifact {:keys [sink]}]
  (assert-disclosure-allowed! artifact {:sink sink}))

(defn assert-publish-allowed!
  "Assert that an evidence node may be published to a sink."
  [evidence-node {:keys [sink]}]
  (assert-disclosure-allowed! evidence-node {:sink sink}))

(defn assert-relay-allowed!
  "Assert that a sealed event may be relayed to a sink."
  [sealed-event {:keys [sink]}]
  (assert-disclosure-allowed! sealed-event {:sink sink}))

(defn assert-attestation-allowed!
  "Assert that an attestation may be sent to a sink."
  [attestation {:keys [sink]}]
  (assert-disclosure-allowed! attestation {:sink sink}))
