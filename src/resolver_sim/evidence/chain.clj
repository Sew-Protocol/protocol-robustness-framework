(ns resolver-sim.evidence.chain
  "Content-addressed evidence chain: binds evidence records into a self-hashed
   artifact registry compatible with test-artifacts.json format.

   Chain:
     transition-evidence -> evidence-hash
     evidence-registry   -> registry-hash  (commit to all evidence hashes)
     manifest            -> artifact-registry-sha (signs the registry hash)

   The registry atom accumulates evidence records during a run, then
   build-registry produces a self-hashed registry map that can be written
   to disk or passed to the existing signing infrastructure."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.hashing :as h]
            [resolver-sim.evidence.config :as evcfg]))

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
   Idempotent: duplicate evidence-hashes are silently ignored."
  [evidence]
  (let [eh (:evidence-hash evidence)]
    (when (and eh (string? eh))
      (swap! evidence-registry-atom
             (fn [reg]
               (if (some #(= eh (:evidence-hash %)) (:artifacts reg))
                 reg
                 (-> reg
                     (update :artifacts conj (evidence->artifact-entry evidence))
                     (update :evidence-hashes conj eh))))))
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
        reg-hash (h/hash-evidence base)]
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
        computed (h/hash-evidence base)]
    (if (= recorded computed)
      {:valid true}
      {:valid false :computed computed :recorded recorded})))

(defn verify-evidence-in-registry
  "Verify that a specific evidence-hash is recorded in the registry.
   Returns {:present true :entry <entry>} or {:present false}."
  [registry evidence-hash]
  (if-let [entry (some #(when (= evidence-hash (:evidence-hash %)) %)
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
     :all-with-component-hashes all-with-component-hashes
     :chain-intact (and reg-valid all-non-nil all-well-formed)}))
