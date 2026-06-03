(ns resolver-sim.protocols.sew.resolver-yield-accrual-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.io.scenarios :as scen-io]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.normalize :as norm]
            [resolver-sim.contract-model.replay :as replay]))

(def ^:private scenario-path "scenarios/S110_resolver-yield-accrual.json")

(deftest test-s110-resolver-yield-accrual
  (let [scenario (norm/normalize-scenario (scen-io/load-scenario-file scenario-path))
        result   (replay/replay-with-protocol sew/protocol scenario)
        world    (:world result)]
    (is (= :pass (:outcome result)))
    (is (zero? (get-in result [:metrics :invariant-violations])))
    (is (= 11000 (get-in world [:total-withdrawn :USDC])))
    (is (= 1000 (get-in world [:total-yield-generated :USDC])))))
