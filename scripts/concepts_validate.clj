(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set])

(def concept-root (io/file "data/concepts"))
(def registry-path (io/file concept-root "registry.edn"))
(def known-protocols #{:protocol/sew-v1 :protocol/prf})
(def supported-maps-to-types #{:protocol.actor :protocol.role :protocol.entity :protocol.action :protocol.outcome})

(def required-keys
  #{:concept/id :concept/name :concept/summary :concept/stakeholder-question
    :concept/protocols :concept/roles :concept/entities :concept/actions
    :concept/outcomes :concept/failure-modes :concept/assumptions
    :concept/out-of-scope :concept/type :concept/layer})

(def type-dir-map
  {:use-case "use-case"
   :decision-quality "decision-quality"
   :assurance "assurance"
   :allocation "allocation"
   :yield "yield"})

(defn edn-files [dir]
  (filter #(and (.endsWith (.getName %) ".edn") (not (.isDirectory %)))
          (file-seq (io/file dir))))

(defn parse-edn [f]
  (try [(edn/read-string (slurp f)) nil]
       (catch Exception e [nil (str (.getMessage e))])))

(defn missing-keys [data]
  (seq (set/difference required-keys (set (keys data)))))

(defn check-maps-to [path context errors]
  (let [maps-val (:maps-to context)]
    (when maps-val
      (when (not (vector? maps-val))
        (swap! errors conj (str path " :maps-to must be a vector, got " (type maps-val))))
      (doseq [v (flatten (if (vector? maps-val) maps-val [maps-val]))]
        (cond
          (string? v)
          (swap! errors conj (str path " :maps-to value \"" v "\" is a string; should be a :protocol.* keyword"))
          (and (keyword? v) (not (some #(= (namespace v) %) (map name supported-maps-to-types))))
          (swap! errors conj (str path " :maps-to value " v " has unsupported namespace; expected one of " supported-maps-to-types)))))))

(defn check-value-maps-to [path obj errors]
  (cond
    (map? obj)
    (do (when (:maps-to obj) (check-maps-to path obj errors))
        (doseq [[k v] obj]
          (check-value-maps-to (str path "/" k) v errors)))
    (sequential? obj)
    (doseq [v obj] (check-value-maps-to path v errors))))

(defn run-validation []
  (println "▶ concepts:validate\n")
  (println "  Parsing registry...")
  (let [[registry reg-err] (parse-edn registry-path)]
    (when reg-err
      (println "    FAIL" (.getPath registry-path) "-" reg-err "\n\nVALIDATION FAILED")
      (System/exit 1))
    (let [reg-entries (:concepts registry)
          reg-by-id (into {} (map (fn [e] [(:concept/id e) e]) reg-entries))
          errors (atom [])
          files-ok (atom 0)]
      (println "    OK" (count reg-entries) "entries")
      (println "  Checking registry...")
      (doseq [[cid entry] reg-by-id]
        (let [f (io/file (:concept/file entry))]
          (when-not (.exists f)
            (swap! errors conj (str "registry entry " cid " references " (:concept/file entry) " but file not found"))
            (println "    FAIL registry entry" cid "file not found:" (:concept/file entry)))))
      (let [ids (map :concept/id reg-entries)
            dups (set (for [[id freq] (frequencies ids) :when (> freq 1)] id))]
        (doseq [id dups]
          (swap! errors conj (str "duplicate concept ID " id " in registry"))
          (println "    FAIL duplicate concept ID" id)))
      (doseq [entry reg-entries]
        (let [ps (:concept/protocols entry)]
          (when ps
            (doseq [p ps]
              (when-not (known-protocols p)
                (swap! errors conj (str "entry " (:concept/id entry) " unknown protocol " p))
                (println "    FAIL entry" (:concept/id entry) "unknown protocol" p))))))
      (println "  Parsing concept files...")
      (doseq [f (sort (edn-files concept-root))]
        (when-not (= (.getName f) "registry.edn")
          (let [rel (str (.relativize (.toPath (.getCanonicalFile concept-root)) (.toPath (.getCanonicalFile f))))
                [data parse-err] (parse-edn f)]
            (if parse-err
              (do (swap! errors conj (str rel ": " parse-err))
                  (println "    FAIL" rel "-" parse-err))
              (let [missing (missing-keys data)]
                (if missing
                  (do (swap! errors conj (str rel ": missing keys " (pr-str missing)))
                      (println "    FAIL" rel "missing keys:" (pr-str missing)))
                  (let [cid (:concept/id data)
                        ctype (:concept/type data)
                        reg-entry (get reg-by-id cid)]
                    (swap! files-ok inc)
                    (when (nil? reg-entry)
                      (swap! errors conj (str rel ": not registered in registry.edn"))
                      (println "    WARN" rel "not registered"))
                    (when reg-entry
                      (let [expected-file (:concept/file reg-entry)
                            expected-rel (clojure.string/replace-first expected-file "data/concepts/" "")]
                        (when (not= expected-rel rel)
                          (swap! errors conj (str rel ": registry file mismatch, expected " expected-file))
                          (println "    FAIL" rel "registry file mismatch, expected" expected-file)))
                      (let [ft ctype rt (:concept/type reg-entry)]
                        (when (not= ft rt)
                          (swap! errors conj (str rel ": type mismatch, file has " ft ", registry has " rt))
                          (println "    FAIL" rel "type mismatch")))
                      (let [fl (:concept/layer data) rl (:concept/layer reg-entry)]
                        (when (not= fl rl)
                          (swap! errors conj (str rel ": layer mismatch, file has " fl ", registry has " rl))
                          (println "    FAIL" rel "layer mismatch"))))
                    (let [expected-dir (get type-dir-map ctype)]
                      (when (and expected-dir (not (.startsWith rel (str expected-dir "/")))
                                 (not= rel (str expected-dir ".edn")))
                        (swap! errors conj (str rel ": expected in " expected-dir "/ directory for type " ctype))
                        (println "    FAIL" rel "wrong directory for type" ctype "expected" (str expected-dir "/"))))
                    (let [ps (:concept/protocols data)]
                      (doseq [p ps]
                        (when-not (known-protocols p)
                          (swap! errors conj (str rel ": unknown protocol " p))
                          (println "    FAIL" rel "unknown protocol" p))))
                    (check-value-maps-to rel data errors))))))))
      (println)
      (if (empty? @errors)
        (println "  OK" @files-ok "files, all valid\n\nVALIDATION PASSED")
        (do (println "  ERRORS:" (count @errors))
            (doseq [e @errors] (println "    -" e))
            (println "\nVALIDATION FAILED")
            (System/exit 1))))))

(run-validation)
