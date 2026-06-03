(ns resolver-sim.protocols.sew.phase-m-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]))

(deftest evidence-gated-slashing-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        r0 "0xRes0"
        r1 "0xRes1"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:dispute-resolver r0 
                                      :reversal-slash-bps 10000
                                      :appeal-window-duration 86400
                                      :resolver-bond-bps 10000
                                      :escrow-fee-bps 50})
        world (-> world (reg/register-stake r0 2000) (reg/register-stake r1 2000))
        {:keys [world workflow-id]} (lc/create-escrow world buyer "0xT" seller 1000 {} snap)
        net-escrow (t/compute-amount-after-fee 1000 50) ; default 50 bps fee
        world (-> (lc/raise-dispute world workflow-id buyer) :world)
        world (-> (res/execute-resolution world workflow-id r0 true "h0" nil) :world)
        esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
        world (-> (res/escalate-dispute world workflow-id buyer esc-fn) :world)]

    (testing "TRACK 1: Automated Slash (Same Evidence)"
      (let [slash-id (str workflow-id "-reversal-0")
            r-final (res/execute-resolution world workflow-id r1 false "h1" nil)
            world-final (:world r-final)]
        (is (= :executed (get-in world-final [:pending-fraud-slashes slash-id :status]))
            "Slash should be executed immediately on same-evidence reversal")
        (is (= 0 (reg/get-stake world-final r0))
            "100% reversal slash-bps on 2000 stake removes all stake")))

    (testing "TRACK 2: Manual Proposal (New Evidence)"
      (let [slash-id (str workflow-id "-reversal-0")
            world-new-info (assoc-in world [:evidence-updated? workflow-id] true)
            r-final (res/execute-resolution world-new-info workflow-id r1 false "h1" nil)
            world-final (:world r-final)
            slash-entry (get-in world-final [:pending-fraud-slashes slash-id])]
        (is (= :pending (:status slash-entry)) "Slash should be PENDING when new evidence exists")
        (is (= 2000 (reg/get-stake world-final r0)) "Resolver NOT slashed yet")

        (testing "Successful Slashing Appeal"
          (let [world-appealed (-> (res/appeal-slash world-final workflow-id r0 slash-id) :world)
                world-upheld   (-> (res/resolve-appeal world-appealed workflow-id gov true slash-id) :world)]
            (is (= :reversed (get-in world-upheld [:pending-fraud-slashes slash-id :status]))
                "Slash status should be REVERSED")

            (testing "Execution of reversed slash fails"
              (let [world-late (assoc world-upheld :block-time 1000000)
                    r-exec (res/execute-fraud-slash world-late workflow-id slash-id)]
                (is (false? (:ok r-exec)))
                (is (= :slash-already-reversed (:error r-exec)))))))))))
