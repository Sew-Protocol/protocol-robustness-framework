(ns resolver-sim.notebooks.speds.story-data
  "Data contract for SPEDS story engine."
  (:require [resolver-sim.notebooks.speds.data]))

(defrecord StoryData
  [trace-id git-sha hash title replay-match-label scenario])

(defn build-story-data
  [artifacts scenario-id]
  (let [ctx (resolver-sim.notebooks.speds.data/generate-proof-context artifacts scenario-id)
        scenario (resolver-sim.notebooks.speds.data/find-scenario-by-id (:coverage artifacts) scenario-id)
        {:keys [replay-match-label]} (resolver-sim.notebooks.speds.data/narrative-metrics artifacts)]
    (map->StoryData (merge ctx {:scenario scenario :replay-match-label replay-match-label}))))
