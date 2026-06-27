(ns dev.artifacts
  (:require
   [clojure.edn :as edn]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [dev.repl :as repl]))

(def default-artifact-root
  "results/test-artifacts")

(defn read-json
  [path]
  (with-open [r (io/reader path)]
    (json/read r :key-fn keyword)))

(defn latest-run-dir
  []
  (->> (file-seq (io/file default-artifact-root))
       (filter #(.isDirectory %))
       (sort-by #(.lastModified %))
       last
       str))

(defn artifact-path
  ([filename]
   (artifact-path (latest-run-dir) filename))
  ([run-dir filename]
   (str (io/file run-dir filename))))

(defn read-artifact
  [filename]
  (read-json (artifact-path filename)))

(defn explain-registry
  ([] (explain-registry (artifact-path "test-artifacts.json")))
  ([path]
   (let [registry (read-json path)
         artifacts (:artifacts registry)]
     {:path path
      :artifact-count (count artifacts)
      :schema-versions (frequencies (map :schema-version artifacts))
      :importance (frequencies (map :importance artifacts))
      :sample-artifacts (take 5 artifacts)})))

(defn tap-registry
  ([] (tap-registry (artifact-path "test-artifacts.json")))
  ([path]
   (let [summary (explain-registry path)]
     (tap> summary)
     summary)))

;; domain-specific helpers:
;; add for:
;; (find-artifacts-by-importance "CORE")
;; (find-artifacts-verifying :solvency)
;; (find-dangling-dependencies)
;; (find-evidence-for-event event-id)
;; (explain-chain)
;; (explain-attestation)
