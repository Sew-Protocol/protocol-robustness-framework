(ns resolver-sim.protocols.sew.compat
  "Compatibility and normalization helpers for Sew event inputs."
  (:require [clojure.string :as str]))

(defn wf-id
  "Compatibility accessor for workflow identifiers.
   Accepts either {:workflow-id n} (current) or {:id n} (legacy)."
  [event]
  (or (get-in event [:params :workflow-id])
      (get-in event [:params :id])))

(defn canonical-action
  "Normalize action names by converting underscores to hyphens."
  [event]
  (-> (:action event)
      name
      (str/replace "_" "-")))