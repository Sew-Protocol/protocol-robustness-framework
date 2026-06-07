(ns resolver-sim.util.attribution
  "Utilities for propagating contextual metadata across execution boundaries."
  (:require [resolver-sim.logging :as log]))

(def ^:dynamic *attribution* {})

(defmacro with-attribution
  "Execute body with merged attribution context."
  [attr & body]
  `(binding [*attribution* (merge *attribution* ~attr)]
     ~@body))

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
