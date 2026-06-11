(ns resolver-sim.protocols.sew.lifecycle-test
  "Unit tests for contract_model/lifecycle.clj.

   Covers createEscrow, release, senderCancel, recipientCancel,
   autoCancelDisputedEscrow — happy paths and every revert condition."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.sew.types     :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def alice "0xAlice")
(def bob   "0xBob")
(def carol "0xCarol")
(def usdc  :0xUSDC)

(def base-snapshot
  (snap-fix/escrow-snapshot
   {:escrow-fee-bps            50    ; 0.5%
    :default-auto-release-delay 0
    :default-auto-cancel-delay  0
    :max-dispute-duration       3600
    :appeal-window-duration     1800}))

(def base-settings
  (t/make-escrow-settings {}))

(def allow-all-strategy
  "Release strategy that always allows."
  (fn [_world _wf _caller] {:allowed? true :reason-code 0}))

(def deny-strategy
  "Release strategy that always denies (not authorized = reason code 1)."
  (fn [_world _wf _caller] {:allowed? false :reason-code 1}))

(defn- create-one
  "Helper: create one escrow from alice → bob, 1000 USDC. Returns result."
  ([] (create-one {}))
  ([settings-overrides]
   (lc/create-escrow
    (t/empty-world 1000)
    alice usdc bob 1000
    (merge base-settings settings-overrides)
    base-snapshot)))

(defn- world-with-one-escrow
  "World with a single created escrow."
  []
  (:world (create-one)))

(defn- world-disputed
  "World with escrow 0 in :disputed state."
  []
  (-> (world-with-one-escrow)
      (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
      (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
      (assoc-in [:dispute-timestamps 0] 1000)))

;; ---------------------------------------------------------------------------
;; create-escrow
;; ---------------------------------------------------------------------------

(deftest create-escrow-happy
  (let [r (create-one)]
    (is (true? (:ok r)))
    (is (= 0 (:workflow-id r)))
    (let [w  (:world r)
          et (t/get-transfer w 0)]
      (is (= :pending (:escrow-state et)))
      (is (= alice (:from et)))
      (is (= bob (:to et)))
      (is (= usdc (:token et)))
      ;; fee = 1000 * 50 / 10000 = 5
      (is (= 995 (:amount-after-fee et)) "amount-after-fee = 1000 - 5")
      (is (= 995 (get-in w [:total-held usdc])) "total-held increases by amount-after-fee")
      (is (= 5   (get-in w [:total-fees usdc])) "total-fees increases by fee"))))

(deftest create-escrow-assigns-sequential-workflow-ids
  (let [w0 (:world (create-one))
        r1 (lc/create-escrow w0 carol usdc alice 2000 base-settings base-snapshot)]
    (is (= 1 (:workflow-id r1)) "second escrow gets workflow-id 1")))

(deftest create-escrow-zero-fee
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 base-settings snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= 1000 (:amount-after-fee et)) "no fee deducted when fee-bps=0")
    (is (= 0    (get-in (:world r) [:total-fees usdc] 0)))))

(deftest create-escrow-guard-nil-token
  (let [r (lc/create-escrow (t/empty-world) alice nil bob 1000 base-settings base-snapshot)]
    (is (false? (:ok r)))
    (is (= :invalid-token (:error r)))))

(deftest create-escrow-guard-nil-to
  (let [r (lc/create-escrow (t/empty-world) alice usdc nil 1000 base-settings base-snapshot)]
    (is (false? (:ok r)))
    (is (= :invalid-recipient (:error r)))))

(deftest create-escrow-guard-zero-amount
  (let [r (lc/create-escrow (t/empty-world) alice usdc bob 0 base-settings base-snapshot)]
    (is (false? (:ok r)))
    (is (= :amount-zero (:error r)))))

(deftest create-escrow-guard-both-auto-times
  (let [r (create-one {:auto-release-time 2000 :auto-cancel-time 3000})]
    (is (false? (:ok r)))
    (is (= :cannot-set-both-auto-times (:error r)))))

