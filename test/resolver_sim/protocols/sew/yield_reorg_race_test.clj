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

(defn- step-by-seq [trace seq-num]
  (some #(when (= seq-num (:seq %)) %) trace))

(deftest test-s83-yield-accrual-reorg-race-replay
  (let [scenario (load-s83)
        result   (sew/replay-with-sew-protocol scenario)
        trace    (:trace result)]
    (is (= :pass (:outcome result)))
    (is (zero? (get-in result [:metrics :invariant-violations])))
    (is (:ok? (analysis/analyze-expected-errors scenario trace)))
    (is (= 8 (:events-processed result)) "exactly 8 events processed")
    (is (= :ok (:result (step-by-seq trace 0))) "register-stake succeeds")
    (is (= :ok (:result (step-by-seq trace 1))) "create_escrow succeeds")
    (is (= :ok (:result (step-by-seq trace 6))) "first execute_pending_settlement succeeds")
    (is (= :rejected (:result (step-by-seq trace 7))))
    (is (= :transfer-not-in-dispute (:error (step-by-seq trace 7))))
    (is (= :refunded
           (get-in (step-by-seq trace 6) [:projection :escrow-transfers 0 :escrow-state])))
    (is (< (get-in (step-by-seq trace 1) [:projection :total-held :USDC] 0)
           (get-in (step-by-seq trace 3) [:projection :total-held :USDC] 0))
        "yield accrual during long dispute increases total-held before settlement")

    ;; Verify no level-1 resolution was recorded (superseded-pending fallback path)
    (let [world (:world result)]
      (is (nil? (get-in world [:previous-decisions 0 1]))
          "no level-1 decision was recorded")
      ;; Challenge archived the pending settlement; execute_pending_settlement
      ;; consumed the archived entry (escrow is now :refunded).
      (let [superseded (get-in world [:superseded-pending-settlements 0])]
        (is (= 1 (count superseded)) "exactly one superseded pending archived")
        (is (= false (get-in superseded [0 :pending :is-release])) "archived decision is refund"))
      ;; Verify yield profile resolved correctly
      (let [snap (t/get-snapshot world 0)]
        (is (= :yield.provider/liquid-lending (:yield-generation-module snap))
            "aave-v3 profile resolved to liquid-lending archetype")
        (is (= :aave-v3 (:yield-profile snap)))))))

(deftest test-yield-preset-normalizes-json-strings
  (is (= :to-recipient (:yield-preset (t/make-escrow-settings {:yield-preset "to-recipient"}))))
  (is (= :to-recipient (:yield-preset (t/make-escrow-settings {:yield_preset "to-recipient"}))))
  (is (= :off (:yield-preset (t/make-escrow-settings {}))))
  (is (t/yield-preset-yield-enabled? "to-recipient"))
  (is (not (t/yield-preset-yield-enabled? "off"))))

(deftest test-s83-reorg-idempotence
  (testing "Replaying S83 twice produces identical outcomes (fork-reconciliation idempotence)"
    (let [run-a (sew/replay-with-sew-protocol (load-s83))
          run-b (sew/replay-with-sew-protocol (load-s83))]
      (is (= :pass (:outcome run-a)))
      (is (= :pass (:outcome run-b)))
      (is (= (:events-processed run-a) (:events-processed run-b))
          "same number of events processed")
      (is (= (set (keys (:pending-fraud-slashes (:world run-a) {})))
             (set (keys (:pending-fraud-slashes (:world run-b) {}))))
          "identical slash entries across replays")
      (is (= (:outcome run-a) (:outcome run-b))
          "identical outcomes"))))
