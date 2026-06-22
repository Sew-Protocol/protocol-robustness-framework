(ns resolver-sim.benchmark.runner
  (:require [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.protocols.sew :as sew]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- find-scenarios-in-suites [suites]
  (mapcat (fn [suite-path]
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

(defn- load-scenario [path]
  (if (.endsWith path ".json")
    (io-sc/load-scenario-file path)
    (edn/read-string (slurp path))))

(defrecord SewAdapter []
  adapter/RepositoryAdapter
  (load-scenarios [_ benchmark]
    (find-scenarios-in-suites (:scenario-suites benchmark)))

  (execute-benchmark [_ benchmark scenarios]
    (mapv (fn [scenario-file]
            (let [path (.getPath scenario-file)
                  scenario (load-scenario path)
                  result   (sew/replay-with-sew-protocol scenario)]
              {:file path
               :outcome (:outcome result)
               :halt-reason (:halt-reason result)
               :metrics (:metrics result)
               :invariant-results (get-in result [:metrics :invariant-results] {})}))
          scenarios))

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

          evidence {:benchmark      manifest
                    :repo           repo-meta
                    :environment    {:os-name (System/getProperty "os.name")
                                    :os-version (System/getProperty "os.version")
                                    :java-version (System/getProperty "java.version")}
                    :results        results
                    :metrics        metrics
                    :reproduce      {:command (str "bb benchmark:reproduce " (or manifest-path "benchmarks/dispute-liveness.edn"))}
                    :invariant-summary {:per-invariant  inv-summary
                                        :total-checks   total-inv-checks
                                        :passed-checks  passed-inv-checks
                                        :all-pass?      all-invariants-pass?}}

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
