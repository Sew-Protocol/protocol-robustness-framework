(ns resolver-sim.protocols.sew.accounting-test
  "Tests for contract_model/accounting.clj and invariants.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.accounting :as ac]
            [resolver-sim.protocols.sew.invariants :as inv]))

(def usdc :0xUSDC)
(def alice "0xAlice")
(def bob   "0xBob")

(def snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50}))

(defn- base-world []
  (let [r (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                            (t/make-escrow-settings {}) snap)]
    (:world r)))

;; ---------------------------------------------------------------------------
;; withdraw-fees
;; ---------------------------------------------------------------------------

(deftest withdraw-fees-happy
  (let [w  (base-world)
        r  (ac/withdraw-fees w usdc)]
    (is (true? (:ok r)))
    (is (= 5 (:amount r)) "fee for 1000 @ 50bps = 5")
    (is (= 0 (get-in (:world r) [:total-fees usdc] 0))
        "fees reset to 0 after withdrawal")))

(deftest withdraw-fees-nothing-to-withdraw
  (let [r (ac/withdraw-fees (t/empty-world) usdc)]
    (is (false? (:ok r)))
    (is (= :no-fees-to-withdraw (:error r)))))

;; ---------------------------------------------------------------------------
;; Held adjustment ledger
;; ---------------------------------------------------------------------------

(deftest add-held-records-custody-adjustment
  (let [auth {:authorization/type :governance
              :authorization/basis :scenario-declared}
        world (ac/add-held (t/empty-world)
                           usdc
                           100
                           {:action "appeal-slash"
                            :reason :appeal-bond-posted
                            :authorization-provenance auth
                            :extra {:held/workflow-id 42
                                    :held/actor alice}})
        adjustment (last (:held-adjustments world))
        position-id [:held/position usdc :appeal-bond 42 alice]]
    (is (= 100 (get-in world [:total-held usdc])))
    (is (= {:by-token {usdc 100}
            :by-position {position-id 100}
            :by-account {:appeal-bond 100}
            :by-owner {alice 100}
            :by-workflow {42 100}}
           (:held-ledger/index world)))
    (is (= 100 (get-in world [:held/positions position-id])))
    (is (= "held-adjustment-0" (:held-adjustment/id adjustment)))
    (is (= :in (:held/direction adjustment)))
    (is (= usdc (:token adjustment)))
    (is (= 100 (:amount adjustment)))
    (is (= 0 (:held/before adjustment)))
    (is (= 100 (:held/after adjustment)))
    (is (= :appeal-bond (:held/account adjustment)))
    (is (= position-id (:held/position-id adjustment)))
    (is (= alice (:owner/address adjustment)))
    (is (= :appeal-bond-posted (:held/reason adjustment)))
    (is (= "appeal-slash" (:held/action adjustment)))
    (is (= 42 (:held/workflow-id adjustment)))
    (is (= auth (:authorization/provenance adjustment)))))

(deftest sub-held-records-custody-adjustment
  (let [position-id [:held/position usdc :escrow-principal 7]
        world (ac/sub-held {:total-held {usdc 150}
                            :held/positions {position-id 150}
                            :held-ledger/index {:by-token {usdc 150}
                                                :by-position {position-id 150}
                                                :by-account {:escrow-principal 150}
                                                :by-owner {}
                                                :by-workflow {7 150}}}
                           usdc
                           40
                           {:action "release"
                            :reason :escrow-settlement-released
                            :extra {:held/workflow-id 7}})
        adjustment (last (:held-adjustments world))]
    (is (= 110 (get-in world [:total-held usdc])))
    (is (= {:by-token {usdc 110}
            :by-position {position-id 110}
            :by-account {:escrow-principal 110}
            :by-owner {}
            :by-workflow {7 110}}
           (:held-ledger/index world)))
    (is (= 110 (get-in world [:held/positions position-id])))
    (is (= :out (:held/direction adjustment)))
    (is (= 150 (:held/before adjustment)))
    (is (= 110 (:held/after adjustment)))
    (is (= :escrow-principal (:held/account adjustment)))
    (is (= position-id (:held/position-id adjustment)))
    (is (= :escrow-settlement-released (:held/reason adjustment)))
    (is (= "release" (:held/action adjustment)))))

(deftest held-adjustment-replay-reconstructs-total-held
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :held/from alice
                                                 :held/to bob}})
                  (ac/add-held usdc 25 {:action "appeal-slash"
                                        :reason :appeal-bond-posted
                                        :authorization-provenance {:authorization/type :governance
                                                                   :authorization/basis :scenario-declared}
                                        :extra {:held/workflow-id 0
                                                :held/actor alice}})
                  (ac/sub-held usdc 40 {:action "release"
                                        :reason :escrow-settlement-released
                                        :extra {:held/workflow-id 0}}))
        replayed-state (ac/replay-held-adjustment-state (:held-adjustments world))]
    (is (= (:held-ledger/index world) (:held-ledger/index replayed-state)))
    (is (= (:total-held world) (:total-held replayed-state)))
    (is (= (:held/positions world) (:held/positions replayed-state)))
    (is (:holds? (inv/held-adjustments-reconstruct-total-held?
                  (assoc-in world [:params :held-adjustments/complete?] true))))))

