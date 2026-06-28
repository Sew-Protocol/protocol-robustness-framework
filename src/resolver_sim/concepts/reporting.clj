(ns resolver-sim.concepts.reporting
  "Concept-aware report enrichment.

   Phase 1 stub: defines the shape of concept-enriched report sections
   without modifying any existing report or runner code.

   When integrated, `enrich-report` will inject concept metadata into
   simulation/benchmark output so that stakeholder-facing summaries
   are available alongside protocol-level results.

   Related: resolver-sim.concepts.registry provides the concept data
   that this namespace formats into report sections.")

;; ── Report enrichment shape ──────────────────────────────────────────────────

(defn enrich-report
  "Given a raw simulation or benchmark result map and a vector of
   loaded concept definitions, return the report with an added
   :concept/section key containing stakeholder-facing summaries.

   Called by the benchmark runner after scenario execution completes."
  [report concepts]
  {:concept/section
   {:concept/summaries
    (mapv (fn [c]
            {:concept/id (:concept/id c)
             :concept/name (:concept/name c)
             :concept/summary (:concept/summary c)
             :concept/stakeholder-question (:concept/stakeholder-question c)
             :concept/assumptions (:concept/assumptions c)
             :concept/out-of-scope (:concept/out-of-scope c)})
          concepts)
    :risk-annotations
    (let [all-failure-modes (mapcat :concept/failure-modes concepts)]
      (when (seq all-failure-modes)
        (mapv (fn [fm]
                {:concept/failure-id (:failure/id fm)
                 :concept/failure-name (:failure/name fm)
                 :concept/failure-summary (:failure/summary fm)
                 :concept/stakeholder-impact (:stakeholder-impact fm)})
              all-failure-modes)))}})

(comment "Expected shape of a concept-enriched report section:

   {:concept/section
    {:concept/summaries
     [{:concept/id <qualified-kw>
       :concept/name <string>
       :concept/summary <string>
       :concept/stakeholder-question <string>
       :concept/assumptions [<string> ...]
       :concept/out-of-scope [<string> ...]}
      ...]
     :risk-annotations
     [{:concept/failure-id <keyword>
       :concept/failure-name <string>
       :concept/failure-summary <string>
       :concept/stakeholder-impact <string>}
      ...]}}")
