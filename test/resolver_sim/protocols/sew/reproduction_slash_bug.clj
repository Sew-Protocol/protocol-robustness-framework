(ns resolver-sim.protocols.sew.reproduction-slash-bug
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry :as reg]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]))

(deftest test-reversal-slash-orphaned-after-fraud-slash
  (testing "Pending reversal slash (Track 2) is orphaned if fraud slash finalizes escrow"
    (let [gov "0xGov"
          r0 "0xRes0"
          r1 "0xRes1"
          buyer "0xBuyer"
          seller "0xSeller"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                         :reversal-slash-bps 2500
                                         :fraud-slash-bps 5000
                                         :appeal-window-duration 2000000
                                         :max-dispute-level 2})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r0 10000)
                     (reg/register-stake r1 5000))
          {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 8000 {} snap)
          
          ;; 1. Raise dispute and create reversal condition
          after-raise-res (lc/raise-dispute world workflow-id buyer)
          _ (println "Raise dispute result:" after-raise-res)
          after-raise (:world after-raise-res)
          after-l0 (res/execute-resolution after-raise workflow-id r0 true "0xhash" nil)
          
          ;; 2. Escalate, provide new evidence to trigger Track 2 (Pending) Reversal Slash
          after-escalation (:world (res/challenge-resolution after-l0 workflow-id buyer (fn [_ _ _ _] {:ok true :new-resolver r1})))
          after-evidence (assoc-in after-escalation [:evidence-updated? workflow-id] true)
          after-l1 (res/execute-resolution after-evidence workflow-id r1 false "0xhash2" nil)
          
          slash-id (str workflow-id "-reversal-0")
          
          ;; 3. Verify Reversal slash is pending
          _ (is (= :pending (get-in after-l1 [:pending-fraud-slashes slash-id :status])))
          
          ;; 4. Propose and Execute Fraud Slash
          after-fraud-prop (-> (res/propose-fraud-slash after-l1 workflow-id gov r0 5000) :world)
          after-fraud-exec (-> (res/execute-fraud-slash after-fraud-prop workflow-id) :world)
          
          ;; 5. Verify fraud slash executed, but reversal slash is still pending and orphaned
          _ (is (= :executed (get-in after-fraud-exec [:pending-fraud-slashes workflow-id :status])))
          _ (is (= :pending (get-in after-fraud-exec [:pending-fraud-slashes slash-id :status])) "Reversal slash should be orphaned")]
      )))
