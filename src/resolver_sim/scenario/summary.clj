(ns resolver-sim.scenario.summary
  "Pure summary aggregation for scenario collection runs.")

(defn build-summary
  "Build a collection summary from entry results.

   `entries` is a vector of maps from `scenario.runner/build-entry-result`
   (must include :pass?).

   Optional keys in `opts`:
     :suite-id — keyword label for the collection
     :elapsed-ms — wall time (caller supplies)"
  [entries & [opts]]
  (let [opts (or opts {})
        passed (count (filter :pass? entries))]
    (cond-> {:passed    passed
             :total     (count entries)
             :elapsed-ms (:elapsed-ms opts 0)
             :results   (vec entries)}
      (:suite-id opts) (assoc :suite-id (:suite-id opts)))))
