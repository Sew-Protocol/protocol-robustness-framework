(ns resolver-sim.monitoring.scenario-runner
  "Basic scenario execution monitoring."
  (:require [resolver-sim.monitoring.jmx :as jmx]
            [resolver-sim.monitoring.metrics :as metrics]
            [resolver-sim.logging :as log]
            [resolver-sim.config :as config])
  (:import (java.util.concurrent.atomic AtomicLong)))

(def ^:private active-scenarios (atom {}))
(def ^:private scenarios-completed (AtomicLong. 0))
(def ^:private scenarios-failed (AtomicLong. 0))

(defn scenario-started [scenario-id params]
  "Record that a scenario has started execution."
  (when (config/monitoring-enabled?)
    (swap! active-scenarios assoc scenario-id {:start-time (System/currentTimeMillis)
                                               :params params})
    (metrics/increment! [:scenario :active-count])
    (log/debug! (str "Scenario started:" scenario-id)))

  (defn scenario-completed [scenario-id result]
    "Record that a scenario has completed successfully."
    (when (config/monitoring-enabled?)
      (when-let [start-entry (@active-scenarios scenario-id)]
        (let [duration (- (System/currentTimeMillis) (:start-time start-entry))]
          (swap! active-scenarios dissoc scenario-id)
          (.incrementAndGet scenarios-completed)
          (metrics/decrement! [:scenario :active-count])
          (metrics/increment! [:scenario :completed-count])
          (metrics/timing! [:scenario :processing-time] duration)
          (log/debug! (str "Scenario completed:" scenario-id " in " duration "ms"))))))

  (defn scenario-failed [scenario-id error]
    "Record that a scenario has failed."
    (when (config/monitoring-enabled?)
      (when-let [start-entry (@active-scenarios scenario-id)]
        (let [duration (- (System/currentTimeMillis) (:start-time start-entry))]
          (swap! active-scenarios dissoc scenario-id)
          (.incrementAndGet scenarios-failed)
          (metrics/decrement! [:scenario :active-count])
          (metrics/increment! [:scenario :failed-count])
          (metrics/timing! [:scenario :processing-time] duration)
          (log/warn! (str "Scenario failed:" scenario-id " in " duration "ms:" (.getMessage error)))))))

  (defn get-active-scenarios []
    "Get currently active scenarios."
    @active-scenarios)

  (defn get-scenarios-completed []
    "Get total scenarios completed."
    (.get scenarios-completed))

  (defn get-scenarios-failed []
    "Get total scenarios failed."
    (.get scenarios-failed))

  (defn reset-statistics! []
    "Reset all scenario statistics."
    (reset! active-scenarios {})
    (.set scenarios-completed 0)
    (.set scenarios-failed 0)
    (metrics/reset-metrics!)
    (log/info! "Scenario statistics reset")))

(defn startup []
  "Start scenario runner monitoring."
  (log/info! "Scenario runner monitoring initialized"))

(defn shutdown []
  "Shutdown scenario runner monitoring."
  (log/info! "Scenario runner monitoring stopped"))

