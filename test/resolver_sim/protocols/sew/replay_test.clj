(ns resolver-sim.protocols.sew.replay-test
  "Unit tests for the open-world scenario replay proto.

   Covers:
     - Structural validation (schema-version, seq, time)
     - Happy-path and dispute scenarios
     - Adversarial rejections (not halts)
     - Invariant enforcement (solvency = not <=, terminal irreversibility)
     - Edge cases: time regression, unknown agent, overflow guard, duplicate seq
     - Escalation flows: full chain (0→1→2), mid-pending escalation, adversarial rejections"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay    :as replay]
            [resolver-sim.db.temporal              :as temporal]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew            :as sew]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.types     :as t]
            [resolver-sim.protocols.sew.resolution :as res]))

;; ---------------------------------------------------------------------------
;; Scenario builder helpers
;; ---------------------------------------------------------------------------

(def ^:private alice    {:id "alice"    :type "honest"        :address "0xAlice"})
(def ^:private bob      {:id "bob"      :type "honest"        :address "0xBob"})
(def ^:private mallory  {:id "mallory"  :type "attacker"      :address "0xMallory"})
(def ^:private resolver {:id "resolver" :type "honest"        :address "0xResolver"})

(def ^:private default-params
  {:resolver-fee-bps 50 :appeal-window-duration 0
   :max-dispute-duration 2592000 :appeal-bond-protocol-fee-bps 0})

(defn- sc [& {:keys [agents params init-time events schema-version]
              :or   {agents       [alice bob resolver]
                     params       default-params
                     init-time    1000
                     schema-version "1.0"}}]
  {:scenario-id        "test"
   :schema-version     schema-version
   :seed               42
   :agents             agents
   :protocol-params    params
   :initial-block-time init-time
   :events             events})

;; ---------------------------------------------------------------------------
;; Section 1: Structural validation
;; ---------------------------------------------------------------------------

(deftest test-validation-wrong-schema-version
  (let [r (sew/replay-with-sew-protocol
           (sc :schema-version "2.0"
               :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unsupported-schema-version (:halt-reason r)))))

(deftest test-validation-missing-schema-version
  (let [r (sew/replay-with-sew-protocol
           (assoc (sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                :params {:token "0xUSDC" :to "0xBob" :amount 5000}}])
                  :schema-version nil))]
    (is (= :invalid (:outcome r)))))

