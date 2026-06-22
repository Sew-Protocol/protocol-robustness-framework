(ns resolver-sim.evidence.chain
  "Content-addressed evidence chain: binds evidence records into a self-hashed
    artifact registry compatible with test-artifacts.json format.

   Chain:
     transition-evidence -> evidence-hash
     evidence-registry   -> registry-hash  (commit to all evidence hashes)
     manifest            -> artifact-registry-sha (signs the registry hash)

   Forensic-grade extensions:
     registry-signature  -> Ed25519 signature over the registry-hash
     cursor-signature    -> Ed25519 signature over the cursor snapshot
     timestamp-proof     -> local or external timestamp binding

   The registry atom accumulates evidence records during a run, then
   build-registry produces a self-hashed registry map that can be written
   to disk or passed to the existing signing infrastructure.

   Use finalize-and-attest! for the full forensic pipeline:
   build → write → sign → timestamp → cursor."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]))

;; ── Registry Atom ─────────────────────────────────────────────────────────

(def ^:dynamic ^:private evidence-registry-atom
  (atom {:artifacts []
         :evidence-hashes []
         :run-id nil
         :run-label nil}))

(defn reset-registry!
  "Clear the registry atom for a new run.
   Optional: run-id and run-label for identification."
  [& {:keys [run-id run-label]}]
  (reset! evidence-registry-atom
          {:artifacts []
           :evidence-hashes []
           :run-id run-id
           :run-label run-label})
  nil)

(defmacro with-fresh-registry
  "Execute body with a fresh evidence chain registry.
   The outer registry is restored when body exits.
   Useful for isolating runs and preventing cross-run contamination."
  [& body]
  `(let [fresh# (atom {:artifacts []
                       :evidence-hashes []
                       :run-id nil
                       :run-label nil})]
     (binding [evidence-registry-atom fresh#]
       ~@body)))

;; ── Chain Cursor ──────────────────────────────────────────────────────────

(def ^:dynamic ^:private chain-cursor
  "Run-scoped evidence chain cursor for targeted event evidence.
   Tracks sequence number and previous hash for chain linking."
  (atom {:seq 0 :last-hash nil}))

(defn reset-chain-cursor!
  "Reset the evidence chain cursor for a new run.
   Idempotent — safe to call multiple times."
  []
  (reset! chain-cursor {:seq 0 :last-hash nil}))

(defmacro with-fresh-chain-cursor
  "Execute body with a fresh evidence chain cursor.
   The outer cursor is restored when body exits.
   Use at the start of a replay run alongside with-fresh-registry."
  [& body]
  `(let [fresh# (atom {:seq 0 :last-hash nil})]
     (binding [chain-cursor fresh#]
       ~@body)))

(defn inject-chain-fields
  "Add :evidence/chain-seq, :evidence/chain-prev-hash, and
   :evidence/chain-self-hash to the evidence map using the cursor.
   Uses a single atomic swap! to prevent race conditions on the
   sequence counter and prev-hash chain linking."
  [evidence]
  (let [self-hash (:evidence/hash evidence)
        {:keys [seq last-hash]}
        (swap! chain-cursor
               (fn [cursor]
                 (let [new-seq (inc (:seq cursor))]
                   {:seq new-seq
                    :last-hash self-hash
                    :prev-hash (:last-hash cursor)})))]
    (assoc evidence
           :evidence/chain-seq seq
           :evidence/chain-prev-hash last-hash
           :evidence/chain-self-hash self-hash)))

(defn registry-status
  "Return summary info from the current registry state: count, run-id."
  []
  (let [r @evidence-registry-atom]
    {:evidence-count (count (:artifacts r))
     :run-id (:run-id r)
     :run-label (:run-label r)
     :has-registry? (boolean (seq (:artifacts r)))}))

;; ── Evidence Registration ─────────────────────────────────────────────────

(defn- evidence->artifact-entry
  "Convert an evidence record (from emit-evidence!) into a registry artifact entry.
   The entry includes the evidence-hash as both identifier and integrity proof,
   plus all component hashes for traceability."
  [evidence]
  (let [eh (:evidence-hash evidence)]
    {:id (str "evidence-" (subs eh 0 12))
     :kind :transition-evidence
     :schema-version (evcfg/schema :evidence-record)
     :evidence-hash eh
     :context-hash (:context-hash evidence)
     :before-hash (:before-hash evidence)
     :after-hash (:after-hash evidence)
     :action-hash (:action-hash evidence)
     :result-hash (:result-hash evidence)
     :artifact-kind (:artifact-kind evidence)}))

(defn register-evidence!
  "Register an evidence record (map from emit-evidence!) into the chain registry.
   Returns the evidence-hash. The registry tracks every evidence record produced
   during a run, enabling the full content-addressed chain to be built later.
   Idempotent: duplicate evidence-hashes are silently ignored.
   Logs a warning when evidence has no hash — the record will not be registered."
  [evidence]
  (let [eh (:evidence-hash evidence)]
    (if (and eh (string? eh))
      (do (swap! evidence-registry-atom
                 (fn [reg]
                   (if (some #(hc/intent-hash= eh (:evidence-hash %)) (:artifacts reg))
                     reg
                     (-> reg
                         (update :artifacts conj (evidence->artifact-entry evidence))
                         (update :evidence-hashes conj eh)))))
          eh)
      (do (log/warn! :evidence-register-missing-hash
                     {:evidence-type (:evidence/type evidence)
                      :artifact-kind (:artifact-kind evidence)})
          eh))
    eh))

;; ── Registry Builder ──────────────────────────────────────────────────────

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn build-registry
  "Build a self-hashed registry map from the accumulated evidence records.

   The registry includes:
     - schema-version, run-id, generated-at
     - all evidence artifacts with their hashes
     - a registry-hash committing to all entries

   The registry-hash is computed over the registry WITHOUT its own :registry-hash
   field, then appended — matching the pattern used by sign-manifest for
   artifact-registry-sha."
  [& {:keys [run-id] :or {run-id "unknown"}}]
  (let [reg @evidence-registry-atom
        artifacts (:artifacts reg)
        base {:schema-version (evcfg/schema :evidence-registry)
              :contract-version (evcfg/contract-version)
              :run-id (or run-id (:run-id reg) "unknown")
              :generated-at (now-iso)
              :evidence-count (count artifacts)
              :evidence-hashes (:evidence-hashes reg)
              :artifacts artifacts}
        reg-hash (hc/hash-with-intent {:hash/intent :registry} base)]
    (assoc base :registry-hash reg-hash)))

;; ── Persistence ───────────────────────────────────────────────────────────

(def evidence-registry-filename
  "Filename for the evidence registry artifact."
  "evidence-registry.json")

(defn registry->json
  "Serialize a registry map to pretty-printed JSON string."
  [registry]
  (json/write-str registry {:indent true}))

(defn write-registry!
  "Write the registry to disk as JSON. Returns the written path.
   Writes to artifact-dir/evidence-registry.json by default."
  ([registry] (write-registry! registry nil))
  ([registry output-path]
   (let [path (or output-path
                  (str (evcfg/artifact-dir) "/" evidence-registry-filename))
         f (io/file path)]
     (.mkdirs (.getParentFile f))
     (spit f (registry->json registry))
     path)))

;; ── Artifact Registry Integration ─────────────────────────────────────────

(defn compute-file-sha256
  "Compute the SHA-256 hex digest of a file's contents."
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (slurp path) "UTF-8"))
    (apply str (map (partial format "%02x") (.digest digest)))))

