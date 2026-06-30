(ns fix-edn-scenarios
  "Convert JSON-style EDN scenario files to proper EDN format."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn json-style->edn
  "Convert JSON-style content to proper EDN format."
  [content]
  (let [;; Parse as JSON first to handle the JSON-style strings
        json-data (json/read-str content :key-fn keyword)

        ;; Convert to proper EDN format
        edn-data (walk/postwalk
                 (fn [x]
                   (cond
                     (string? x) (if (re-matches #"^[\"'].+[\"']$" x)
                                   (edn/read-string x)
                                   x)
                     (map? x) (into {} (map (fn [[k v]] [k v]) x))
                     :else x))
                 json-data)]
    edn-data))

(defn fix-scenario-file
  "Fix a single scenario file to use proper EDN format."
  [file-path]
  (try
    (let [content (slurp file-path)
          ;; Check if it's already proper EDN
          parsed-edn (try (edn/read-string content) (catch Exception _ nil))

          ;; If EDN parsing fails, it's likely JSON-style content
          (if (or (nil? parsed-edn)
                  (and (map? parsed-edn) (string? (:scenario-id parsed-edn))))
            (let [fixed-content (json-style->edn content)]
              (spit file-path (with-out-str (clojure.pprint/pprint fixed-content)))
              (println "✓ Fixed:" file-path)
              true)
            (do
              (println "✓ Already proper EDN:" file-path)
              false)))
    (catch Exception e
      (println "✗ Error processing" file-path ":" (.getMessage e))
      false)))

(defn fix-all-edn-scenarios
  "Fix all EDN scenario files in the scenarios/edn directory."
  []
  (let [scenario-dir "scenarios/edn"
        files (->> (io/file scenario-dir)
                  .listFiles
                  (filter #(and (.isFile %)
                               (str/ends-with? (.getName %) ".edn"))))
        results (map fix-scenario-file files)]
    (println "\n=== Summary ===")
    (println "Fixed files:" (count (filter true? results))
             "out of" (count files) "total files.")))

;; Uncomment to run:
;; (fix-all-edn-scenarios)