(deftest complete-held-ledger-allows-create-and-release
  (let [world0 (assoc-in (t/empty-world 1000) [:params :held-adjustments/complete?] true)
        created (lc/create-escrow world0 alice usdc bob 1000
                                  (t/make-escrow-settings {}) snap)
        released (lc/release (:world created) 0 alice (fn [_ _ _] {:allowed? true}))
        world' (:world released)
        adjustments (:held-adjustments world')]
    (is (:ok created))
    (is (:ok released))
    (is (= [:escrow-principal-deposited :escrow-settlement-released]
           (mapv :held/reason adjustments)))
    (is (= ["create-escrow" "finalize-released"]
           (mapv :held/action adjustments)))
    (is (= [[:held/position usdc :escrow-principal 0]
            [:held/position usdc :escrow-principal 0]]
           (mapv :held/position-id adjustments)))
    (is (= (get-in world' [:held-ledger/index :by-token]) (:total-held world')))
    (is (= (get-in world' [:held-ledger/index :by-position]) (:held/positions world')))
    (is (= 0 (get-in world' [:total-held usdc] 0)))
    (is (= 0 (get-in world' [:held/positions [:held/position usdc :escrow-principal 0]] 0)))
    (is (:holds? (inv/held-adjustments-reconstruct-total-held? world')))))

(deftest add-held-rejects-invalid-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires token"
                        (ac/add-held (t/empty-world) nil 10)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"non-negative amount"
                        (ac/add-held (t/empty-world) usdc -1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires authorization provenance"
                        (ac/add-held (t/empty-world)
                                     usdc
                                     10
                                     {:action "governance-correction"
                                      :reason :governance-authorised-correction}))))

(deftest sub-held-rejects-underflow-and-invalid-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"underflow"
                        (ac/sub-held {:total-held {usdc 5}} usdc 10)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires token"
                        (ac/sub-held {:total-held {usdc 5}} nil 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"non-negative amount"
                        (ac/sub-held {:total-held {usdc 5}} usdc -1))))

(deftest legacy-held-arities-are-tracked-during-migration
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 10)
                  (ac/sub-held usdc 4))]
    (is (= 6 (get-in world [:total-held usdc])))
    (is (= 2 (get-in world [:held-adjustments/legacy-uses :count])))
    (is (= [{:held/direction :in :token usdc :amount 10}
            {:held/direction :out :token usdc :amount 4}]
           (get-in world [:held-adjustments/legacy-uses :entries])))))

(deftest legacy-held-arities-are-forbidden-when-ledger-complete
  (let [world (assoc-in (t/empty-world) [:params :held-adjustments/complete?] true)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"legacy held arity is forbidden"
                          (ac/add-held world usdc 10)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"legacy held arity is forbidden"
                          (ac/sub-held (assoc-in world [:total-held usdc] 10) usdc 1)))))

(deftest held-adjustments-complete-rejects-legacy-uses
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 10)
                  (assoc-in [:params :held-adjustments/complete?] true))
        result (inv/held-adjustments-reconstruct-total-held? world)]
    (is (false? (:holds? result)))
    (is (= :held-adjustments-legacy-uses-present
           (-> result :violations first :type)))))

;; ---------------------------------------------------------------------------
;; claimable balances
;; ---------------------------------------------------------------------------

(deftest record-and-withdraw-claimable
  (let [;; Manually put a terminal escrow in place
        w0 (base-world)
        w1 (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
        w2 (ac/record-claimable w1 0 bob 995)
        r  (ac/withdraw-escrow w2 0 bob)]
    (is (true? (:ok r)))
    (is (= 995 (:amount r)))
    (is (= 0 (get-in (:world r) [:claimable 0 bob] 0))
        "claimable cleared after withdrawal")))

(deftest withdraw-claimable-not-finalized
  (let [w (base-world)   ; state = :pending
        r (ac/withdraw-escrow w 0 bob)]
    (is (false? (:ok r)))
    (is (= :transfer-not-finalized (:error r)))))

(deftest withdraw-claimable-nothing-to-claim
  (let [w (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] :released)
        r (ac/withdraw-escrow w 0 alice)]
    (is (false? (:ok r)))
    (is (= :no-claimable-balance (:error r)))))

;; ---------------------------------------------------------------------------
;; BondCollector
;; ---------------------------------------------------------------------------

(deftest post-appeal-bond-deducts-fee
  (let [w    (t/empty-world)
        snap (snap-fix/escrow-snapshot {:appeal-bond-protocol-fee-bps 200}) ; 2%
        w'   (ac/post-appeal-bond w 0 alice snap usdc 1000)
        adjustment (last (:held-adjustments w'))]
    (is (= 980  (get-in w' [:bond-balances 0 alice] 0)) "net after 2% fee")
    (is (= 20   (get-in w' [:bond-fees usdc] 0))        "protocol fee recorded")
    (is (= :appeal-bond-posted (:held/reason adjustment)))
    (is (= "post-appeal-bond" (:held/action adjustment)))
    (is (= 0 (:held/workflow-id adjustment)))
    (is (= alice (:held/actor adjustment)))))

(deftest slash-bond-happy
  (let [w  (-> (t/empty-world)
               (assoc-in [:escrow-transfers 0] {:token usdc
                                                :escrow-state :disputed
                                                :to bob
                                                :from alice
                                                :amount-after-fee 0})
               (assoc-in [:bond-balances 0 alice] 980)
               (assoc-in [:total-held usdc] 980))
        r  (ac/slash-bond w 0 alice)]
    (is (true? (:ok r)))
    (is (= 980 (:slashed r)))
    (is (= 0 (get-in (:world r) [:bond-balances 0 alice] 0)))
    (is (= 980 (get-in (:world r) [:bond-slashed 0] 0)))
    (is (= :appeal-bond-slashed
           (:held/reason (last (:held-adjustments (:world r))))))))

(deftest slash-bond-nothing-to-slash
  (let [r (ac/slash-bond (t/empty-world) 0 alice)]
    (is (false? (:ok r)))
    (is (= :no-bond-to-slash (:error r)))))

(deftest return-bond-happy
  (let [w (-> (t/empty-world)
              (assoc-in [:escrow-transfers 0] {:token usdc
                                               :escrow-state :disputed
                                               :to bob
                                               :from alice
                                               :amount-after-fee 0})
              (assoc-in [:bond-balances 0 alice] 980)
              (assoc-in [:total-held usdc] 980))
        r (ac/return-bond w 0 alice)]
    (is (true? (:ok r)))
    (is (= 980 (:returned r)))
    (is (= 0 (get-in (:world r) [:bond-balances 0 alice] 0)))
    (is (= 980 (get-in (:world r) [:claimable 0 alice] 0)))
    (is (= :appeal-bond-returned
           (:held/reason (last (:held-adjustments (:world r))))))))

;; ---------------------------------------------------------------------------
;; Invariants
;; ---------------------------------------------------------------------------

(deftest solvency-holds-after-create
  (let [w (base-world)]
    (is (:holds? (inv/solvency-holds? w nil)))))

(deftest solvency-fails-when-held-exceeds-live
  "Manually corrupt total-held to exceed live sum — invariant should catch it."
  (let [w    (base-world)
        bad  (assoc-in w [:total-held usdc] -1)]
    ;; live sum = 995 (one pending escrow), held = -1 → violation
    (is (not (:holds? (inv/solvency-holds? bad nil))))))

(deftest fees-non-negative-holds
  (let [w (base-world)]
    (is (:holds? (inv/fees-non-negative? w)))))

(deftest fee-monotonicity-holds-after-create
  (let [w0 (t/empty-world 1000)
        w1 (:world (lc/create-escrow w0 alice usdc bob 1000
                                     (t/make-escrow-settings {}) snap))]
    (is (:holds? (inv/fee-increased-or-equal? w0 w1))
        "fees after create >= fees before create")))

(deftest terminal-states-unchanged-invariant
  (let [w0 (assoc-in (base-world) [:escrow-transfers 0 :escrow-state] :released)
        ;; Attempt to change state (simulating a bug):
        w1  (assoc-in w0 [:escrow-transfers 0 :escrow-state] :pending)]
    (is (:holds? (inv/terminal-states-unchanged? w0 w0)) "unchanged is fine")
    (is (not (:holds? (inv/terminal-states-unchanged? w0 w1)))
        "changed terminal state detected")))

(deftest check-all-healthy-world
  (let [result (inv/check-all (base-world))]
    (is (:all-hold? result))))

(deftest single-resolution-payout-consistency-detects-dual-claimable
  (let [w0 (base-world)
        w1 (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
        w2 (assoc-in w1 [:claimable-v2 0 :settlement/principal bob] 995)
        ;; corruption: both sides become claimable for same finalized workflow
        bad (assoc-in w2 [:claimable-v2 0 :settlement/principal alice] 995)
        r (inv/single-resolution-payout-consistent? bad)]
    (is (false? (:holds? r)))
    (is (= 0 (-> r :violations first :workflow-id)))))

(deftest fraud-slash-executions-accounted-detects-missing-stake-debit
  (let [resolver "0xResolver"
        world (-> (t/empty-world 1000)
                  (assoc-in [:pending-fraud-slashes "wf0"]
                            {:resolver resolver
                             :amount 200
                             :reason :fraud
                             :status :executed
                             :proposed-at 1000
                             :appeal-deadline 1100
                             :appeal-bond-held 0
                             :contest-deadline 0})
                  ;; corruption: executed slash not reflected in slash totals
                  (assoc-in [:resolver-slash-total resolver] 0))
        r (inv/fraud-slash-executions-accounted? world)]
    (is (false? (:holds? r)))
    (is (= resolver (-> r :violations first :resolver)))))
