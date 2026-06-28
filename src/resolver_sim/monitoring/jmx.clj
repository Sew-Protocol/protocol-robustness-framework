(ns resolver-sim.monitoring.jmx
  "JMX monitoring infrastructure for PRF parallel processing."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.config :as config])
  (:import (javax.management ObjectName MalformedObjectNameException)
           (java.lang.management ManagementFactory)))

(def ^:private jmx-config (atom nil))
(def ^:private mbean-server (atom nil))
(def ^:private registered-mbeans (atom #{}))

(defn init-jmx-config! []
  "Initialize JMX configuration from config system."
  (reset! jmx-config (config/load-config :monitoring false)))

(defn start-jmx-server! []
  "Start the JMX server with configured settings."
  (when (@jmx-config :enabled)
    (try
      (let [server (ManagementFactory/getPlatformMBeanServer)]
        (reset! mbean-server server)
        (log/info! "JMX server using platform MBeanServer"))
      (catch Exception e
        (log/error! (str "Failed to access platform MBeanServer:" (.getMessage e)))
        nil))))

(defn stop-jmx-server! []
  "Stop the JMX server."
  (reset! mbean-server nil)
  (log/info! "JMX server reference cleared"))

(defn register-mbean [mbean object-name]
  "Register an MBean with the server."
  (when (@jmx-config :enabled)
    (try
      (let [obj-name (ObjectName. object-name)
            server @mbean-server]
        (when server
          (.registerMBean server mbean obj-name)
          (swap! registered-mbeans conj object-name)
          (log/debug! "Registered MBean:" object-name)))
      (catch MalformedObjectNameException e
        (log/error! "Invalid ObjectName:" object-name ":" (.getMessage e)))
      (catch Exception e
        (log/error! "Failed to register MBean:" (.getMessage e))))))

(defn unregister-mbean [object-name]
  "Unregister an MBean."
  (when (@jmx-config :enabled)
    (try
      (let [obj-name (ObjectName. object-name)
            server @mbean-server]
        (when server
          (.unregisterMBean server obj-name)
          (swap! registered-mbeans disj object-name)
          (log/debug! "Unregistered MBean:" object-name)))
      (catch Exception e
        (log/error! "Failed to unregister MBean:" (.getMessage e))))))

(defn create-domain-object-name [type & components]
  "Create a standard ObjectName for PRF domains."
  (str "org.prf.monitoring:type=" type "," (clojure.string/join "," components)))

(defmacro defmbean [name & body]
  "Define an MBean interface. gen-interface returns the generated class."
  `(def ~name
     (gen-interface
      :name ~(symbol (str (namespace-munge *ns*) "." name))
      :methods ~(vec (for [method body]
                       (if (vector? method)
                         (let [[name arg-types return-type] method]
                           [name arg-types return-type])
                         method))))))

(defn startup []
  "Initialize JMX monitoring system."
  (init-jmx-config!)
  (start-jmx-server!)
  (log/info! "JMX monitoring initialized"))

(defn shutdown []
  "Shutdown JMX monitoring system."
  (doseq [mbean-name @registered-mbeans]
    (unregister-mbean mbean-name))
  (stop-jmx-server!)
  (log/info! "JMX monitoring shut down"))
