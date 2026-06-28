(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set])

(defn parse-edn [f]
  (try [(edn/read-string (slurp f)) nil]
       (catch Exception e [nil (str (.getMessage e))])))

(defn find-edn [path]
  (when (.exists (io/file path))
    path))

(defn run-validation []
  (println "▶ benchmarks:validate\n")
  (let [errors (atom [])]

    ;; 1. Validate benchmark pack
    (println "  Checking pack definition...")
    (let [pack-path "benchmarks/packs/prf-core/protocol-robustness-v0.edn"]
      (if-let [f (find-edn pack-path)]
        (let [[data parse-err] (parse-edn (io/file f))]
          (if parse-err
            (do (swap! errors conj (str pack-path ": " parse-err))
                (println "    FAIL" pack-path "-" parse-err))
            (do
              (println "    OK" pack-path)
              (let [id (:benchmark/id data)]
                (when (nil? id)
                  (swap! errors conj (str pack-path " missing :benchmark/id"))
                  (println "    FAIL" pack-path "missing :benchmark/id"))
                (when (nil? (:benchmark/scenario-suite data))
                  (swap! errors conj (str pack-path " missing :benchmark/scenario-suite"))
                  (println "    FAIL" pack-path "missing scenario suite"))
                (when (nil? (:benchmark/scoring-rule data))
                  (swap! errors conj (str pack-path " missing :benchmark/scoring-rule"))
                  (println "    FAIL" pack-path "missing scoring rule"))
                (when (nil? (:benchmark/concepts data))
                  (swap! errors conj (str pack-path " missing :benchmark/concepts"))
                  (println "    FAIL" pack-path "missing concepts"))
                (when (nil? (:benchmark/scenarios data))
                  (swap! errors conj (str pack-path " missing :benchmark/scenarios"))
                  (println "    FAIL" pack-path "missing scenarios"))
                (when (nil? (:benchmark/purpose data))
                  (swap! errors conj (str pack-path " missing :benchmark/purpose"))
                  (println "    FAIL" pack-path "missing purpose"))
                (when id
                  (println "    ID:" id))
                (doseq [s (:benchmark/scenarios data)]
                  (when (nil? (:scenario/id s))
                    (swap! errors conj (str pack-path " scenario missing :scenario/id"))
                    (println "    FAIL" pack-path "scenario missing id"))
                  (when (nil? (:dimension s))
                    (swap! errors conj (str pack-path " scenario " (:scenario/id s) " missing :dimension"))
                    (println "    FAIL" pack-path "scenario" (:scenario/id s) "missing dimension")))))))
        (do (swap! errors conj (str pack-path " not found"))
            (println "    FAIL" pack-path "not found"))))

    ;; 2. Validate concepts file
    (println "  Checking benchmark concepts...")
    (let [concepts-path "benchmarks/concepts/protocol-robustness-v0.edn"]
      (if-let [f (find-edn concepts-path)]
        (let [[data parse-err] (parse-edn (io/file f))]
          (if parse-err
            (do (swap! errors conj (str concepts-path ": " parse-err))
                (println "    FAIL" concepts-path "-" parse-err))
            (do
              (println "    OK" concepts-path)
              (doseq [c (:concepts data)]
                (doseq [k [:concept/id :concept/title :concept/summary
                           :concept/stakeholder-language :concept/maps-to
                           :concept/why-it-matters]]
                  (when (nil? (get c k))
                    (swap! errors conj (str concepts-path " concept " (:concept/id c) " missing " k))
                    (println "    FAIL" concepts-path "concept" (:concept/id c) "missing" k)))))))
        (do (swap! errors conj (str concepts-path " not found"))
            (println "    FAIL" concepts-path "not found"))))

    ;; 3. Validate scoring definition
    (println "  Checking scoring definition...")
    (let [scoring-path "benchmarks/scoring/robustness-dimensions-v0.edn"]
      (if-let [f (find-edn scoring-path)]
        (let [[data parse-err] (parse-edn (io/file f))]
          (if parse-err
            (do (swap! errors conj (str scoring-path ": " parse-err))
                (println "    FAIL" scoring-path "-" parse-err))
            (do
              (println "    OK" scoring-path)
              (when (nil? (:scoring/id data))
                (swap! errors conj (str scoring-path " missing :scoring/id"))
                (println "    FAIL" scoring-path "missing :scoring/id"))
              (when (nil? (:scoring/rules data))
                (swap! errors conj (str scoring-path " missing :scoring/rules"))
                (println "    FAIL" scoring-path "missing :scoring/rules")))))
        (do (swap! errors conj (str scoring-path " not found"))
            (println "    FAIL" scoring-path "not found"))))

    ;; 4. Validate that referenced scenarios exist in the suite
    (println "  Checking scenario references...")
    (let [suite-manifest "suites/reference-validation-v1/manifest.edn"]
      (if-let [f (find-edn suite-manifest)]
        (let [[data parse-err] (parse-edn (io/file f))]
          (if parse-err
            (do (swap! errors conj (str suite-manifest ": " parse-err))
                (println "    FAIL" suite-manifest "-" parse-err))
            (let [suite-scenarios (set (map :id (:scenarios data)))
                  wanted ["malicious-resolver-verdict-v1"
                          "dispute-flooding-v1"
                          "autopush-settlement-v1"]]
              (doseq [s wanted]
                (if (suite-scenarios s)
                  (println "    OK scenario" s "found in suite")
                  (do (swap! errors conj (str "scenario " s " not found in suite " suite-manifest))
                      (println "    FAIL scenario" s "not found in suite")))))))
        (do (swap! errors conj (str suite-manifest " not found"))
            (println "    FAIL" suite-manifest "not found"))))

    ;; 5. Validate concept IDs match
    (println "  Checking concept ID consistency...")
    (let [concepts-path "benchmarks/concepts/protocol-robustness-v0.edn"]
      (when-let [f (find-edn concepts-path)]
        (let [[data _] (parse-edn (io/file f))
              concept-ids (set (map :concept/id (:concepts data)))
              pack-path "benchmarks/packs/prf-core/protocol-robustness-v0.edn"]
          (when-let [pf (find-edn pack-path)]
            (let [[pdata _] (parse-edn (io/file pf))]
              (doseq [c (:benchmark/concepts pdata)]
                (if (concept-ids c)
                  (println "    OK concept" c "found in concepts file")
                  (do (swap! errors conj (str "concept " c " referenced by pack but not defined in concepts file"))
                      (println "    FAIL concept" c "referenced by pack but not defined")))))))))

    ;; 6. Check that :evidence references point to known evidence types
    (println "  Checking evidence references...")
    (let [concepts-path "benchmarks/concepts/protocol-robustness-v0.edn"]
      (when-let [f (find-edn concepts-path)]
        (let [[data _] (parse-edn (io/file f))]
          (doseq [c (:concepts data)]
            (let [evidence (:evidence (:concept/maps-to c))]
              (when (and evidence (not (vector? evidence)))
                (swap! errors conj (str "concept " (:concept/id c) " :maps-to :evidence should be a vector"))
                (println "    FAIL concept" (:concept/id c) ":evidence should be vector")))))))

    (println)
    (if (empty? @errors)
      (println "  OK all checks passed\n\nBENCHMARK VALIDATION PASSED")
      (do (println "  ERRORS:" (count @errors))
          (doseq [e @errors] (println "    -" e))
          (println "\nBENCHMARK VALIDATION FAILED")
          (System/exit 1)))))

(run-validation)
