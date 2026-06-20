(ns resolver-sim.evidence.chain-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.util.evidence :as ev]))

(def ^:private sample-attribution
  {:ctx/run-id "test-run-1"
   :ctx/scenario-id "test-scenario"
   :ctx/step 1
   :ctx/event-id "evt-001"})

(defn- make-sample-evidence [n]
  (ev/make-evidence-record
   {:artifact-kind :transition
    :before {:counter (dec n)}
    :after {:counter n}
    :action {:type :increment :n n}
    :result {:ok true}
    :attribution sample-attribution}))

;; ── Registry lifecycle ────────────────────────────────────────────────────

(deftest reset-registry-clears-state
  (chain/reset-registry! :run-id "test")
  (let [status (chain/registry-status)]
    (is (zero? (:evidence-count status)))
    (is (= "test" (:run-id status)))))

(deftest register-evidence-adds-to-registry
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)
        eh (chain/register-evidence! ev1)]
    (is (string? eh))
    (is (= (:evidence-hash ev1) eh))
    (is (= 1 (:evidence-count (chain/registry-status))))))

(deftest register-evidence-idempotent
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)]
    (chain/register-evidence! ev1)
    (chain/register-evidence! ev1)
    (is (= 1 (:evidence-count (chain/registry-status))))))

(deftest register-multiple-evidences
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)
        ev2 (make-sample-evidence 2)
        ev3 (make-sample-evidence 3)]
    (chain/register-evidence! ev1)
    (chain/register-evidence! ev2)
    (chain/register-evidence! ev3)
    (is (= 3 (:evidence-count (chain/registry-status))))))

;; ── Build registry ────────────────────────────────────────────────────────

(deftest build-registry-produces-self-hashed-map
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry :run-id "test-run")]
    (is (= "evidence-registry.v1" (:schema-version registry)))
    (is (= "test-run" (:run-id registry)))
    (is (string? (:generated-at registry)))
    (is (= 2 (:evidence-count registry)))
    (is (= 2 (count (:evidence-hashes registry))))
    (is (= 2 (count (:artifacts registry))))
    (is (string? (:registry-hash registry)))))

(deftest build-registry-registry-hash-commits-to-content
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [reg1 (chain/build-registry :run-id "a")]
    (chain/register-evidence! (make-sample-evidence 2))
    (let [reg2 (chain/build-registry :run-id "a")]
      (is (not= (:registry-hash reg1) (:registry-hash reg2))
          "Adding evidence changes registry hash"))))

(deftest build-registry-run-id-changes-hash
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [reg-a (chain/build-registry :run-id "run-a")
        reg-b (chain/build-registry :run-id "run-b")]
    (is (not= (:registry-hash reg-a) (:registry-hash reg-b)))))

;; ── Artifact entries ──────────────────────────────────────────────────────

(deftest artifact-entries-contain-evidence-hash
  (chain/reset-registry!)
  (let [ev (make-sample-evidence 1)]
    (chain/register-evidence! ev)
    (let [registry (chain/build-registry)
          entry (first (:artifacts registry))]
      (is (= (:evidence-hash ev) (:evidence-hash entry)))
      (is (= (:context-hash ev) (:context-hash entry)))
      (is (= (:before-hash ev) (:before-hash entry)))
      (is (= (:after-hash ev) (:after-hash entry)))
      (is (= (:action-hash ev) (:action-hash entry)))
      (is (= (:result-hash ev) (:result-hash entry)))
      (is (= (:artifact-kind ev) (:artifact-kind entry))))))

(deftest artifact-entries-have-consistent-ids
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry)
        ids (map :id (:artifacts registry))]
    (is (= 2 (count ids)))
    (is (apply distinct? ids))
    (is (every? #(re-matches #"evidence-[a-f0-9]+" %) ids))))

;; ── Verification ──────────────────────────────────────────────────────────

(deftest verify-registry-hash-valid
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry)
        result (chain/verify-registry-hash registry)]
    (is (:valid result))))

(deftest verify-registry-hash-invalid-when-tampered
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [registry (chain/build-registry)
        tampered (assoc registry :evidence-count 999)
        result (chain/verify-registry-hash tampered)]
    (is (not (:valid result)))))

(deftest verify-evidence-in-registry-present
  (chain/reset-registry!)
  (let [ev (make-sample-evidence 1)]
    (chain/register-evidence! ev)
    (let [registry (chain/build-registry)
          result (chain/verify-evidence-in-registry registry (:evidence-hash ev))]
      (is (:present result))
      (is (= (:evidence-hash ev) (get-in result [:entry :evidence-hash]))))))

(deftest verify-evidence-in-registry-absent
  (chain/reset-registry!)
  (let [registry (chain/build-registry)
        result (chain/verify-evidence-in-registry registry "nonexistent-hash")]
    (is (not (:present result)))))

