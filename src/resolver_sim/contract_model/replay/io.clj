(ns resolver-sim.contract-model.replay.io
  "Serialization and IO helpers for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [resolver-sim.io.serialization :as serialization]))

(defn kw->json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn kw-val->str [_k v]
  (if (keyword? v) (name v) v))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (serialization/serialize-artifact (dissoc result :protocol)))
