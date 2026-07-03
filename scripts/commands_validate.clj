(require '[clojure.data.json :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def registry-path "data/commands/registry.edn")
(def docs-path "docs/COMMANDS.md")
(def bb-path "bb.edn")
(def output-path "target/validation/commands-validation.json")

(def allowed-categories
  #{:validation :scenario :benchmark :evidence :forensic :report :concept
    :portability :probability :game-theory :maintenance})

(def allowed-statuses #{:active :deprecated})
(def allowed-tiers #{:fast :default :full :manual :deprecated})

(defn public-bb-tasks []
  (->> (re-seq #"(?m)^[ \t]{2,}([A-Za-z0-9:._-]+)\s+\{" (slurp bb-path))
       (map second)
       set))

(defn command-name-from-argv [argv]
  (str "bb " (str/join " " argv)))

(defn write-report! [report]
  (.mkdirs (.getParentFile (io/file output-path)))
  (spit output-path (json/write-str report :escape-slash false)))

(defn fail! [errors warnings]
  (let [report {:schema-version "commands.validation.v1"
                :status :failed
                :errors errors
                :warnings warnings}]
    (write-report! report)
    (doseq [e errors]
      (println "FAIL:" e))
    (when (seq warnings)
      (doseq [w warnings]
        (println "WARN:" w)))
    (println "VALIDATION FAILED")
    (System/exit 1)))

(defn pass! [command-count warnings]
  (let [report {:schema-version "commands.validation.v1"
                :status :passed
                :command-count command-count
                :warnings warnings}]
    (write-report! report)
    (doseq [w warnings]
      (println "WARN:" w))
    (println (str "VALIDATION PASSED (" command-count " commands)"))))

(defn registry-entry-errors [entry task-names docs-text]
  (let [name (:command/name entry)
        argv (:command/argv entry)
        docs (:command/docs entry)
        outputs (:command/outputs entry)
        errs (transient [])]
    (when-not (:command/id entry)
      (conj! errs "registry entry missing :command/id"))
    (when-not (and (vector? argv) (seq argv))
      (conj! errs (str name " must declare non-empty :command/argv")))
    (when-not (= name (command-name-from-argv argv))
      (conj! errs (str name " does not match argv " (pr-str argv))))
    (when-not (contains? allowed-categories (:command/category entry))
      (conj! errs (str name " has unsupported :command/category " (:command/category entry))))
    (when-not (contains? allowed-statuses (:command/status entry))
      (conj! errs (str name " has unsupported :command/status " (:command/status entry))))
    (when-not (contains? allowed-tiers (:command/backstop-tier entry))
      (conj! errs (str name " has unsupported :command/backstop-tier " (:command/backstop-tier entry))))
    (when-not (:command/owner-surface entry)
      (conj! errs (str name " missing :command/owner-surface")))
    (when-not (string? (:command/description entry))
      (conj! errs (str name " missing string :command/description")))
    (when-not (vector? docs)
      (conj! errs (str name " must declare :command/docs vector")))
    (when-not (vector? outputs)
      (conj! errs (str name " must declare :command/outputs vector")))
    (when-not (contains? task-names (first argv))
      (conj! errs (str name " points to missing bb task " (first argv))))
    (doseq [doc docs]
      (when-not (.exists (io/file doc))
        (conj! errs (str name " references missing doc file " doc)))
      (when-not (str/includes? docs-text name)
        (conj! errs (str name " is missing from docs/COMMANDS.md"))))
    (when (and (#{:fast :default} (:command/backstop-tier entry))
               (= :deprecated (:command/status entry)))
      (conj! errs (str name " cannot be both deprecated and part of an automatic backstop tier")))
    (when (and (#{:fast :default} (:command/backstop-tier entry))
               (some #{:secret :secrets} (:command/requires entry)))
      (conj! errs (str name " cannot require secrets in an automatic backstop tier")))
    (persistent! errs)))

(defn run-validation []
  (println "▶ commands:validate")
  (let [registry (edn/read-string (slurp registry-path))
        docs-text (slurp docs-path)
        task-names (public-bb-tasks)
        commands (:commands registry)
        errors (vec (mapcat #(registry-entry-errors % task-names docs-text) commands))
        warnings (vec (for [task ["validate" "evidence:validate" "backstop:full"]
                            :when (not (contains? task-names task))]
                        (str "canonical surface not yet implemented: bb " task)))]
    (if (seq errors)
      (fail! errors warnings)
      (pass! (count commands) warnings))))

(run-validation)
