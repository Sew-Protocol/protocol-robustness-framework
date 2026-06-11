(ns resolver-sim.io.event-evidence
  "System for capturing and persisting high-fidelity evidence for high-impact transitions.
   Integrates with with-attribution context to bind causal metadata."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.util.attribution :as attr]))

(defn- evidence-filename [attribution reason]
  (let [ctx (:attribution/context attribution)
        sid (:ctx/scenario-id ctx "unknown")
        idx (:ctx/event-index ctx "unknown")]
    (str (name reason) "-" sid "-" idx ".json")))

(defn capture-event-evidence!
  "Persist a structured evidence record for a critical transition.
   Binds the current attribution envelope to the record."
  [reason pre post inputs & [calc]]
  (let [attribution (attr/attribution-envelope)
        evidence (cond-> {:schema/version "event-evidence/v1"
                          :evidence/type :transition-evidence
                          :evidence/reason reason
                          :attribution attribution
                          :inputs inputs
                          :pre-state pre
                          :post-state post}
                   calc (assoc :calculation calc))
        out-dir "results/test-artifacts/event-evidence"
        filename (evidence-filename attribution reason)
        f (io/file out-dir filename)]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str (attr/artifact-safe-attribution evidence) {:indent true}))
    (println "Captured event evidence:" filename)
    evidence))
