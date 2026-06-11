(ns resolver-sim.util.attribution
  "Utilities for propagating contextual metadata across execution boundaries."
  (:require [resolver-sim.logging :as log]))

(def ^:dynamic *attribution* {})

(def ^:private attribution-version 1)

(defmacro with-attribution
  "Execute body with merged attribution context.

   Intended for small, serializable identifiers and causal metadata only.
   Do not put large world snapshots, functions, exceptions, or runtime objects here."
  [attr & body]
  `(binding [*attribution* (merge *attribution* ~attr)]
     ~@body))

(defn current-attribution
  "Return current attribution context."
  []
  *attribution*)

(defn- artifact-safe-value?
  [v]
  (or (nil? v)
      (keyword? v)
      (string? v)
      (number? v)
      (boolean? v)
      (and (vector? v) (every? artifact-safe-value? v))
      (and (map? v)
           (every? artifact-safe-value? (keys v))
           (every? artifact-safe-value? (vals v)))))

(defn artifact-safe-attribution
  "Drop values that should not enter persisted artifacts."
  [attr]
  (into {}
        (filter (fn [[k v]]
                  (and (keyword? k)
                       (artifact-safe-value? v))))
        attr))

(defn attribution-envelope
  "Return a versioned, artifact-safe attribution envelope."
  []
  {:attribution/version attribution-version
   :attribution/context (artifact-safe-attribution *attribution*)})

(defn annotate-evidence
  "Attach current attribution envelope to a persisted evidence record."
  [evidence]
  (assoc evidence :attribution (attribution-envelope)))

(defn log-with-attr [level msg & [data]]
  (log/log! level msg (merge data *attribution*)))
(defn make-context
  "Create an attribution context map."
  [data]
  data)

(defn log-annotated!
  "Log a message with attribution context."
  [level msg ctx data]
  (log/log! level msg (merge data ctx)))
