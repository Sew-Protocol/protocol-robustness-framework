(ns resolver-sim.benchmark.hashing
  "Single source of truth for deterministic, content-addressed hashing.
   All evidence hashing across the codebase should route through this namespace
   to ensure consistent canonicalization and hash format."
  (:require [clojure.walk :as walk]
            [clojure.edn :as edn])
  (:import [java.security MessageDigest]))

(defn canonicalize [data]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (try (into (sorted-map) x)
            (catch ClassCastException _
              (into (array-map) (sort-by (fn [[k _]] (str k)) x))))
       x))
   data))

(defn- sha256-hex
  "Return the SHA-256 hex digest of a UTF-8 string."
  [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn hash-evidence
  "Compute a deterministic, content-addressed hash of any Clojure value.
   Same input always produces the same hex string, across JVM invocations.
   Returns plain hex (no prefix)."
  [evidence]
  (let [canonical (canonicalize evidence)
        s (pr-str canonical)]
    (sha256-hex s)))

(defn stable-hash
  "Alias for hash-evidence: deterministic content-addressed hash.
   Plain hex output, no prefix."
  [x]
  (hash-evidence x))

(defn stable-hash-prefixed
  "Deterministic content-addressed hash with 'sha256:' prefix.
   Used by the evidence capture pipeline where schema compliance
   requires the prefix. Same canonicalization as stable-hash."
  [x]
  (str "sha256:" (hash-evidence x)))
