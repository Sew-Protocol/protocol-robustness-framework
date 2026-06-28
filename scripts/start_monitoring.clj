(ns start-monitoring
  "Script to start the PRF monitoring system."
  (:require [resolver-sim.monitoring :as monitoring]
            [resolver-sim.config :as config]
            [resolver-sim.logging :as log]))

(defn -main [& args]
  (println "Starting PRF Monitoring System...")

  (when (config/monitoring-enabled?)
    (println "✅ Monitoring is enabled in configuration")

    (try
      (monitoring/init-monitoring!)
      (println "✅ Monitoring system initialized successfully")

      (println "\n📊 Monitoring Components Started:")
      (println "   • JMX Server on port:" (config/jmx-port))
      (println "   • Metrics System")
      (println "   • Scenario Runner Monitoring")
      (println "   • Thread Pool Monitoring")
      (println "   • Web Dashboard on port:" (config/monitoring-dashboard-port))

      (println "\n🔗 Access Monitoring:")
      (println "   • Web Dashboard: http://localhost:" (config/monitoring-dashboard-port) "/monitoring")
      (println "   • JSON API: http://localhost:" (config/monitoring-dashboard-port) "/api/monitoring")
      (println "   • JMX Console: Connect to port" (config/jmx-port))

      (println "\n🎯 Example Usage:")
      (println "   # Monitor a thread pool:")
      (println "   (require '[resolver-sim.monitoring :as monitoring])")
      (println "   (monitoring/monitor-thread-pool \"my-pool\" executor)")

      (println "\n   # Add custom metrics:")
      (println "   (monitoring/increment-metric [:my-module :operations])")
      (println "   (monitoring/timing-metric 150 [:operation :duration])")

      (println "\n✅ Monitoring system is running!")
      (println "   Press Ctrl+C to stop...")

      ;; Keep running until interrupted
      (while true
        (Thread/sleep 1000))

    (catch Exception e
      (log/error "Failed to start monitoring:" (.getMessage e))
      (println "❌ Error starting monitoring:" (.getMessage e))
      (System/exit 1)))

    (println "⚠️  Monitoring is disabled in configuration")))

;; For interactive use
(comment
  (require 'start-monitoring)
  (start-monitoring/-main))
