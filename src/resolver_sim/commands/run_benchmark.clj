(ns resolver-sim.commands.run-benchmark
  "Run a benchmark by ID or manifest path.
   Invoked by `prf.jar run-benchmark <benchmark-id>`.")

(defn run
  "Run a benchmark by ID or manifest path.
   Options:
     :benchmark-id — the benchmark ID string (from cmd/args)
     :output       — output path for evidence bundle
     :key          — path to private key for signing
     :json?        — when true, output as JSON
     :cmd/args     — positional args (first is benchmark-id)"
  [{:keys [output key] :as opts}]
  (let [cmd-args (:cmd/args opts)
        benchmark-id (or (first cmd-args)
                         (when (:benchmark-id opts) (:benchmark-id opts))
                         (when-let [s (:benchmark opts)] s))]
    (if-not benchmark-id
      (do (println "Usage: prf.jar run-benchmark <benchmark-id>")
          (println "  e.g. prf.jar run-benchmark sew/escrow-dispute-v1")
          2)
      (do
        (println "Running benchmark...")
        (println (str "  benchmark: " benchmark-id))
        (println (str "  output: " (or output "<default>")))
        (flush)
        (let [benchmark-runner (requiring-resolve 'resolver-sim.benchmark.cli/run-and-report)
              result (benchmark-runner benchmark-id
                                       {:output output
                                        :key key})]
          (or (:exit-code result) 0))))))
