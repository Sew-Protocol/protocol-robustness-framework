(ns debug-test
  (:require [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.replay-test :as rt]))

(let [alice    {:id "alice"    :type "honest"        :address "0xAlice"}
      bob      {:id "bob"      :type "honest"        :address "0xBob"}
      resolver {:id "resolver" :type "resolver"      :address "0xResolver"}
      sc       (fn [& {:keys [agents params init-time events schema-version]
                      :or   {agents       [alice bob resolver]
                             params       {:resolver-fee-bps 50 :appeal-window-duration 0 :max-dispute-duration 2592000 :resolver-bond-bps 0}
                             init-time    1000
                             schema-version "1.0"}}]
                 {:scenario-id        "test"
                  :schema-version     schema-version
                  :seed               42
                  :agents             agents
                  :protocol-params    params
                  :initial-block-time init-time
                  :events             events})
      r (sew/replay-with-sew-protocol
         (sc :agents [alice bob resolver]
             :events [{:seq 0 :time 1000 :agent "nobody" :action "create_escrow"
                        :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
  (println :outcome (:outcome r))
  (println :halt-reason (:halt-reason r)))
