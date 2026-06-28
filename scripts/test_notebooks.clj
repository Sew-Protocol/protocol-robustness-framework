(ns scripts.test-notebooks
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── helpers ────────────────────────────────────────────────────────────────────

(def notebooks-dir (io/file "notebooks"))

(defn read-ns-form [^java.io.File f]
  (with-open [rdr (io/reader f)]
    (some (fn [line]
            (when-let [m (re-find #"\(ns\s+(\S+)" line)]
              (symbol (last m))))
          (line-seq rdr))))

(defn notebook-files []
  (sort
   (filter
    (fn [f]
      (and (.isFile f)
           (re-find #"\.clj$" (.getName f))
           (not (str/includes? (.getPath f) "/archive/"))
           (not (str/includes? (.getName f) "_template"))))
    (file-seq notebooks-dir))))

(def ^:const notebook-timeout-ms
  "Maximum milliseconds to wait for a single notebook to load.
   JVM startup for the first notebook (~40s) plus require time for
   notebooks with expensive top-level computations (scenario replay,
   trace data loading).  Notebooks exceeding this should use delay
   or lazy evaluation at top level."
  120000)

(defn load-notebook [ns-sym]
  (let [f (future
            (try
              (require ns-sym)
              :loaded
              (catch Exception e
                (println "  ✗" ns-sym)
                (println "    " (ex-message e))
                :failed)))]
    (let [result (deref f notebook-timeout-ms :timeout)]
      (if (= :timeout result)
        (do (future-cancel f)
            (println "  ⏱" ns-sym "(timed out after" notebook-timeout-ms "ms)")
            false)
        (do (when (= :loaded result) (println "  ✓" ns-sym))
            (= :loaded result))))))

;; ── entry point ────────────────────────────────────────────────────────────────

(defn -main [& args]
  ;; Suppress log spam from notebook namepace loading
  (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "error")
  (let [files (notebook-files)
        total (count files)
        results (mapv (fn [f]
                        (if-let [ns-sym (read-ns-form f)]
                          (load-notebook ns-sym)
                          (do (println "  ?" (.getPath f) "(no ns form found)")
                              false)))
                      files)
        failed (count (remove true? results))
        passed (- total failed)]
    (println)
    (println "─── Notebook Load Summary ───")
    (println (str "  Passed: " passed "/" total))
    (when (pos? failed)
      (println (str "  Failed: " failed)))
    (println)
    (System/exit (if (pos? failed) 1 0))))
