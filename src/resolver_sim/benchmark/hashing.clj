(ns resolver-sim.benchmark.hashing
  (:require [clojure.walk :as walk]
            [clojure.edn :as edn])
  (:import [java.security MessageDigest]))

(defn canonicalize [data]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into (sorted-map) x)
       x))
   data))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn hash-evidence [evidence]
  (let [canonical (canonicalize evidence)
        s (pr-str canonical)]
    (sha256 s)))
