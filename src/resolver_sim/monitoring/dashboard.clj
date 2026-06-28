(ns resolver-sim.monitoring.dashboard
  "Simple monitoring dashboard using Ring."
  (:require [resolver-sim.monitoring.scenario-runner :as scenario-monitoring]
            [resolver-sim.monitoring.thread-pools :as thread-pool-monitoring]
            [resolver-sim.monitoring.metrics :as metrics]
            [resolver-sim.config :as config]
            [resolver-sim.logging :as log]
            [ring.util.response :as response]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress)))

(def ^:private server (atom nil))
(def ^:private running (atom false))

(defn- collect-monitoring-data []
  "Collect all monitoring data into a single map."
  {
   :timestamp (System/currentTimeMillis)
   :scenario {
     :active-count (count (scenario-monitoring/get-active-scenarios))
     :completed (scenario-monitoring/get-scenarios-completed)
     :failed (scenario-monitoring/get-scenarios-failed)
     :processing-rate (scenario-monitoring/get-processing-rate)
     :error-rate (scenario-monitoring/get-error-rate)
   }
   :thread-pools {
     :monitored-pools (vec (thread-pool-monitoring/get-monitored-pool-names))
     :average-utilization (thread-pool-monitoring/get-average-utilization)
     :bottlenecks (thread-pool-monitoring/detect-bottlenecks)
     :bottleneck-count (thread-pool-monitoring/get-bottleneck-count)
     :critical-bottlenecks (thread-pool-monitoring/get-critical-bottlenecks)
   }
   :metrics {
     :counters (into {}
                     (for [key (metrics/get-all-metric-names)
                           :when (.startsWith key "counter.")]
                       [(keyword (subs key 8)) (metrics/get-counter [(keyword (subs key 8))])]))
     :gauges (into {}
                   (for [key (metrics/get-all-metric-names)
                         :when (.startsWith key "gauge.")]
                     [(keyword (subs key 6)) (metrics/get-gauge [(keyword (subs key 6))])]))
     :histograms (into {}
                      (for [key (metrics/get-all-metric-names)
                            :when (.startsWith key "histogram.")]
                        [(keyword (subs key 10)) (metrics/get-histogram-stats [(keyword (subs key 10))])]))
   }
  })

(defn- json-handler [request]
  "Handle JSON API requests."
  (try
    (let [data (collect-monitoring-data)
          json-response (json/write-str data)]
      (-> (response/response json-response)
          (response/content-type "application/json")))
    (catch Exception e
      (log/error! (str "Dashboard error:" (.getMessage e)))
      (response/internal-server-error (.getMessage e)))))