(deftest test-validation-non-contiguous-seq
  (let [r (sew/replay-with-sew-protocol
           (sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                        {:seq 2 :time 1001 :agent "alice" :action "release"  ; gap at 1
                          :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :non-contiguous-event-seq (:halt-reason r)))))

(deftest test-validation-duplicate-seq
  (let [r (sew/replay-with-sew-protocol
           (sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                        {:seq 0 :time 1001 :agent "alice" :action "release"  ; dup
                          :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))))

(deftest test-validation-non-monotonic-time
  (let [r (sew/replay-with-sew-protocol
           (sc :events [{:seq 0 :time 1005 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                        {:seq 1 :time 1000 :agent "alice" :action "release"  ; backward
                          :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :non-monotonic-event-time (:halt-reason r)))))

(deftest test-validation-event-time-before-initial
  (let [r (sew/replay-with-sew-protocol
           (sc :init-time 2000
               :events [{:seq 0 :time 999 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :event-time-before-initial (:halt-reason r)))))

(deftest test-validation-unknown-agent-in-event
  (let [r (sew/replay-with-sew-protocol
           (sc :events [{:seq 0 :time 1000 :agent "nobody" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unknown-agent-in-event (:halt-reason r)))))

(deftest test-validation-duplicate-agent-ids
  (let [alice2 {:id "alice" :type "attacker" :address "0xEvil"}  ; same id, different address
        r (sew/replay-with-sew-protocol
           (sc :agents [alice alice2 bob resolver]
               :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :duplicate-agent-ids (:halt-reason r)))))

(deftest test-validation-duplicate-agent-addresses
  (let [alice2 {:id "alice2" :type "attacker" :address "0xAlice"}  ; same address, different id
        r (sew/replay-with-sew-protocol
           (sc :agents [alice alice2 bob resolver]
               :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :duplicate-agent-addresses (:halt-reason r)))))

;; ---------------------------------------------------------------------------
;; Section 2: Time regression in process-step
;; ---------------------------------------------------------------------------

(deftest test-time-regression-is-rejected-not-halted
  ;; Build scenario with valid structure but inject a backward-time event via
  ;; process-step directly (bypasses replay-scenario validation for testing the
  ;; step-level guard independently)
  (let [world0  (t/empty-world 2000)
        context {:agent-index {"alice" alice "resolver" resolver}
                 :snapshot    (t/make-module-snapshot {:escrow-fee-bps 50})}
        event   {:seq 0 :time 999 :agent "alice" :action "advance_time" :params {}}
        result  (replay/process-step sew/protocol context world0 event)]
    (testing "rejected, not halted"
      (is (= :rejected (get-in result [:trace-entry :result])))
      (is (false? (:halted? result))))
    (testing "world unchanged"
      (is (= 2000 (:block-time (:world result)))))))

;; ---------------------------------------------------------------------------
;; Section 3: Happy path
;; ---------------------------------------------------------------------------

(deftest test-happy-path-release
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (:events-processed r)))
    (is (= 1 (get-in r [:metrics :total-escrows])))
    (is (= 10000 (get-in r [:metrics :total-volume])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= 0 (get-in r [:trace 0 :extra :workflow-id])))))

(deftest test-compat-action-alias-underscore-vs-hyphen
  (let [base-events
        [{:seq 0 :time 1000 :agent "alice"
          :params {:token "0xUSDC" :to "0xBob" :amount 10000 :custom-resolver "0xResolver"}}
         {:seq 1 :time 1001 :agent "alice" :params {:workflow-id 0}}]
        r-underscore
        (sew/replay-with-sew-protocol
          (sc :events [(assoc (nth base-events 0) :action "create_escrow")
                       (assoc (nth base-events 1) :action "release")]))
        r-hyphen
        (sew/replay-with-sew-protocol
          (sc :events [(assoc (nth base-events 0) :action "create-escrow")
                       (assoc (nth base-events 1) :action "release")]))]
    (is (= :pass (:outcome r-underscore)))
    (is (= :pass (:outcome r-hyphen)))
    (is (= (mapv :result (:trace r-underscore))
           (mapv :result (:trace r-hyphen))))
    (is (= (get-in r-underscore [:trace 0 :transition/id])
           (get-in r-hyphen [:trace 0 :transition/id])))
    (is (= :scenario.transition/create_escrow
           (get-in r-underscore [:trace 0 :transition/id])))
    (is (= (get-in r-underscore [:trace 0 :extra :workflow-id])
           (get-in r-hyphen [:trace 0 :extra :workflow-id])))))

(deftest test-compat-workflow-id-alias-id-vs-workflow-id
  (let [create {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                :params {:token "0xUSDC" :to "0xBob" :amount 10000 :custom-resolver "0xResolver"}}
        release-workflow-id {:seq 1 :time 1001 :agent "alice" :action "release"
                             :params {:workflow-id 0}}
        release-id          {:seq 1 :time 1001 :agent "alice" :action "release"
                             :params {:id 0}}
        r-workflow-id (sew/replay-with-sew-protocol (sc :events [create release-workflow-id]))
        r-id          (sew/replay-with-sew-protocol (sc :events [create release-id]))]
    (is (= :pass (:outcome r-workflow-id)))
    (is (= :pass (:outcome r-id)))
    (is (= (mapv :result (:trace r-workflow-id))
           (mapv :result (:trace r-id))))
    (is (= (get-in r-workflow-id [:trace 1 :error])
           (get-in r-id [:trace 1 :error])))))

(deftest test-temporal-evidence-wiring-opt-in
  (testing "temporal evidence is emitted only when :temporal-evidence {:enabled? true}"
    (let [calls (atom [])
          scenario-base
          (sc :events
              [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                :params {:token "0xUSDC" :to "0xBob" :amount 10000
                         :custom-resolver "0xResolver"}}
               {:seq 1 :time 1001 :agent "alice" :action "release"
                :params {:workflow-id 0}}])]
      (with-redefs [temporal/record-temporal-run!
                    (fn [ds payload]
                      (swap! calls conj {:ds ds :payload payload})
                      {:ok true})]
        ;; disabled / absent => no call
        (sew/replay-with-sew-protocol scenario-base)
        (is (= 0 (count @calls)))

        ;; enabled => exactly one terminal emission
        (sew/replay-with-sew-protocol
         (assoc scenario-base
                :temporal-evidence {:enabled? true
                                    :datasource nil
                                    :run-id "wire-test-run"
                                    :batch-id :wire-batch
                                    :suite-id :wire-suite
                                    :git-sha "abc123"}))
        (is (= 1 (count @calls)))
        (is (= "wire-test-run" (get-in @calls [0 :payload :run :run-id])))
        (is (= :pass (get-in @calls [0 :payload :run :outcome])))))))

;; ---------------------------------------------------------------------------
;; Section 4: Dispute + resolution
;; ---------------------------------------------------------------------------

(deftest test-dispute-and-resolution
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "bob" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1005 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= 1 (get-in r [:metrics :disputes-triggered])))
    (is (= 1 (get-in r [:metrics :resolutions-executed])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (every? #(= :ok (:result %)) (:trace r)))))

(deftest test-trace-projection-includes-funds-ledger-summary
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 10000
                          :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                 :params {:workflow-id 0}}]))
        p (proto/trace-projection sew/protocol r)]
    (is (map? (:funds-ledger-summary p)))
    (is (contains? (:trace-summary p) :funds-conservation-holds?))
    (is (contains? (:trace-summary p) :funds-drift-total))
    (is (contains? (:trace-summary p) :funds-drift-by-token))))

;; ---------------------------------------------------------------------------
;; Section 5: Adversarial rejections — non-fatal
;; ---------------------------------------------------------------------------

(deftest test-attacker-unauthorized-resolution-is-revert
  (let [r (sew/replay-with-sew-protocol
           (sc :agents [alice bob mallory resolver]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "alice" :action "raise_dispute"
                  :params {:workflow-id 0}}
                ;; Mallory is not the resolver
                {:seq 2 :time 1002 :agent "mallory" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release false}}
                ;; Legit resolver succeeds
                {:seq 3 :time 1003 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (= 1 (get-in r [:metrics :attack-attempts])))
    (is (= 0 (get-in r [:metrics :attack-successes])))))

(deftest test-dispute-after-release-rejected
  (let [r (sew/replay-with-sew-protocol
           (sc :agents [alice bob mallory]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "mallory" :action "raise_dispute"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

(deftest test-unknown-agent-in-action-is-revert-not-halt
  ;; Unknown agent passes validation because it's not in the event list
  ;; at the scenario level but IS here (we're testing process-step directly)
  (let [world0  (t/empty-world 1000)
        context {:agent-index {"alice" alice}
                 :snapshot    (t/make-module-snapshot {:escrow-fee-bps 50})}
        event   {:seq 0 :time 1000 :agent "nobody" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
        result  (replay/process-step sew/protocol context world0 event)]
    (is (= :rejected (get-in result [:trace-entry :result])))
    (is (false? (:halted? result)))
    (is (= :unknown-agent (:error (:trace-entry result))))))

;; ---------------------------------------------------------------------------
;; Section 6: Overflow guard
;; ---------------------------------------------------------------------------

(deftest test-amount-overflow-guard
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               ;; amount > max-safe-amount = 922337203685477
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 999999999999999999}}]))]
    (is (= :pass (:outcome r)))    ; outcome is pass — it's a clean revert
    (is (= :rejected (get-in r [:trace 0 :result])))
    (is (= :amount-out-of-safe-range (get-in r [:trace 0 :error])))))

;; ---------------------------------------------------------------------------
;; Section 7: Mutual cancel
;; ---------------------------------------------------------------------------

(deftest test-mutual-cancel
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                {:seq 1 :time 1001 :agent "alice" :action "sender_cancel"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "bob" :action "recipient_cancel"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (every? #(= :ok (:result %)) (:trace r)))))

;; ---------------------------------------------------------------------------
;; Section 8: Auto-cancel after max dispute duration
;; ---------------------------------------------------------------------------

(deftest test-auto-cancel-after-timeout
  (let [r (sew/replay-with-sew-protocol
           (sc :params (assoc default-params :max-dispute-duration 500)
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 8000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "bob" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1501 :agent "alice" :action "auto_cancel_disputed"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 2 :result])))))

;; ---------------------------------------------------------------------------
;; Section 9: Appeal window
;; ---------------------------------------------------------------------------

(deftest test-appeal-window-deferred-settlement
  (let [r (sew/replay-with-sew-protocol
           (sc :params (assoc default-params :appeal-window-duration 100)
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "alice" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1000 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release true}}
                ;; Before deadline (deadline = 1000 + 100 = 1100)
                {:seq 3 :time 1050 :agent "bob" :action "execute_pending_settlement"
                  :params {:workflow-id 0}}
                ;; After deadline
                {:seq 4 :time 1101 :agent "bob" :action "execute_pending_settlement"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :ok (get-in r [:trace 4 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 10: Multiple concurrent escrows
;; ---------------------------------------------------------------------------

(deftest test-multiple-escrows-isolated
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 7000
                            :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "alice" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 3 :time 1002 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release false}}
                {:seq 4 :time 1003 :agent "alice" :action "release"
                  :params {:workflow-id 1}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (get-in r [:metrics :total-escrows])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 11: Solvency invariant — strict equality
;; ---------------------------------------------------------------------------

(deftest test-solvency-strict-equality-catches-overfunded-world
  ;; Directly construct a world where total-held > sum of live escrows
  ;; (simulates a finalization bug that forgot to call sub-held)
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0]
                             {:token "0xUSDC" :to "0xBob" :from "0xAlice"
                              :amount-after-fee 9000
                              :dispute-resolver nil
                              :auto-release-time 0 :auto-cancel-time 0
                              :escrow-state :released     ; terminal — not live
                              :sender-status :none :recipient-status :none})
                  ;; total-held still has 9000 — not decremented (the bug)
                  (assoc-in [:total-held "0xUSDC"] 9000))]
    (let [r (inv/check-all world)]
      (testing "strict equality catches orphaned held amount"
        ;; live-sum = 0 (escrow is terminal); held = 9000; 0 ≠ 9000 → violation
        (is (false? (:all-hold? r)))))))

(deftest test-solvency-strict-equality-passes-clean-world
  ;; Clean world: total-held exactly matches live escrow amount
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0]
                             {:token "0xUSDC" :to "0xBob" :from "0xAlice"
                              :amount-after-fee 5000
                              :dispute-resolver nil
                              :auto-release-time 0 :auto-cancel-time 0
                              :escrow-state :pending
                              :sender-status :none :recipient-status :none})
                  (assoc-in [:total-held "0xUSDC"] 5000))]
    (let [r (inv/check-all world)]
      (is (true? (:all-hold? r))))))

;; ---------------------------------------------------------------------------
;; Section 12: Terminal state irreversibility
;; ---------------------------------------------------------------------------

(deftest test-terminal-irreversibility-catches-regression
  ;; Simulate a world where an escrow regressed from :released to :pending
  (let [world-before (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :released))
        world-after  (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :pending))
        r            (inv/check-transition world-before world-after)]
    (is (false? (:all-hold? r)))
    (is (seq (get-in r [:results :terminal-states-unchanged :violations])))))

(deftest test-terminal-irreversibility-passes-valid-transition
  (let [world-before (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :pending))
        world-after  (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :disputed))
        r            (inv/check-transition world-before world-after)]
    (is (true? (:all-hold? r)))))

;; ---------------------------------------------------------------------------
;; Section 13: JSON serialization
;; ---------------------------------------------------------------------------

(deftest test-json-serialization
  (let [r    (sew/replay-with-sew-protocol
              (sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                     :params {:token "0xUSDC" :to "0xBob" :amount 500}}]))
        json (replay/result->json-str r)]
    (is (string? json))
    (is (clojure.string/includes? json "pass"))))

;; ---------------------------------------------------------------------------
;; Section 14: advance_time no-op
;; ---------------------------------------------------------------------------

(deftest test-advance-time-event
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 1000}}
                {:seq 1 :time 2000 :agent "alice" :action "advance_time"
                  :params {}}
                {:seq 2 :time 2000 :agent "alice" :action "sender_cancel"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2000 (get-in r [:trace 1 :world :block-time])))))

