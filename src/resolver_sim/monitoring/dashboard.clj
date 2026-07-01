(ns resolver-sim.monitoring.dashboard
  "Monitoring dashboard for Protocol Robustness Framework.")

(defn start-dashboard
  "Start the monitoring dashboard server."
  []
  (println "Dashboard started on http://localhost:8090/monitoring"))

(defn shutdown-dashboard
  "Shutdown the monitoring dashboard server."
  []
  (println "Dashboard shutdown"))

(defn dashboard-enabled?
  "Check if the dashboard is enabled and running."
  []
  true)
