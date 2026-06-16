(ns resolver-sim.io.results
  "Serialization of simulation results and traces.
   Handles export to CSV/EDN and standardized trace persistence."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- kw->json-key [kw]
  (name kw))

(defn- kw-val->str [v]
  (if (keyword? v) (name v) v))

(defn result->json-str
  "Serialize a simulation result/trace to a JSON string."
  [result]
  (json/write-str result :key-fn kw->json-key :value-fn kw-val->str :indent true))

(defn create-run-directory
  "Create a timestamped output directory for a simulation run and return its path."
  [base-dir scenario-id]
  (let [ts   (-> (java.time.LocalDateTime/now)
                 (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")))
        path (str base-dir "/" ts "_" scenario-id)]
    (.mkdirs (io/file path))
    path))

(defn write-edn
  "Write a Clojure value to an EDN file."
  [path data]
  (io/make-parents (io/file path))
  (spit path (pr-str data)))

(defn write-csv
  "Write a seq of maps to a CSV file (header = keys of first row)."
  [path rows]
  (when (seq rows)
    (io/make-parents (io/file path))
    (let [ks (keys (first rows))
          header (clojure.string/join "," (map name ks))
          lines  (map (fn [r] (clojure.string/join "," (map #(get r %) ks))) rows)]
      (spit path (clojure.string/join "\n" (cons header lines))))))

(defn write-run-metadata
  "Write run metadata map to an EDN file."
  [path metadata]
  (write-edn path metadata))

(defn persist-trace!
  "Persist a trial trace to the given results directory."
  [dir-path scenario-id trace]
  (let [file (io/file dir-path (str scenario-id ".trace.json"))]
    (io/make-parents file)
    (spit file (result->json-str trace))))
