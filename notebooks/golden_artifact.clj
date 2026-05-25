(ns notebooks.golden-artifact
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.story :as story]))

;; # Sew Protocol — Technical Validation Story
;; ## Scenario S26: L1 Reorg Fork Deflection (Forking Strategist)

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)]
   [:div {:style {:background "#000" :padding "40px" :minHeight "100vh"}}
    (story/generate-deflection-story "s26-forking-strategist-l1-reversal" artifacts)]))