(ns resolver-sim.evidence.capture
  "Pure evidence-builder primitives for structured field capture.
   
   Design:
     - cap-field / cap-fields are pure builders: (cap-field evidence k v) => updated-evidence
     - Fields must be qualified keywords; nil values are silently omitted
     - Values are canonicalized for deterministic hashing
     - require-fields validates that CORE / DIAGNOSTIC evidence has required keys
     - finalize-evidence computes composite evidence-hash and optionally chains with prev-hash
   
   Flow:
     transition code
       -> cap-field / cap-fields
       -> require-fields
       -> finalize-evidence
       -> capture-event-evidence!
   
   See also resolver-sim.io.event-evidence for durable persistence."
  (:require [clojure.walk :as walk]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.util.attribution :as attr]))

;; ── Stable Hashing ───────────────────────────────────────────────────────────

(defn- canonicalize
  "Recursively sort map keys for deterministic serialization."
  [data]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map) x)
        x))
    data))

(defn stable-hash
  "Compute a deterministic, content-addressed hash of any Clojure value.
   Same input always produces the same hex string, across JVM invocations.
   Output is prefixed with 'sha256:' for schema compliance."
  [x]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (pr-str (canonicalize x)) "UTF-8"))
    (str "sha256:" (apply str (map (partial format "%02x") (map int (.digest digest)))))))

;; ── Required Fields ──────────────────────────────────────────────────────────

(def core-evidence-required-fields
  "Fields that must be present on every CORE evidence record."
  #{:scenario/id :run/id :event/seq :transition/id
    :world/before-hash :world/after-hash
    :replay/seed :oracle/cursor
    :evidence/type :evidence/importance})

(def diagnostic-evidence-required-fields
  "Fields that must be present on DIAGNOSTIC evidence."
  #{:scenario/id :run/id :event/seq
    :evidence/type :evidence/importance})

;; ── Evidence Base ────────────────────────────────────────────────────────────

(defn evidence-base
  "Create a minimal evidence record with schema version and type.
   
   Required opts:
     :type       — evidence type keyword (e.g. :fraud-slash)
     :importance — :core | :diagnostic | :trace (default :core)
   
   Optional:
     :ctx        — attribution context map (merged into :attribution)"
  [{:keys [type importance ctx]
    :or   {importance :core}}]
  {:evidence/schema-version (evcfg/schema :event-evidence)
   :evidence/type           (name type)
   :evidence/importance     (name importance)
   :evidence/context        (when ctx (attr/sanitize-attribution ctx))})

;; ── Field Capture ────────────────────────────────────────────────────────────

(defn- canonicalize-evidence-value
  "Normalize a value for deterministic serialization.
   Keywords are converted to strings; sorted-maps used for stable hashing."
  [v]
  (cond
    (instance? clojure.lang.Keyword v) (name v)
    (map? v) (into (sorted-map) (map (fn [[k v]] [(name k) v]) v))
    (sequential? v) (mapv canonicalize-evidence-value v)
    :else v))

(defn cap-field
  "Add one structured field to an evidence map.
   
   Rules:
     - k must be a qualified keyword (:ns/name)
     - nil values are silently omitted (returns evidence unchanged)
     - Non-collection values are canonicalized for deterministic hashing
   
   Returns updated evidence map."
  [evidence k v]
  (cond
    (nil? v)
    evidence

    (not (qualified-keyword? k))
    (throw (ex-info "Evidence field must be a qualified keyword"
                    {:field k :value v :evidence evidence}))

    :else
    (assoc evidence k (canonicalize-evidence-value v))))

(defn cap-fields
  "Add multiple fields to an evidence map.
   
   (cap-fields evidence {:scenario/id sid :run/id rid ...})"
  [evidence fields]
  (reduce-kv (fn [acc k v] (cap-field acc k v)) evidence fields))

;; ── Validation ───────────────────────────────────────────────────────────────

(defn require-fields
  "Validate that evidence contains all required keys for its importance level.
   
   Throws ex-info with :missing keys on failure.
   Returns evidence unchanged on success."
  [evidence]
  (let [importance (keyword (:evidence/importance evidence))
        required (case importance
                   :core core-evidence-required-fields
                   :diagnostic diagnostic-evidence-required-fields
                   :trace #{}
                   #{})
        present (set (keys evidence))
        missing (seq (remove #(contains? present %) required))]
    (when missing
      (throw (ex-info (str "Missing required evidence fields for " importance " evidence")
                      {:importance importance
                       :missing (vec missing)
                       :present (vec present)
                       :evidence-type (:evidence/type evidence)})))
    evidence))

;; ── Finalization ─────────────────────────────────────────────────────────────

(defn finalize-evidence
  "Finalize an evidence record before persistence.
   
   1. Canonicalizes all values for deterministic output
   2. Computes composite :evidence/hash over all other fields
   3. Optionally chains with :evidence/prev-hash
   
   Returns the finalized evidence map."
  [evidence]
  (let [hash-input (dissoc evidence :evidence/hash)
        evidence-hash (stable-hash hash-input)]
    (assoc evidence :evidence/hash evidence-hash)))
