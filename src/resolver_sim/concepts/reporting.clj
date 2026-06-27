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

   Currently returns nil — shape is defined but not integrated.
   When activated, this fn will be called by the report pipeline
   after scenario execution completes."
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
    (when-let [failure-modes (some :concept/failure-modes concepts)]
      (mapv (fn [fm]
              {:concept/failure-id (:failure/id fm)
               :concept/failure-name (:failure/name fm)
               :concept/failure-summary (:failure/summary fm)
               :concept/stakeholder-impact (:stakeholder-impact fm)})
            (flatten failure-modes)))}
   :enriched-report report})

(defn report-has-concepts?
  "Check whether a report map has concept enrichment."
  [report]
  (contains? report :concept/section))

;; ── Report section shape specification ───────────────────────────────────────

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
      ...]}
    :enriched-report <original-report-map>}

   Integration point: the runner or report pipeline should call
   `enrich-report` after scenario execution and before writing output.
   The enriched report can then be serialised alongside the raw results.")
