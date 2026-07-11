(ns resolver-sim.protocols.sew.accounting-test
  "Tests for contract_model/accounting.clj and invariants.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.accounting :as ac]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.hash.canonical          :as hash]))

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
        artifact (get-in world [:held-artifacts (:held-adjustment/id adjustment)])
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
    (is (= auth (:authorization/provenance adjustment)))
    (is (= "held-custody-adjustment.artifact.v1" (:schema-version artifact)))
    (is (= :held-custody-adjustment (:artifact/kind artifact)))
    (is (= "held-custody-held-adjustment-0" (:artifact/id artifact)))
    (is (string? (:artifact/hash artifact)))
    (is (= "held-adjustment-0" (:held-adjustment/id artifact)))
    (is (= :in (:held/direction artifact)))
    (is (= :appeal-bond-posted (:held/reason artifact)))
    (is (= {:authorization/type :governance
            :authorization/basis :scenario-declared}
           (:authorization/provenance artifact)))))

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
                            :extra {:held/workflow-id 7
                                    :owner/address bob}})
        adjustment (last (:held-adjustments world))]
    (is (= 110 (get-in world [:total-held usdc])))
    (is (= {:by-token {usdc 110}
            :by-position {position-id 110}
            :by-account {:escrow-principal 110}
            :by-owner {bob -40}
            :by-workflow {7 110}}
           (:held-ledger/index world)))
    (is (= 110 (get-in world [:held/positions position-id])))
    (is (= :out (:held/direction adjustment)))
    (is (= 150 (:held/before adjustment)))
    (is (= 110 (:held/after adjustment)))
    (is (= :escrow-principal (:held/account adjustment)))
    (is (= position-id (:held/position-id adjustment)))
    (is (= bob (:owner/address adjustment)))
    (is (= :escrow-settlement-released (:held/reason adjustment)))
    (is (= "release" (:held/action adjustment)))))

(deftest held-adjustment-replay-reconstructs-total-held
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
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
                                        :extra {:held/workflow-id 0
                                                :owner/address bob}}))
        replayed-state (ac/replay-held-adjustment-state (:held-adjustments world))]
    (is (= (:held-ledger/index world) (:held-ledger/index replayed-state)))
    (is (= (:total-held world) (:total-held replayed-state)))
    (is (= (:held/positions world) (:held/positions replayed-state)))
    (is (= (set (map :held-adjustment/id (:held-adjustments world)))
           (set (keys (:held-artifacts world)))))
    (is (:holds? (inv/held-adjustments-reconstruct-total-held?
                  (assoc-in world [:params :held-adjustments/complete?] true))))))