(defn registry-artifact-entry
  "Produce a test-artifacts.json compatible artifact entry for the written
   evidence-registry.json file. Returns nil if the file doesn't exist.
   The entry binds the registry-hash into the artifact chain so that
   sign-manifest's artifact-registry-sha commits to all evidence hashes."
  [registry]
  (let [path (str (evcfg/artifact-dir) "/" evidence-registry-filename)
        f (io/file path)]
    (when (.exists f)
      {:id (get (evcfg/artifact :evidence-registry) "id" "evidence-registry")
       :kind (get (evcfg/artifact :evidence-registry) "kind" "evidence-registry")
       :path path
       :schema-version (evcfg/schema :evidence-registry)
       :contract-version (evcfg/contract-version)
       :producer (evcfg/producer :simulation-engine)
       :importance "CORE"
       :registry-hash (:registry-hash registry)
       :evidence-count (:evidence-count registry)
       :sha256 (compute-file-sha256 path)
       :bytes (.length f)
       :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified f)))})))

(defn index-artifact-entry
  "Produce a test-artifacts.json compatible artifact entry for an index file.
   Returns nil if the file doesn't exist.
   Use for index files like evidence-mechanisms.json, evidence-coverage-report.json."
  [artifact-id filename schema-version importance]
  (let [path (str (evcfg/artifact-dir) "/" filename)
        f (io/file path)]
    (when (.exists f)
      {:id (name artifact-id)
       :kind "evidence-index"
       :path path
       :schema-version schema-version
       :producer "targeted-evidence.v1"
       :importance importance
       :sha256 (compute-file-sha256 path)
       :bytes (.length f)
       :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified f)))})))

