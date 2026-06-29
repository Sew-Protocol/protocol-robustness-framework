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
  "Look up a scenario's outcome in the evidence results list.
   Prefer explicit :scenario/id, then fall back to file-path matching."
  [scenario-id results]
  (let [match (or (first (filter #(= scenario-id (:scenario/id %)) results))
                  (first (filter #(str/includes? (:file %) scenario-id) results)))]
    (when match
      {:outcome (:outcome match)
       :halt-reason (:halt-reason match)
       :file (:file match)
       :scenario/evidence-root (:scenario/evidence-root match)})))

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
               :concept/maps-to (:concept/maps-to concept)
               :scenario/evidence-root (:scenario/evidence-root outcome-data)}))
          scenario-entries)))

;; ── Scoring classification ───────────────────────────────────────────────────

(defn- classify-per-scenario
  "Classify based on scenario outcome counts.
   Falls back to :unclassified when no scenarios were executed."
  [total passed rule]
  (let [{:keys [pass fail mixed score-fn]} rule]
    (cond
      (zero? total)
      {:classification-label "No scenarios executed"
       :scoring/summary (or score-fn "N/A")}

      (nil? total)
      {:classification-label "Unclassified — no scenario metrics"
       :scoring/summary (or score-fn "N/A")}

      (= total passed) {:classification-label pass  :scoring/summary (or score-fn "N/A")}
      (zero? passed)   {:classification-label fail  :scoring/summary (or score-fn "N/A")}
      :else            {:classification-label mixed :scoring/summary (or score-fn "N/A")})))

(defn- classify-per-claim
  "Classify based on claim results. Returns nil when claim-results are absent
   or empty — caller should fall back to per-scenario classification."
  [claim-results rule]
  (when (seq claim-results)
    (let [{:keys [pass fail mixed score-fn]} rule
          all-pass? (every? #(= :pass (:claim/outcome %)) claim-results)
          any-fail? (some #(= :fail (:claim/outcome %)) claim-results)]
      {:classification-label (cond all-pass? pass  any-fail? fail  :else mixed)
       :scoring/summary (or score-fn "N/A")})))

(defn- classify-critical
  "Classify based on severity-weighted claim results.
   Requires :critical-severity set in the scoring rule and non-nil claim-results.
   Returns nil when either is missing."
  [claim-results rule]
  (when (and (seq claim-results) (:critical-severity rule))
    (let [{:keys [pass fail mixed score-fn critical-severity]} rule
          all-pass? (every? #(= :pass (:claim/outcome %)) claim-results)
          critical-fail? (some (fn [cr]
                                 (and (= :fail (:claim/outcome cr))
                                      (contains? critical-severity
                                        (get-in cr [:claim/severity] :low))))
                               claim-results)]
      {:classification-label (cond
                               all-pass?    pass
                               critical-fail? fail
                               :else        mixed)
       :scoring/summary (or score-fn "N/A")})))

(defn classify-result
  "Apply the scoring rule's classifier to available evidence.
   Dispatches on (:classifier scoring-rules):
     :pass-fail-per-scenario  — uses scenario outcome counts
     :pass-fail-per-claim    — uses per-claim outcomes from claim-results
     :pass-fail-critical     — uses severity-weighted claim results
   Returns {:classification-label <string> :scoring/summary <string>}
   or {:classification-label \"Unclassified\" :reason <string>}."
  [total passed scoring-rules claim-results]
  (if-not (map? scoring-rules)
    {:classification-label "Unclassified" :reason "No scoring rules defined"}
    (let [{:keys [classifier score-fn] :as rule} scoring-rules]
      (case classifier
        :pass-fail-per-scenario
        (classify-per-scenario total passed rule)

        :pass-fail-per-claim
        (or (classify-per-claim claim-results rule)
            (classify-per-scenario total passed
              (assoc rule :pass "All scenarios pass" :fail "At least one scenario fails"
                           :mixed "Some scenarios pass, some fail")))

        :pass-fail-critical
        (or (classify-critical claim-results rule)
            {:classification-label "Unclassified"
             :reason (str "Cannot classify " (pr-str classifier)
                          " — claim-results or :critical-severity is missing")})

        {:classification-label "Unclassified"
         :reason (str "Unknown classifier: " (pr-str classifier))}))))

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
        dimensions (build-dimension-results results manifest concepts scoring)]
    {:benchmark/id (:benchmark/id manifest)
     :purpose (:benchmark/purpose manifest)
     :scenario/suite (:benchmark/scenario-suite manifest)
     :scenario/suite-description (:benchmark/scenario-suite-description manifest)
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
     :scoring/classification (classify-result (:total metrics) (:passed metrics)
                                               (:scoring/rules scoring)
                                               (:claim-results evidence))
     :claim/status (let [cr (:claim-results evidence)]
                     (cond
                       (seq cr)
                       (if (every? #(= :pass (:claim/outcome %)) cr)
                         :verified
                         :partial)
                       (seq (:benchmark/claims manifest))
                       :declared-not-verified
                       :else :none))
     :claim-results (:claim-results evidence)
     :dimensions dimensions
     :invariant-summary inv-summary
     :concept/section (:concept/section evidence)}))
