(ns resolver-sim.util.attribution
  "Utilities for propagating contextual metadata across execution boundaries.")

(def ^:dynamic *attribution* {})

(defmacro with-attribution
  "Execute body with merged attribution context."
  [attr & body]
  `(binding [*attribution* (merge *attribution* ~attr)]
     ~@body))
