(ns resolver-sim.hash.canonical
  "Canonical hash implementation per CANONICAL_HASH_SPEC_V1
   and CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.

   This is the single authoritative hashing implementation for all
   evidence, world-state, manifest, and bundle hashing. All new code
   MUST use this namespace. The old resolver-sim.benchmark.hashing
   is deprecated.

   API:
     (validate-canonical-value! value)     — throws on unsupported types
     (canonical-bytes value)               — byte-array of typed encoding
     (hash-bytes bytes)                    — raw SHA-256 digest (32 bytes)
     (domain-hash domain-tag value)        — SHA-256(domain_tag || canonical_bytes), returns hex"
  (:import [java.security MessageDigest]
           [java.io ByteArrayOutputStream]
           [java.math BigInteger]))

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Tags (per Binary Encoding ABI)
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:const tag-null       (byte 0x00))
(def ^:const tag-bool-false (byte 0x01))
(def ^:const tag-bool-true  (byte 0x02))
(def ^:const tag-int        (byte 0x10))
(def ^:const tag-string     (byte 0x20))
(def ^:const tag-keyword    (byte 0x22))
(def ^:const tag-array      (byte 0x30))
(def ^:const tag-map        (byte 0x31))

;; ──────────────────────────────────────────────────────────────────────────────
;; Domain Tags (per Canonical Hash Spec V1)
;; ──────────────────────────────────────────────────────────────────────────────

(def domain-tags
  "Map of keyword domain identifiers to their ASCII domain tag strings.
   The domain tag is prepended to canonical bytes before hashing
   to prevent cross-domain hash collisions.

   NOTE: Maintained for backward compatibility with callers that
   pass keywords to domain-hash. Intent contracts now use strings
   directly via :intent/domain-tag."
  {:world-state     "WORLD_STATE_V1"
   :evidence-record "EVIDENCE_RECORD_V1"
   :evidence-chain  "EVIDENCE_CHAIN_V1"
   :merkle-leaf     "EVIDENCE_MERKLE_LEAF_V1"
   :merkle-node     "EVIDENCE_MERKLE_NODE_V1"
   :registry        "REGISTRY_V1"
   :manifest        "MANIFEST_V1"
   :provenance      "PROVENANCE_V1"
   :bundle-root     "BUNDLE_ROOT_V1"
   :evidence-content "EVIDENCE_CONTENT_V1"
   :state-diff       "STATE_DIFF_V1"
   :params-manifest  "PARAMS_MANIFEST_V1"
   :evm-projection   "EVM_PROJECTION_V1"})

