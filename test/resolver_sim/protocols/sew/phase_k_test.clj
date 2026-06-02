(ns resolver-sim.protocols.sew.phase-k-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]
            [resolver-sim.protocols.sew.accounting :as acct]))

(deftest tiered-authority-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver "0xResolver"
        token "0xToken"
        snap (t/make-module-snapshot {:dispute-resolver resolver 
                                      :escrow-fee-bps 0
                                      :resolver-bond-bps 10000})]
    
    (testing "create-escrow fails if resolver has no stake"
      (let [r (lc/create-escrow world buyer token seller 1000 {} snap)]
        (is (false? (:ok r)))
        (is (= :insufficient-resolver-stake (:error r)))))

    (testing "create-escrow succeeds after resolver stakes"
      (let [world-staked (reg/register-stake world resolver 1000)
            r (lc/create-escrow world-staked buyer token seller 1000 {} snap)]
        (is (true? (:ok r)))
        (is (= 0 (:workflow-id r)))))

    (testing "create-escrow fails if stake is too low for escrow amount"
      (let [world-staked (reg/register-stake world resolver 999)
            r (lc/create-escrow world-staked buyer token seller 1000 {} snap)]
        (is (false? (:ok r)))
        (is (= :insufficient-resolver-stake (:error r)))))))

(deftest auto-slashing-on-reversal-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        r0 "0xRes0"
        r1 "0xRes1"
        token "0xToken"
        snap (t/make-module-snapshot {:dispute-resolver r0 
                                      :escrow-fee-bps 0 
                                      :appeal-window-duration 3600
                                      :appeal-bond-bps 1000
                                      :reversal-slash-bps 2500})
        ;; Register stakes
        world (-> world
                  (reg/register-stake r0 5000)
                  (reg/register-stake r1 5000))
        ;; Create escrow and raise dispute
        {:keys [world workflow-id]} (lc/create-escrow world buyer token seller 5000 {} snap)
        world (-> world
                  (lc/raise-dispute workflow-id buyer)
                  :world)]

    (testing "Level 0 resolution"
      (let [r (res/execute-resolution world workflow-id r0 true "hash0" nil)
            world-res (:world r)]
        (is (true? (:ok r)))
        (is (= r0 (get-in world-res [:previous-decisions workflow-id 0 :resolver])))
        
        (testing "Escalation to Level 1"
          (let [esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
                r-esc (res/escalate-dispute world-res workflow-id seller esc-fn)
                world-esc (:world r-esc)]
            (is (true? (:ok r-esc)))
            (is (= 1 (t/dispute-level world-esc workflow-id)))

            (testing "Level 1 resolution reverses Level 0 -> Slashing"
              (let [r-final (res/execute-resolution world-esc workflow-id r1 false "hash1" nil)
                    world-final (:world r-final)]
                (is (true? (:ok r-final)))
                ;; Check if r0 was slashed (25% of 1000 = 250)
                ;; 25% of 5000 stake = 1250 slashed
                (is (= 3750 (reg/get-stake world-final r0)))
                (is (= 625 (get-in world-final [:bond-distribution :insurance])))
                (is (= 375 (get-in world-final [:bond-distribution :protocol])))))))))))

(deftest manual-fraud-slash-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver "0xRes"
        gov "0xGov"
        token "0xToken"
        snap (t/make-module-snapshot {:appeal-window-duration 86400}) ; 1 day
        world (reg/register-stake world resolver 10000)
        ;; Create escrow
        {:keys [world workflow-id]} (lc/create-escrow world buyer token seller 5000 {} snap)
        ;; Propose
        r-prop (res/propose-fraud-slash world workflow-id gov resolver 5000)
        world-prop (:world r-prop)]
    
    (is (true? (:ok r-prop)))
    (is (= 10000 (reg/get-stake world-prop resolver))) ; Not slashed yet
    
    (testing "Cannot execute before timelock"
      (let [r-exec (res/execute-fraud-slash world-prop workflow-id)]
        (is (false? (:ok r-exec)))
        (is (= :timelock-not-expired (:error r-exec)))))
    
    (testing "Execute after timelock"
      (let [world-time (assoc world-prop :block-time 100000) ; > 86400
            r-exec (res/execute-fraud-slash world-time workflow-id)
            world-final (:world r-exec)]
        (is (true? (:ok r-exec)))
        (is (= 5000 (reg/get-stake world-final resolver))) ; Slashed!
        (is (= 2500 (get-in world-final [:bond-distribution :insurance])))))))
