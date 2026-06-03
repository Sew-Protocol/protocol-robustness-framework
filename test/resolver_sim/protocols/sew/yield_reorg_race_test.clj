(ns resolver-sim.protocols.sew.yield-reorg-race-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.io.scenarios :as scen-io]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.scenario.normalize :as norm]))

(def ^:private scenario-path "scenarios/S83_yield-accrual-reorg-race.json")

(defn- load-s83 []
  (norm/normalize-scenario (scen-io/load-scenario-file scenario-path)))

(deftest test-s83-yield-accrual-reorg-race-replay
  (let [scenario (load-s83)
        result   (sew/replay-with-sew-protocol scenario)
        trace    (:trace result)]
    (is (= :pass (:outcome result)))
    (is (zero? (get-in result [:metrics :invariant-violations])))
    (is (:ok? (analysis/analyze-expected-errors scenario trace)))
    (is (= :ok (:result (nth trace 0))) "register-stake succeeds")
    (is (= :ok (:result (nth trace 1))) "create_escrow succeeds")
    (is (= :ok (:result (nth trace 6))) "first execute_pending_settlement succeeds")
    (is (= :rejected (:result (nth trace 7))))
    (is (= :transfer-not-in-dispute (:error (nth trace 7))))
    (is (= :refunded
           (get-in trace [6 :projection :escrow-transfers 0 :escrow-state])))
    (is (< (get-in trace [1 :projection :total-held :USDC] 0)
           (get-in trace [3 :projection :total-held :USDC] 0))
        "yield accrual during long dispute increases total-held before settlement")))

(deftest test-yield-preset-normalizes-json-strings
  (is (= :to-recipient (:yield-preset (t/make-escrow-settings {:yield-preset "to-recipient"}))))
  (is (= :to-recipient (:yield-preset (t/make-escrow-settings {:yield_preset "to-recipient"}))))
  (is (= :off (:yield-preset (t/make-escrow-settings {}))))
  (is (t/yield-preset-yield-enabled? "to-recipient"))
  (is (not (t/yield-preset-yield-enabled? "off"))))
