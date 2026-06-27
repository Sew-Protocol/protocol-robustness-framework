(ns resolver-sim.tools.stability-checker
  "Stability manifest checker.

   Reads STABILITY_MANIFEST.edn, recomputes canonical hashes of listed
   source files via hash-with-intent with intent :stability/snapshot,
   and reports which stability surfaces have changed since their last
   recorded checkpoint.

   Usage: bb stability:check"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.time ZoneId]
           [java.time.format DateTimeFormatter]))

;; ── Manifest loading ─────────────────────────────────────────────────────────

(def manifest-path "STABILITY_MANIFEST.edn")

(defn- load-manifest
  ([] (load-manifest manifest-path))
  ([path]
   (let [f (io/file path)]
     (if (.exists f)
       (edn/read-string (slurp f))
       (throw (ex-info (str "Stability manifest not found: " path)
                       {:path path}))))))

;; ── Hash computation ─────────────────────────────────────────────────────────

(defn- compute-entry-hash
  "Compute the canonical hash for a stability entry's listed source files.
   Reads each file, builds a sorted map of path → content, and hashes it
   with intent :stability/snapshot.

   For the self-referential manifest entry (:stability/stability-manifest),
   hashes the entry definition itself (with :stability/hash dissoc'd) rather
   than reading STABILITY_MANIFEST.edn from disk (which would create a
   circular dependency between the file content and the recorded hash)."
  [entry]
  (if (= :stability/stability-manifest (:stability/id entry))
    (hc/hash-with-intent {:hash/intent :stability/snapshot}
                         {:files {"STABILITY_MANIFEST.edn (entry)"
                                  (pr-str (dissoc entry :stability/hash))}})
    (let [files (:stability/files entry)
          file-contents (into (sorted-map)
                              (map (fn [path]
                                     (let [f (io/file path)]
                                       (if (.exists f)
                                         [(str path) (slurp f)]
                                         [(str path) nil]))))
                              files)]
      (hc/hash-with-intent {:hash/intent :stability/snapshot}
                           {:files (into {} (remove (fn [[_ v]] (nil? v)) file-contents))}))))

;; ── Status classification ────────────────────────────────────────────────────

(defn- classify-entry
  "Classify a stability entry as :unchanged, :changed, or :missing-files.
   Returns a map with :stability/id, :stability/level, :stability/started-at,
   :stability/status, and :stability/matched."
  [entry]
  (let [files (:stability/files entry)
        missing (remove #(.exists (io/file %)) files)]
    (if (seq missing)
      {:stability/id (:stability/id entry)
       :stability/status :missing-files
       :stability/missing missing}
      (let [computed (compute-entry-hash entry)
            recorded (:stability/hash entry)]
        {:stability/id (:stability/id entry)
         :stability/level (:stability/level entry)
         :stability/started-at (:stability/started-at entry)
         :stability/status (if (= computed recorded) :unchanged :changed)
         :stability/matched (= computed recorded)
         :stability/recorded-hash recorded
         :stability/computed-hash computed}))) )

;; ── Reporting ────────────────────────────────────────────────────────────────

(defn- status-icon [status]
  (case status
    :unchanged "✅"
    :changed "❌"
    :missing-files "⚠️ "
    "❓"))

(defn- level-label [level]
  (name (or level :unknown)))

(defn- format-date [d]
  (when d
    (try
      (.format DateTimeFormatter/ISO_LOCAL_DATE
               (.atZone (.toInstant ^java.util.Date d) (ZoneId/systemDefault)))
      (catch Exception _
        (let [s (str d)]
          (subs s 0 (min 10 (count s))))))))

(defn- print-report [results]
  (printf "\nStability Check — %s\n" (.toString (java.time.LocalDate/now)))
  (println (apply str (repeat 94 "─")))
  (println (format "  %-48s %-12s %-14s %s" "Surface" "Level" "Started At" "Status"))
  (println (apply str (repeat 94 "─")))
  (doseq [r results]
    (println (format "  %-48s %-12s %-14s %s %s"
                     (str (:stability/id r))
                     (level-label (:stability/level r))
                     (or (format-date (:stability/started-at r)) "-")
                     (status-icon (:stability/status r))
                     (case (:stability/status r)
                       :changed (str "(hash mismatch)")
                       :missing-files (str "(missing: " (pr-str (:stability/missing r)) ")")
                       ""))))
  (println (apply str (repeat 94 "─")))
  (let [unchanged (count (filter #(= :unchanged (:stability/status %)) results))
        changed (count (filter #(= :changed (:stability/status %)) results))
        missing (count (filter #(= :missing-files (:stability/status %)) results))]
    (println (format "  %d unchanged, %d changed, %d missing — %d total"
                     unchanged changed missing (count results)))
    (when (pos? changed)
      (println "  ❌ Some stable surfaces have changed — review STABILITY.md"))
    (when (pos? missing)
      (println "  ⚠️  Some files referenced in the manifest are missing")))
  (println))

;; ── Entry point ──────────────────────────────────────────────────────────────

(defn -main [& _]
  (let [manifest (load-manifest)
        entries (:stability/entries manifest)
        results (mapv classify-entry entries)]
    (print-report results)
    (let [changed (filter #(= :changed (:stability/status %)) results)
          missing (filter #(= :missing-files (:stability/status %)) results)]
      (System/exit (if (or (seq changed) (seq missing)) 1 0)))))
