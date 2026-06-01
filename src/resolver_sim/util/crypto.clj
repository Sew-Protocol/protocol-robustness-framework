(ns resolver-sim.util.crypto
  (:require [clojure.data.json :as json]))

(defn compute-canonical-hash
  "Deterministically hash a data map by serializing to JSON and taking a hash.
   This emulates Keccak256 binding in the protocol."
  [data-map]
  (let [json-data (json/write-str (into (sorted-map) data-map))]
    (hash json-data)))

(defn compute-delta-hash
  "Computes a delta hash representing the difference between two roots."
  [root-old root-new]
  (compute-canonical-hash {:old root-old :new root-new}))