(defn- html-handler [request]
  "Handle HTML dashboard requests."
  (try
    (let [data (collect-monitoring-data)
          bottlenecks (:bottlenecks (:thread-pools data))
          timestamp (:timestamp data)]

      (response/response
        (str "<html>
<head>
  <title>PRF Monitoring Dashboard</title>
  <style>
    body { font-family: Arial, sans-serif; margin: 20px; }
    .dashboard { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; }
    .card { border: 1px solid #ddd; border-radius: 8px; padding: 15px; background: white; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
    .card-header { font-weight: bold; margin-bottom: 10px; color: #333; }
    .metric { margin: 5px 0; }
    .metric-label { display: inline-block; width: 200px; }
    .warning { color: #e67e22; }
    .critical { color: #e74c3c; }
    .bottleneck { background: #fadbd8; padding: 10px; margin: 5px 0; border-radius: 4px; }
    .status-ok { color: #27ae60; }
    .status-warn { color: #f39c12; }
    .status-critical { color: #e74c3c; }
  </style>
</head>
<body>
  <h1>PRF Monitoring Dashboard</h1>
  <div class='dashboard'>

    <!-- Scenario Card -->
    <div class='card'>
      <div class='card-header'>Scenario Execution</div>
      <div class='metric'><span class='metric-label'>Active:</span> " (:active-count (:scenario data)) "</div>
      <div class='metric'><span class='metric-label'>Completed:</span> " (:completed (:scenario data)) "</div>
      <div class='metric'><span class='metric-label'>Failed:</span> " (:failed (:scenario data)) "</div>
      <div class='metric'><span class='metric-label'>Processing Rate:</span> " (format "%.2f/s" (:processing-rate (:scenario data))) "</div>
      <div class='metric'><span class='metric-label'>Error Rate:</span> " (format "%.2f%%" (* 100.0 (:error-rate (:scenario data)))) "</div>
    </div>

    <!-- Thread Pools Card -->
    <div class='card'>
      <div class='card-header'>Thread Pools</div>
      <div class='metric'><span class='metric-label'>Monitored Pools:</span> " (count (:monitored-pools (:thread-pools data))) "</div>
      <div class='metric'><span class='metric-label'>Avg Utilization:</span> " (format "%.2f%%" (* 100.0 (:average-utilization (:thread-pools data)))) "</div>
      <div class='metric'><span class='metric-label'>Bottlenecks:</span>
        <span class='" (if (pos? (:bottleneck-count (:thread-pools data))) "status-critical" "status-ok") "'>
          " (:bottleneck-count (:thread-pools data)) "</span>
      </div>

      " (when (seq bottlenecks)
          (str "<div class='card-header' style='margin-top: 15px;'>Detected Bottlenecks</div>"
               (str/join ""
                 (for [bottleneck bottlenecks]
                   (str "<div class='bottleneck'>
                     <strong>" (:pool-name bottleneck) "</strong> - "
                        (name (:severity bottleneck)) "<br>
                     Utilization: " (format "%.1f%%" (* 100.0 (:utilization bottleneck))) "<br>
                     Queue: " (:queue-size bottleneck) " items
                   </div>"))))) "
    </div>

    <!-- Metrics Card -->
    <div class='card'>
      <div class='card-header'>Metrics Summary</div>
      <div class='metric'><span class='metric-label'>Counters:</span> " (count (:counters (:metrics data))) "</div>
      <div class='metric'><span class='metric-label'>Gauges:</span> " (count (:gauges (:metrics data))) "</div>
      <div class='metric'><span class='metric-label'>Histograms:</span> " (count (:histograms (:metrics data))) "</div>
    </div>

  </div>
  <div style='margin-top: 20px; font-size: 12px; color: #7f8c8d;'>
    Dashboard updated: " (java.util.Date.) " | Data timestamp: " (java.util.Date. timestamp) "
  </div>
</body>
</html>"))
    (catch Exception e
      (log/error! (str "HTML dashboard error:" (.getMessage e)))
      (response/internal-server-error (.getMessage e)))))

(defn- create-http-server [port]
  "Create and start HTTP server."
  (try
    (let [server (HttpServer/create (InetSocketAddress. port) 0)
          context (.createContext server "/api/monitoring")
          html-context (.createContext server "/monitoring")]

      (.setExecutor server nil) ; Use default executor

      (.handle context
        (reify HttpHandler
          (handle [this exchange]
            (let [response (json-handler {:uri (.getRequestURI exchange)})]
              (.sendResponseHeaders exchange
                   (response :status 200)
                   (count (.getBytes (response :body) "UTF-8")))
              (with-open [os (.getResponseBody exchange)]
                (.write os (.getBytes (response :body) "UTF-8")))))))

      (.handle html-context
        (reify HttpHandler
          (handle [this exchange]
            (let [response (html-handler {:uri (.getRequestURI exchange)})]
              (.sendResponseHeaders exchange
                   (response :status 200)
                   (count (.getBytes (response :body) "UTF-8")))
              (with-open [os (.getResponseBody exchange)]
                (.write os (.getBytes (response :body) "UTF-8")))))))

      (.start server)
      (log/info! (str "Monitoring dashboard started on port" port))
      server)
    (catch Exception e
      (log/error! (str "Failed to start dashboard server:" (.getMessage e)))
      nil)))

(defn startup [port]
  "Start the monitoring dashboard server."
  (when (config/monitoring-enabled?)
    (when-not @running
      (let [server (create-http-server port)]
        (when server
          (reset! server server)
          (reset! running true)
          :started)))))

(defn shutdown []
  "Shutdown the monitoring dashboard server."
  (when @running
    (when-let [s @server]
      (.stop s 0)
      (reset! server nil)
      (reset! running false)
      (log/info! "Monitoring dashboard stopped"))

(defn running? []
  "Check if dashboard is running."
  @running)
