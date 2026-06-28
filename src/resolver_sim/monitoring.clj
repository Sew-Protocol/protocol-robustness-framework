(ns resolver-sim.monitoring
  "Main monitoring namespace - entry point for PRF monitoring system."
  (:require [resolver-sim.monitoring.jmx :as jmx]
            [resolver-sim.monitoring.scenario-runner :as scenario-monitoring]
            [resolver-sim.monitoring.invariant-checker :as invariant-monitoring]
            [resolver-sim.monitoring.metrics :as metrics]
            [resolver-sim.config :as config]
            [resolver-sim.logging :as log]))

(def ^:private monitoring-enabled (atom false))

(declare shutdown-monitoring!)

(defn- safe-startup
  "Start a monitoring component, logging but not throwing on failure."
  [label start-fn & args]
  (try
    (apply start-fn args)
    (log/info! (str label " started"))
    (catch Exception e
      (log/warn! (str label " failed to start: " (.getMessage e))))))

(defn init-monitoring! []
  "Initialize the complete monitoring system."
  (if (config/monitoring-enabled?)
    (try
      (log/info! "Starting PRF monitoring system...")

      ;; Start JMX infrastructure
      (safe-startup "JMX" jmx/startup)

      ;; Start metrics system
      (safe-startup "Metrics" #(metrics/startup))

      ;; Start scenario monitoring
      (safe-startup "Scenario monitoring" scenario-monitoring/startup)

      ;; Invariant monitoring, thread pools, dashboard loaded at runtime
      ;; to avoid hard dependencies on broken sub-namespaces.
      (safe-startup "Invariant monitoring"
                    #((requiring-resolve 'resolver-sim.monitoring.invariant-checker/startup)))

      (safe-startup "Thread pool monitoring"
                    #((requiring-resolve 'resolver-sim.monitoring.thread-pools/startup)))

      (safe-startup "Dashboard"
                    #((requiring-resolve 'resolver-sim.monitoring.dashboard/startup)
                      (config/monitoring-dashboard-port)))

      (reset! monitoring-enabled true)
      (log/info! "PRF monitoring system started successfully")
      :started

      (catch Exception e
        (log/error! "Failed to start monitoring system:" (.getMessage e))
        (shutdown-monitoring!)
        :failed))

    :disabled))

(defn shutdown-monitoring! []
  "Shutdown the monitoring system."
  (when @monitoring-enabled
    (try
      (log/info! "Shutting down PRF monitoring system...")

      ;; Shutdown dashboard (deferred load)
      (try
        ((requiring-resolve 'resolver-sim.monitoring.dashboard/shutdown))
        (catch Exception _ nil))

      ;; Shutdown thread pool monitoring (deferred load)
      (try
        ((requiring-resolve 'resolver-sim.monitoring.thread-pools/shutdown))
        (catch Exception _ nil))

      ;; Shutdown invariant monitoring
      (invariant-monitoring/shutdown)

      ;; Shutdown scenario monitoring
      (scenario-monitoring/shutdown)

      ;; Shutdown metrics system
      (metrics/shutdown)

      ;; Shutdown JMX infrastructure
      (jmx/shutdown)

      (reset! monitoring-enabled false)
      (log/info! "PRF monitoring system shut down successfully")

      (catch Exception e
        (log/error! "Error shutting down monitoring system:" (.getMessage e))))

    :shutdown))

(defn monitoring-enabled? []
  "Check if monitoring is enabled."
  @monitoring-enabled)

(defn monitor-thread-pool [pool-name executor]
  "Monitor a specific thread pool (deferred load)."
  (when (config/monitoring-enabled?)
    ((requiring-resolve 'resolver-sim.monitoring.thread-pools/monitor-thread-pool)
     pool-name executor)))

(defn unmonitor-thread-pool [pool-name]
  "Stop monitoring a specific thread pool (deferred load)."
  ((requiring-resolve 'resolver-sim.monitoring.thread-pools/unmonitor-thread-pool)
   pool-name))

;; Convenience functions for common metrics
(defn increment-metric [& keys]
  "Increment a counter metric."
  (when (config/monitoring-enabled?)
    (apply metrics/increment! keys)))

(defn timing-metric [duration-ms & keys]
  "Record a timing metric."
  (when (config/monitoring-enabled?)
    (apply metrics/timing! (concat keys [duration-ms]))))

(defn gauge-metric [value & keys]
  "Set a gauge metric."
  (when (config/monitoring-enabled?)
    (apply metrics/gauge! (concat keys [value]))))

(defn rate-metric [rate & keys]
  "Record a rate metric."
  (when (config/monitoring-enabled?)
    (apply metrics/rate! (concat keys [rate]))))

(defn -main
  "Entry point for `bb monitor`. Starts the monitoring system and blocks."
  [& _args]
  (let [result (init-monitoring!)]
    (println "Monitoring init result:" result)
    ;; Keep alive until SIGINT
    @(promise)))
