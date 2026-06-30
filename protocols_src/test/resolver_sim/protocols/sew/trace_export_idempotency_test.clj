(ns resolver-sim.protocols.sew.trace-export-idempotency-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.io.trace-export :as trace-export]
            [resolver-sim.io.scenarios :as scen-io]))

(deftest s64-export-surfaces-idempotency-on-deduped-steps
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S64_replay-event-id-dedupe.edn")
        result   (replay/replay-with-protocol sew/protocol scenario)
        fixture  (trace-export/export-trace-fixture result scenario)
        dup-exec (some #(when (= 3 (:seq %)) %) (:steps fixture))
        dup-set  (some #(when (= 5 (:seq %)) %) (:steps fixture))]
    (is (= :pass (:outcome result)))
    (is (= 2 (get-in fixture [:metadata "idempotency" :dedupe_step_count])))
    (is (= [3 5] (get-in fixture [:metadata "idempotency" :dedupe_steps])))
    (is (= "no-op-duplicate" (get-in dup-exec [:attributes :idempotency])))
    (is (= "evt-exec-res-1" (get-in dup-exec [:attributes :event_id])))
    (is (= "no-op-duplicate" (get-in dup-set [:attributes :idempotency])))
    (is (= "evt-settle-1" (get-in dup-set [:attributes :event_id])))))