;; ---------------------------------------------------------------------------
;; Section 15: Full escalation chain 0 → 1 → 2
;;
;; Uses process-step directly with an escalation-fn in context.
;; Priority-3 authority: no custom-resolver; et.dispute-resolver tracks rounds.
;;
;; Setup:
;;   appeal-window-duration = 500 → execute-resolution defers verdict
;;   Level 0 → Level 1 → Level 2 (final)
;;   At level 2 execute-resolution is IMMEDIATE (bypasses appeal window)
;; ---------------------------------------------------------------------------

(def ^:private res-level-0 "0xResolver0")
(def ^:private res-level-1 "0xResolver1")
(def ^:private res-level-2 "0xResolver2")

(def ^:private appeal-params
  ;; Keep appeal window longer than the 1-day escalation cooldown so
  ;; multi-round escalation tests can validly appeal after cooldown.
  {:resolver-fee-bps 50 :appeal-window-duration 200000
   :max-dispute-duration 2592000 :appeal-bond-protocol-fee-bps 0})

(defn- make-step-context
  "Build a context with appeal-params snapshot and the cycling escalation-fn."
  []
  (let [agents [{:id "alice"    :address "0xAlice"    :type "honest"}
                {:id "bob"      :address "0xBob"      :type "honest"}
                {:id "resolver0" :address res-level-0  :type "resolver"}
                {:id "resolver1" :address res-level-1  :type "resolver"}
                {:id "resolver2" :address res-level-2  :type "resolver"}]]
    (proto/build-execution-context sew/protocol agents
                          (assoc appeal-params :escalation-resolvers {:1 res-level-1 :2 res-level-2}))))

