(ns resolver-sim.protocols.sew.race-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.invariants.accounting :as acct-inv]))

;; ---------------------------------------------------------------------------
;; Race Test Suite (S34)
;; ---------------------------------------------------------------------------

(defn- run-race-pair
  "Execute two actions in A->B and B->A order and verify stability."
  [initial-state actA actB]
  (let [;; Order A -> B
        w1 (actA initial-state)
        r1 (if (:ok w1) (actB (:world w1)) {:ok false :error :step1-failed})
        
        ;; Order B -> A
        w2 (actB initial-state)
        r2 (if (:ok w2) (actA (:world w2)) {:ok false :error :step1-failed})]

    ;; Verify both orderings resulted in a valid (or clean reverted) state
    {:a-then-b {:result r1 :consistent? (when (:ok r1) (acct-inv/accounting-consistent? (:world r1)))}
     :b-then-a {:result r2 :consistent? (when (:ok r2) (acct-inv/accounting-consistent? (:world r2)))}
     :same-terminal? (= (:world r1) (:world r2))}))

(deftest same-block-ordering-race
  (let [buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 60})
        ;; 1. release vs raise_dispute
        initial-race (lc/create-escrow (t/empty-world 1000) buyer "0xToken" seller 1000 {} snap)
        wf0 (:workflow-id initial-race)
        
        race1 (run-race-pair (:world initial-race)
                             #(lc/release % wf0 buyer nil)
                             #(sm/transition-to-disputed % wf0 buyer))
        
        ;; 2. sender_cancel vs raise_dispute
        race2 (run-race-pair (:world initial-race)
                             #(lc/sender-cancel % wf0 buyer nil)
                             #(sm/transition-to-disputed % wf0 buyer))]

    (testing "Release vs RaiseDispute race"
      (is (or (and (:consistent? (:a-then-b race1)) (:consistent? (:b-then-a race1)))
              (= :invalid-state-for-disputed (get-in race1 [:b-then-a :result :error]))))
      (is (or (and (:consistent? (:a-then-b race1)) (:consistent? (:b-then-a race1)))
              (= :invalid-state-for-release (get-in race1 [:a-then-b :result :error])))))

    (testing "Cancel vs RaiseDispute race"
      (is (or (and (:consistent? (:a-then-b race2)) (:consistent? (:b-then-a race2)))
              (= :invalid-state-for-disputed (get-in race2 [:b-then-a :result :error]))))
      (is (or (and (:consistent? (:a-then-b race2)) (:consistent? (:b-then-a race2)))
              (= :transfer-not-pending (get-in race2 [:a-then-b :result :error])))))))
