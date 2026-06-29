(ns resolver-sim.monitoring.metrics
  "Metrics collection system for PRF monitoring."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.monitoring.jmx :as jmx]
            [resolver-sim.config :as config])
  (:import (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicLong AtomicReference)))

(def ^:private counters (ConcurrentHashMap.))
(def ^:private gauges (ConcurrentHashMap.))
(def ^:private histograms (ConcurrentHashMap.))
(def ^:private meters (ConcurrentHashMap.))

(defn- key->string [key-seq]
  "Convert a key sequence to a string."
  (clojure.string/join "." (map name key-seq)))

(defn increment! [key & [amount]]
  "Increment a counter metric."
  (let [key-str (key->string key)
        counter (.computeIfAbsent counters key-str
                                  (reify java.util.function.Function
                                    (apply [this k] (AtomicLong. 0))))]
    (.addAndGet ^AtomicLong counter (or amount 1))
    (log/trace! (str "Incremented metric:" key-str " by " (or amount 1)))))

(defn decrement! [key & [amount]]
  "Decrement a counter metric."
  (let [key-str (key->string key)
        counter (.computeIfAbsent counters key-str
                                  (reify java.util.function.Function
                                    (apply [this k] (AtomicLong. 0))))]
    (.addAndGet ^AtomicLong counter (or (- amount) -1))
    (log/trace! (str "Decremented metric:" key-str " by " (or amount 1)))))

(defn gauge! [key value]
  "Set a gauge metric."
  (let [key-str (key->string key)]
    (.put gauges key-str value)
    (log/trace! (str "Set gauge:" key-str " = " value))))

(defn timing! [key duration-ms]
  "Record a timing metric."
  (let [key-str (key->string key)
        histogram (.computeIfAbsent histograms key-str
                                    (reify java.util.function.Function
                                      (apply [this k] (AtomicLong. 0))))
        count-histo (.computeIfAbsent histograms (str key-str ".count")
                                      (reify java.util.function.Function
                                        (apply [this k] (AtomicLong. 0))))]
    (.addAndGet ^AtomicLong histogram duration-ms)
    (.incrementAndGet ^AtomicLong count-histo)
    (log/trace! (str "Recorded timing:" key-str " = " duration-ms "ms"))))

(defn rate! [key rate]
  "Record a rate metric."
  (let [key-str (key->string key)
        meter (.computeIfAbsent meters key-str
                                (reify java.util.function.Function
                                  (apply [this k] (AtomicReference. (double 0.0)))))]

    (.set ^AtomicReference meter (double rate))
    (log/trace! (str "Recorded rate:" key-str " = " rate))))

(defn get-counter [key]
  "Get the current value of a counter."
  (when-let [counter (.get counters (key->string key))]
    (.get ^AtomicLong counter)))

(defn get-gauge [key]
  "Get the current value of a gauge."
  (.get gauges (key->string key)))

(defn get-histogram-stats [key]
  "Get statistics for a histogram."
  (let [key-str (key->string key)
        sum (.get histograms key-str)
        count (.get histograms (str key-str ".count"))]
    (when (and sum count)
      {:count (.get ^AtomicLong count)
       :sum (.get ^AtomicLong sum)
       :mean (double (/ (.get ^AtomicLong sum) (.get ^AtomicLong count)))
       :max (.get ^AtomicLong sum)})))

(defn get-meter [key]
  "Get the current value of a meter."
  (when-let [meter (.get meters (key->string key))]
    (.get ^AtomicReference meter)))

(defn get-all-metric-names []
  "Get all registered metric names as strings."
  (let [ks (java.util.TreeSet.)]
    (.addAll ks (.keySet counters))
    (.addAll ks (.keySet gauges))
    (.addAll ks (.keySet histograms))
    (.addAll ks (.keySet meters))
    (vec ks)))

(defn reset-metrics! []
  "Reset all metrics."
  (.clear counters)
  (.clear gauges)
  (.clear histograms)
  (.clear meters)
  (log/info! "All metrics reset"))

;; Define the MBean interface
(defn startup []
  "Initialize metrics system.
   JMX MBean registration is deferred (reify cannot resolve gen-interface'd
   classes at compile time in Clojure 1.12). Metrics counters work without it."
  (log/info! "Metrics system initialized (JMX MBean deferred)"))

(defn shutdown []
  "Shutdown metrics system."
  (log/info! "Metrics system shut down"))