(deftest held-custody-closed-form-checks-pass-on-valid-artifacts
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
                                                 :held/from alice
                                                 :held/to bob}})
                  (ac/sub-held usdc 40 {:action "release"
                                        :reason :escrow-settlement-released
                                        :extra {:held/workflow-id 0
                                                :owner/address bob}}))
        checks (ac/held-custody-closed-form-checks (vals (:held-artifacts world)))]
    (is (= [:held-custody/hash-integrity
            :held-custody/local-delta
            :held-custody/non-negative-after
            :held-custody/sequence-replay]
           (mapv :check/id checks)))
    (is (every? #(= :pass (:status %)) checks))))

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

(deftest held-artifacts-must-match-derived-ledger-view
  (let [world (-> (t/empty-world)
                  (ac/add-held usdc 100 {:action "create-escrow"
                                         :reason :escrow-principal-deposited
                                         :extra {:held/workflow-id 0
                                                 :owner/address alice
                                                 :held/from alice
                                                 :held/to bob}}))
        tampered (assoc-in world
                           [:held-artifacts "held-adjustment-0" :amount]
                           999)]
    (is (:holds? (inv/held-artifacts-derived-from-adjustments? world)))
    (is (false? (:holds? (inv/held-artifacts-derived-from-adjustments? tampered))))))

(deftest add-held-rejects-invalid-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires token"
                        (ac/add-held (t/empty-world) nil 10 {:action "test"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"non-negative amount"
                        (ac/add-held (t/empty-world) usdc -1 {:action "test"})))
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
                        (ac/sub-held {:total-held {usdc 5}} usdc 10
                                     {:action "test" :reason :escrow-settlement-released})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires token"
                        (ac/sub-held {:total-held {usdc 5}} nil 1 {:action "test"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"non-negative amount"
                        (ac/sub-held {:total-held {usdc 5}} usdc -1 {:action "test"}))))

;; ---------------------------------------------------------------------------
;; Force-authorisation consumption
;; ---------------------------------------------------------------------------

(deftest force-authorised-sub-held-succeeds
  (let [auth-id "fa-test-release-a1b2c3d4"
        held 100
        sub-amt 40
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount sub-amt
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :force-authorised-release
                    :held/workflow-id 42}
         scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-hash scope-hash}
         world (ac/sub-held {:total-held {usdc held}}
                            usdc sub-amt
                           {:action "finalize-released"
                            :reason :force-authorised-release
                            :authorization-provenance auth-prov
                            :extra {:held/workflow-id 42
                                    :owner/address bob}})
        consumed (get-in world [:force-authorisations/consumed auth-id])]
    (is (= (- held sub-amt) (get-in world [:total-held usdc])))
    (is (true? (:consumed? consumed)))
    (is (= auth-id (:authorization/id consumed)))
    (is (= :force-authorisation (:authorization/type consumed)))
    (is (= scope-hash (:authorization/scope-hash consumed)))
    (is (= sub-amt (:amount consumed)))
    (is (= 42 (:workflow-id consumed)))
    (is (= bob (:owner/address consumed)))
    (is (= :force-authorised-release (:held/reason consumed)))
    (is (= "finalize-released" (:consumed/action consumed)))))

(deftest force-authorised-sub-held-rejects-reuse
  (let [auth-id "fa-test-reuse-a1b2c3d4"
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount 40
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :force-authorised-release
                    :held/workflow-id 42}
         scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-hash scope-hash}
         world (ac/sub-held {:total-held {usdc 100}}
                            usdc 40
                            {:action "finalize-released"
                             :reason :force-authorised-release
                             :authorization-provenance auth-prov
                             :extra {:held/workflow-id 42
                                     :owner/address bob}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"already consumed"
                          (ac/sub-held world usdc 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id 42
                                                :owner/address bob}})))))

(deftest force-authorised-sub-held-rejects-scope-mismatch
  (let [auth-id "fa-test-mismatch-a1b2c3d4"
         ;; Scope hash computed with :force-authorised-refund
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount 40
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :force-authorised-refund
                    :held/workflow-id 42}
         scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
         auth-prov {:authorization/type :force-authorisation
                    :authorization/id auth-id
                    :authorization/scope-hash scope-hash}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"scope mismatch"
                          (ac/sub-held {:total-held {usdc 100}} usdc 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance auth-prov
                                        :extra {:held/workflow-id 42
                                                :owner/address bob}})))))

(deftest exceptional-held-reason-requires-auth
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires authorization provenance"
                        (ac/sub-held {:total-held {usdc 100}} usdc 40
                                     {:action "finalize-released"
                                      :reason :force-authorised-release
                                      :extra {:held/workflow-id 42
                                              :owner/address bob}}))))

(deftest normal-held-reason-no-auth-ok
  (let [world (ac/sub-held {:total-held {usdc 100} :held-ledger/index {:by-token {usdc 100}}}
                           usdc 40
                           {:action "finalize-released"
                            :reason :escrow-settlement-released
                            :extra {:held/workflow-id 42
                                    :owner/address bob}})]
    (is (= 60 (get-in world [:total-held usdc])))))

;; ---------------------------------------------------------------------------
;; Transition-level held-adjustment delta invariant
;; ---------------------------------------------------------------------------