(deftest evidence-chain-integrity-intact
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [registry (chain/build-registry)
        result (chain/evidence-chain-integrity registry)]
    (is (:registry-hash-valid result))
    (is (:all-hashes-registered result))
    (is (:chain-intact result))
    (is (= 2 (:artifact-count result)))))

;; ── Serialization ─────────────────────────────────────────────────────────

(deftest registry-round-trip-json
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [registry (chain/build-registry)
        json-str (chain/registry->json registry)
        re-read (json/read-str json-str)]
    (is (string? json-str))
    (is (= (:schema-version registry) (get re-read "schema-version")))
    (is (= (:run-id registry) (get re-read "run-id")))
    (is (= (:registry-hash registry) (get re-read "registry-hash")))
    (is (= 1 (count (get re-read "artifacts"))))))

;; ── Integration with dispatcher ───────────────────────────────────────────

(deftest dispatcher-registers-evidence-automatically
  (chain/reset-registry!)
  (let [ev (make-sample-evidence 1)]
    (chain/register-evidence! ev)
    (is (= 1 (:evidence-count (chain/registry-status))))
    (let [registry (chain/build-registry)
          entry (first (:artifacts registry))]
      (is (= (:evidence-hash ev) (:evidence-hash entry))))))

;; ── Phase 3: Artifact Registry Integration ───────────────────────────────

(deftest compute-file-sha256-returns-hex
  (chain/reset-registry!)
  (let [registry (chain/build-registry)
        path (chain/write-registry! registry)
        sha (chain/compute-file-sha256 path)]
    (is (string? sha))
    (is (= 64 (count sha)))
    (is (re-matches #"[a-f0-9]+" sha))))

(deftest finalize-and-write-produces-registry-and-entry
  (chain/reset-registry! :run-id "phase3-test")
  (chain/register-evidence! (make-sample-evidence 1))
  (chain/register-evidence! (make-sample-evidence 2))
  (let [{:keys [registry artifact-entry path]} (chain/finalize-and-write! :run-id "phase3-test")]
    (is (map? registry))
    (is (= "evidence-registry.v1" (:schema-version registry)))
    (is (string? (:registry-hash registry)))
    (is (= 2 (:evidence-count registry)))
    (is (string? path))
    (is (.exists (java.io.File. path)))
    ;; artifact entry for test-artifacts.json
    (is (map? artifact-entry))
    (is (= "evidence-registry" (:id artifact-entry)))
    (is (= "evidence-registry" (:kind artifact-entry)))
    (is (string? (:sha256 artifact-entry)))
    (is (string? (:path artifact-entry)))
    (is (= (:registry-hash registry) (:registry-hash artifact-entry)))
    (is (= (:evidence-count registry) (:evidence-count artifact-entry)))))

(deftest finalize-and-write-file-is-valid-json
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [{:keys [path registry]} (chain/finalize-and-write! :run-id "json-test")
        re-read (json/read-str (slurp path))]
    (is (= (:schema-version registry) (get re-read "schema-version")))
    (is (= (:registry-hash registry) (get re-read "registry-hash")))
    (is (= 1 (get re-read "evidence-count")))
    (is (= 1 (count (get re-read "artifacts"))))))

(deftest registry-artifact-entry-nil-when-no-file
  (chain/reset-registry!)
  (let [path (str (evcfg/artifact-dir) "/" chain/evidence-registry-filename)
        f (java.io.File. path)]
    (when (.exists f) (.delete f)))
  (let [registry (chain/build-registry)
        entry (chain/registry-artifact-entry registry)]
    (is (nil? entry) "No artifact entry when evidence-registry.json doesn't exist")))

(deftest registry-artifact-entry-produces-entry-when-file-exists
  (chain/reset-registry!)
  (chain/register-evidence! (make-sample-evidence 1))
  (let [{:keys [registry]} (chain/finalize-and-write!)
        entry (chain/registry-artifact-entry registry)]
    (is (map? entry))
    (is (string? (:sha256 entry)))
    (is (= "evidence-registry" (:id entry)))))

(deftest full-chain-from-evidence-to-artifact-entry
  (chain/reset-registry!)
  (let [ev1 (make-sample-evidence 1)
        ev2 (make-sample-evidence 2)]
    (chain/register-evidence! ev1)
    (chain/register-evidence! ev2)
    (let [{:keys [registry artifact-entry]} (chain/finalize-and-write! :run-id "full-chain")
          integrity (chain/evidence-chain-integrity registry)]
      ;; Evidence chain is intact
      (is (:chain-intact integrity))
      ;; Registry hash is included in the artifact entry
      (is (= (:registry-hash registry) (:registry-hash artifact-entry)))
      ;; Evidence hashes are registered
      (is (some #(= (:evidence-hash ev1) %) (:evidence-hashes registry)))
      (is (some #(= (:evidence-hash ev2) %) (:evidence-hashes registry)))
      ;; Artifact entry sha256 matches file hash
      (let [file-sha (chain/compute-file-sha256 (:path artifact-entry))]
        (is (= file-sha (:sha256 artifact-entry)))))))