(defn- initial-disputed-world
  "Build a world with one :disputed escrow whose et.dispute-resolver = res-level-0.
   Uses process-step to drive create_escrow + raise_dispute so invariants hold."
  [context]
  (let [w0 (t/empty-world 1000)
        s1 (replay/process-step sew/protocol context w0
                                {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                 :params {:token "0xUSDC" :to "0xBob" :amount 10000}})
        ;; Set Priority-3 resolver (no custom-resolver in create, so we patch directly)
        w1 (assoc-in (:world s1) [:escrow-transfers 0 :dispute-resolver] res-level-0)
        s2 (replay/process-step sew/protocol context w1
                                {:seq 1 :time 1000 :agent "alice" :action "raise_dispute"
                                 :params {:workflow-id 0}})]
    (:world s2)))

(deftest test-full-escalation-chain-0-to-2
  "Full chain: execute-resolution(deferred@0) → escalate → execute-resolution(deferred@1)
               → escalate → execute-resolution(immediate@2=final)."
  (let [ctx  (make-step-context)
        w    (initial-disputed-world ctx)
        ;; Level 0: resolver0 submits verdict → deferred (appeal window = 500)
        s1   (replay/process-step sew/protocol ctx w
                                  {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})
        _    (testing "level-0 verdict deferred"
               (is (= :ok (get-in s1 [:trace-entry :result])))
               (is (:exists (t/get-pending (:world s1) 0))))
        ;; Escalate 0→1 — each escalation must be in a distinct block AND respect cooldown
        s2   (replay/process-step sew/protocol ctx (:world s1)
                                  {:seq 3 :time 90001 :agent "alice" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
        _    (testing "escalation 0→1"
               (is (= :ok (get-in s2 [:trace-entry :result])))
               (is (= 1 (t/dispute-level (:world s2) 0)))
               (is (= res-level-1 (get-in (:world s2) [:escrow-transfers 0 :dispute-resolver])))
               (is (nil? (get-in (:world s2) [:pending-settlements 0]))
                   "pending cleared by escalation"))
        ;; Level 1: resolver1 submits verdict → still deferred (not yet final round)
        s3   (replay/process-step sew/protocol ctx (:world s2)
                                  {:seq 4 :time 90001 :agent "resolver1" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})
        _    (testing "level-1 verdict deferred"
               (is (= :ok (get-in s3 [:trace-entry :result])))
               (is (:exists (t/get-pending (:world s3) 0))))
        ;; Escalate 1→2 — must respect cooldown vs alice's previous escalation
        s4   (replay/process-step sew/protocol ctx (:world s3)
                                  {:seq 5 :time 180002 :agent "alice" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
        _    (testing "escalation 1→2"
               (is (= :ok (get-in s4 [:trace-entry :result])))
               (is (= 2 (t/dispute-level (:world s4) 0)))
               (is (= res-level-2 (get-in (:world s4) [:escrow-transfers 0 :dispute-resolver])))
               (is (nil? (get-in (:world s4) [:pending-settlements 0]))))
        ;; Level 2 (final round): resolver2 submits verdict → IMMEDIATE (no pending)
        s5   (replay/process-step sew/protocol ctx (:world s4)
                                  {:seq 6 :time 180002 :agent "resolver2" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})]
    (testing "final-round verdict is immediate"
      (is (= :ok (get-in s5 [:trace-entry :result])))
      (is (= :released (t/escrow-state (:world s5) 0)))
      (is (not (:exists (t/get-pending (:world s5) 0)))))
    (testing "invariants hold throughout"
      (is (true? (:all-hold? (inv/check-all (:world s5))))))))

;; ---------------------------------------------------------------------------
;; Section 16: Mid-pending escalation
;;
;; Pending settlement at level 0 → escalation clears it → stale execute-pending
;; is rejected → new resolver at level 1 proceeds.
;; ---------------------------------------------------------------------------

(deftest test-escalation-clears-pending-settlement
  "Escalation clears a pending settlement; stale execute-pending-settlement is rejected."
  (let [ctx (make-step-context)
        w   (initial-disputed-world ctx)
        ;; Submit verdict at level 0 → deferred
        s1  (replay/process-step sew/protocol ctx w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release false}})
        _   (is (:exists (t/get-pending (:world s1) 0)) "pending exists before escalation")
        ;; Escalate 0→1 — clears pending (must jump 1 day for cooldown)
        s2  (replay/process-step sew/protocol ctx (:world s1)
                                 {:seq 3 :time 90001 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (testing "pending cleared"
              (is (= :ok (get-in s2 [:trace-entry :result])))
              (is (nil? (get-in (:world s2) [:pending-settlements 0]))))
        ;; Try to execute-pending-settlement after escalation → rejected
        s3  (replay/process-step sew/protocol ctx (:world s2)
                                 {:seq 4 :time 90501 :agent "bob" :action "execute_pending_settlement"
                                  :params {:workflow-id 0}})
        _   (testing "stale execute-pending rejected"
              (is (= :rejected (get-in s3 [:trace-entry :result]))))
        ;; New resolver (level 1) successfully resolves
        s4  (replay/process-step sew/protocol ctx (:world s3)
                                 {:seq 5 :time 90501 :agent "resolver1" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release false}})]
    (testing "level-1 resolver succeeds"
      (is (= :ok (get-in s4 [:trace-entry :result]))))
    (testing "invariants hold"
      (is (true? (:all-hold? (inv/check-all (:world s4))))))))

;; ---------------------------------------------------------------------------
;; Section 17: Adversarial escalation rejections
;;
;; a. Non-participant tries to escalate → :not-participant (rejected, not halt)
;; b. After reaching max level, third escalation attempt → :escalation-not-allowed
;; ---------------------------------------------------------------------------

(deftest test-non-participant-escalation-rejected
  "Attacker (not :from or :to) cannot escalate a dispute."
  (let [ctx-with-mallory
        (let [agents [{:id "alice"    :address "0xAlice"    :type "honest"}
                      {:id "bob"      :address "0xBob"      :type "honest"}
                      {:id "mallory"  :address "0xMallory"  :type "attacker"}
                      {:id "resolver0" :address res-level-0 :type "resolver"}
                      {:id "resolver1" :address res-level-1 :type "resolver"}]]
          (proto/build-execution-context sew/protocol agents
                                (assoc appeal-params :escalation-resolvers {:1 res-level-1})))
        w   (initial-disputed-world ctx-with-mallory)
        ;; Submit verdict → deferred
        s1  (replay/process-step sew/protocol ctx-with-mallory w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        ;; Mallory (not a participant) tries to escalate
        s2  (replay/process-step sew/protocol ctx-with-mallory (:world s1)
                                 {:seq 3 :time 1000 :agent "mallory" :action "escalate_dispute"
                                  :params {:workflow-id 0}})]
    (testing "non-participant escalation rejected"
      (is (= :rejected (get-in s2 [:trace-entry :result])))
      (is (= :not-participant (get-in s2 [:trace-entry :error]))))
    (testing "world unchanged (level still 0)"
      (is (= 0 (t/dispute-level (:world s2) 0))))
    (testing "no halt"
      (is (false? (:halted? s2))))))

(deftest test-max-level-escalation-rejected
  "After two escalations (level = max-dispute-level), a third attempt is rejected."
  (let [ctx (make-step-context)
        w   (initial-disputed-world ctx)
        ;; Level 0: submit → deferred → escalate 0→1
        s1  (replay/process-step sew/protocol ctx w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        s2  (replay/process-step sew/protocol ctx (:world s1)
                                 {:seq 3 :time 90001 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (is (= 1 (t/dispute-level (:world s2) 0)))
        ;; Level 1: submit → deferred → escalate 1→2
        s3  (replay/process-step sew/protocol ctx (:world s2)
                                 {:seq 4 :time 90001 :agent "resolver1" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        s4  (replay/process-step sew/protocol ctx (:world s3)
                                 {:seq 5 :time 180002 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (testing "second escalation ok"
              (is (= :ok (get-in s4 [:trace-entry :result])))
              (is (= 2 (t/dispute-level (:world s4) 0))))
        ;; Level 2 is final: execute-resolution is immediate (no pending created)
        s5  (replay/process-step sew/protocol ctx (:world s4)
                                 {:seq 6 :time 180002 :agent "resolver2" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        _   (testing "final-round verdict is immediate"
              (is (= :ok (get-in s5 [:trace-entry :result])))
              (is (= :released (t/escrow-state (:world s5) 0))))
        ;; After finalization, a third escalation is impossible (not :disputed)
        s6  (replay/process-step sew/protocol ctx (:world s5)
                                 {:seq 7 :time 180002 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})]
    (testing "escalation rejected after finalization"
      (is (= :rejected (get-in s6 [:trace-entry :result]))))
    (testing "no halt — just a revert"
      (is (false? (:halted? s6))))
    (testing "invariants hold throughout"
      (is (true? (:all-hold? (inv/check-all (:world s6))))))))

;; ---------------------------------------------------------------------------
;; Section 18: Workflow-id alias resolution
;; ---------------------------------------------------------------------------

(deftest test-wf-alias-save-and-resolve
  ":save-id-as captures the assigned workflow-id; subsequent events resolve the alias."
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000
                            :custom-resolver "0xResolver"}
                  :save-id-as "wf0"}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id "wf0"}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (:events-processed r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))))

(deftest test-wf-alias-multi-escrow
  "Multiple aliases can be saved and resolved independently."
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 3000}
                  :save-id-as "wf0"}
                {:seq 1 :time 1001 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 4000}
                  :save-id-as "wf1"}
                {:seq 2 :time 1002 :agent "alice" :action "release"
                  :params {:workflow-id "wf1"}}
                {:seq 3 :time 1003 :agent "alice" :action "release"
                  :params {:workflow-id "wf0"}}]))]
    (is (= :pass (:outcome r)))
    (is (= 4 (:events-processed r)))
    (is (= 2 (get-in r [:metrics :total-escrows])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-wf-alias-unresolved-returns-invalid
  "A string alias with no prior :save-id-as returns :invalid outcome."
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id "no-such-alias"}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unresolved-alias (:halt-reason r)))))

