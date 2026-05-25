(ns scripts.generate-state-machine-docs
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.definitions.registry :as defs]))

(def state-machine-doc-path "docs/overview/STATE_MACHINE_GENERATED.md")
(def transition-coverage-doc-path "docs/overview/TRANSITION_COVERAGE_GENERATED.md")

(defn- normalize-action [a]
  (-> (if (keyword? a) (name a) (str a))
      str/lower-case
      (str/replace "-" "_")
      keyword))

(defn- scenario-id-from-file [f]
  (-> (.getName ^java.io.File f)
      (str/replace #"\.json$" "")))

(defn- load-scenario [f]
  (json/read-str (slurp f) :key-fn keyword))

(defn- scenario-files []
  (->> (file-seq (io/file "scenarios"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".json"))
       (sort-by #(.getName ^java.io.File %))))

(defn- scenario-actions [scenario]
  (->> (:events scenario)
       (map :action)
       (map normalize-action)
       set))

(defn- transition-coverage [scenarios]
  (let [all-transitions (sort (defs/canonical-transition-ids))]
    (into (sorted-map)
          (for [tr all-transitions]
            [tr (->> scenarios
                     (filter (fn [{:keys [actions]}] (contains? actions tr)))
                     (map :id)
                     sort
                     vec)]))))

(defn- state-machine-markdown []
  (let [rows (sort-by key defs/transitions)]
    (str "# State Machine (Generated)\n\n"
         "Source of truth: `src/resolver_sim/definitions/registry.clj` (`transitions`).\n\n"
         "| Transition ID | Label |\n"
         "|---|---|\n"
         (apply str
                (for [[tr meta] rows]
                  (str "| `" (name tr) "` | " (:label meta) " |\n")))
         "\n"
         "Definitions hash: `" (defs/definitions-hash) "`\n")))

(defn- transition-coverage-markdown [coverage]
  (str "# Transition Coverage (Generated)\n\n"
       "Derived from scenario events in `scenarios/*.json` and canonical transition IDs in registry.\n\n"
       "| Transition ID | Label | Covered by scenarios | Count |\n"
       "|---|---|---|---:|\n"
       (apply str
              (for [[tr scenario-ids] coverage]
                (str "| `" (name tr) "` | "
                     (or (get-in (defs/transition-def tr) [:label]) "")
                     " | "
                     (if (seq scenario-ids)
                       (str/join ", " scenario-ids)
                       "_none_")
                     " | " (count scenario-ids) " |\n")))
       "\n"
       "Definitions hash: `" (defs/definitions-hash) "`\n"))

(defn- write-if-changed! [path content]
  (let [f (io/file path)
        current (when (.exists f) (slurp f))]
    (when-not (= current content)
      (.mkdirs (.getParentFile f))
      (spit f content))
    {:path path :changed? (not= current content)}))

(defn- generate! []
  (let [scenarios (->> (scenario-files)
                       (map (fn [f]
                              (let [sc (load-scenario f)]
                                {:id (or (:id sc) (:scenario-id sc) (scenario-id-from-file f))
                                 :actions (scenario-actions sc)}))))
        coverage (transition-coverage scenarios)
        sm-doc (state-machine-markdown)
        cov-doc (transition-coverage-markdown coverage)
        r1 (write-if-changed! state-machine-doc-path sm-doc)
        r2 (write-if-changed! transition-coverage-doc-path cov-doc)]
    (println "Generated state machine docs:")
    (println "-" (:path r1) "changed?" (:changed? r1))
    (println "-" (:path r2) "changed?" (:changed? r2))))

(defn- check! []
  (let [scenarios (->> (scenario-files)
                       (map (fn [f]
                              (let [sc (load-scenario f)]
                                {:id (or (:id sc) (:scenario-id sc) (scenario-id-from-file f))
                                 :actions (scenario-actions sc)}))))
        coverage (transition-coverage scenarios)
        expected-sm (state-machine-markdown)
        expected-cov (transition-coverage-markdown coverage)
        current-sm (when (.exists (io/file state-machine-doc-path)) (slurp state-machine-doc-path))
        current-cov (when (.exists (io/file transition-coverage-doc-path)) (slurp transition-coverage-doc-path))]
    (when (not= expected-sm current-sm)
      (binding [*out* *err*]
        (println "State machine generated doc is stale:" state-machine-doc-path)
        (println "Run: clojure scripts/generate_state_machine_docs.clj"))
      (System/exit 1))
    (when (not= expected-cov current-cov)
      (binding [*out* *err*]
        (println "Transition coverage generated doc is stale:" transition-coverage-doc-path)
        (println "Run: clojure scripts/generate_state_machine_docs.clj"))
      (System/exit 1))
    (println "State machine docs are up to date.")))

(defn -main [& args]
  (if (= "--check" (first args))
    (check!)
    (generate!)))

(apply -main *command-line-args*)
