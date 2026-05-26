(ns scripts.speds-consistency-check
  (:require [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.findings :as findings]
            [resolver-sim.notebooks.speds.issues :as issues]
            [resolver-sim.notebooks.speds.validation :as validation]))

(defn run-check!
  []
  (let [artifacts (data/load-run-artifacts)
        findings-bundle (findings/generate-findings-bundle artifacts)
        issues-bundle (issues/generate-issues-bundle artifacts)
        report (validation/run-speds-consistency-checks
                {:frames [{:header "sample"
                           :footer-left "left"
                           :footer-right "right"
                           :content [:div "ok"]
                           :claims [{:claim-id :sample
                                     :value "ok"
                                     :source-artifact "script"
                                     :source-path [:sample :path]}]}]
                 :files ["notebooks/workbench_production.clj"
                         "src/resolver_sim/notebooks/speds/story.clj"
                         "notebooks/golden_artifact.clj"
                         "notebooks/dispute_artifact.clj"
                         "notebooks/atlas_artifact.clj"]
                 :artifacts artifacts
                 :findings (:findings findings-bundle)
                  :issues (:issues issues-bundle)
                 :claim-sources {:git-sha {:path [:summary :git_sha] :required? false}
                                 :coverage-scenarios {:path [:coverage :scenarios] :required? true}}})]
    (println "SPEDS consistency report:")
    (prn report)
    report))

(run-check!)
