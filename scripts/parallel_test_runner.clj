(ns scripts.parallel-test-runner
  "Run Clojure test namespaces in parallel using future, each with isolated
   artifact directory to prevent evidence reconciliation warnings.
   Usage: clojure -M:test -m scripts.parallel-test-runner ns1 ns2 ns3"
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.config :as evcfg]))

(defn -main
  [& namespaces]
  (let [syms (map symbol namespaces)
        _ (doseq [s syms] (require s))
        start (System/currentTimeMillis)
        tmp-root (str (System/getProperty "java.io.tmpdir")
                      "/parallel-test-artifacts-" (java.util.UUID/randomUUID))
        futures (mapv (fn [sym]
                        (let [ns-artifact-dir (str tmp-root "/" (munge (str sym)))]
                          (.mkdirs (io/file ns-artifact-dir))
                          (future
                            (chain/with-fresh-registry*
                             (fn []
                               (ar/with-fresh-registry*
                                (fn []
                                  (binding [evcfg/*artifact-dir* ns-artifact-dir]
                                    (try (t/run-tests sym)
                                         (catch Exception e
                                           (println "ERROR in" sym ":" (.getMessage e))
                                           {:test 0 :pass 0 :fail 1 :error 1}))))))))))
                      syms)
        results (mapv deref futures)
        elapsed (- (System/currentTimeMillis) start)
        total {:test (apply + (map :test results))
               :pass (apply + (map :pass results))
               :fail (apply + (map :fail results))
               :error (apply + (map :error results))}]
    (println "\n┌─ Parallel test summary ─────────────────────────────────────┐")
    (println (format "│  %4d tests, %4d assertions, %d failures, %d errors  │"
                     (:test total) (:pass total) (:fail total) (:error total)))
    (println (format "│  elapsed: %.2fs                                          │"
                     (/ elapsed 1000.0)))
    (println "├─────────────────────────────────────────────────────────────┤")
    (println (format "│  artifact dirs: %s                                    " tmp-root))
    (println "└─────────────────────────────────────────────────────────────┘")
    (try
      (when (nil? (System/getenv "KEEP_PARALLEL_TEST_ARTIFACTS"))
        (let [root (io/file tmp-root)]
          (when (.exists root)
            (doseq [f (reverse (doall (file-seq root)))]
              (.delete f)))))
      (catch Exception e
        (println "WARN: artifact cleanup failed:" (.getMessage e))))
    (when (pos? (+ (:fail total) (:error total)))
      (System/exit 1))))