(ns scripts.generate-scenario-docs
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- parse-scenario [file]
  (try
    (json/read (io/reader file) :key-fn keyword)
    (catch Exception _ nil)))

(defn -main [& _]
  (let [scenarios (->> (file-seq (io/file "scenarios"))
                       (filter #(.endsWith (.getName ^java.io.File %) ".json"))
                       (keep parse-scenario)
                       (sort-by :scenario-id))
        doc-path "docs/scenarios.md"]
    (with-open [w (io/writer doc-path)]
      (.write w "# Scenario Registry\n\n")
      (.write w "| ID | Title | Purpose | Author | Created |\n")
      (.write w "| :--- | :--- | :--- | :--- | :--- |\n")
      (doseq [s scenarios]
        (let [p (:provenance s {})]
          (.write w (format "| %s | %s | %s | %s | %s |\n"
                            (:scenario-id s "N/A")
                            (:title s "N/A")
                            (:purpose s "N/A")
                            (:author p "N/A")
                            (:created-at p "N/A"))))))
    (println "Generated" doc-path)))