(deftest test-wf-alias-integer-passes-through
  "Integer workflow-ids bypass the alias layer unchanged."
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))))

;; ---------------------------------------------------------------------------
;; Section 19b: withdraw_stake behavior
;; ---------------------------------------------------------------------------

(deftest test-withdraw-stake-blocked-during-active-dispute
  (let [r (sew/replay-with-sew-protocol
           (assoc (sc :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000
                           :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "bob" :action "raise_dispute"
                 :params {:workflow-id 0}}
                {:seq 3 :time 1002 :agent "resolver" :action "withdraw_stake"
                 :params {:amount 1000}}])
                  :allow-open-disputes? true))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :active-disputes-block-withdrawal (get-in r [:trace 3 :error])))))

(deftest test-withdraw-stake-succeeds-after-dispute-resolution
  (let [r (sew/replay-with-sew-protocol
           (sc :params (assoc default-params :appeal-window-duration 0)
               :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000
                          :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "bob" :action "raise_dispute"
                 :params {:workflow-id 0}}
                {:seq 3 :time 1002 :agent "resolver" :action "execute_resolution"
                 :params {:workflow-id 0 :is-release true}}
                {:seq 4 :time 1003 :agent "resolver" :action "withdraw_stake"
                 :params {:amount 1000}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 4 :result])))))

