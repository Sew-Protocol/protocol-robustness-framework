(ns resolver-sim.notebook-support.speds.story-data
  "Data contract for SPEDS story engine — plain map, no record needed."
  (:require [resolver-sim.notebook-support.speds.data]))

(defn build-story-data
  "Builds a story context map with keys :trace-id, :git-sha, :hash,
   :title, :replay-match-label, :scenario."
  [artifacts scenario-id]
  (let [ctx (resolver-sim.notebook-support.speds.data/generate-proof-context artifacts scenario-id)
        scenario (resolver-sim.notebook-support.speds.data/find-scenario-by-id (:coverage artifacts) scenario-id)
        {:keys [replay-match-label]} (resolver-sim.notebook-support.speds.data/narrative-metrics artifacts)]
    (merge ctx {:scenario scenario :replay-match-label replay-match-label})))
