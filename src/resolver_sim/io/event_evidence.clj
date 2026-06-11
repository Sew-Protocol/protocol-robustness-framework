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
  (let [;; Analyze attribution quality against scenario requirements
        requirements (into attr/required-evidence-keys attr/scenario-evidence-keys)
        attr-report  (attr/current-evidence-attribution requirements)
        
        evidence {:schema_version      "event-evidence/v1"
                  :evidence/type       :transition-evidence
                  :evidence/reason     reason
                  :attribution         (:attribution attr-report)
                  :attribution_quality (name (:quality attr-report))
                  :missing_attribution (vec (map name (:missing attr-report)))
                  :inputs              inputs
                  :pre-state           pre
                  :post-state          post
                  :calculation         calc}
        
        out-dir  "results/test-artifacts/event-evidence"
        filename (evidence-filename {:attribution/context (:attribution attr-report)} reason)
        f        (io/file out-dir filename)]
    
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str (attr/sanitize-attribution evidence) {:indent true}))
    (println "Captured event evidence:" filename " (Quality:" (name (:quality attr-report)) ")")
    evidence))
