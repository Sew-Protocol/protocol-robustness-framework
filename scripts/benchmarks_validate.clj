(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[resolver-sim.scenario.suites :as suites])

(defn parse-edn [f]
  (try [(edn/read-string (slurp f)) nil]
       (catch Exception e [nil (str (.getMessage e))])))

(defn file-exists? [path]
  (.exists (io/file path)))

(defn read-edn-file [path]
  (when (file-exists? path)
    (edn/read-string (slurp path))))

(defn scoring-path-for [scoring-id]
  (let [filename (case scoring-id
                   :scoring/robustness-dimensions-v0 "robustness-dimensions-v0.edn"
                   :scoring/binary-claims-v1 "binary-claims-v1.edn"
                   :scoring/severity-weighted-robustness-v1 "severity-weighted-v1.edn"
                   :scoring/severity-weighted-v1 "severity-weighted-v1.edn"
                   :scoring/shortfall-allocation-v0 "shortfall-allocation-v0.edn"
                   nil)]
    (when filename
      (str "benchmarks/scoring/" filename))))

(defn validate-file-exists! [errors path label]
  (if (file-exists? path)
    (println "    OK" label path)
    (do (swap! errors conj (str label " missing: " path))
        (println "    FAIL" label "missing:" path))))

(defn validate-reference-validation-manifest! [errors]
  (println "  Checking reference-validation manifest...")
  (let [[manifest parse-err] (parse-edn (io/file "suites/reference-validation-v1/manifest.edn"))]
    (if parse-err
      (do (swap! errors conj (str "suites/reference-validation-v1/manifest.edn: " parse-err))
          (println "    FAIL manifest -" parse-err))
      (let [by-id (set (map :id (:scenarios manifest)))
            by-path (set (map :simulator/scenario-path (:scenarios manifest)))]
        (doseq [id ["malicious-resolver-verdict-v1"
                    "dispute-flooding-v1"
                    "autopush-settlement-v1"]]
          (when-not (by-id id)
            (swap! errors conj (str "reference-validation manifest missing public scenario id " id))
            (println "    FAIL public scenario id missing:" id)))
        (doseq [path ["scenarios/S25_profit-maximizer-slash-lifecycle.json"
                      "scenarios/S62_resolver-throughput-exhaustion.json"
                      "scenarios/S05_pending-settlement-execute.json"]]
          (when-not (by-path path)
            (swap! errors conj (str "reference-validation manifest missing simulator path " path))
            (println "    FAIL simulator path missing:" path)))))))

(defn validate-benchmark-file! [errors benchmark-path benchmark]
  (println "    Checking benchmark..." benchmark-path)
  (validate-file-exists! errors benchmark-path "benchmark")

  (let [suite-key (:benchmark/scenario-suite benchmark)
        scoring-id (:benchmark/scoring-rule benchmark)]
    (when-not (suites/suite-paths suite-key)
      (swap! errors conj (str "unknown suite " suite-key " in " benchmark-path))
      (println "    FAIL unknown suite" suite-key))

    (when-let [scoring-path (scoring-path-for scoring-id)]
      (validate-file-exists! errors scoring-path "scoring"))

    (when-not scoring-id
      (swap! errors conj (str "missing scoring rule in " benchmark-path))
      (println "    FAIL missing scoring rule"))

    (when (= :benchmark/prf-protocol-robustness-v0 (:benchmark/id benchmark))
      (let [manifest (read-edn-file "suites/reference-validation-v1/manifest.edn")
            scenario-ids (set (map :id (:scenarios manifest)))]
        (doseq [scenario (:benchmark/scenarios benchmark)]
          (let [id (:scenario/id scenario)
                scenario-path (some->> (:scenarios manifest)
                                       (filter #(= id (:id %)))
                                       first
                                       :simulator/scenario-path)]
            (when-not (scenario-ids id)
              (swap! errors conj (str "public scenario id missing from reference-validation manifest: " id))
              (println "    FAIL public scenario id missing:" id))
            (when-not scenario-path
              (swap! errors conj (str "reference-validation manifest missing simulator path for public id " id))
              (println "    FAIL simulator path missing for public id:" id))))))))

(defn validate-pack-registry! [errors registry-path]
  (let [[data parse-err] (parse-edn (io/file registry-path))]
    (if parse-err
      (do (swap! errors conj (str registry-path ": " parse-err))
          (println "    FAIL" registry-path "-" parse-err))
      (let [pack-dir (.getParent (io/file registry-path))]
        (println "  Checking pack registry..." registry-path)
        (when (nil? (:pack/id data))
          (swap! errors conj (str registry-path " missing :pack/id"))
          (println "    FAIL" registry-path "missing :pack/id"))
        (doseq [benchmark-ref (:benchmarks data)]
          (let [benchmark-path (str pack-dir "/" (:benchmark/file benchmark-ref))]
            (validate-file-exists! errors benchmark-path "benchmark file")
            (when-let [benchmark (read-edn-file benchmark-path)]
              (validate-benchmark-file! errors benchmark-path benchmark))))))))

(defn run-validation []
  (println "▶ benchmarks:validate\n")
  (let [errors (atom [])]
    (println "  Checking benchmark registry...")
    (let [[registry parse-err] (parse-edn (io/file "benchmarks/registry.edn"))]
      (if parse-err
        (do (swap! errors conj (str "benchmarks/registry.edn: " parse-err))
            (println "    FAIL benchmarks/registry.edn -" parse-err))
        (doseq [pack (:packs registry)]
          (validate-file-exists! errors (str "benchmarks/" (:pack/registry pack)) "pack registry"))))

    (doseq [registry-path ["benchmarks/packs/prf-core/registry.edn"
                           "benchmarks/packs/sew/registry.edn"]]
      (validate-pack-registry! errors registry-path))

    (println "  Checking benchmark concepts...")
    (validate-file-exists! errors "benchmarks/concepts/protocol-robustness-v0.edn" "concepts")

    (println "  Checking scoring definitions...")
    (doseq [path ["benchmarks/scoring/robustness-dimensions-v0.edn"
                  "benchmarks/scoring/binary-claims-v1.edn"
                  "benchmarks/scoring/severity-weighted-v1.edn"
                  "benchmarks/scoring/shortfall-allocation-v0.edn"]]
      (validate-file-exists! errors path "scoring"))

    (validate-reference-validation-manifest! errors)

    (println)
    (if (empty? @errors)
      (println "  OK all checks passed\n\nBENCHMARK VALIDATION PASSED")
      (do (println "  ERRORS:" (count @errors))
          (doseq [e @errors] (println "    -" e))
          (println "\nBENCHMARK VALIDATION FAILED")
          (System/exit 1)))))

(run-validation)
