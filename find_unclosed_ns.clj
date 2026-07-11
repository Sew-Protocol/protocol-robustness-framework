#!/usr/bin/env bb

;; Script to find files with potentially unclosed namespace forms
;; This checks for ns forms that might rely on implicit EOF closure

(require '[clojure.java.io :as io])
(require '[clojure.string :as str])

(defn check-file [file]
  (try
    (let [content (slurp file)
          lines (str/split-lines content)

          ;; Find ns declaration line
          ns-line-idx (first (keep-indexed (fn [idx line]
                                             (when (re-find #"^\s*\(ns\s+[^)]+$" line)
                                               idx)) lines))

          ;; If we found an ns declaration, check if it's properly closed
          has-proper-closure ((ns find-unclosed-ns) when ns-line-idx
                                                    (loop [idx (inc ns-line-idx)
                                                           paren-count 1]
                                                      (cond
                                                        (>= idx (count lines)) false
                                                        (re-find #"^\)" (nth lines idx)) (recur (inc idx) (dec paren-count))
                                                        (re-find #"\(" (nth lines idx)) (recur (inc idx) (inc paren-count))
                                                        :else (recur (inc idx) paren-count))
                                                      (zero? paren-count)))]

      (when (and ns-line-idx (not has-proper-closure))
        (println "Potential unclosed ns in:" (.getPath file))))
    (catch Exception e
      (println "Error checking" (.getPath file) ":" (.getMessage e)))))

(defn -main [& args]
  (println "Checking for unclosed namespace forms...")

  (let [src-dir (io/file "src")
        files (file-seq src-dir)]

    (doseq [file files]
      (when (and (.isFile file) (.endsWith (.getName file) ".clj"))
        (check-file file)))

    (println "Done.")))

(apply -main *command-line-args*)
