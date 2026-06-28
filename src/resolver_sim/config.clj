(ns resolver-sim.config
  "Configuration management for PRF."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.logging :as log]))

(def ^:private config-cache (atom nil))

(defn- load-config-file [path]
  "Load configuration from EDN file."
  (try
    (when (.exists (io/file path))
      (edn/read-string (slurp path)))
    (catch Exception e
      (log/warn! (str "Failed to load config file " path ":" (.getMessage e)))
      nil)))

(defn load-config
  "Load configuration with optional namespace."
  ([namespace]
   (load-config namespace true))
  ([namespace use-cache?]
   (when use-cache?
     (when-let [cached @config-cache]
       (get cached namespace)))

   (let [base-config (load-config-file "config.edn")
         namespace-config (load-config-file (str "config/" (name namespace) ".edn"))
         merged (merge base-config namespace-config)]

     (when use-cache?
       (swap! config-cache assoc namespace merged))

     merged)))

(defn monitoring-enabled? []
  "Check if monitoring is enabled."
  (boolean (:enabled (load-config :monitoring false))))

(defn monitoring-dashboard-port []
  "Get monitoring dashboard port."
  (or (:dashboard-port (load-config :monitoring false)) 8090))

(defn jmx-port []
  "Get JMX port."
  (or (:jmx-port (load-config :monitoring false)) 1099))

(defn reset-config-cache! []
  "Reset the configuration cache."
  (reset! config-cache nil)
  (log/debug! "Configuration cache reset"))

;; Backward compatibility with evidence config
(defn evidence-config []
  "Get evidence configuration."
  (load-config :evidence false))
