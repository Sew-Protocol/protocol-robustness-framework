(ns resolver-sim.commands.benchmark
  "Benchmark validation commands.
   Port of scripts/benchmarks_validate.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- validate-packs
  "Core validation. Returns {:errors []}."
  [errors registry-file]
  (try
    (let [registry (edn/read-string (slurp registry-file))
          packs (:packs registry [])]
      (when (empty? packs)
        (swap! errors conj "No benchmark packs registered"))
      (doseq [pack packs]
        (let [pid (:pack/id pack)]
          (doseq [k [:pack/id :pack/description :pack/registry]]
            (when (nil? (get pack k))
              (swap! errors conj (str "Pack " (or pid "<unnamed>") " missing " (name k)))))
          (when-let [reg-path (:pack/registry pack)]
            (let [pf (io/file "benchmarks" reg-path)]
              (when-not (.exists pf)
                (swap! errors conj (str "Pack " (or pid "<unnamed>") " registry not found: " reg-path))))))))
    (catch Exception e
      (swap! errors conj (str "Registry parse failed: " (.getMessage e))))))

(defn validate
  "Validate benchmark pack definitions and referenced resources."
  [{:keys [json?] :as opts}]
  (println "Validating benchmarks...")
  (let [errors (atom [])
        registry-file (io/file "benchmarks/registry.edn")]
    (if-not (.exists registry-file)
      (println "  Benchmark registry not found: benchmarks/registry.edn")
      (validate-packs errors registry-file))
    (let [exit-code (if (empty? @errors) 0 1)]
      (doseq [e @errors] (println (str "  ✗ " e)))
      (println (str "  " (count @errors) " error(s)"))
      {:exit-code exit-code
       :message (if (zero? exit-code)
                  "Benchmark validation passed"
                  "Benchmark validation failed")
       :errors @errors})))
