(ns resolver-sim.benchmark.runner
  (:require [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.hashing :as hashing]
            [resolver-sim.benchmark.adapter :as adapter]
             [resolver-sim.logging :as log]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.protocols.sew :as sew]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
         
         evidence {:benchmark manifest
                   :repo      repo-meta
                   :environment {:os-name (System/getProperty "os.name")
                                 :os-version (System/getProperty "os.version")
                                 :java-version (System/getProperty "java.version")}
                   :results   results
                   :metrics   metrics
                   :reproduce {:command (str "bb benchmark:reproduce " (or manifest-path "benchmarks/dispute-liveness.edn"))}}
         
         hashable-evidence (dissoc evidence :timestamp)
         evidence-hash (hashing/hash-evidence hashable-evidence)
         final-evidence (assoc evidence :evidence/hash evidence-hash)]
     
     (when-not passed?
        (log/warn! "benchmark/failed" {:passed (:passed metrics) :total (:total metrics)})
       (println "Benchmark FAILED:" (:passed metrics) "/" (:total metrics) "passed."))
     
     final-evidence)))

(defn write-evidence [evidence output-path]
  (io/make-parents output-path)
  (spit output-path (pr-str evidence))
  (log/info! "benchmark/evidence-written" {:output-path output-path})
  (println "Evidence bundle written to:" output-path))
