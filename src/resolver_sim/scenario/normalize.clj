(ns resolver-sim.scenario.normalize
  "JSON/EDN scenario normalization for file-backed traces (pure)."
  (:require [clojure.walk :as walk]))

(defn- normalize-keyword-strings
  [v]
  (cond
    (string? v)
    (if (and (.startsWith v ":") (> (count v) 1))
      (keyword (subs v 1))
      v)
    (keyword? v) v
    :else v))

(defn- normalize-map-keys
  [m]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                 (let [normalized-k (if (string? k)
                                      (try (Integer/parseInt k)
                                           (catch Exception _ k))
                                      k)]
                   (assoc acc normalized-k v)))
               {} m)
    m))

(defn- normalize-error-kw [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn- normalize-expected-errors
  [scenario]
  (if-let [errs (:expected-errors scenario)]
    (assoc scenario :expected-errors
           (mapv #(update % :error normalize-error-kw) errs))
    scenario))

(defn normalize-scenario
  "Recursively normalize a loaded scenario to fix JSON deserialization issues."
  [x]
  (let [normalized (walk/postwalk
                    (fn [v]
                      (cond
                        (map? v)
                        (let [key-normalized (normalize-map-keys v)]
                          (reduce-kv (fn [m k kv]
                                       (assoc m k (normalize-keyword-strings kv)))
                                     key-normalized key-normalized))

                        (string? v)
                        (normalize-keyword-strings v)

                        :else v))
                    x)]
    (if (map? normalized)
      (normalize-expected-errors normalized)
      normalized)))
