(ns resolver-sim.benchmark.runner
  (:require [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.concepts.registry :as concepts-registry]
            [resolver-sim.concepts.reporting :as concepts-reporting]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariants :as sew-inv]
            [resolver-sim.scenario.suites :as suites]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- find-scenarios-in-suites [suites]
  (mapcat (fn [suite-path]
            (let [dir (io/file suite-path)
                  scenario-dir (io/file dir "scenarios")
                  search-dir (if (.exists scenario-dir) scenario-dir dir)]
              (if (.isDirectory search-dir)
                (filter #(let [name (.getName %)]
                           (and (or (.endsWith name ".json")
                                    (.endsWith name ".edn"))
                                (not (str/includes? (.getPath %) "expected"))
                                (not (str/includes? (.getPath %) "results"))))
                        (file-seq search-dir))
                [])))
          suites))

(defn- resolve-suite-scenarios
  "Resolve a :suite/ keyword from the suite registry to a list of
   java.io.File objects for benchmark execution.
   Returns empty vector if the suite is unknown or has no scenarios."
  [suite-kw]
  (let [paths (suites/suite-paths suite-kw)]
    (mapv io/file paths)))

(defn- load-scenario [path]
  (io-sc/load-scenario-file path))

(defn- reference-validation-id-by-path
  [scenario-path]
  (try
    (let [manifest (edn/read-string (slurp "suites/reference-validation-v1/manifest.edn"))
          scenarios (:scenarios manifest)]
      (some (fn [scenario]
              (when (= scenario-path (:simulator/scenario-path scenario))
                (:id scenario)))
            scenarios))
    (catch Exception _ nil)))

(defn- benchmark-public-scenario-id
  [suite-kw scenario-path]
  (when (= suite-kw :suite/reference-validation-v1)
    (reference-validation-id-by-path scenario-path)))

(defn- benchmark-local-concept-namespace?
  [concept-id]
  (contains? #{"robustness" "allocation"} (namespace concept-id)))

(defn- benchmark-concept-files
  []
  (let [root (io/file "benchmarks/concepts")]
    (when (.exists root)
      (->> (file-seq root)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))))))

(defn- load-benchmark-local-concepts
  []
  (try
    (->> (benchmark-concept-files)
         (mapcat (fn [f]
                   (try
                     (:concepts (edn/read-string (slurp f)))
                     (catch Exception e
                       (log/warn! "benchmark/local-concepts-load-failed"
                                  {:path (.getPath f)
                                   :error (.getMessage e)})
                       []))))
         vec)
    (catch Exception e
      (log/warn! "benchmark/local-concepts-load-failed"
                 {:error (.getMessage e)})
      [])))

(defn- benchmark-local-concept-summary
  [concept]
  {:concept/id (:concept/id concept)
   :concept/name (or (:concept/name concept) (:concept/title concept))
   :concept/title (:concept/title concept)
   :concept/summary (:concept/summary concept)
   :concept/stakeholder-question (or (:concept/stakeholder-question concept)
                                     (:concept/stakeholder-language concept))
   :concept/stakeholder-language (:concept/stakeholder-language concept)
   :concept/assumptions (or (:concept/assumptions concept) [])
   :concept/out-of-scope (or (:concept/out-of-scope concept) [])
   :concept/why-it-matters (:concept/why-it-matters concept)
   :concept/maps-to (:concept/maps-to concept)
   :concept/failure-modes (:concept/failure-modes concept)})

(defn- benchmark-local-concept-section
  [concepts]
  (when (seq concepts)
    {:concept/summaries (mapv benchmark-local-concept-summary concepts)}))

(defn- merge-concept-sections
  [& sections]
  (let [sections (remove nil? sections)
        summaries (vec (mapcat :concept/summaries sections))
        risk-annotations (vec (mapcat :risk-annotations sections))]
    (when (or (seq summaries) (seq risk-annotations))
      (cond-> {:concept/summaries summaries}
        (seq risk-annotations) (assoc :risk-annotations risk-annotations)))))

