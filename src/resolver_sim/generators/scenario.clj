(ns resolver-sim.generators.scenario
  "SEW Protocol scenario composition for deterministic generator output."
  (:require [resolver-sim.generators.stateful :as stateful]
            [resolver-sim.protocols.protocol :as engine]
            [resolver-sim.protocols.sew :as sew]))

(def default-agents
  [{:id "buyer" :strategy "honest" :address "0xBuyer"}
   {:id "seller" :strategy "honest" :address "0xSeller"}
   {:id "resolver" :role "resolver" :address "0xResolver"}])

(def default-protocol-params
  {:resolver-fee-bps 50
   :appeal-window-duration 60
   :max-dispute-duration 3600
   :appeal-bond-protocol-fee-bps 0})

(defn build-scenario
  "Build a deterministic replay-compatible scenario map."
  [{:keys [seed max-steps initial-block-time profile]
    :or {seed 42 max-steps 4 initial-block-time 1000 profile :phase1-lifecycle}}]
  (let [context (engine/build-execution-context sew/protocol default-agents default-protocol-params)
        world0  (engine/init-world sew/protocol {:initial-block-time initial-block-time})
        {:keys [events]} (stateful/generate-event-sequence
                          {:seed seed
                           :max-steps max-steps
                           :profile profile
                           :context context
                           :world0 world0
                           :initial-time initial-block-time})]
    {:scenario-id        (str "generated-" seed)
     :schema-version     "1.0"
     :seed               seed
     :generator-profile  profile
     :agents             default-agents
     :protocol-params    default-protocol-params
     :initial-block-time initial-block-time
     :events             events}))