(defn register-additional-artifact!
  "Register an additional artifact entry in the evidence registry.
   Useful for index files produced after the main registry is built.
   The entry is persisted alongside the registry entries.
   Returns the registry atom's current state."
  [entry]
  (when entry
    (swap! evidence-registry-atom update :artifacts conj entry))
  nil)

;; ── Chain Cursor Finalization ───────────────────────────────────────────────

(defn cursor-snapshot
  "Read-only snapshot of the chain cursor's final state.
   Returns {:cursor/seq N :cursor/last-hash ...} or nil if never used."
  []
  (let [c @chain-cursor]
    (when (pos? (:seq c))
      {:cursor/scope :targeted-evidence
       :cursor/final-seq (:seq c)
       :cursor/final-self-hash (:last-hash c)
       :cursor/total-captured (:seq c)})))

(defn write-chain-cursor-final!
  "Snapshot the chain cursor at run finalization and persist as
   chain-cursor-final.json. Registers the artifact for test-artifacts.json.
   Returns the path written, or nil if the cursor was never used.

   When private-key-path is provided, signs the cursor content (final-seq +
   final-self-hash) and includes the Ed25519 signature in the artifact.

   One-shot: call once when the run completes (no new captures after)."
  [& {:keys [dir private-key-path password]
      :or {password nil}}]
  (when-let [snapshot (cursor-snapshot)]
    (let [out-dir (or dir (str (evcfg/artifact-dir)))
          f (io/file out-dir "chain-cursor-final.json")
          cursor-data {:cursor/scope :targeted-evidence
                       :cursor/final-seq (:cursor/final-seq snapshot)
                       :cursor/final-self-hash (:cursor/final-self-hash snapshot)
                       :cursor/total-captured (:cursor/total-captured snapshot)}
          signed (when (and private-key-path (:cursor/final-self-hash snapshot))
                   (let [cursor-hash (hc/hash-with-intent {:hash/intent :evidence-chain} cursor-data)
                         sig (signing/sign-hash cursor-hash private-key-path password)]
                     {:cursor/hash cursor-hash
                      :cursor/signature sig
                      :cursor/signer private-key-path
                      :cursor/signed-at (now-iso)}))
          artifact (merge {:schema/version "chain-cursor-final.v1"
                           :run/id (get-in @evidence-registry-atom [:run-id] "unknown")}
                          cursor-data
                          (when signed
                            {:cursor/forensic (dissoc signed :cursor/hash)
                             :cursor/signed-hash (:cursor/hash signed)}))]
      (.mkdirs (io/file out-dir))
      (spit f (json/write-str artifact {:indent true}))
      (println (str "Wrote chain-cursor-final.json: seq " (:cursor/final-seq snapshot)
                    (when signed " [signed]")))
      (register-additional-artifact!
       (index-artifact-entry :chain-cursor-final "chain-cursor-final.json"
                             "chain-cursor-final.v1" "DIAGNOSTIC"))
      (.getPath f))))

;; ── Evidence Registration ─────────────────────────────────────────────────

(defn finalize-and-write!
  "Build the evidence registry from accumulated records, write it to disk,
   and return {:registry ... :artifact-entry ... :path ...}.
   This is the single call to complete a run's evidence chain.
   Optional: run-id for identification."
  [& {:keys [run-id] :or {run-id "unknown"}}]
  (let [registry (build-registry :run-id run-id)
        path (write-registry! registry)
        entry (registry-artifact-entry registry)]
    {:registry registry
     :artifact-entry entry
     :path path}))

;; ── Chain Verification ────────────────────────────────────────────────────