(deftest held-adjustments-cover-total-held-delta-passes
  (let [world-before {:total-held {usdc 100} :held-adjustments []}
        world-after  (ac/sub-held world-before usdc 40
                                  {:action "release"
                                   :reason :escrow-settlement-released
                                   :extra {:held/workflow-id 7
                                           :owner/address bob}})
        result (inv/held-adjustments-cover-total-held-delta?
                world-before world-after)]
    (is (:holds? result))))

(deftest held-adjustments-cover-total-held-delta-detects-mismatch
  (let [world-before {:total-held {usdc 100} :held-adjustments []}
        world-after  (assoc-in world-before [:total-held usdc] 50)
        result (inv/held-adjustments-cover-total-held-delta?
                world-before world-after)]
    (is (false? (:holds? result)))
    (is (= -50 (-> result :violations first :delta-held)))
    (is (= 0 (-> result :violations first :held-adjustment-delta)))))

(deftest held-adjustments-cover-total-held-delta-supports-allowlist
  (let [world-before {:total-held {usdc 100}
                      :held-adjustments []
                      :params {:held-adjustments/allow-transition-mismatch true}}
        world-after  (assoc-in world-before [:total-held usdc] 50)
        result (inv/held-adjustments-cover-total-held-delta?
                world-before world-after)]
    (is (:holds? result))))

;; ---------------------------------------------------------------------------
;; Partial-fill principal loss reason
;; ---------------------------------------------------------------------------

(deftest partial-fill-principal-loss-requires-auth
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires authorization provenance"
                        (ac/sub-held {:total-held {usdc 100}} usdc 40
                                     {:action "impair"
                                      :reason :partial-fill-principal-loss
                                      :extra {:held/workflow-id 42}}))))

(deftest partial-fill-principal-loss-with-auth-succeeds
  (let [auth-id "fa-pl-test"
         scope-map {:authorization/id auth-id
                    :authorization/type :force-authorisation
                    :held/direction :out
                    :token usdc
                    :amount 40
                    :held/account :escrow-principal
                    :owner/address bob
                    :held/reason :partial-fill-principal-loss
                    :held/workflow-id 42}
        scope-hash (hash/domain-hash "force-authorisation-scope" scope-map)
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-hash scope-hash}
        world (ac/sub-held {:total-held {usdc 100}}
                           usdc 40
                           {:action "impair"
                            :reason :partial-fill-principal-loss
                            :authorization-provenance auth-prov
                            :extra {:held/workflow-id 42
                                    :owner/address bob}})]
    (is (= 60 (get-in world [:total-held usdc])))
    (is (true? (get-in world [:force-authorisations/consumed auth-id :consumed?])))
    (is (= :partial-fill-principal-loss
           (get-in world [:force-authorisations/consumed auth-id :held/reason])))))

;; ---------------------------------------------------------------------------
;; claimable balances
;; ---------------------------------------------------------------------------

(deftest record-and-withdraw-claimable
  (let [;; Manually put a terminal escrow in place
        w0 (base-world)
        w1 (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
        w2 (ac/record-claimable-v2 w1 0 :settlement/principal bob 995)
        r  (ac/withdraw-escrow w2 0 bob)]
    (is (true? (:ok r)))
    (is (= 995 (:amount r)))
    (is (= 0 (get-in (:world r) [:claimable-v2 0 :settlement/principal bob] 0))
        "claimable cleared after withdrawal")))

(deftest withdraw-claimable-pending-no-claimable
  (let [w (base-world)   ; state = :pending, no claimable balance
        r (ac/withdraw-escrow w 0 bob)]
    (is (false? (:ok r)))
    (is (= :no-claimable-balance (:error r)))))

(deftest withdraw-claimable-pending-with-claimable
  (let [w (-> (base-world)
              (assoc-in [:claimable-v2 0 :settlement/principal bob] 500))
        r (ac/withdraw-escrow w 0 bob)]
    (is (true? (:ok r)))
    (is (= 500 (:amount r)))
    (is (= 0 (get-in (:world r) [:claimable-v2 0 :settlement/principal bob] 0)))))

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
    (is (= 980 (get-in (:world r) [:claimable-v2 0 :settlement/principal alice] 0)))
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