(defrecord SewAdapter []
  adapter/RepositoryAdapter
  (load-scenarios [_ benchmark]
    (if-let [suite-kw (:benchmark/scenario-suite benchmark)]
      (resolve-suite-scenarios suite-kw)
      (find-scenarios-in-suites (:scenario-suites benchmark))))

  (execute-benchmark [_ _benchmark scenarios]
    (let [suite-kw (:benchmark/scenario-suite _benchmark)]
      (mapv (fn [scenario-file]
              (let [path (.getPath scenario-file)
                    scenario (load-scenario path)
                    result   (sew/replay-with-sew-protocol scenario
                                                           {:allow-dirty? true})
                    public-id (benchmark-public-scenario-id suite-kw path)
                    scenario-evidence (hc/hash-with-intent
                                       {:hash/intent :evidence-content}
                                       (select-keys result
                                                    [:events-processed :outcome :halt-reason]))
                  ;; Post-hoc invariant check: run check-all on final world, then
                  ;; merge with any per-step failures from the replay metrics.
                    final-world (:world result)
                    step-failures (get-in result [:metrics :invariant-results] {})
                    all-inv-ids (sort sew-inv/canonical-ids)
                    post-check (when final-world
                                 (:results (sew-inv/check-all final-world)))
                    inv-results (mapv (fn [id]
                                        {:id id
                                         :result (cond
                                                   (contains? step-failures id) :fail
                                                   (get post-check id) :pass
                                                   (false? (get-in post-check [id :holds?])) :fail
                                                   :else :pass)})
                                      all-inv-ids)]
                {:file path
                 :scenario/id public-id
                 :simulator/scenario-path path
                 :outcome (:outcome result)
                 :halt-reason (:halt-reason result)
                 :metrics (:metrics result)
                 :invariant-results inv-results
                 :scenario/evidence-root scenario-evidence}))
            scenarios)))

  (collect-metrics [_ results]
    {:total (count results)
     :passed (count (filter #(= :pass (:outcome %)) results))}))

(def default-adapter (->SewAdapter))

(defn load-manifest [path]
  (if (.exists (io/file path))
    (edn/read-string (slurp path))
    (throw (ex-info "Benchmark manifest not found" {:path path}))))

(defn run-benchmark
  ([manifest-path] (run-benchmark manifest-path default-adapter))
  ([manifest-path adapter]
   (let [manifest (load-manifest manifest-path)
         repo-meta (repo/metadata)
         scenarios (adapter/load-scenarios adapter manifest)
         _ (do
             (println "Executing" (count scenarios) "scenarios...")
             (log/info! "benchmark/execute" {:scenario-count (count scenarios)
                                             :manifest manifest-path}))
         results (adapter/execute-benchmark adapter manifest scenarios)

         metrics (adapter/collect-metrics adapter results)
         passed? (= (:total metrics) (:passed metrics))

          ;; Aggregate invariant summary across all scenarios
         all-inv-results (mapcat :invariant-results results)
         seen-ids (into #{} (map :id) all-inv-results)
         id->passes (fn [id] (filter #(and (= id (:id %)) (= :pass (:result %))) all-inv-results))
         id->total  (fn [id] (count (filter #(= id (:id %)) all-inv-results)))
         inv-summary (into {}
                           (map (fn [id]
                                  [id {:passed (count (id->passes id))
                                       :total  (id->total id)}]))
                           seen-ids)
         total-inv-checks (count all-inv-results)
         passed-inv-checks (count (filter #(= :pass (:result %)) all-inv-results))
         all-invariants-pass? (= total-inv-checks passed-inv-checks)

            ;; ── Claim evaluation ────────────────────────────────────────────
         claim-results (try
                         (benchmark-claims/evaluate-manifest-claims manifest results)
                         (catch Exception e
                           (log/warn! "benchmark/claim-evaluation-failed"
                                      {:error (.getMessage e)})
                           []))

            ;; ── Concept enrichment ──────────────────────────────────────────
         concept-ids (:benchmark/concepts manifest)
         concept-section (when (seq concept-ids)
                           (try
                             (let [{:keys [concepts]} (concepts-registry/load-registry)
                                   local-concepts (load-benchmark-local-concepts)
                                   global-by-id (into {} (map (fn [c] [(:concept/id c) c]) concepts))
                                   local-by-id (into {} (map (fn [c] [(:concept/id c) c]) local-concepts))
                                   local-ids (set (keys local-by-id))
                                   global-relevant (keep global-by-id (remove local-ids concept-ids))
                                   local-relevant (keep local-by-id concept-ids)
                                   benchmark-local (remove (set (concat (keys global-by-id) (keys local-by-id)))
                                                           concept-ids)
                                   global-section (when (seq global-relevant)
                                                    (:concept/section
                                                     (concepts-reporting/enrich-report nil global-relevant)))
                                   local-section (benchmark-local-concept-section local-relevant)]
                               (when (and (seq benchmark-local)
                                          (not-every? benchmark-local-concept-namespace?
                                                      benchmark-local))
                                 (log/warn! "benchmark/unknown-concepts" {:stale benchmark-local}))
                               (merge-concept-sections global-section local-section))
                             (catch Exception e
                               (log/warn! "benchmark/concept-enrichment-failed"
                                          {:error (.getMessage e)})
                               nil)))

         evidence {:benchmark      manifest
                   :repo           repo-meta
                   :environment    {:os-name (System/getProperty "os.name")
                                    :os-version (System/getProperty "os.version")
                                    :java-version (System/getProperty "java.version")}
                   :results        results
                   :metrics        metrics
                   :claim-results  claim-results
                   :reproduce      {:command (str "bb benchmark:reproduce " (or manifest-path "benchmarks/packs/sew/escrow-dispute-v1.edn"))}
                   :invariant-summary {:per-invariant  inv-summary
                                       :total-checks   total-inv-checks
                                       :passed-checks  passed-inv-checks
                                       :all-pass?      all-invariants-pass?}
                   :concept/section concept-section}

         hashable-evidence (dissoc evidence :timestamp)
         bundle-root-hash (hc/hash-with-intent {:hash/intent :bundle-root} hashable-evidence)

           ;; Certification artifact using :benchmark-certification intent
         certification {:benchmark-id      (or (:id manifest) "unknown")
                        :scenario-count    (:total metrics)
                        :all-invariants-pass all-invariants-pass?
                        :final-state-hash  nil  ;; filled after scenario completes
                        :evidence-chain-root nil  ;; filled by evidence chain
                        :invariant-summary inv-summary}
         cert-hash (hc/hash-with-intent {:hash/intent :benchmark-certification} certification)

         final-evidence (assoc evidence
                               :evidence/hash bundle-root-hash
                               :benchmark-certification (assoc certification
                                                               :certification-hash cert-hash))]

     (when-not passed?
       (log/warn! "benchmark/failed" {:passed (:passed metrics) :total (:total metrics)})
       (println "Benchmark FAILED:" (:passed metrics) "/" (:total metrics) "passed."))

     final-evidence)))

(defn write-evidence [evidence output-path]
  (io/make-parents output-path)
  (spit output-path (pr-str evidence))
  (log/info! "benchmark/evidence-written" {:output-path output-path})
  (println "Evidence bundle written to:" output-path))
