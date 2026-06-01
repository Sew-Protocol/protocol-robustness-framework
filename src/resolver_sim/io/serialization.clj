(ns resolver-sim.io.serialization
  "Protocol and utilities for JSON-safe data conversion."
  (:require [clojure.data.json :as json]))

(defprotocol ToJsonData
  (to-json-data [this] "Convert this record or object to a JSON-serializable structure."))

(extend-protocol ToJsonData
  clojure.lang.IPersistentMap
  (to-json-data [this]
    (into {} (map (fn [[k v]] [k (to-json-data v)]) this)))

  clojure.lang.IPersistentVector
  (to-json-data [this]
    (mapv to-json-data this))

  clojure.lang.IPersistentSet
  (to-json-data [this]
    (mapv to-json-data this))

  ;; Generic fallback for other records or types
  clojure.lang.IRecord
  (to-json-data [this]
    (into {} (map (fn [[k v]] [k (to-json-data v)]) this)))

  ;; Handle specific types that might cause JSON serialization issues
  java.lang.Object
  (to-json-data [this]
    (if (instance? clojure.lang.IRecord this)
      (into {} (map (fn [[k v]] [k (to-json-data v)]) this))
      (str this)))

  nil
  (to-json-data [this] nil))

(defn serialize-artifact [artifact]
  (json/write-str (to-json-data artifact)))
