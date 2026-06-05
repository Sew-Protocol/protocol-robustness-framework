(ns resolver_sim.notebooks.speds.story-data
  "Data contract for SPEDS story engine — plain map, no record needed."
  (:require [resolver_sim.notebooks.speds.data]))

(defn build-story-data
  "Builds a story context map with keys :trace-id, :git-sha, :hash,
   :title, :replay-match-label, :scenario."
  [artifacts scenario-id]
  (let [ctx (resolver_sim.notebooks.speds.data/generate-proof-context artifacts scenario-id)
        scenario (resolver_sim.notebooks.speds.data/find-scenario-by-id (:coverage artifacts) scenario-id)
        {:keys [replay-match-label]} (resolver_sim.notebooks.speds.data/narrative-metrics artifacts)]
    (merge ctx {:scenario scenario :replay-match-label replay-match-label})))