(deftest create-escrow-applies-default-auto-release
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :default-auto-release-delay 3600})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 base-settings snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= (+ 1000 3600) (:auto-release-time et))
        "auto-release-time = block-time + default-delay")))

(deftest create-escrow-applies-default-auto-cancel
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :default-auto-cancel-delay 7200})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 base-settings snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= (+ 1000 7200) (:auto-cancel-time et)))))

(deftest create-escrow-explicit-auto-times-override-defaults
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :default-auto-release-delay 3600})
        sett (t/make-escrow-settings {:auto-release-time 9999})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 sett snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= 9999 (:auto-release-time et)) "explicit setting overrides default")))

(deftest create-escrow-custom-resolver-in-settings
  (let [sett (t/make-escrow-settings {:custom-resolver "0xResolver"})
        r    (lc/create-escrow (t/empty-world) alice usdc bob 1000 sett base-snapshot)
        et   (t/get-transfer (:world r) 0)]
    (is (= "0xResolver" (:dispute-resolver et)))))

(deftest create-escrow-snapshot-frozen
  ;; Verify the snapshot stored in world does not reference live config.
  ;; After creation, modifying global config does NOT change the per-escrow snapshot.
  (let [w1    (world-with-one-escrow)
        ;; "change governance" — modify the snapshot reference outside the stored map
        snap2 (snap-fix/escrow-snapshot {:escrow-fee-bps 999})
        w2    (assoc-in w1 [:module-snapshots 99] snap2)]  ; different id, shouldn't matter
    (is (= 50 (:escrow-fee-bps (t/get-snapshot w2 0)))
        "snapshot for workflow 0 is frozen at creation")))

;; ---------------------------------------------------------------------------
;; release
;; ---------------------------------------------------------------------------

(deftest release-happy
  (let [w (world-with-one-escrow)
        r (lc/release w 0 alice allow-all-strategy)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))
    (is (= 0 (get-in (:world r) [:total-held usdc] 0))
        "total-held decremented on release")))

(deftest release-invalid-workflow
  (let [r (lc/release (world-with-one-escrow) 99 alice allow-all-strategy)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest release-not-pending
  (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] :disputed)
        r (lc/release w 0 alice allow-all-strategy)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))))

(deftest release-no-strategy
  (let [r (lc/release (world-with-one-escrow) 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :release-strategy-not-set (:error r)))))

(deftest release-strategy-denies
  (let [r (lc/release (world-with-one-escrow) 0 carol deny-strategy)]
    (is (false? (:ok r)))
    (is (= :not-sender (:error r)))))

;; ---------------------------------------------------------------------------
;; sender-cancel
;; ---------------------------------------------------------------------------

(deftest sender-cancel-unilateral
  (let [w (world-with-one-escrow)
        r (lc/sender-cancel w 0 alice {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))
    (is (= 0 (get-in (:world r) [:total-held usdc] 0)))))

(deftest sender-cancel-mutual-consent-first-party
  "Sender sets agree-to-cancel but recipient hasn't yet — no finalisation."
  (let [w (world-with-one-escrow)
        r (lc/sender-cancel w 0 alice nil)]
    (is (true? (:ok r)))
    (is (= :pending (t/escrow-state (:world r) 0)) "still pending")
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :sender-status])))))

(deftest sender-cancel-mutual-consent-both-agree
  "Sender and recipient both call cancel — finalises on second call."
  (let [w0   (world-with-one-escrow)
        ;; Recipient already agreed
        w1   (assoc-in w0 [:escrow-transfers 0 :recipient-status] :agree-to-cancel)
        r    (lc/sender-cancel w1 0 alice nil)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)) "finalises when both agree")))

(deftest sender-cancel-not-sender
  (let [r (lc/sender-cancel (world-with-one-escrow) 0 bob nil)]
    (is (false? (:ok r)))
    (is (= :not-sender (:error r)))))

