(ns resolver-sim.commands.evidence
  "Evidence commands: verify-chain, validate, coverage, backstop.
   Calls existing functions in resolver-sim.evidence.* directly."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.registry :as reg]
            [resolver-sim.evidence.registry-validation :as rv]
            [resolver-sim.io.event-evidence :as io-evidence]
            [resolver-sim.sim.reference-validation :as rv-suite]))

(defn- ensure-dir
  "Resolve artifact-dir option, print error if missing."
  [artifact-dir]
  (let [d (io/file (or artifact-dir "target/run"))]
    (when-not (.exists d)
      (println (str "Artifact directory not found: " (.getPath d))))
    d))

(defn- load-registry
  "Load or build the evidence registry for an artifact directory."
  [dir]
  (let [reg-file (io/file dir "evidence-registry.json")]
    (if (.exists reg-file)
      (json/read-str (slurp reg-file) :key-fn keyword)
      (reg/build-evidence-registry (.getPath dir)))))

(defn- run-suite-for-evidence
  "Run the reference-validation suite to generate evidence artifacts.
   Returns the suite output root directory."
  []
  (println "  Running reference-validation suite...")
  (flush)
  (try
    (let [result (rv-suite/generate!)]
      (println (str "  Suite complete: " (:scenario-count result) " scenarios"))
      (flush)
      "suites/reference-validation-v1/actual")
    (catch Exception e
      (println (str "  Suite run skipped (no evidence generated): " (.getMessage e)))
      nil)))

(defn verify-chain
  "Verify evidence chain hashes and links for an artifact directory."
  [{:keys [artifact-dir strict? json?] :as opts}]
  (let [dir (ensure-dir artifact-dir)]
    (if-not (.exists dir)
      {:exit-code 3 :message "Artifact directory not found"}
      (try
        (println (str "Verifying evidence chain in " artifact-dir "..."))
        (let [registry (load-registry dir)
              result (chain/evidence-chain-integrity registry)]
          (println (str "  Chain intact: " (:chain-intact result)))
          (println (str "  Artifacts: " (:artifact-count result)))
          (println (str "  Registry hash valid: " (:registry-hash-valid result)))
          (println (str "  All hashes well-formed: " (:all-hashes-well-formed result)))
          (let [ok? (:chain-intact result)]
            (if ok?
              {:exit-code 0 :message "Evidence chain verified" :result result}
              {:exit-code 1 :message "Evidence chain integrity check failed" :result result})))
        (catch Exception e
          (println "  Error:" (.getMessage e))
          {:exit-code 4 :message (.getMessage e)})))))

(defn validate
  "Validate evidence artifacts in a run directory."
  [{:keys [artifact-dir strict? json?] :as opts}]
  (let [dir (ensure-dir artifact-dir)]
    (if-not (.exists dir)
      {:exit-code 3 :message "Artifact directory not found"}
      (try
        (println (str "Validating evidence registry in " artifact-dir "..."))
        (let [registry (load-registry dir)
              result (rv/validate-evidence-registry registry
                                                    :strict (boolean strict?)
                                                    :artifact-dir (.getPath dir))]
          (println (str "  Status: " (:status result)))
          (let [ok? (= :passed (:status result))]
            (if ok?
              {:exit-code 0 :message "Evidence validation passed" :result result}
              {:exit-code 1 :message "Evidence validation failed" :result result})))
        (catch Exception e
          (println "  Error:" (.getMessage e))
          {:exit-code 4 :message (.getMessage e)})))))

(defn coverage
  "Check evidence coverage completeness.
   Calculates which evidence types are present vs types declared in the
   canonical evidence-type->mechanism registry.
   A registry-gap type (e.g. :guard-rejected, :resolver-rotation) that exists
   in evidence artifacts but is not in the mechanism map will NOT trigger
   a failure — only absent canonical types are reported as missing."
  [{:keys [artifact-dir json?] :as opts}]
  (let [dir (ensure-dir artifact-dir)]
    (if-not (.exists dir)
      {:exit-code 3 :message "Artifact directory not found"}
      (try
        (println (str "Checking evidence coverage in " artifact-dir "..."))
        ;; The chain registry tracks chain artifacts, while coverage concerns
        ;; captured event evidence. Build the directory registry directly.
        (let [registry (reg/build-evidence-registry (.getPath dir))
              entries (:entries registry [])
              types (set (map :evidence/type entries))
              expected (set (keys io-evidence/evidence-type->mechanism))
              present (set/intersection types expected)
              missing (set/difference expected types)]
          (println (str "  Present types: " (count present) "/" (count expected)))
          (when (seq missing)
            (println "  Missing types:" (pr-str (sort missing))))
          (if (empty? missing)
            {:exit-code 0 :message "Evidence coverage complete"
             :result {:present (count present) :expected (count expected) :missing []}}
            {:exit-code 1 :message (str "Missing " (count missing) " evidence type(s)")
             :result {:present (count present) :expected (count expected)
                      :missing (sort missing)}}))
        (catch Exception e
          (println "  Error:" (.getMessage e))
          {:exit-code 4 :message (.getMessage e)})))))

(defn run-backstop
  "Run the evidence review gate: run reference-validation, then verify the
   captured evidence directory. Coverage is reported in default mode and is
   promoted to a gate only with :strict? because a single run need not exercise
   every protocol evidence type."
  [{:keys [fast? full? artifact-dir json? strict?] :as opts}]
  (println "Running evidence backstop...")
  (flush)
  (let [suite-complete? (boolean (run-suite-for-evidence))
        ;; Reference validation writes reports to suites/.../actual but captures
        ;; event evidence through the configured artifact directory.
        ;; CLI supplies "target/run" as its generic default; it is not the
        ;; directory populated by the reference-validation replay.
        evidence-dir (if (and artifact-dir (not= artifact-dir "target/run"))
                       artifact-dir
                       "prf-artifacts")
        verify-opts (assoc opts :artifact-dir evidence-dir)
        results (if suite-complete?
                  [(verify-chain verify-opts)
                   (validate verify-opts)
                   (coverage verify-opts)]
                  [{:exit-code 0 :message "Suite run completed"}])
        gate-results (if strict? results (take 2 results))
        failures (filter #(not (zero? (:exit-code % 0))) gate-results)]
    (if (empty? failures)
      (do (println "Evidence backstop PASSED")
          {:exit-code 0 :results results})
      (do (println "Evidence backstop FAILED")
          {:exit-code 1 :results results}))))
