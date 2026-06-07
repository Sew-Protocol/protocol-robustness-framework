(ns resolver-sim.financial.loss-test
  "Tests for financial loss lifecycle classification.
   Covers all five lifecycle states: :normal, :loss-pending-finality,
   :loss-realized, :loss-irrecoverable, and the haircut path."
  (:require [clojure.test :refer :all]
            [resolver-sim.financial.loss :as loss]
            [resolver-sim.protocols.sew.types :as t]))

(deftest no-shortfall-is-normal
  (let [world (t/empty-world 1000)
        r     (loss/classify-loss world :USDC)]
    (is (= :normal (:loss/status r)))
    (is (false? (:loss/user-realized? r)))
    (is (nil? (:loss/reason r)))))

(deftest shortfall-without-financial-finality-pending
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions "pos1"]
                            {:token :USDC :workflow-id 0
                             :status :unwinding
                             :shortfall {:fulfilled-amount 800
                                         :deferred-amount 200
                                         :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-pending-finality (:loss/status r))
        "shortfall without financial finality must be pending, not realized")
    (is (false? (:loss/user-realized? r)))))

(deftest shortfall-during-challengeable-phase-is-risk-not-pending
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                  (assoc-in [:yield/positions "pos1"]
                            {:token :USDC :workflow-id 0
                             :shortfall {:fulfilled-amount 800
                                         :deferred-amount 200
                                         :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-risk (:loss/status r))
        "shortfall while disputed must be risk, not pending (challenge window still open)")
    (is (false? (:loss/user-realized? r)))))

(deftest deferred-shortfall-with-finality-realized
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {:USDC 500})
                  (assoc :claimable {:USDC 200})
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions "pos1"]
                            {:token :USDC :workflow-id 0
                             :shortfall {:fulfilled-amount 800
                                         :deferred-amount 200
                                         :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-realized (:loss/status r))
        "deferred shortfall at finality must be realized")
    (is (true? (:loss/user-realized? r)))))

(deftest haircut-only-shortfall-realized
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {:USDC 500})
                  (assoc :claimable {:USDC 200})
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions "pos1"]
                            {:token :USDC :workflow-id 0
                             :shortfall {:fulfilled-amount 800
                                         :deferred-amount 0
                                         :haircut-amount 200}}))
        r     (loss/classify-loss world :USDC)]
    (is (= :loss-realized (:loss/status r))
        "haircut-only shortfall must be realized (haircut is a loss)")
    (is (= :haircut-loss (:loss/reason r)))
    (is (true? (:loss/user-realized? r)))))

(deftest irrecoverable-when-held-below-threshold
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {:USDC 50})
                  (assoc :claimable {:USDC 10})
                  (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                  (assoc-in [:yield/positions "pos1"]
                            {:token :USDC :workflow-id 0
                             :shortfall {:fulfilled-amount 0
                                         :deferred-amount 1000
                                         :haircut-amount 0}}))
        r     (loss/classify-loss world :USDC {:max-irrecoverable-ratio 0.2})]
    (is (= :loss-irrecoverable (:loss/status r))
        "held+claimable 60 / deferred 1000 = 0.06 < 0.2 threshold")
    (is (= :irrecoverable-shortfall (:loss/reason r)))))

(deftest loss-active-predicate
  (let [normal-world (t/empty-world 1000)
        r1 (loss/classify-loss normal-world :USDC)
        ;; Create a world with shortfall
        loss-world (-> (t/empty-world 1000)
                       (assoc-in [:escrow-transfers 0 :escrow-state] :refunded)
                       (assoc-in [:yield/positions "pos1"]
                                 {:token :USDC :workflow-id 0
                                  :shortfall {:fulfilled-amount 0
                                              :deferred-amount 100
                                              :haircut-amount 0}}))
        r2 (loss/classify-loss loss-world :USDC)]
    (is (false? (loss/loss-active? r1)))
    (is (true? (loss/loss-active? r2)))
    (is (false? (loss/loss-realized? r1)))
    (is (true? (loss/loss-realized? r2)))))
