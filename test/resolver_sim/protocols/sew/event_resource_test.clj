(ns resolver-sim.protocols.sew.event-resource-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.protocol :as proto]))

(deftest test-event-resource-id-decoupling
  (let [scenario {:scenario-id "resource-id-test"
                  :initial-block-time 1000
                  :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 10000}}]}
        result (sew/replay-with-sew-protocol scenario)
        trace-entry (first (:trace result))]
    (is (contains? trace-entry :resource-refs))
    (is (= #{{:resource/type :protected-transfer
              :resource/id   0
              :resource/role :primary}}
           (:resource-refs trace-entry)))))
