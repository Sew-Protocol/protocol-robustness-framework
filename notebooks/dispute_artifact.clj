(ns notebooks.dispute-artifact
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.story :as story]))

;; # Sew Protocol — Technical Validation Story
;; ## Scenario S47b: Appeal Window +1s Boundary (Deadline Enforcement)

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)]
   [:div {:style {:background "#000" :padding "40px" :minHeight "100vh"}}
    (story/generate-deflection-story "scenarios/s47b-appeal-window-plus-one-rejected" artifacts)]))