(deftest sender-cancel-not-pending
  (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] :released)
        r (lc/sender-cancel w 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))))

(deftest sender-cancel-strategy-blocks
  (let [r (lc/sender-cancel (world-with-one-escrow) 0 alice {:can-cancel? false :unilateral-cancel? false})]
    (is (false? (:ok r)))
    (is (= :not-authorized-to-cancel-yet (:error r)))))

;; ---------------------------------------------------------------------------
;; recipient-cancel
;; ---------------------------------------------------------------------------

(deftest recipient-cancel-unilateral
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest recipient-cancel-mutual-consent-first-party
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob nil)]
    (is (true? (:ok r)))
    (is (= :pending (t/escrow-state (:world r) 0)))
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :recipient-status])))))

(deftest recipient-cancel-mutual-consent-finalises
  (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :sender-status] :agree-to-cancel)
        r (lc/recipient-cancel w 0 bob nil)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest recipient-cancel-not-recipient
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :not-recipient (:error r)))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-escrow
;; ---------------------------------------------------------------------------

(deftest auto-cancel-disputed-happy
  (let [w (-> (world-disputed)
              ;; block-time > dispute-ts + max-dispute-duration (1000+3600=4600)
              (assoc :block-time 5000))
        r (lc/auto-cancel-disputed-escrow w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))
    (is (nil? (get-in (:world r) [:dispute-timestamps 0]))
        "dispute timestamp cleared on auto-cancel")))

(deftest auto-cancel-disputed-not-disputed
  (let [r (lc/auto-cancel-disputed-escrow (world-with-one-escrow) 0)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(deftest auto-cancel-disputed-has-pending-settlement
  "CRIT-3: must not override a resolver's pending decision."
  (let [pending {:exists true :is-release true :appeal-deadline 9999 :resolution-hash nil}
        w       (-> (world-disputed)
                    (assoc :block-time 5000)
                    (assoc-in [:pending-settlements 0] pending))
        r       (lc/auto-cancel-disputed-escrow w 0)]
    (is (false? (:ok r)))
    (is (= :has-pending-settlement (:error r)))))

(deftest auto-cancel-disputed-timeout-not-exceeded
  (let [w (assoc (world-disputed) :block-time 2000)  ; 2000 < 1000+3600
        r (lc/auto-cancel-disputed-escrow w 0)]
    (is (false? (:ok r)))
    (is (= :dispute-timeout-not-exceeded (:error r)))))

;; ---------------------------------------------------------------------------
;; Fork-finality: terminal mutation rejection
;;
;; These tests verify that every lifecycle action rejects terminal escrows at
;; the domain-function level, not only at the state-machine transition graph.
;; They complement the absorbing-state tests in state_machine_test.clj.
;; ---------------------------------------------------------------------------

(deftest terminal-escrow-rejects-release
  (testing "Terminal escrows reject release at lifecycle guard"
    (doseq [terminal t/terminal-states]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/release w 0 alice (fn [_ _ _] {:allowed? true}))]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-sender-cancel
  (testing "Terminal escrows reject sender-cancel at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/sender-cancel w 0 alice nil)]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-recipient-cancel
  (testing "Terminal escrows reject recipient-cancel at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/recipient-cancel w 0 bob nil)]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-raise-dispute
  (testing "Terminal escrows reject raise-dispute at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/raise-dispute w 0 alice)]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-execute-resolution
  (testing "Terminal escrows reject execute-resolution at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (-> (world-with-one-escrow)
                    (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                    (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
                    (assoc-in [:escrow-transfers 0 :dispute-resolver] "0xResolver")
                    (assoc-in [:escrow-transfers 0 :escrow-state] terminal))
              r (res/execute-resolution w 0 "0xResolver" true "0xhash" nil)]
          (is (false? (:ok r)))
          (is (= :transfer-not-in-dispute (:error r))))))))
