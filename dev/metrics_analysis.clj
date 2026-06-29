(ns metrics-analysis
  "Example of analyzing test run metrics for parallel optimization."
  (:require [dev.scenarios :as scenarios]
            [res (ns dev.metrics-analysis) olver-sim.monitoring :as monitoring]
            [resolver-sim.monitoring.metrics :as metrics]
            [dev.repl :as repl]))

(defn analyze-scenario-timing
  "Run a scenario and analyze its timing metrics."
  [scenario-name]
  (println "\n=== Analyzing Scenario:" scenario-name "===")

  ;; Reset metrics for clean analysis
  (when (metrics/get-counter [:scenario :completed])
    (metrics/gauge! [:analysis :start-time] (System/currentTimeMillis)))

  ;; Run the scenario
  (let [start-time (System/currentTimeMillis)
        result (scenarios/run-scenario scenario-name)
        end-time (System/currentTimeMillis)
        duration (- end-time start-time)]

    (println "Scenario completed in" duration "ms")
    (println "Outcome:" (:outcome result))

    ;; Record timing
    (metrics/timing-metric duration [:scenario :duration])
    (metrics/increment-metric [:scenario :completed])

    ;; Get current metrics
    (def all-metrics (metrics/get-all-metric-names))
    (def timing-stats (metrics/get-histogram-stats [:scenario :duration]))
    (def completed (metrics/get-counter [:scenario :completed]))
    (def failed (metrics/get-counter [:scenario :failed]))

    (println "\nCurrent Metrics:")
    (println "  Completed scenarios:" completed)
    (println "  Failed scenarios:" failed)
    (println "  Duration stats:" timing-stats)

    ;; Return analysis
    {:scenario scenario-name
     :duration duration
     :outcome (:outcome result)
     :metrics {:completed completed
               :failed failed
               :timing timing-stats
               :all-metrics all-metrics}}))

(defn analyze-parallel-performance
  "Analyze performance of running multiple scenarios in parallel."
  []
  (println "\n=== Parallel Performance Analysis ===")

  ;; Reset metrics
  (metrics/gauge! [:analysis :start-time] (System/currentTimeMillis))

  ;; Run multiple scenarios
  (let [scenarios-to-run ["S01 baseline-happy-path"
                          "S02 dispute-timeout"
                          "S03 refund-after-timeout"]
        start-time (System/currentTimeMillis)
        results (mapv #(try
                         (scenarios/run-scenario %)
                         (catch Exception e
                           {:scenario % :error (.getMessage e)}))
                      scenarios-to-run)
        end-time (System/currentTimeMillis)
        total-duration (- end-time start-time)
        avg-duration (/ total-duration (count scenarios-to-run))]

    (println "\nParallel Execution Results:")
    (println "  Total time:" total-duration "ms")
    (println "  Average per scenario:" avg-duration "ms")
    (println "  Scenarios run:" (count scenarios-to-run))

    ;; Analyze thread pool utilization
    (when-let [utilization (try
                             ((requiring-resolve 'resolver-sim.monitoring.thread-pools/get-average-utilization))
                             (catch Exception _ nil))]
      (println "  Thread pool utilization:" utilization)
      (when (> utilization 0.8)
        (println "  ⚠️  High utilization - consider increasing thread pool size")))

    ;; Get final metrics
    (def final-metrics {:total-duration total-duration
                        :average-duration avg-duration
                        :scenario-count (count scenarios-to-run)
                        :completed (metrics/get-counter [:scenario :completed])
                        :failed (metrics/get-counter [:scenario :failed])
                        :timing-stats (metrics/get-histogram-stats [:scenario :duration])})

    (tap> final-metrics)
    final-metrics))

(defn find-optimization-opportunities
  "Identify potential optimizations based on current metrics."
  []
  (println "\n=== Optimization Opportunities ===")

  (let [utilization (try
                      ((requiring-resolve 'resolver-sim.monitoring.thread-pools/get-average-utilization))
                      (catch Exception _ 0.0))
        bottlenecks (try
                      ((requiring-resolve 'resolver-sim.monitoring.thread-pools/detect-bottlenecks))
                      (catch Exception _ []))
        avg-duration (get-in (metrics/get-histogram-stats [:scenario :duration]) [:mean] 0)
        completed (metrics/get-counter [:scenario :completed] 0)
        failed (metrics/get-counter [:scenario :failed] 0)]

    (println "\nCurrent Performance:")
    (println "  Average scenario duration:" (format "%.1f" avg-duration) "ms")
    (println "  Completed scenarios:" completed)
    (println "  Failed scenarios:" failed)
    (println "  Thread pool utilization:" (format "%.1f" (* 100 utilization)) "%")
    (println "  Bottlenecks detected:" (count bottlenecks))

    (println "\nOptimization Recommendations:")

    (when (> utilization 0.8)
      (println "  🔧 Increase thread pool size - utilization is high (" (format "%.1f" (* 100 utilization)) "%)"))

    (when (> (count bottlenecks) 0)
      (println "  ⚠️  Address bottlenecks:" (pr-str bottlenecks)))

    (when (> avg-duration 1000)
      (println "  ⏱️  Optimize slow scenarios - average duration" (format "%.1f" avg-duration) "ms"))

    (when (> failed 0)
      (let [error-rate (float (/ failed completed))]
        (when (> error-rate 0.05)
          (println "  🚨  High error rate:" (format "%.1f" (* 100 error-rate)) "%"))))

    {:utilization utilization
     :bottlenecks bottlenecks
     :avg-duration avg-duration
     :completed completed
     :failed failed
     :recommendations (cond-> []
                        (> utilization 0.8) (conj :increase-thread-pool)
                        (> (count bottlenecks) 0) (conj :address-bottlenecks)
                        (> avg-duration 1000) (conj :optimize-slow-scenarios)
                        (> (/ failed completed) 0.05) (conj :reduce-errors))}))

(comment
  ;; Example usage:

  ;; 1. Start monitoring
  (monitoring/init-monitoring!)

  ;; 2. Analyze individual scenario
  (analyze-scenario-timing "S103 l2-reversal-slash-ids")

  ;; 3. Analyze parallel performance
  (analyze-parallel-performance)

  ;; 4. Get optimization recommendations
  (find-optimization-opportunities)

  ;; 5. Shutdown when done
  (monitoring/shutdown-monitoring!))
