(ns scripts.speds-generate-issues
  (:require [resolver_sim.notebooks.speds.data :as data]
            [resolver_sim.notebooks.speds.findings :as findings]
            [resolver_sim.notebooks.speds.issues :as issues]))

(defn -main []
  (let [artifacts (data/load-run-artifacts)
        findings-bundle (findings/save-findings! artifacts)
        bundle (issues/save-issues! artifacts)]
    (println "Generated findings bundle:")
    (println "- path:" findings/findings-path)
    (println "- run:" (get-in findings-bundle [:run :run_id]))
    (println "- finding-count:" (count (:findings findings-bundle)))
    (println "Generated issues bundle:")
    (println "- path:" issues/issues-path)
    (println "- run:" (:run/id bundle))
    (println "- issue-count:" (:issue-count bundle))))

(-main)
