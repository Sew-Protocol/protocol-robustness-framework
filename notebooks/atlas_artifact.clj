(ns notebooks.atlas-artifact
  (:require [nextjournal.clerk :as clerk]
            [resolver_sim.notebooks.speds.data :as data]
            [resolver_sim.notebooks.speds.story :as story]))

;; # Atlas of Protocol Robustness
;; ## Corpus-Wide Adversarial Coverage & Scenario Mapping

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)]
   [:div {:style {:background "#020617" :padding "40px" :minHeight "100vh"}}
    (story/generate-atlas-view artifacts)]))