(deftest test-withdraw-stake-invalid-amount-nil-rejected
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1001 :agent "resolver" :action "withdraw_stake"
                 :params {}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-amount (get-in r [:trace 1 :error])))))

(deftest test-withdraw-stake-invalid-amount-zero-rejected
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1001 :agent "resolver" :action "withdraw_stake"
                 :params {:amount 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-amount (get-in r [:trace 1 :error])))))

(deftest test-withdraw-stake-invalid-amount-negative-rejected
  (let [r (sew/replay-with-sew-protocol
           (sc :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1001 :agent "resolver" :action "withdraw_stake"
                 :params {:amount -1}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-amount (get-in r [:trace 1 :error])))))

(deftest test-withdraw-stake-pending-slash-boundary-allows-withdraw
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :params (assoc default-params
                              :appeal-window-duration 100
                              :appeal-bond-amount 50)
               :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "gov" :action "propose_fraud_slash"
                 :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 1000}}
                ;; current=5000, pending-slash=1000, amount=4000 => current-amount=1000 (allowed)
                {:seq 3 :time 1002 :agent "resolver" :action "withdraw_stake"
                 :params {:amount 4000}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-withdraw-stake-pending-slash-boundary-blocks-withdraw
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :params (assoc default-params
                              :appeal-window-duration 100
                              :appeal-bond-amount 50)
               :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "gov" :action "propose_fraud_slash"
                 :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 1000}}
                ;; current=5000, pending-slash=1000, amount=4001 => current-amount=999 (blocked)
                {:seq 3 :time 1002 :agent "resolver" :action "withdraw_stake"
                 :params {:amount 4001}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :pending-slash-blocks-withdrawal (get-in r [:trace 3 :error])))))

(deftest test-withdraw-stake-blocked-while-resolver-frozen
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :params (assoc default-params :appeal-window-duration 100)
               :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "gov" :action "propose_fraud_slash"
                 :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 1000}}
                ;; appeal window expired (1001 + 100)
                {:seq 3 :time 1101 :agent "alice" :action "execute_fraud_slash"
                 :params {:workflow-id 0}}
                ;; still within freeze window: withdraw must be blocked
                {:seq 4 :time 2000 :agent "resolver" :action "withdraw_stake"
                 :params {:amount 100}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= :rejected (get-in r [:trace 4 :result])))
    (is (= :resolver-frozen (get-in r [:trace 4 :error])))))

(deftest test-withdraw-stake-allows-at-unfreeze-boundary
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        freeze-until (+ 1101 259200)
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :params (assoc default-params :appeal-window-duration 100)
               :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                 :params {:amount 5000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "gov" :action "propose_fraud_slash"
                 :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 1000}}
                {:seq 3 :time 1101 :agent "alice" :action "execute_fraud_slash"
                 :params {:workflow-id 0}}
                ;; exactly at freeze boundary: should be allowed
                {:seq 4 :time freeze-until :agent "resolver" :action "withdraw_stake"
                 :params {:amount 100}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= :ok (get-in r [:trace 4 :result])))))

(deftest test-withdraw-escrow-liquidity-crunch-ordering-toggle-then-withdraw
  "Same timestamp ordering: if liquidity crunch is enabled first,
   withdraw_escrow should be rejected as liquidity-insufficient."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                 :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "gov" :action "set_token_liquidity_crunch"
                 :params {:token "0xUSDC" :active? true}}
                {:seq 3 :time 1002 :agent "bob" :action "withdraw_escrow"
                 :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :liquidity-insufficient (get-in r [:trace 3 :error])))))

(deftest test-withdraw-escrow-liquidity-crunch-ordering-withdraw-then-toggle
  "Same timestamp ordering: if withdraw_escrow executes before liquidity crunch
   is enabled, withdrawal should succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                 :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "bob" :action "withdraw_escrow"
                 :params {:workflow-id 0}}
                {:seq 3 :time 1002 :agent "gov" :action "set_token_liquidity_crunch"
                 :params {:token "0xUSDC" :active? true}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-withdraw-fees-liquidity-crunch-ordering-toggle-then-withdraw
  "Same timestamp ordering: if liquidity crunch is enabled first,
   withdraw_fees should be rejected as liquidity-insufficient."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "gov" :action "set_token_liquidity_crunch"
                 :params {:token "0xUSDC" :active? true}}
                {:seq 2 :time 1001 :agent "gov" :action "withdraw_fees"
                 :params {:token "0xUSDC"}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= :liquidity-insufficient (get-in r [:trace 2 :error])))))

