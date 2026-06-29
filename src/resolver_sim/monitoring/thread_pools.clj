(ns resolver-sim.monitoring.thread-pools
  "Thread pool monitoring with bottleneck detection."
  (:require [resolver-sim.monitoring.jmx :as jmx]
            [resolver-sim.monitoring.metrics :as metrics]
            [resolver-sim.logging :as log]
            [resolver-sim.config :as config]
            [clojure.core.async :as async])
  (:import (java.util.concurrent ThreadPoolExecutor ExecutorService TimeUnit)
           (java.util.concurrent.atomic AtomicLong AtomicInteger)
           (javax.management DynamicMBean MBeanInfo MBeanOperationInfo
                             MBeanParameterInfo AttributeNotFoundException
                             ReflectionException)))

(def ^:private monitored-pools (atom {}))
(def ^:private pool-stats (atom {}))
(def ^:private monitoring-enabled (atom false))

(defn- update-pool-stats [pool-name ^ThreadPoolExecutor executor]
  "Update statistics for a thread pool."
  (let [active-count (.getActiveCount executor)
        pool-size (.getPoolSize executor)
        core-pool-size (.getCorePoolSize executor)
        maximum-pool-size (.getMaximumPoolSize executor)
        queue-size (.size (.getQueue executor))
        completed-task-count (.getCompletedTaskCount executor)
        task-count (.getTaskCount executor)
        queue-remaining-capacity (.remainingCapacity (.getQueue executor))

        utilization (if (zero? maximum-pool-size)
                      0.0
                      (double (/ pool-size maximum-pool-size)))
        queue-utilization (if (zero? (+ queue-size queue-remaining-capacity))
                            0.0
                            (double (/ queue-size (+ queue-size queue-remaining-capacity))))

        stats {:active-count active-count
               :pool-size pool-size
               :core-pool-size core-pool-size
               :maximum-pool-size maximum-pool-size
               :queue-size queue-size
               :completed-task-count completed-task-count
               :task-count task-count
               :queue-remaining-capacity queue-remaining-capacity
               :utilization utilization
               :queue-utilization queue-utilization
               :last-update (System/currentTimeMillis)
               :bottleneck? (or (> utilization 0.9) (> queue-utilization 0.8))}]

    (swap! pool-stats assoc pool-name stats)

    ;; Update metrics
    (metrics/gauge! [:thread-pool pool-name :active-count] active-count)
    (metrics/gauge! [:thread-pool pool-name :pool-size] pool-size)
    (metrics/gauge! [:thread-pool pool-name :queue-size] queue-size)
    (metrics/gauge! [:thread-pool pool-name :utilization] (* 100.0 utilization))
    (metrics/gauge! [:thread-pool pool-name :queue-utilization] (* 100.0 queue-utilization))

    stats))

(defn- start-monitoring-loop [pool-name ^ThreadPoolExecutor executor]
  "Start monitoring loop for a thread pool."
  (async/go-loop [last-update (System/currentTimeMillis)]
    (when (@monitored-pools pool-name)
      (try
        (update-pool-stats pool-name executor)
        (catch Exception e
          (log/error! (str "Error monitoring thread pool " pool-name ": " (.getMessage e)))))

      (async/<! (async/timeout (or (get-in (config/load-config :monitoring false) [:sampling-interval-ms]) 1000)))
      (recur (System/currentTimeMillis)))))

(defn monitor-thread-pool [pool-name executor]
  "Start monitoring a thread pool."
  (when (config/monitoring-enabled?)
    (when (instance? ThreadPoolExecutor executor)
      (swap! monitored-pools assoc pool-name executor)
      (start-monitoring-loop pool-name executor)
      (log/info! (str "Started monitoring thread pool: " pool-name))
      true)
    false))

(defn unmonitor-thread-pool [pool-name]
  "Stop monitoring a thread pool."
  (swap! monitored-pools dissoc pool-name)
  (swap! pool-stats dissoc pool-name)
  (log/info! (str "Stopped monitoring thread pool: " pool-name)))

(defn get-monitored-pool-names []
  "Get names of all monitored thread pools."
  (keys @monitored-pools))

(defn get-thread-pool-stats [pool-name]
  "Get statistics for a specific thread pool."
  (get @pool-stats pool-name))

(defn get-all-thread-pool-stats []
  "Get statistics for all monitored thread pools."
  @pool-stats)

(defn get-average-utilization []
  "Get average utilization across all thread pools."
  (let [stats (vals @pool-stats)
        total-util (reduce + 0.0 (map :utilization stats))
        pool-count (count stats)]
    (if (zero? pool-count)
      0.0
      (double (/ total-util pool-count)))))

