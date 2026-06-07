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