(deftest test-withdraw-fees-liquidity-crunch-ordering-withdraw-then-toggle
  "Same timestamp ordering: if withdraw_fees executes before liquidity crunch
   is enabled, fee withdrawal should succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "gov" :action "withdraw_fees"
                 :params {:token "0xUSDC"}}
                {:seq 2 :time 1001 :agent "gov" :action "set_token_liquidity_crunch"
                 :params {:token "0xUSDC" :active? true}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))))

(deftest test-withdraw-fees-non-governance-rejected
  "Security: only governance may withdraw protocol fees."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                ;; alice is not governance; fee withdrawal must be rejected
                {:seq 1 :time 1001 :agent "alice" :action "withdraw_fees"
                 :params {:token "0xUSDC"}}
                ;; governance path remains valid
                {:seq 2 :time 1002 :agent "gov" :action "withdraw_fees"
                 :params {:token "0xUSDC"}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :not-governance (get-in r [:trace 1 :error])))
    (is (= :ok (get-in r [:trace 2 :result])))))

(deftest test-mixed-withdraw-ordering-same-timestamp-escrow-then-fees
  "With both withdrawals in same block/time after release:
   withdraw_escrow first then withdraw_fees should both succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                 :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "bob" :action "withdraw_escrow"
                 :params {:workflow-id 0}}
                {:seq 3 :time 1002 :agent "gov" :action "withdraw_fees"
                 :params {:token "0xUSDC"}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-mixed-withdraw-ordering-same-timestamp-fees-then-escrow
  "With both withdrawals in same block/time after release:
   withdraw_fees first then withdraw_escrow should both succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sc :agents [alice bob resolver gov]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                 :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "gov" :action "withdraw_fees"
                 :params {:token "0xUSDC"}}
                {:seq 3 :time 1002 :agent "bob" :action "withdraw_escrow"
                 :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

;; ---------------------------------------------------------------------------
;; Section 20: Invariant-violation metric tracking
;;
;; Tests that accum-metrics correctly:
;;   a. Increments :invariant-violations for every failing invariant
;;   b. Records ONLY the failing invariants in :invariant-results
;;   c. Ignores passing invariants (:holds? true)
;;   d. Auto-cancel-after-timeout: full replay path where invariants hold
;; ---------------------------------------------------------------------------

(deftest test-accum-metrics-invariant-tracking
  "accum-metrics increments :invariant-violations and records :invariant-results
   only for entries where :holds? is false."
  (let [accum-fn #'replay/accum-metrics
        base-metrics {:total-escrows 0 :total-volume 0 :disputes-triggered 0
                      :resolutions-executed 0 :pending-settlements-executed 0
                      :attack-attempts 0 :attack-successes 0 :rejected-attacks 0
                      :reverts 0 :invariant-violations 0 :invariant-results {}
                      :double-settlements 0 :invalid-state-transitions 0 :funds-lost 0}
        event       {:seq 0 :time 1000 :agent "alice" :action "advance_time" :params {}}
        ;; Synthetic violations: conservation-of-funds fails, solvency passes
        trace-entry {:result :ok
                     :world  {:total-held {} :block-time 1000}
                     :violations {:conservation-of-funds {:holds? false :violations ["held mismatch"]}
                                  :solvency              {:holds? true  :violations []}}}
        agent-index {}
        world-before {:total-held {} :block-time 1000}]
    (testing "violations map triggers increment"
      (let [m (accum-fn sew/protocol base-metrics event trace-entry agent-index world-before)]
        (is (= 1 (:invariant-violations m)))
        (is (= {:conservation-of-funds :fail} (:invariant-results m)))
        (is (not (contains? (:invariant-results m) :solvency))
            "passing invariant must NOT appear in :invariant-results")))))

(deftest test-accum-metrics-multiple-violations
  "Multiple failing invariants in one step each appear in :invariant-results."
  (let [accum-fn #'replay/accum-metrics
        base-metrics {:total-escrows 0 :total-volume 0 :disputes-triggered 0
                      :resolutions-executed 0 :pending-settlements-executed 0
                      :attack-attempts 0 :attack-successes 0 :rejected-attacks 0
                      :reverts 0 :invariant-violations 0 :invariant-results {}
                      :double-settlements 0 :invalid-state-transitions 0 :funds-lost 0}
        event       {:seq 0 :time 1000 :agent "alice" :action "advance_time" :params {}}
        trace-entry {:result :ok
                     :world  {:total-held {} :block-time 1000}
                     :violations {:conservation-of-funds {:holds? false :violations ["mismatch"]}
                                  :no-negative-balances  {:holds? false :violations ["negative"]}
                                  :solvency              {:holds? true  :violations []}}}
        m (accum-fn sew/protocol base-metrics event trace-entry {} {:total-held {} :block-time 1000})]
    (is (= 1 (:invariant-violations m))
        "aggregate counter increments once per step regardless of violation count")
    (is (= {:conservation-of-funds :fail :no-negative-balances :fail}
           (:invariant-results m)))
    (is (not (contains? (:invariant-results m) :solvency)))))

(deftest test-accum-metrics-no-violations-unchanged
  "When :violations is nil (normal step), :invariant-violations stays zero."
  (let [accum-fn #'replay/accum-metrics
        base-metrics {:total-escrows 0 :total-volume 0 :disputes-triggered 0
                      :resolutions-executed 0 :pending-settlements-executed 0
                      :attack-attempts 0 :attack-successes 0 :rejected-attacks 0
                      :reverts 0 :invariant-violations 0 :invariant-results {}
                      :double-settlements 0 :invalid-state-transitions 0 :funds-lost 0}
        event       {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                     :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
        trace-entry {:result :ok :world {:total-held {} :block-time 1000} :violations nil}
        m (accum-fn sew/protocol base-metrics event trace-entry {} {:total-held {} :block-time 1000})]
    (is (= 0 (:invariant-violations m)))
    (is (= {} (:invariant-results m)))))

(deftest test-auto-cancel-after-timeout
  "auto_cancel_disputed succeeds when block-time > dispute-timestamp + max-dispute-duration."
  (let [r (sew/replay-with-sew-protocol
           (sc :params (assoc default-params :max-dispute-duration 500)
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 8000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "bob" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1501 :agent "alice" :action "auto_cancel_disputed"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

(deftest test-replay-appeal-bond-custody-lifecycle
  "Replay-level parity check for appeal bond custody:
   - appeal holds custody/bond
   - upheld appeal refunds bond to resolver claimable
   - rejected appeal forfeits bond to insurance accounting"
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r-upheld
        (sew/replay-with-sew-protocol
         (sc :agents [alice bob resolver gov]
             :params (assoc default-params
                            :appeal-window-duration 100
                            :appeal-bond-amount 70)
             :events
             [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
               :params {:amount 5000}}
              {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
               :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
              {:seq 2 :time 1001 :agent "gov" :action "propose_fraud_slash"
               :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 100}}
              {:seq 3 :time 1002 :agent "resolver" :action "appeal_slash"
               :params {:workflow-id 0}}
              {:seq 4 :time 1003 :agent "gov" :action "resolve_appeal"
               :params {:workflow-id 0 :upheld? true}}]))
        r-rejected
        (sew/replay-with-sew-protocol
         (sc :agents [alice bob resolver gov]
             :params (assoc default-params
                            :appeal-window-duration 100
                            :appeal-bond-amount 80)
             :events
             [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
               :params {:amount 5000}}
              {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
               :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
              {:seq 2 :time 1001 :agent "gov" :action "propose_fraud_slash"
               :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 100}}
              {:seq 3 :time 1002 :agent "resolver" :action "appeal_slash"
               :params {:workflow-id 0}}
              {:seq 4 :time 1003 :agent "gov" :action "resolve_appeal"
               :params {:workflow-id 0 :upheld? false}}]))
        w-upheld   (get-in r-upheld [:trace 4 :world])
        w-rejected (get-in r-rejected [:trace 4 :world])
        assert-appeal-resolution-semantics
        (fn [world upheld?]
          ;; Helper to remove ambiguity around `upheld?` semantics using
          ;; snapshot-visible effects (world snapshot omits pending-fraud-slashes).
          ;; true  => APPEAL upheld  => bond refunded to resolver claimable
          ;; false => APPEAL rejected => bond forfeited to insurance
          (if upheld?
            (is (pos? (get-in world [:claimable 0 "0xResolver"] 0))
                "upheld?=true should refund appeal bond to resolver claimable")
            (is (pos? (get-in world [:bond-distribution :insurance] 0))
                "upheld?=false should forfeit appeal bond to insurance")))]
    (is (= :pass (:outcome r-upheld)))
    (is (= :pass (:outcome r-rejected)))
    (assert-appeal-resolution-semantics w-upheld true)
    (assert-appeal-resolution-semantics w-rejected false)
    (is (= 70 (get-in w-upheld [:claimable 0 "0xResolver"] 0)))
    (is (= 80 (get-in w-rejected [:bond-distribution :insurance] 0)))
    (is (= 0 (get-in w-rejected [:claimable 0 "0xResolver"] 0)))))

(deftest test-replay-s35-profit-maximizer-governance-wins-appeal
  "Dedicated replay test mirroring invariant scenario S35 exactly.
   Path: pending -> appealed -> pending (upheld? false) -> executed after deadline."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r   (sew/replay-with-sew-protocol
             (sc :agents [alice bob resolver gov]
                 :params (assoc default-params :appeal-window-duration 120)
                 :events
                 [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                   :params {:amount 10000}}
                  {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                   :params {:token "0xUSDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                  {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                   :params {:workflow-id 0}}
                  {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                   :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                  {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                   :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                  {:seq 5 :time 1140 :agent "resolver" :action "appeal_slash"
                   :params {:workflow-id 0}}
                  {:seq 6 :time 1160 :agent "gov" :action "resolve_appeal"
                   :params {:workflow-id 0 :upheld? false}}
                  {:seq 7 :time 1241 :agent "alice" :action "execute_pending_settlement"
                   :params {:workflow-id 0}}
                  {:seq 8 :time 1255 :agent "gov" :action "execute_fraud_slash"
                   :params {:workflow-id 0}}]))
        w-appealed (get-in r [:trace 5 :world])
        w-resolved (get-in r [:trace 6 :world])
        w-final    (get-in r [:trace 8 :world])]
    (is (= :pass (:outcome r)))
    ;; Snapshot-level proxy checks for appealed->rejected path.
    ;; In this S35 mirror, appeal-bond-amount is default 0, so no insurance
    ;; movement occurs at resolve_appeal; slash remains live until execution.
    (is (= 0 (get-in w-appealed [:claimable 0 "0xResolver"] 0)))
    (is (= 0 (get-in w-resolved [:claimable 0 "0xResolver"] 0)))
    (is (= :released (get-in w-final [:live-states 0])))
    (is (= 9500 (get-in w-final [:resolver-stakes "0xResolver"])))
    (is (= 500 (get-in w-final [:resolver-slash-total "0xResolver"])))))
