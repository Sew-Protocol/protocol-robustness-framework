(ns scripts.parallel-test-runner
  "Run Clojure test namespaces in parallel using future.
   Usage: clojure -M:test -m scripts.parallel-test-runner ns1 ns2 ns3"
  (:require [clojure.test :as t]))

(defn -main
  [& namespaces]
  (let [syms (map symbol namespaces)
        _ (doseq [s syms] (require s))
        start (System/currentTimeMillis)
        futures (mapv (fn [sym] (future (t/run-tests sym))) syms)
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
    (println "└─────────────────────────────────────────────────────────────┘")
    (when (pos? (+ (:fail total) (:error total)))
      (System/exit 1))))