(defn detect-bottlenecks []
  "Detect potential bottlenecks in thread pools."
  (let [thresholds (get-in (config/load-config :monitoring false) [:alert-thresholds] {})
        high-util (get thresholds :high-utilization 0.9)
        high-queue (get thresholds :high-queue-utilization 0.8)]

    (into []
          (for [[pool-name stats] @pool-stats
                :when (:bottleneck? stats)
                :let [utilization (:utilization stats)
                      queue-util (:queue-utilization stats)]
                :when (or (> utilization high-util) (> queue-util high-queue))]
            {:pool-name pool-name
             :utilization utilization
             :queue-utilization queue-util
             :active-threads (:active-count stats)
             :queue-size (:queue-size stats)
             :max-pool-size (:maximum-pool-size stats)
             :severity (cond
                         (and (> utilization 0.95) (> queue-util 0.9)) :critical
                         (or (> utilization high-util) (> queue-util high-queue)) :warning
                         :else :info)}))))

(defn get-bottleneck-count []
  "Get count of current bottlenecks."
  (count (detect-bottlenecks)))

(defn get-critical-bottlenecks []
  "Get only critical bottlenecks."
  (filter #(= :critical (:severity %)) (detect-bottlenecks)))

;; Define the MBean interface
(def ^:private string-array-type (Class/forName "[Ljava.lang.String;"))

(defn- make-mbean-info
  "Build MBeanInfo metadata for the thread pool monitor MBean."
  []
  (MBeanInfo.
   "ThreadPoolMonitor"
   "Thread pool monitoring MBean"
   nil  ;; no attributes
   nil  ;; no constructors
   (into-array MBeanOperationInfo
               [(MBeanOperationInfo.
                 "getMonitoredPoolNames" "Get monitored pool names"
                 nil  ;; no params
                 string-array-type
                 MBeanOperationInfo/INFO)
                (MBeanOperationInfo.
                 "getThreadPoolStats" "Get stats for a pool"
                 (into-array MBeanParameterInfo
                             [(MBeanParameterInfo.
                               "poolName" "java.lang.String" "Pool name")])
                 string-array-type
                 MBeanOperationInfo/INFO)
                (MBeanOperationInfo.
                 "getAllThreadPoolStats" "Get all pool stats"
                 nil
                 string-array-type
                 MBeanOperationInfo/INFO)
                (MBeanOperationInfo.
                 "getAverageUtilization" "Get average pool utilization"
                 nil
                 Double/TYPE
                 MBeanOperationInfo/INFO)
                (MBeanOperationInfo.
                 "detectBottlenecks" "Detect bottlenecks"
                 nil
                 string-array-type
                 MBeanOperationInfo/INFO)
                (MBeanOperationInfo.
                 "getBottleneckCount" "Get bottleneck count"
                 nil
                 Integer/TYPE
                 MBeanOperationInfo/INFO)
                (MBeanOperationInfo.
                 "getCriticalBottleneckCount" "Get critical bottleneck count"
                 nil
                 Integer/TYPE
                 MBeanOperationInfo/INFO)])
   nil))  ;; no notifications

(defn create-thread-pool-monitor-mbean []
  "Create and register the thread pool monitor MBean."
  (reify DynamicMBean
    (getAttribute [this attribute]
      (throw (AttributeNotFoundException. attribute)))

    (setAttribute [this attribute]
      (throw (AttributeNotFoundException. (.getName attribute))))

    (getAttributes [this attributes]
      (javax.management.AttributeList.))

    (setAttributes [this attributes]
      attributes)

    (invoke [this action-name params signature]
      (case action-name
        "getMonitoredPoolNames" (into-array String (get-monitored-pool-names))
        "getThreadPoolStats" (if-let [stats (get-thread-pool-stats (first params))]
                               (into-array String
                                           (map (fn [[k v]] (str (name k) "=" v)) stats))
                               (into-array String []))
        "getAllThreadPoolStats" (into-array String
                                            (mapcat (fn [[pool-name stats]]
                                                      (map (fn [[k v]] (str pool-name "." (name k) "=" v)) stats))
                                                    @pool-stats))
        "getAverageUtilization" (get-average-utilization)
        "detectBottlenecks" (into-array String
                                        (map (fn [bottleneck]
                                               (str (:pool-name bottleneck) "=" (:severity bottleneck)
                                                    " util=" (format "%.2f" (* 100.0 (:utilization bottleneck)))
                                                    " queue=" (:queue-size bottleneck)))
                                             (detect-bottlenecks)))
        "getBottleneckCount" (get-bottleneck-count)
        "getCriticalBottleneckCount" (count (get-critical-bottlenecks))
        (throw (ReflectionException.
                (NoSuchMethodException. action-name)))))

    (getMBeanInfo [this]
      (make-mbean-info))))

(defn startup []
  "Initialize thread pool monitoring."
  (when (config/monitoring-enabled?)
    (reset! monitoring-enabled true)
    (let [mbean (create-thread-pool-monitor-mbean)
          object-name (jmx/create-domain-object-name "ThreadPoolMonitor")]
      (jmx/register-mbean mbean object-name)
      (log/info! "Thread pool JMX monitoring started"))))

(defn shutdown []
  "Shutdown thread pool monitoring."
  (when @monitoring-enabled
    (doseq [pool-name (get-monitored-pool-names)]
      (unmonitor-thread-pool pool-name))
    (let [object-name (jmx/create-domain-object-name "ThreadPoolMonitor")]
      (jmx/unregister-mbean object-name))
    (reset! monitoring-enabled false)
    (log/info! "Thread pool JMX monitoring stopped")))
