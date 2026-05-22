(ns resolver-sim.protocols.sew.phase-l-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]
            [resolver-sim.protocols.sew.accounting :as acct]))

(deftest watchtower-bounty-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        watchtower "0xWatchtower"
        r0 "0xMaliciousRes"
        r1 "0xHonestSenior"
        token "0xToken"
        ;; Configure Phase L: 3-day challenge window + 20% bounty
        snap (t/make-module-snapshot 
               {:dispute-resolver r0 
                :reversal-slash-bps 10000 ; 100%
                :challenge-window-duration 259200
                :challenge-bounty-bps 2000}) ; 20%
        
        ;; 1. Setup stakes
        world (-> world
                  (reg/register-stake r0 1000)
                  (reg/register-stake r1 1000))
        
        ;; 2. Create Escrow (1000 tokens)
        {:keys [world workflow-id]} (lc/create-escrow world buyer token seller 1000 {} snap)
        net-escrow (get-in world [:escrow-transfers workflow-id :amount-after-fee]) ; 995
        
        ;; 3. Raise Dispute
        world (-> (lc/raise-dispute world workflow-id buyer) :world)
        
        ;; 4. Malicious Resolution (r0) -> Release
        world (-> (res/execute-resolution world workflow-id r0 true "hash0" nil) :world)]

    (testing "Resolution enters challenge window instead of finalizing"
      (is (= :disputed (t/escrow-state world workflow-id)))
      (is (:exists (t/get-pending world workflow-id))))

    (testing "Watchtower challenges the resolution"
      (let [esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
            r (res/challenge-resolution world workflow-id watchtower esc-fn)
            world-challenged (:world r)]
        (is (true? (:ok r)))
        (is (= 1 (t/dispute-level world-challenged workflow-id)))
        (is (= watchtower (get-in world-challenged [:challengers workflow-id 0])))
        
        (testing "Honest Senior reverses Level 0 -> Slash + Bounty"
          (let [r-final (res/execute-resolution world-challenged workflow-id r1 false "hash1" nil)
                world-final (:world r-final)
                expected-bounty (quot (* net-escrow 2000) 10000)]
            (is (true? (:ok r-final)))
            
            ;; Malicious R0 is slashed 100% (995)
            (is (= (- 1000 net-escrow) (reg/get-stake world-final r0)))
            
            ;; Watchtower receives bounty
            (is (= expected-bounty (get-in world-final [:claimable watchtower])))
            
            ;; Protocol and Insurance receive remaining; retained share is tracked
            ;; in :retained-slash-reserves (not :bond-distribution :burned).
            (let [remaining (- net-escrow expected-bounty)
                  insurance (get-in world-final [:bond-distribution :insurance])
                  protocol  (get-in world-final [:bond-distribution :protocol])
                  retained  (get-in world-final [:retained-slash-reserves] 0)]
              ;; Check that total distributed = net_escrow
              (is (= net-escrow (+ insurance protocol expected-bounty retained))))))))))
