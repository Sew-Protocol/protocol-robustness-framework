(ns resolver-sim.io.params
  "Load and validate parameter files (EDN format)."
  (:require [resolver-sim.stochastic.types :as types]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-edn
  "Load EDN param file."
  [path]
  (let [file (io/file path)]
    (if (.exists file)
      (edn/read-string (slurp file))
      (throw (ex-info (format "Param file not found: %s" path) {:path path})))))

(defn merge-defaults
  "Merge scenario params with defaults."
  [scenario]
  (merge types/default-params scenario))

(defn validate-and-merge
  "Load, validate (including effective oracle-fixture), and merge with defaults."
  [path]
  (-> path load-edn merge-defaults types/validate-scenario))

(defn validate-scenario
  "Validate an in-memory scenario map (same checks as validate-and-merge)."
  [scenario]
  (-> scenario merge-defaults types/validate-scenario))
