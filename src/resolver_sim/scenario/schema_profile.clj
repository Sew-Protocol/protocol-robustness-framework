(ns resolver-sim.scenario.schema-profile
  "Schema-version profile for scenario validation/reporting assumptions.
   Keeps version-coupling centralized and overrideable."
  (:require [resolver-sim.scenario.outcome-semantics :as ose]))

(def default-profile
  {:supported-versions #{"1.0" "1.1"}
   :enriched-version "1.1"
   :required-fields-by-version
   {"1.1" [:id :title :purpose :scenario-author]}
   :purpose-requirements
   {:theory-falsification {:requires-theory? true
                           :requires-metric-falsifies-if? true}
    :adversarial-robustness {:requires-theory-or-expectations? true}}})

(defn supported-version? [version]
  (contains? (:supported-versions default-profile) (str version)))

(defn required-fields
  [version]
  (get-in default-profile [:required-fields-by-version (str version)] []))

(defn enriched-version []
  (:enriched-version default-profile))

(defn requires-theory? [purpose]
  (true? (get-in default-profile [:purpose-requirements (ose/normalize-purpose purpose) :requires-theory?])))

(defn requires-theory-or-expectations? [purpose]
  (true? (get-in default-profile [:purpose-requirements (ose/normalize-purpose purpose) :requires-theory-or-expectations?])))

(defn requires-metric-falsifies-if? [purpose]
  (true? (get-in default-profile [:purpose-requirements (ose/normalize-purpose purpose) :requires-metric-falsifies-if?])))
