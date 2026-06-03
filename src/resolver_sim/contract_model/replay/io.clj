(ns resolver-sim.contract-model.replay.io
  "Serialization and IO helpers for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.data.json :as json]))

(defn kw->json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn kw-val->str [_k v]
  (if (keyword? v) (name v) v))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (let [serializable (dissoc result :protocol)]
    (json/write-str serializable :key-fn kw->json-key :value-fn kw-val->str)))