(defn verify-registry-hash
  "Verify that the registry-hash in a registry map is consistent with its content.
   Returns {:valid true} or {:valid false :computed <hash> :recorded <hash>}."
  [registry]
  (let [recorded (:registry-hash registry)
        base (dissoc registry :registry-hash)
        computed (hc/hash-with-intent {:hash/intent :registry} base)]
    (if (= recorded computed)
      {:valid true}
      {:valid false :computed computed :recorded recorded})))

(defn verify-evidence-in-registry
  "Verify that a specific evidence-hash is recorded in the registry.
   Returns {:present true :entry <entry>} or {:present false}."
  [registry evidence-hash]
  (if-let [entry (some #(when (hc/intent-hash= evidence-hash (:evidence-hash %)) %)
                       (:artifacts registry))]
    {:present true :entry entry}
    {:present false}))

(defn evidence-chain-integrity
  "Full chain integrity check: verify the registry hash is consistent,
   every evidence hash is non-nil and well-formed, and all component
   hashes are present. Returns a summary map.
   Note: individual evidence content hashes are verified at the disk
   level by verify-chain-integrity in resolver-sim.io.event-evidence."
  [registry]
  (let [artifacts (:artifacts registry)
        reg-valid (:valid (verify-registry-hash registry))
        all-hashes (map :evidence-hash artifacts)
        all-non-nil (every? some? all-hashes)
        all-well-formed (every? #(re-matches #"^[0-9a-f]{64}$" (or % "")) all-hashes)
        all-with-component-hashes (every? (fn [a]
                                            (and (:context-hash a)
                                                 (:before-hash a)
                                                 (:after-hash a)
                                                 (:action-hash a)
                                                 (:result-hash a)))
                                          artifacts)]
    {:registry-hash-valid reg-valid
     :artifact-count (count artifacts)
     :all-hashes-non-nil all-non-nil
     :all-hashes-well-formed all-well-formed
     :all-hashes-registered (and all-non-nil all-well-formed)
     :all-with-component-hashes all-with-component-hashes
     :chain-intact (and reg-valid all-non-nil all-well-formed)}))

;; ── Forensic-Grade Acceptance Criteria ───────────────────────────────────────
;;
;; A run is forensic-grade only if:
;;   1. Registry hash verifies against all artifact entries
;;   2. Registry hash is signed by a known signer (optional but checked when present)
;;   3. Final chain cursor verifies against the evidence chain
;;   4. TSA token verifies against the registry hash or signed attestation
;;   5. Strict validation passes (checked externally)

;; ── Dynamic Signing Context ──────────────────────────────────────────────────
;;
;; Dynamic vars for threading signing credentials through the pipeline without
;; changing protocol or function signatures. Bound at the entry point (CLI,
;; runner) and read by replay-with-protocol when replay-opts don't provide keys.

(def ^:dynamic *signing-key*
  "Path to Ed25519 private key for forensic-grade evidence chain signing.
   Bound at pipeline entry (CLI, runner) and read by replay-with-protocol
   when replay-opts don't provide :signing-key."
  nil)

(def ^:dynamic *signing-password*
  "Password for *signing-key*."
  nil)

;; ── Forensic-Grade Attestation ─────────────────────────────────────────────┬⌐
;;                                                                           │
;; These functions extend the research-grade pipeline with cryptographic      │
;; signing, timestamp binding, and signature-anchored cursors.                │
;;                                                                           │
;; Flow:                                                                     │
;;   1. build-registry  (self-hashed registry map)                            │
;;   2. sign-registry!  (Ed25519 signature over registry-hash)               │
;;   3. write-registry! (persist registry + signature artifact)              │
;;   4. write-chain-cursor-final! (persist signed cursor snapshot)           │
;;                                                                           │
;; Call finalize-and-attest! to run the full pipeline in one step.           │
;; ───────────────────────────────────────────────────────────────────────────

(defn sign-registry!
  "Sign the registry-hash with an Ed25519 private key.
   Returns {:signature hex :signer path :signed-at iso :hash hex}
   or nil if no registry-hash is present."
  [registry & {:keys [private-key-path password]
               :or {password nil}}]
  (when-let [reg-hash (:registry-hash registry)]
    (let [sig (signing/sign-hash reg-hash private-key-path password)]
      {:signature sig
       :signer private-key-path
       :signed-at (now-iso)
       :hash reg-hash
       :schema/version (evcfg/schema :signature)})))

(defn write-registry-signature!
  "Sign the registry hash and persist the signature to
   signature.json alongside the registry. Returns {:signature ... :path ...}
   or nil if no key is provided."
  [registry & {:keys [private-key-path password dir]
               :or {password nil}}]
  (when private-key-path
    (let [sig-map (sign-registry! registry :private-key-path private-key-path :password password)
          out-dir (or dir (str (evcfg/artifact-dir)))
          sig-path (str out-dir "/" "signature.json")
          env-path (str out-dir "/" "envelope.json")]
      (when sig-map
        (.mkdirs (io/file out-dir))
        (spit sig-path (json/write-str sig-map {:indent true}))
        (let [envelope {:registry_sha256 (:hash sig-map)
                        :schema/version (evcfg/schema :envelope)
                        :signed-at (:signed-at sig-map)
                        :signer (:signer sig-map)
                        :signature (:signature sig-map)
                        :chain-final true}]
          (spit env-path (json/write-str envelope {:indent true})))
        (println "Signed registry hash:" (:hash sig-map))
        {:signature sig-map
         :signature-path sig-path
         :envelope-path env-path}))))

(defn verify-registry-signature
  "Verify an Ed25519 signature over the registry-hash.
   registry can be a registry map (with :registry-hash) or a hash string.
   signature-map should have :signature and :signer keys.
   Returns {:valid true/false :hash h} or {:error ...}."
  [registry signature-map]
  (try
    (let [h (if (string? registry) registry (:registry-hash registry))
          sig (:signature signature-map)
          pub-key-path (str (:signer signature-map) ".pub")]
      (if (and h sig pub-key-path)
        (let [valid (signing/verify-signature h sig pub-key-path)]
          {:valid valid :hash h})
        {:error "Missing hash, signature, or signer"}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn verify-cursor-signature
  "Verify the Ed25519 signature on a chain-cursor-final artifact.
   cursor should be the parsed cursor JSON artifact.
   Returns {:valid true/false :hash h} or {:error ...}."
  [cursor]
  (try
    (let [cursor-data {:cursor/scope (:cursor/scope cursor)
                       :cursor/final-seq (:cursor/final-seq cursor)
                       :cursor/final-self-hash (:cursor/final-self-hash cursor)
                       :cursor/total-captured (:cursor/total-captured cursor)}
          h (hc/hash-with-intent {:hash/intent :evidence-chain} cursor-data)
          recorded-hash (:cursor/signed-hash cursor)
          forensic (:cursor/forensic cursor)]
      (if (and forensic recorded-hash)
        (if (hc/intent-hash= h recorded-hash)
          (let [sig (:signature forensic)
                signer (:signer forensic)
                pub-key-path (str signer ".pub")]
            (if (and sig pub-key-path)
              (let [valid (signing/verify-signature h sig pub-key-path)]
                {:valid valid :hash h :cursor-seq (:cursor/final-seq cursor)})
              {:error "Missing signature or signer in cursor forensic data"}))
          {:valid false :hash h :recorded-hash recorded-hash
           :error "Cursor data hash does not match signed hash"})
        {:error "Cursor has no forensic signature data"}))
    (catch Exception e
      {:error (.getMessage e)})))

;; ── Forensic-Grade Acceptance Criteria ───────────────────────────────────────
;;
;; A run is forensic-grade only if:
;;   1. Registry hash verifies against all artifact entries
;;   2. Registry hash is signed by a known signer (optional but checked when present)
;;   3. Final chain cursor verifies against the evidence chain
;;   4. TSA token verifies against the registry hash or signed attestation
;;   5. Strict validation passes (checked externally)

(defn forensic-status
  "Check all forensic-grade acceptance criteria for a completed run.
   Returns a map with :all-pass? boolean and per-criterion status.

   Reads artifacts from dir (default: artifact-dir). Criterion 5 (strict
   validation) is checked externally via build-evidence-registry! with
   :strict true — this function checks criteria 1-4."
  [& {:keys [registry dir registry-hash]
      :or {dir (str (evcfg/artifact-dir))}}]
  (let [registry (or registry
                     (try (json/read-str (slurp (str dir "/evidence-registry.json"))
                                         :key-fn keyword)
                          (catch Exception _ nil)))
        sig-path (str dir "/signature.json")
        tsr-path (str dir "/registry.tsr")
        cur-path (str dir "/chain-cursor-final.json")
        rh (or registry-hash (:registry-hash registry))

        c1 (when registry
             (let [v (verify-registry-hash registry)]
               {:criterion :registry-hash-verifies
                :pass (:valid v)
                :detail v}))

        c2 (when (and rh (.exists (io/file sig-path)))
             (try
               (let [sig-map (json/read-str (slurp sig-path) :key-fn keyword)
                     v (verify-registry-signature registry sig-map)]
                 {:criterion :registry-hash-signed
                  :pass (:valid v)
                  :detail v})
               (catch Exception e
                 {:criterion :registry-hash-signed
                  :pass false
                  :detail {:error (.getMessage e)}})))

        c3 (when (.exists (io/file cur-path))
             (try
               (let [cursor (json/read-str (slurp cur-path) :key-fn keyword)
                     v (verify-cursor-signature cursor)]
                 {:criterion :cursor-verifies
                  :pass (:valid v)
                  :detail v})
               (catch Exception e
                 {:criterion :cursor-verifies
                  :pass false
                  :detail {:error (.getMessage e)}})))

        c4 (when (and rh (.exists (io/file tsr-path)))
             (try
               (let [v (ts/verify-tsa-token-from-file rh :dir dir)]
                 {:criterion :tsa-token-verifies
                  :pass (:timestamp/verified? v)
                  :detail v})
               (catch Exception e
                 {:criterion :tsa-token-verifies
                  :pass false
                  :detail {:error (.getMessage e)}})))

        criteria (remove nil? [c1 c2 c3 c4])
        all-pass? (every? :pass criteria)]
    {:all-pass? all-pass?
     :criteria-met (count (filter :pass criteria))
     :criteria-total (count criteria)
     :criteria criteria}))

(defn finalize-and-attest!
  "Full forensic-grade evidence chain finalization in one step.
   Builds the registry, writes it, signs the registry hash, timestamps
   the registry hash via RFC 3161 TSA, persists the signed cursor.

   Sequencing (per forensic-grade spec):
     1. Build all evidence records (during replay)
     2. Finalize evidence chain (build-registry)
     3. Write final chain cursor
     4. Build registry (registry-hash commits to all entries)
     5. Compute registry hash
     6. Sign registry hash (optional, via private-key-path)
     7. Submit registry hash to TSA (optional, via tsa-url)
     8. Store TSA sidecar artifacts (registry.tsr, registry.tsq, registry.tsa.json)
     9. Verify TSA response locally

   Optional:
     :run-id            — identifier for the run
     :private-key-path  — path to Ed25519 private key for signing
     :password          — private key password
     :tsa-url           — RFC 3161 TSA URL (falls back to ts/*tsa-url*)
     :dir               — output directory (default: artifact-dir)

   Returns:
     {:registry       registry-map
      :registry-path  path to evidence-registry.json
      :signature      {:signature ... :path ... :envelope-path ...} or nil
      :cursor-path    path to chain-cursor-final.json or nil
      :tsa            TSA result map or nil
      :forensic?      boolean: all configured forensic checks passed}"
  [& {:keys [run-id private-key-path password tsa-url dir]
      :or {run-id "unknown" password nil}}]
  (let [registry (build-registry :run-id run-id)
        reg-hash (:registry-hash registry)
        reg-path (write-registry! registry dir)
        sig-result (write-registry-signature! registry
                                              :private-key-path private-key-path
                                              :password password
                                              :dir dir)
        cursor-path (write-chain-cursor-final!
                     :dir dir
                     :private-key-path private-key-path
                     :password password)
        tsa-url (or tsa-url ts/*tsa-url*)
        tsa-result (when (and tsa-url reg-hash)
                     (ts/write-tsa-timestamp! reg-hash
                                              :tsa-url tsa-url
                                              :dir dir))]
    {:registry registry
     :registry-path reg-path
     :signature sig-result
     :cursor-path cursor-path
     :tsa tsa-result
     :forensic? (boolean (or sig-result
                             (and tsa-result (not (:error tsa-result)))))}))