;; ──────────────────────────────────────────────────────────────────────────────
;; varuint Encoding (LEB128, little-endian base-128)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- encode-varuint
  "Encode a non-negative integer as LEB128 varuint.
   Minimal representation: no leading zeros."
  [n]
  (let [bos (ByteArrayOutputStream.)
        n (biginteger n)]
    (loop [n n]
      (let [b (.byteValue (.and n (BigInteger/valueOf 0x7F)))
            n' (.shiftRight n 7)]
        (if (.equals n' BigInteger/ZERO)
          (do (.write bos (int b))
              (.toByteArray bos))
          (do (.write bos (int (bit-or (int b) 0x80)))
              (recur n')))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; ZigZag Encoding (signed → unsigned for varuint)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- zigzag
  "ZigZag encode a signed integer of arbitrary precision to an unsigned integer.
   n >= 0 → 2n
   n <  0 → -2n - 1"
  [n]
  (if (neg? n)
    (-' (*' -2 n) 1)
    (*' 2 n)))

;; ──────────────────────────────────────────────────────────────────────────────
;; UTF-8 Encoding
;; ──────────────────────────────────────────────────────────────────────────────

(defn- utf8-bytes
  "Encode a string as UTF-8 byte array."
  [s]
  (.getBytes s "UTF-8"))

;; ──────────────────────────────────────────────────────────────────────────────
;; Keyword Name
;; ──────────────────────────────────────────────────────────────────────────────

(defn- keyword-string
  "Return the portable string representation of a keyword.
   :resolver/id → \"resolver/id\"
   :active      → \"active\""
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Integer coercion
;; ──────────────────────────────────────────────────────────────────────────────

(defn- coerce-integer
  "Coerce any Clojure/Java integer type to BigInteger for uniform handling."
  [v]
  (cond
    (instance? BigInteger v) v
    (instance? Long v) (BigInteger/valueOf (long v))
    (instance? Integer v) (BigInteger/valueOf (int v))
    (instance? clojure.lang.BigInt v) (.toBigInteger v)
    (instance? Short v) (BigInteger/valueOf (short v))
    (instance? Byte v) (BigInteger/valueOf (byte v))
    :else (BigInteger/valueOf (long v))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Byte array helpers
;; ──────────────────────────────────────────────────────────────────────────────

(defn- ba-concat
  "Concatenate multiple byte-arrays into one."
  [& bas]
  (let [total (reduce + (map count bas))
        out (byte-array total)]
    (loop [idx 0, bas bas]
      (when (seq bas)
        (let [ba (first bas)]
          (System/arraycopy ba 0 out idx (count ba))
          (recur (+ idx (count ba)) (rest bas)))))
    out))

(defn- ba-of
  "Create a byte-array from individual byte arguments."
  [& bs]
  (byte-array bs))

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Validation
;; ──────────────────────────────────────────────────────────────────────────────

(defn- canonical-type?
  "Return true if v is a supported canonical type."
  [v]
  (or (nil? v)
      (instance? Boolean v)
      (instance? Long v)
      (instance? Integer v)
      (instance? Short v)
      (instance? Byte v)
      (instance? clojure.lang.BigInt v)
      (instance? BigInteger v)
      (instance? String v)
      (instance? clojure.lang.Keyword v)
      (instance? clojure.lang.IPersistentVector v)
      (instance? clojure.lang.IPersistentMap v)))

(defn- map-key-type?
  "Return true if k is a permitted map key type."
  [k]
  (or (instance? String k)
      (instance? clojure.lang.Keyword k)))

(defn validate-canonical-value!
  "Walk a value tree and validate that all values are supported
   canonical types. Throws ex-info on the first unsupported type.
   Map keys must be String or Keyword."
  [v]
  (let [walker (fn walk [x]
                 (cond
                   (nil? x) x
                   (instance? Boolean x) x
                   (instance? Long x) x
                   (instance? Integer x) x
                   (instance? Short x) x
                   (instance? Byte x) x
                   (instance? clojure.lang.BigInt x) x
                   (instance? BigInteger x) x
                   (instance? String x) x
                   (instance? clojure.lang.Keyword x) x
                   (instance? clojure.lang.IPersistentVector x)
                   (do (run! walk x) x)
                   (instance? clojure.lang.IPersistentMap x)
                   (do (doseq [[k v] x]
                         (when-not (map-key-type? k)
                           (throw (ex-info "Map key must be String or Keyword"
                                           {:key k :type (type k)})))
                         (walk v))
                       x)
                   :else
                   (throw (ex-info "Unsupported type for canonical hashing"
                                   {:type (type x) :value x :class (.getName (class x))}))))]
    (walker v)
    nil))

;; ──────────────────────────────────────────────────────────────────────────────
;; Canonical Bytes
;; ──────────────────────────────────────────────────────────────────────────────

(defn canonical-bytes
  "Produce the canonical typed binary encoding of a value.
   Returns a byte-array per CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI."
  [v]
  (cond
    (nil? v)
    (ba-of tag-null)

    (instance? Boolean v)
    (ba-of (if v tag-bool-true tag-bool-false))

    (number? v)
    (let [bi (coerce-integer v)
          zv (zigzag bi)
          vu (encode-varuint zv)]
      (ba-concat (ba-of tag-int) vu))

    (instance? String v)
    (let [bs (utf8-bytes v)
          len (encode-varuint (count bs))]
      (ba-concat (ba-of tag-string) len bs))

    (instance? clojure.lang.Keyword v)
    (let [s (keyword-string v)
          bs (utf8-bytes s)
          len (encode-varuint (count bs))]
      (ba-concat (ba-of tag-keyword) len bs))

    (instance? clojure.lang.IPersistentVector v)
    (let [count-enc (encode-varuint (count v))
          elements (map canonical-bytes v)]
      (apply ba-concat (ba-of tag-array) count-enc elements))

    (instance? clojure.lang.IPersistentMap v)
    (let [pairs (map (fn [[k v]]
                       {:key-bytes (canonical-bytes k)
                        :val-bytes (canonical-bytes v)})
                     v)
          sorted (sort-by :key-bytes (fn [^bytes a ^bytes b]
                                       (let [alen (count a)
                                             blen (count b)
                                             minlen (min alen blen)]
                                         (loop [i 0]
                                           (if (= i minlen)
                                             (< alen blen)
                                             (let [ai (bit-and (int (aget a i)) 0xFF)
                                                   bi (bit-and (int (aget b i)) 0xFF)]
                                               (if (= ai bi)
                                                 (recur (inc i))
                                                 (< ai bi)))))))
                          pairs)
          count-enc (encode-varuint (count v))
          elements (mapcat (fn [p] [(:key-bytes p) (:val-bytes p)]) sorted)]
      (apply ba-concat (ba-of tag-map) count-enc elements))

    :else
    (throw (ex-info "Cannot encode unsupported type"
                    {:type (type v) :value v}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; World State Projection
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Semantic projection that selects the identity-relevant structure from a
;; simulation world state, transforming runtime and non-canonical types into
;; canonical-safe representations. Every transformation is explicit and
;; documented — no fields are silently dropped.
;;
;; Transformation rules:
;;   java.time.Instant   → ISO-8601 string (.toString)
;;   Double, Float       → deterministic scientific notation (%.17g)
;;   PersistentHashSet   → sorted vector (deterministic ordering)
;;   Function / IFn      → :fn keyword (implementation detail marker)
;;   List, Seq           → vector (for canonical encoding compliance)
;;   Canonical types     → passed through unchanged
;;
;; The result is a pure canonical value tree suitable for validation via
;; validate-canonical-value! and encoding via canonical-bytes.

(defn project-world-to-structure-view
  "Project a simulation world state into a deterministic, canonical-safe
   structure view for content-addressed hashing.

   This is a *semantic projection*: it selects the identity-relevant
   structure from the world state, transforming runtime or non-canonical
   types into their canonical representations. It is NOT a data-cleaning
   step — it is an intentional identity lens.

   The projection is idempotent and fully deterministic across JVM
   invocations. The result passes validate-canonical-value! and is safe
   to pass directly to canonical-bytes or domain-hash.

   Identity-critical world state substructures like position maps,
   risk configurations, token balances, resolver states, and accounting
   data pass through unchanged. Yield module implementations (:ops
   functions) are projected to :fn markers — they are simulation
   infrastructure, not domain state."
  [world]
  (letfn [(walk [x]
            (cond
              ;; Canonical types — pass through unchanged
              (nil? x) nil
              (instance? Boolean x) x
              (instance? Long x) x
              (instance? Integer x) x
              (instance? Short x) x
              (instance? Byte x) x
              (instance? clojure.lang.BigInt x) x
              (instance? java.math.BigInteger x) x
              (instance? String x) x
              (instance? clojure.lang.Keyword x) x
              (instance? clojure.lang.IPersistentVector x)
              (mapv walk x)
              ;; Map — recurse keys and values; ordering is handled at encode time
              (instance? clojure.lang.IPersistentMap x)
              (persistent!
               (reduce-kv (fn [m k v] (assoc! m (walk k) (walk v)))
                          (transient {}) x))
              ;; Set → sorted vector
              (instance? clojure.lang.IPersistentSet x)
              (vec (sort (map walk x)))
              ;; java.time.Instant → ISO-8601 string
              (instance? java.time.Instant x)
              (.toString x)
              ;; Double, Float → deterministic scientific notation
              (instance? Double x)
              (format "%.17g" (double x))
              (instance? Float x)
              (format "%.17g" (float x))
              ;; Ratio — convert to double string (deterministic, portable)
              (instance? clojure.lang.Ratio x)
              (format "%.17g" (double x))
              ;; Function / IFn — simulation infrastructure marker
              (fn? x)
              :fn
              ;; List, LazySeq, etc. — must be vectors for canonical encoding
              (sequential? x)
              (mapv walk x)
              :else
              (throw (ex-info
                      "Cannot project unsupported type to structure view"
                      {:type (type x) :value x}))))]
    (walk world)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Hashing
;; ──────────────────────────────────────────────────────────────────────────────

(defn hash-bytes
  "Compute raw SHA-256 digest of a byte array.
   Returns a 32-byte byte-array for use in Merkle construction
   and domain-separated hashing."
  [^bytes ba]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest ba)
    (.digest digest)))

(defn- bytes->hex
  "Convert a byte array to a lowercase hex string."
  [^bytes ba]
  (let [sb (StringBuilder.)]
    (doseq [b ba]
      (.append sb (format "%02x" (bit-and (int b) 0xFF))))
    (.toString sb)))

(defn domain-hash
  "Compute a domain-separated canonical hash.
   HASH = SHA256(DOMAIN_TAG || CANONICAL_BYTES)
   where DOMAIN_TAG is the UTF-8 encoding of the domain tag string.
   Returns a 64-char hex string.
   domain-tag should be a keyword from domain-tags, or a string."
  ([v]
   (domain-hash :evidence-record v))
  ([domain-tag v]
   (let [tag-str (if (instance? String domain-tag)
                   domain-tag
                   (or (domain-tags domain-tag)
                       (throw (ex-info "Unknown domain tag"
                                       {:domain-tag domain-tag
                                        :known (keys domain-tags)}))))
         tag-bytes (utf8-bytes tag-str)
         canon (canonical-bytes v)]
     (bytes->hex (hash-bytes (ba-concat tag-bytes canon))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Evidence Content Projection (for JSON-round-trippable hashes)
;; ──────────────────────────────────────────────────────────────────────────────

(defn- project-for-content-hash
  "Project an evidence record into a form suitable for content-addressed
   hashing that survives JSON serialization/deserialization.
   Keywords are converted to strings (matching JSON behavior) and
   maps are sorted for deterministic ordering."
  [data]
  (letfn [(walk [v]
            (cond
              (instance? clojure.lang.Keyword v) (name v)
              (instance? clojure.lang.IPersistentMap v)
              (into (sorted-map) (map (fn [[k v]] [(walk k) (walk v)]) v))
              (instance? clojure.lang.IPersistentVector v)
              (mapv walk v)
              (sequential? v)
              (mapv walk v)
              :else v))]
    (walk data)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Registry Contract
;; ──────────────────────────────────────────────────────────────────────────────
;;
;; Each intent is a machine-readable contract that explicitly declares:
;;   :intent/description — what kind of data this hash represents
;;   :intent/includes    — what data categories are intentionally covered
;;   :intent/excludes    — what data categories are explicitly excluded
;;   :intent/projection-fn — projection function applied before hashing
;;   :intent/domain-tag  — domain tag string for domain-separated hashing
;;   :intent/version     — monotonic integer; projection changes require increment
;;
;; This eliminates semantic drift between intents, provides explicit
;; machine-readable boundaries, and enables future linting support
;; (e.g., check that data being hashed matches declared scope).

(def hash-intents
  "Map of hash intent keywords to their Intent Registry Contracts.
   Each contract explicitly declares the intent name, description,
   includes, exclusions, projection function, domain tag, and version.

   Usage: (hash-with-intent {:hash/intent :world-structure} data)

   Per INTENT_REGISTRY_SPEC_V1, each field is required."
  {:world-structure
   {:intent/name        :world-structure
    :intent/domain-tag  "WORLD_STATE_V1"
    :intent/description "Structural identity of system state for evidence anchoring"
    :intent/includes    #{:domain-state :positions :balances :config
                          :oracle-state :resolver-registry :bond-state
                          :dispute-state :escrow-state :time-context}
    :intent/excludes    #{:module-implementations :runtime-values
                          :functions :sets :ratios :instants :doubles}
    :intent/projection-fn project-world-to-structure-view
    :intent/version     1}

   :evidence-record
   {:intent/name        :evidence-record
    :intent/domain-tag  "EVIDENCE_RECORD_V1"
    :intent/description "Content identity of an individual evidence record"
    :intent/includes    #{:attribution :action :result :context
                          :artifact-kind :temporal-context :sub-hashes}
    :intent/excludes    #{:evidence-hash :timestamp :chain-metadata}
    :intent/projection-fn identity
    :intent/version     1}

   :evidence-content
   {:intent/name        :evidence-content
    :intent/domain-tag  "EVIDENCE_CONTENT_V1"
    :intent/description "JSON-round-trippable content hash of an evidence record"
    :intent/includes    #{:serialized-content :evidence-fields :artifact-body}
    :intent/excludes    #{:keywords :hash-fields :chain-metadata :timestamps}
    :intent/projection-fn project-for-content-hash
    :intent/version     1}

   :evidence-chain
   {:intent/name        :evidence-chain
    :intent/domain-tag  "EVIDENCE_CHAIN_V1"
    :intent/description "Evidence chain linking structure for audit trails"
    :intent/includes    #{:chain-links :registry-structure :prev-hash
                          :chain-seq :self-hash}
    :intent/excludes    #{:artifact-content :evidence-payload :timestamps}
    :intent/projection-fn identity
    :intent/version     1}

   :manifest
   {:intent/name        :manifest
    :intent/domain-tag  "MANIFEST_V1"
    :intent/description "Bundle manifest identity for artifact packaging"
    :intent/includes    #{:manifest-metadata :bundle-structure :schema-version}
    :intent/excludes    #{:content-payloads :individual-artifacts}
    :intent/projection-fn identity
    :intent/version     1}

   :bundle-root
   {:intent/name        :bundle-root
    :intent/domain-tag  "BUNDLE_ROOT_V1"
    :intent/description "Top-level benchmark commitment root"
    :intent/includes    #{:benchmark-metadata :root-commitment :bundle-summary}
    :intent/excludes    #{:individual-results :detailed-evidence :traces}
    :intent/projection-fn identity
    :intent/version     1}

   :registry
   {:intent/name        :registry
    :intent/domain-tag  "REGISTRY_V1"
    :intent/description "Evidence registry commitment for artifact catalog"
    :intent/includes    #{:registry-index :artifact-catalog :commitment-root}
    :intent/excludes    #{:artifact-content :detailed-evidence :world-state}
    :intent/projection-fn identity
    :intent/version     1}

   :provenance
   {:intent/name        :provenance
    :intent/domain-tag  "PROVENANCE_V1"
    :intent/description "Provenance lineage and verification metadata"
    :intent/includes    #{:provenance-lineage :verification-metadata :links}
    :intent/excludes    #{:raw-evidence-content :world-snapshots}
    :intent/projection-fn identity
    :intent/version     1}

   :evm-projection
   {:intent/name        :evm-projection
    :intent/domain-tag  "EVM_PROJECTION_V1"
    :intent/description "EVM-compatible world subset for cross-system comparison"
    :intent/includes    #{:comparable-world-subset :computed-invariants}
    :intent/excludes    #{:sim-only-fields :module-implementations}
    :intent/projection-fn project-world-to-structure-view
    :intent/version     1}

   :state-diff
   {:intent/name        :state-diff
    :intent/domain-tag  "STATE_DIFF_V1"
    :intent/description "Structural diff state hash for trace comparisons"
    :intent/includes    #{:diff-changes :path-stripped-values}
    :intent/excludes    #{:before-values :after-values :raw-world-state}
    :intent/projection-fn identity
    :intent/version     1}

   :params-manifest
   {:intent/name        :params-manifest
    :intent/domain-tag  "PARAMS_MANIFEST_V1"
    :intent/description "Parameter manifest for multi-epoch reproducibility"
    :intent/includes    #{:sim-params :config-params :run-params}
    :intent/excludes    #{:runtime-state :evidence-data}
    :intent/projection-fn identity
    :intent/version     1}

   :invariant-attestation
   {:intent/name        :invariant-attestation
    :intent/domain-tag  "INVARIANT_ATTESTATION_V1"
    :intent/description "Per-step invariant attestation: which invariants held, which failed"
    :intent/includes    #{:step :invariants :passed :failed :invariant-set-hash}
    :intent/excludes    #{:full-world-state :action-detail :raw-trace}
    :intent/projection-fn identity
    :intent/version     1}

   :projection-evidence
   {:intent/name        :projection-evidence
    :intent/domain-tag  "PROJECTION_EVIDENCE_V1"
    :intent/description "Projection hash paired with world hash for cross-system comparison"
    :intent/includes    #{:step :world-hash :projection-hash :projection-version}
    :intent/excludes    #{:full-world-state :internal-fields}
    :intent/projection-fn identity
    :intent/version     1}

   :checkpoint-evidence
   {:intent/name        :checkpoint-evidence
    :intent/domain-tag  "CHECKPOINT_EVIDENCE_V1"
    :intent/description "Attestable checkpoint with world hash and chain position"
    :intent/includes    #{:checkpoint-id :event-seq :world-hash :chain-head}
    :intent/excludes    #{:full-world-state :trace-detail}
    :intent/projection-fn identity
    :intent/version     1}

   :benchmark-certification
   {:intent/name        :benchmark-certification
    :intent/domain-tag  "BENCHMARK_CERTIFICATION_V1"
    :intent/description "Benchmark run certification with invariant summary"
    :intent/includes    #{:benchmark-id :scenario-count :all-invariants-pass
                          :final-state-hash :evidence-chain-root :invariant-summary}
    :intent/excludes    #{:individual-results :detailed-evidence :traces}
    :intent/projection-fn identity
    :intent/version     1}})

(defn resolve-intent
  "Look up an intent contract by keyword name from the registry.
   Returns the full intent contract map or throws on unknown intent.
   Used internally by hash-with-intent and available for external
   inspection and linting."
  [intent-kw]
  (or (hash-intents intent-kw)
      (throw (ex-info "Unknown hash intent"
                      {:intent intent-kw
                       :known  (vec (keys hash-intents))}))))

(defn validate-registry!
  "Validate the intent registry against INTENT_REGISTRY_SPEC_V1.
   Checks that every contract has all required fields with correct types.
   Returns nil if valid, throws on first violation.
   Call at startup or in test fixtures to ensure registry integrity."
  []
  (doseq [[kw contract] hash-intents]
    (let [expected-fields [:intent/name :intent/domain-tag :intent/description
                           :intent/includes :intent/excludes
                           :intent/projection-fn :intent/version]
          field-types {:intent/name         keyword?
                       :intent/domain-tag   string?
                       :intent/description  string?
                       :intent/includes     set?
                       :intent/excludes     set?
                       :intent/projection-fn fn?
                       :intent/version      (every-pred integer? pos?)}]
      (doseq [f expected-fields]
        (when-not (contains? contract f)
          (throw (ex-info (str "Intent " kw " missing required field " f)
                          {:intent kw :missing f}))))
      (doseq [[f pred] field-types]
        (when-not (pred (get contract f))
          (throw (ex-info (str "Intent " kw " field " f " has wrong type")
                          {:intent kw :field f :value (get contract f)})))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Constraint Enforcement
;; ──────────────────────────────────────────────────────────────────────────────
;; Self-validating identity graph: each intent contract enforces its
;; exclusion rules at test/development time, preventing:
;;   - Accidental misuse (hashing excluded data with wrong intent)
;;   - Silent semantic drift (intent contract drifts from actual usage)
;;   - Cross-intent hash comparison (comparing hashes from different intents)

(def ^:dynamic *validate-intent-constraints*
  "When truthy, hash-with-intent validates data against the intent's
   :intent/excludes before hashing. Enable in tests or development.
   Default false for production performance.

   Usage:
     (binding [hc/*validate-intent-constraints* true]
       (hc/hash-with-intent {:hash/intent :evidence-record} data))"
  false)

(defn- walk-for-excludes
  "Walk a value tree and return all nodes that match any of the given
   type-based exclude predicates. Each predicate takes a value and
   returns a violation string or nil."
  [predicates value]
  (let [results (volatile! [])
        preds (vec predicates)]
    (letfn [(walk [x]
              (doseq [[i pred] (map-indexed vector preds)
                      :let [r (pred x)]
                      :when r]
                (vswap! results conj {:predicate-index i :detail r}))
              (cond
                (instance? clojure.lang.IPersistentMap x)
                (run! (fn [[k v]] (walk k) (walk v)) x)
                (instance? clojure.lang.IPersistentVector x)
                (run! walk x)
                (instance? clojure.lang.IPersistentSet x)
                (run! walk x)
                (sequential? x)
                (run! walk x)))]
      (walk value)
      @results)))

(def ^:private exclude-type-checkers
  "Map from exclude category keywords to type-checker predicates.
   Each predicate returns a violation string or nil.
   Applied to every node in the data tree."
  {:functions (fn [v] (when (fn? v) "function value"))
   :sets      (fn [v] (when (instance? clojure.lang.IPersistentSet v)
                        "set (unsupported)"))
   :ratios    (fn [v] (when (instance? clojure.lang.Ratio v)
                        "ratio (unsupported)"))
   :instants  (fn [v] (when (instance? java.time.Instant v)
                        "java.time.Instant"))
   :doubles   (fn [v] (when (or (instance? Double v)
                                (instance? Float v))
                        "double or float (unsupported)"))
   :keywords  (fn [v] (when (instance? clojure.lang.Keyword v)
                        "keyword value (projected away by :evidence-content)"))})

(def ^:private exclude-root-checkers
  "Map from exclude category keywords to root-level checkers.
   Each predicate takes the ROOT value and returns a violation
   string or nil. These check structural properties."
  {:evidence-hash  (fn [v] (when (and (map? v) (contains? v :evidence/hash))
                             "root map contains :evidence/hash"))
   :timestamp      (fn [v] (when (and (map? v) (contains? v :evidence/timestamp))
                             "root map contains :evidence/timestamp"))
   :timestamps     (fn [v] (when (and (map? v) (contains? v :evidence/timestamp))
                             "root map contains :evidence/timestamp"))
   :hash-fields    (fn [v]
                     (when (and (map? v)
                                (some #(.endsWith (name %) "hash") (keys v)))
                       "root map contains hash-related keys"))
   :chain-metadata (fn [v]
                     (when (and (map? v)
                                (some #(re-find #"^evidence/chain-" (name %))
                                      (keys v)))
                       "root map contains chain-metadata keys"))})

(defn validate-intent-constraints!
  "Validate that a value does not violate an intent contract's exclusion rules.
   Walks the value tree checking for excluded types and structural patterns.

   Throws ex-info with :violations detailing every detected violation.
   Returns nil if the value passes all checks.

   Runtime/type exclusions use walk-for-excludes (recursive tree walk).
   Structural/root exclusions use exclude-root-checkers (top-level only).
   Unknown exclude categories are silently skipped (they may require
   semantic analysis not expressible as type/structural checks).

   Callers can invoke this directly for defensive checking:
     (hc/validate-intent-constraints! :evidence-record data)"
  [intent-kw value]
  (let [contract (resolve-intent intent-kw)
        excludes (:intent/excludes contract)
        violations (volatile! [])]
    ;; Tree-walk type checks
    (let [type-preds (keep #(when-let [c (get exclude-type-checkers %)]
                              [% c])
                           excludes)]
      (doseq [[exclude pred] type-preds
              v (walk-for-excludes [pred] value)]
        (vswap! violations conj {:exclude exclude :detail (:detail v)})))
    ;; Root-level structural checks
    (doseq [exclude excludes
            :let [checker (get exclude-root-checkers exclude)]
            :when checker
            :let [violation (checker value)]
            :when violation]
      (vswap! violations conj {:exclude exclude :detail violation}))
    (when (seq @violations)
      (throw (ex-info (str "Intent " intent-kw " constraints violated: "
                           (pr-str (mapv :detail @violations)))
                      {:intent intent-kw
                       :violations @violations
                       :value (try (subs (pr-str value) 0 200) (catch Exception _ "<unprintable>"))})))))

(defn intent-hash=
  "Compare hash values with intent awareness.
   Prevents accidental cross-intent hash comparison.

   Each argument can be:
   - A map with :hash/intent and :hash/hex keys (intent-aware)
   - A string (legacy plain hex hash, intent-agnostic)

   When both arguments have intent metadata and intents differ,
   returns false. Use :allow-cross-intent? true to override.

   Usage:
     (intent-hash= result1 result2)
     (intent-hash= result1 result2 {:allow-cross-intent? true})"
  ([a b] (intent-hash= a b nil))
  ([a b {:keys [allow-cross-intent?] :or {allow-cross-intent? false}}]
   (let [a-intent (when (map? a) (:hash/intent a))
         b-intent (when (map? b) (:hash/intent b))
         a-hex    (if (map? a) (:hash/hex a) a)
         b-hex    (if (map? b) (:hash/hex b) b)]
     (if (and a-intent b-intent (not= a-intent b-intent) (not allow-cross-intent?))
       false
       (= a-hex b-hex)))))

(defn hash-with-intent
  "Compute a hash with an explicit intent declaration.

   The intent map documents WHY this hash is being computed, what
   projection (if any) is applied to the data, and what domain tag
   separates the hash. This prevents accidental misuse, silent
   semantic drift, and confusion during refactors.

   When *validate-intent-constraints* is true, validates data against
   the intent's :intent/excludes before hashing (enable in tests).

   Usage:
     (hash-with-intent {:hash/intent :world-structure} world-state)
     (hash-with-intent {:hash/intent :evidence-record} evidence-data)
     (hash-with-intent {:hash/intent :evidence-content} evidence-map)
     (hash-with-intent {:hash/intent :manifest} manifest-data)

   Returns a hex string (64 chars). For intent-aware comparison,
   use intent-hash= or wrap the result:
     {:hash/intent :evidence-record, :hash/hex (hash-with-intent ...)}

   See hash-intents for all supported intents with their scope
   and exclusion contracts."
  [{:keys [hash/intent]} value]
  (let [{:intent/keys [projection-fn domain-tag]} (resolve-intent intent)]
    (when *validate-intent-constraints*
      (validate-intent-constraints! intent value))
    (domain-hash domain-tag (projection-fn value))))
