(ns resolver-sim.io.fixtures
  "Fixture loading and fixture-reference resolution for scenarios.
   Owns the mapping from fixture keywords to file paths and the
   resolution of :protocol-params-ref into effective protocol-params.

   Extracted from sim/fixtures.clj to keep io-level code from
   depending on simulation-level namespaces.

   The fixture reference system provides:
     - A `resolve-protocol-params-ref` function that loads a fixture
       file by keyword and merges it with inline scenario params
     - SHA-256 content hashing for reproducibility
     - Validation helpers for fixture ref integrity"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.util.deep-merge :as dm])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Fixture key → file path mapping
;; ---------------------------------------------------------------------------

(defn fixture-key->path
  "Convert a fixture keyword to a relative file path.
   Examples:
     :protocol/kleros    → data/fixtures/protocol/kleros.edn
     :traces/s18-kleros   → data/fixtures/traces/s18-kleros.trace.json
   Bare keywords (no namespace) go to data/fixtures/<name>.edn."
  [k]
  (let [ns  (namespace k)
        nm  (name k)
        ext (if (= ns "traces") ".trace.json" ".edn")]
    (if ns
      (str "data/fixtures/" ns "/" nm ext)
      (str "data/fixtures/" nm ext))))

(def ^:private allowed-fixture-namespaces
  #{"protocol" "states" "actors" "authority" "tokens" "thresholds" "suites" "traces"})

(defn valid-fixture-ref?
  "True when k is a fixture reference keyword with a known namespace."
  [k]
  (boolean (and (keyword? k) (namespace k)
                (contains? allowed-fixture-namespaces (namespace k)))))

;; ---------------------------------------------------------------------------
;; Fixture file loading
;; ---------------------------------------------------------------------------

(defn load-fixture
  "Load a fixture by keyword. Tries classpath resource first, then filesystem.
   Supports EDN (.edn) and JSON (.json / .trace.json) formats.
   Returns the parsed Clojure data."
  [k]
  (let [path          (fixture-key->path k)
        resource-path (str "resource:" path)]
    (if (rp/path-exists? resource-path)
      (let [content (rp/slurp-path resource-path)]
        (if (.endsWith path ".json")
          (json/read-str content :key-fn keyword)
          (edn/read-string content)))
      (let [f (io/file path)]
        (if (.exists f)
          (with-open [r (io/reader f)]
            (if (.endsWith path ".json")
              (json/read r :key-fn keyword)
              (edn/read (java.io.PushbackReader. r))))
          (throw (ex-info "Fixture not found"
                          {:key k :path path :resource-path resource-path})))))))

;; ---------------------------------------------------------------------------
;; Fixture reference normalization
;; ---------------------------------------------------------------------------

(defn normalize-fixture-ref
  "Accept :protocol/kleros or \"protocol/kleros\", always return keyword.
   For JSON scenarios the ref comes as a string; for EDN it can be a keyword."
  [ref]
  (cond
    (keyword? ref) ref
    (string? ref)  (keyword ref)
    :else (throw (ex-info "Invalid fixture ref — must be keyword or string"
                          {:ref ref :type (type ref)}))))

;; ---------------------------------------------------------------------------
;; Content hashing
;; ---------------------------------------------------------------------------

(defn fixture-content-hash
  "SHA-256 hex digest of fixture file content.
   Used for evidence provenance — lets reviewers verify which fixture
   version was used even after the file changes.
   Returns nil if the file cannot be read."
  [path]
  (try
    (let [content (rp/slurp-path (str "resource:" path))
          digest  (MessageDigest/getInstance "SHA-256")]
      (.update digest (.getBytes content "UTF-8"))
      (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Fixture reference resolution
;; ---------------------------------------------------------------------------

(defn resolve-protocol-params-ref
  "Resolve :protocol-params-ref in a scenario map.

   If the scenario has a :protocol-params-ref key, loads the referenced
   fixture, deep-merges with any inline :protocol-params, and replaces
   the scenario's :protocol-params with the merged result.

   Merge semantics:
     - Fixture values provide defaults
     - Inline scenario params override fixture values (deliberate perturbation)
     - Nested maps are merged recursively

   The resolved scenario gains:
     - :protocol-params — the effective merged params
     - :fixture-refs — provenance data for evidence capture
   The :protocol-params-ref key is removed.

   Returns the updated scenario map unchanged if no ref is present."
  [scenario]
  (if-let [ref (:protocol-params-ref scenario)]
    (let [fixture-ref   (normalize-fixture-ref ref)
          _             (when-not (valid-fixture-ref? fixture-ref)
                          (throw (ex-info "Invalid fixture ref namespace"
                                          {:ref fixture-ref
                                           :allowed allowed-fixture-namespaces
                                           :scenario-id (:scenario-id scenario)})))
          fixture-path  (fixture-key->path fixture-ref)
          fixture       (load-fixture fixture-ref)
          inline        (:protocol-params scenario {})
          effective     (dm/deep-merge fixture inline)
          ref-entry     {:slot :protocol-params
                         :ref  fixture-ref
                         :path fixture-path
                         :content-hash (fixture-content-hash fixture-path)}]
      (-> scenario
          (assoc :protocol-params effective)
          (assoc :effective-protocol-params effective)
          (update :fixture-refs (fnil conj []) ref-entry)
          (dissoc :protocol-params-ref)))
    scenario))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-fixture-ref
  "Validate a resolved fixture reference map. Returns nil or throws."
  [ref-entry]
  (when (map? ref-entry)
    (let [{:keys [slot ref path content-hash]} ref-entry]
      (when-not slot
        (throw (ex-info "Fixture ref missing :slot" {:ref-entry ref-entry})))
      (when-not ref
        (throw (ex-info "Fixture ref missing :ref" {:ref-entry ref-entry})))
      (when (and content-hash (not= 64 (count content-hash)))
        (throw (ex-info "Fixture ref content-hash unexpected length"
                        {:ref-entry ref-entry :content-hash content-hash}))))))
