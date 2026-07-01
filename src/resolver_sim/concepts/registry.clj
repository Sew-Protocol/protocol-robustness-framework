(ns resolver-sim.concepts.registry
  "Load and validate concept metadata from data/concepts/.

   Phase 1 scope:
   - Load registry index and individual concept definitions.
   - Validate concept IDs, required fields, and file references.
   - Provide lookup functions for report enrichment.

   No protocol execution changes, no scenario generation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.logging :as log]))

;; ── Registry loading ─────────────────────────────────────────────────────────

(def ^:private concept-registry-path "data/concepts/registry.edn")

(def required-concept-keys
  #{:concept/id :concept/name :concept/summary
    :concept/stakeholder-question :concept/protocols
    :concept/roles :concept/entities :concept/actions
    :concept/outcomes :concept/failure-modes
    :concept/metrics :concept/assumptions
    :concept/out-of-scope})

(defn- load-edn
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (edn/read-string (slurp f))
      (throw (ex-info (str "Concept file not found: " path) {:path path})))))

(defn- resolve-concept-file
  "Resolve a concept file path. Paths are relative to the project root."
  [concept-entry]
  (let [rel-path (:concept/file concept-entry)
        f (io/file rel-path)]
    (if (.exists f)
      (.getAbsolutePath f)
      (throw (ex-info (str "Concept file not found: " rel-path)
                      {:concept-id (:concept/id concept-entry)
                       :path rel-path})))))

(defn- validate-concept
  "Validate a single concept definition."
  [concept]
  (let [id (:concept/id concept)
        missing (set/difference required-concept-keys
                                (set (keys concept)))]
    (when (seq missing)
      (log/warn! "concept/missing-keys" {:concept-id id :missing missing}))
    ;; Validate role maps-to references
    (doseq [[role-key role] (:concept/roles concept)]
      (when-not (vector? (:maps-to role))
        (log/warn! "concept/invalid-maps-to"
                   {:concept-id id :role role-key
                    :maps-to (:maps-to role)})))
    concept))

;; ── Public API ───────────────────────────────────────────────────────────────

(def load-registry
  "Load the concept registry and all referenced concept definitions.
   Returns {:registry <registry-map> :concepts <vec-of-concept-maps>}.
   Cached after first load — disk is read once per process."
  (let [cache (atom nil)]
    (fn load-registry*
      ([] (load-registry* concept-registry-path))
      ([registry-path]
       (if-let [cached @cache]
         cached
         (let [result (let [registry (load-edn registry-path)
                            concepts (mapv (fn [entry]
                                             (let [path (resolve-concept-file entry)
                                                   concept (load-edn path)]
                                               (validate-concept concept)))
                                           (:concepts registry))]
                        (log/info! "concepts/loaded" {:count (count concepts)
                                                      :ids (mapv :concept/id concepts)})
                        {:registry registry
                         :concepts concepts})]
           (reset! cache result)
           result))))))

(defn lookup-concept
  "Find a concept definition by qualified keyword."
  [concepts id]
  (first (filter #(= (:concept/id %) id) concepts)))

(defn concept-index
  "Build a concept-id -> concept map."
  [concepts]
  (into {} (map (fn [concept] [(:concept/id concept) concept]) concepts)))

(defn concept-ids
  "Return all registered concept IDs."
  [concepts]
  (mapv :concept/id concepts))

(defn concepts-for-protocol
  "Return concept definitions that support a given protocol."
  [concepts protocol-id]
  (filter #(contains? (:concept/protocols %) protocol-id) concepts))

(defn missing-related-concepts
  "Return unresolved :concept/related references as
   {:from <concept-id> :to <related-concept-id>} maps."
  [concepts]
  (let [known-ids (set (concept-ids concepts))]
    (mapcat (fn [concept]
              (keep (fn [related-id]
                      (when-not (contains? known-ids related-id)
                        {:from (:concept/id concept)
                         :to related-id}))
                    (:concept/related concept)))
            concepts)))
