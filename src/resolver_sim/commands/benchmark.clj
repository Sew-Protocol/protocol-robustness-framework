(ns resolver-sim.commands.benchmark
  "Benchmark validation commands.
   Port of scripts/benchmarks_validate.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.coverage :as coverage]))

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

(defn- read-edn-file [file]
  (edn/read-string (slurp file)))

(defn- validate-active-manifests
  "Apply the lifecycle invariant used by the public benchmark catalogue.
   This deliberately checks runnable evaluator dispatch, not just EDN shape."
  [errors]
  (try
    (let [claim-registry (read-edn-file "benchmarks/claim-registry.edn")
          known-claim-ids (set (map :claim/id (:claims claim-registry)))
          registry (read-edn-file "benchmarks/registry.edn")]
      (doseq [pack (:packs registry)
              :let [pack-file (io/file "benchmarks" (:pack/registry pack))
                    pack-registry (read-edn-file pack-file)
                    pack-dir (.getParent pack-file)]
              benchmark-ref (:benchmarks pack-registry)
              :when (= :active (:benchmark/status benchmark-ref))]
        (let [manifest-file (io/file pack-dir (:benchmark/file benchmark-ref))
              manifest (read-edn-file manifest-file)
              manifest-status (:benchmark/status manifest)]
          (when (not= :active manifest-status)
            (swap! errors conj (str "Active benchmark " (:benchmark/id benchmark-ref)
                                    " must declare :benchmark/status :active in its manifest")))
          (doseq [violation (coverage/active-benchmark-errors manifest known-claim-ids)]
            (swap! errors conj (str "Active benchmark " (:benchmark/id benchmark-ref)
                                    " lifecycle violation " violation))))))
    (catch Exception e
      (swap! errors conj (str "Active benchmark lifecycle validation failed: " (.getMessage e))))))

(defn validate
  "Validate benchmark pack definitions and referenced resources."
  [{:keys [json?] :as opts}]
  (println "Validating benchmarks...")
  (let [errors (atom [])
        registry-file (io/file "benchmarks/registry.edn")]
    (if-not (.exists registry-file)
      (println "  Benchmark registry not found: benchmarks/registry.edn")
      (validate-packs errors registry-file))
    (when (.exists registry-file)
      (validate-active-manifests errors))
    (let [exit-code (if (empty? @errors) 0 1)]
      (doseq [e @errors] (println (str "  ✗ " e)))
      (println (str "  " (count @errors) " error(s)"))
      {:exit-code exit-code
       :message (if (zero? exit-code)
                  "Benchmark validation passed"
                  "Benchmark validation failed")
       :errors @errors})))
