(ns resolver-sim.benchmark.report
  "Build a report-ready data structure from a benchmark evidence bundle,
   benchmark concepts, and scoring definition.
   
   Produces a plain map suitable for Clerk rendering — no view logic."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── Data loading ──────────────────────────────────────────────────────────────

(defn load-edn
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (edn/read-string (slurp f))
      (throw (ex-info (str "File not found: " path) {:path path})))))

(defn load-evidence
  [path]
  (load-edn path))

(defn load-benchmark-concepts
  [path]
  (:concepts (load-edn path)))

(defn load-scoring
  [path]
  (load-edn path))

;; ── Concept lookup ───────────────────────────────────────────────────────────

(defn concept-index
  "Build a dimension-ID → concept map from benchmark concepts."
  [concepts]
  (into {} (map (fn [c] [(:concept/id c) c]) concepts)))

(defn dimension->concept
  "Look up the concept definition for a robustness dimension keyword."
  [concept-idx dim-id]
  (get concept-idx dim-id))

;; ── Dimension results ────────────────────────────────────────────────────────

(defn scenario->outcome
  "Look up a scenario's outcome in the evidence results list by file path.
   The mapping is heuristic: scenario-id must appear in the path string."
  [scenario-id results]
  (let [match (first (filter #(str/includes? (:file %) scenario-id) results))]
    (when match
      {:outcome (:outcome match)
       :halt-reason (:halt-reason match)
       :file (:file match)})))

(defn build-dimension-results
  "For each dimension/scenario entry in the benchmark manifest, find the
   actual scenario outcome from the evidence results and merge concept data.
   
   evidence-results — vector of scenario result maps from the evidence bundle
   manifest         — the benchmark manifest map
   concepts         — vector of benchmark concept definitions
   scoring          — the scoring definition map
   
   Returns a vector of dimension result maps."
  [evidence-results manifest concepts scoring]
  (let [scenario-entries (:benchmark/scenarios manifest)
        concept-idx (concept-index concepts)
        dimension-rules (->> scoring :scoring/rules :dimensions
                             (map (fn [d] [(:dimension/id d) d]))
                             (into {}))]
    (mapv (fn [entry]
            (let [dim-id (:dimension entry)
                  scenario-id (:scenario/id entry)
                  outcome-data (scenario->outcome scenario-id evidence-results)
                  concept (dimension->concept concept-idx dim-id)
                  scoring-rule (get dimension-rules dim-id)]
              {:dimension dim-id
               :scenario/id scenario-id
               :outcome (:outcome outcome-data)
               :halt-reason (:halt-reason outcome-data)
               :pass-condition (:pass-condition scoring-rule)
               :pass-condition-met? (= :pass (:outcome outcome-data))
               :concept/title (:concept/title concept)
               :concept/summary (:concept/summary concept)
               :concept/stakeholder-language (:concept/stakeholder-language concept)
               :concept/why-it-matters (:concept/why-it-matters concept)
               :concept/maps-to (:concept/maps-to concept)}))
          scenario-entries)))

;; ── Report assembly ──────────────────────────────────────────────────────────

(defn build-report
  "Build a complete report data structure from:
     evidence-path    — EDN evidence bundle produced by bb benchmark:run
     concepts-path    — benchmark concepts EDN (e.g. benchmarks/concepts/protocol-robustness-v0.edn)
     scoring-path     — scoring definition EDN (e.g. benchmarks/scoring/robustness-dimensions-v0.edn)
   
   Returns a plain map suitable for Clerk rendering."
  [evidence-path concepts-path scoring-path]
  (let [evidence (load-evidence evidence-path)
        manifest (:benchmark evidence)
        concepts (load-benchmark-concepts concepts-path)
        scoring (load-scoring scoring-path)
        metrics (:metrics evidence)
        results (:results evidence)
        inv-summary (:invariant-summary evidence)
        ev-hash (:evidence/hash evidence)
        dimensions (mapv #(assoc % :evidence/ref ev-hash)
                         (build-dimension-results results manifest concepts scoring))]
    {:benchmark/id (:benchmark/id manifest)
     :purpose (:benchmark/purpose manifest)
     :evidence/path evidence-path
     :evidence/hash (:evidence/hash evidence)
     :environment (:environment evidence)
     :reproduce (:reproduce evidence)
     :total-scenarios (:total metrics)
     :passed-scenarios (:passed metrics)
     :all-pass? (and (pos? (:total metrics))
                     (= (:total metrics) (:passed metrics)))
     :score (if (pos? (:total metrics))
              (float (/ (:passed metrics) (:total metrics)))
              0.0)
     :dimensions dimensions
     :invariant-summary inv-summary
     :concept/section (:concept/section evidence)}))
