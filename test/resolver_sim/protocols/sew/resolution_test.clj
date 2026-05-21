(ns resolver-sim.protocols.sew.resolution-test
  "Tests for contract_model/resolution.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol       :as proto]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.authority  :as auth]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew            :as sew]
            [resolver-sim.contract-model.replay     :as replay]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def carol    "0xCarol")
(def usdc     "0xUSDC")

(defn- base-world
  "World with one :disputed escrow, block-time=1000, appeal-window as given."
  [appeal-window-duration]
  (let [snap (t/make-module-snapshot {:escrow-fee-bps        50
                                      :max-dispute-duration  3600
                                      :appeal-window-duration appeal-window-duration})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                               (t/make-escrow-settings {}) snap)
        w    (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state]     :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status]    :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
        (assoc-in [:dispute-timestamps 0] 1000))))

(def direct-resolver-fn nil)  ; no module — use direct resolver

;; ---------------------------------------------------------------------------
;; execute-resolution: no appeal window
;; ---------------------------------------------------------------------------

(deftest execute-resolution-immediate-release
  (let [w (base-world 0)
        r (res/execute-resolution w 0 resolver true "0xhash" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))
    (is (nil? (get-in (:world r) [:pending-settlements 0]))
        "no pending settlement when appeal window = 0")))

(deftest execute-resolution-immediate-refund
  (let [w (base-world 0)
        r (res/execute-resolution w 0 resolver false "0xhash" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

;; ---------------------------------------------------------------------------
;; execute-resolution: with appeal window
;; ---------------------------------------------------------------------------

(deftest execute-resolution-creates-pending-settlement
  (let [w (base-world 1800)
        r (res/execute-resolution w 0 resolver true "0xhash" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (= :disputed (t/escrow-state (:world r) 0))
        "state unchanged when deferred")
    (let [pending (t/get-pending (:world r) 0)]
      (is (:exists pending))
      (is (:is-release pending))
      (is (= (+ 1000 1800) (:appeal-deadline pending))
          "appeal-deadline = block-time + appeal-window-duration"))))

;; ---------------------------------------------------------------------------
;; execute-resolution guards
;; ---------------------------------------------------------------------------

(deftest execute-resolution-not-authorized
  (let [r (res/execute-resolution (base-world 0) 0 carol true "0xhash" direct-resolver-fn)]
    (is (false? (:ok r)))
    (is (= :not-authorized-resolver (:error r)))))

(deftest execute-resolution-not-disputed
  (let [w (assoc-in (base-world 0) [:escrow-transfers 0 :escrow-state] :pending)
        r (res/execute-resolution w 0 resolver true "0xhash" direct-resolver-fn)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(deftest execute-resolution-invalid-workflow
  (let [r (res/execute-resolution (base-world 0) 99 resolver true "0xhash" direct-resolver-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

;; ---------------------------------------------------------------------------
;; execute-pending-settlement
;; ---------------------------------------------------------------------------

(defn- world-with-pending
  "World with a pending settlement set, block-time at/after deadline."
  [block-time appeal-deadline is-release]
  (-> (base-world 1800)
      (assoc :block-time block-time)
      (assoc-in [:pending-settlements 0]
                (t/make-pending-settlement
                 {:exists true :is-release is-release
                  :appeal-deadline appeal-deadline :resolution-hash "0xhash"}))))

(deftest execute-pending-release-after-deadline
  (let [w (world-with-pending 3000 2800 true)
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest execute-pending-refund-after-deadline
  (let [w (world-with-pending 3000 2800 false)
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest execute-pending-before-deadline
  (let [w (world-with-pending 2000 2800 true)
        r (res/execute-pending-settlement w 0)]
    (is (false? (:ok r)))
    (is (= :appeal-window-not-expired (:error r)))))

(deftest execute-pending-no-pending-settlement
  (let [r (res/execute-pending-settlement (base-world 0) 0)]
    (is (false? (:ok r)))
    (is (= :no-pending-settlement (:error r)))))

;; ---------------------------------------------------------------------------
;; automate-timed-actions
;; ---------------------------------------------------------------------------

(deftest automate-dispatches-execute-pending
  "Pending settlement ready → executes it first (highest priority)."
  (let [w (world-with-pending 3000 2800 true)
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :execute-pending (:action r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest automate-dispatches-auto-release
  (let [w (-> (base-world 0)
              (assoc :block-time 5000)
              (assoc-in [:escrow-transfers 0 :escrow-state]    :pending)
              (assoc-in [:escrow-transfers 0 :auto-release-time] 4000))
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :auto-release (:action r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest automate-dispatches-auto-cancel
  (let [w (-> (base-world 0)
              (assoc :block-time 5000)
              (assoc-in [:escrow-transfers 0 :escrow-state]   :pending)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 4000))
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :auto-cancel (:action r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest automate-returns-none-when-nothing-due
  (let [w (base-world 0)   ; block-time=1000, no auto-times
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :none (:action r)))))

;; ---------------------------------------------------------------------------
;; cancel-pending-on-escalation
;; ---------------------------------------------------------------------------

(deftest escalation-cancels-pending-settlement
  (let [w  (world-with-pending 3000 2800 true)
        w' (res/cancel-pending-on-escalation w 0)]
    (is (nil? (get-in w' [:pending-settlements 0]))
        "pending settlement cleared on escalation")))

;; ---------------------------------------------------------------------------
;; escalate-dispute
;; ---------------------------------------------------------------------------

(def ^:private senior-resolver "0xSenior")

(defn- make-escalation-fn
  "Stub: always succeeds, returns new-resolver."
  [new-resolver]
  (fn [_world _wf _caller _level]
    {:ok true :new-resolver new-resolver}))

(defn- disputed-world-with-pending
  "Disputed world at level 0 with a pending settlement already set."
  []
  (-> (base-world 1800)
      (assoc-in [:dispute-timestamps 0] 1000)
      ;; Manually set a pending settlement to confirm it is cleared by escalation
      (assoc-in [:pending-settlements 0]
                (t/make-pending-settlement {:exists true :is-release true
                                            :appeal-deadline 2800
                                            :resolution-hash "0xhash"}))))

(defn- with-pending
  "Manually add a pending settlement to a world."
  [world workflow-id is-release appeal-deadline]
  (assoc-in world [:pending-settlements workflow-id]
            (t/make-pending-settlement {:exists true :is-release is-release
                                        :appeal-deadline appeal-deadline
                                        :resolution-hash "0xhash"})))

(deftest escalate-dispute-ok
  (let [w   (-> (base-world 0) (with-pending 0 true 5000))
        r   (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (testing "returns ok"
      (is (true? (:ok r))))
    (testing "level increments to 1"
      (is (= 1 (t/dispute-level (:world r) 0)))
      (is (= 1 (:new-level r))))
    (testing "dispute-resolver updated to new resolver"
      (is (= senior-resolver (get-in (:world r) [:escrow-transfers 0 :dispute-resolver])))
      (is (= senior-resolver (:new-resolver r))))
    (testing "state remains disputed"
      (is (= :disputed (t/escrow-state (:world r) 0))))))

(deftest escalate-dispute-clears-pending-settlement
  (let [w (disputed-world-with-pending)
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r)))
    (is (nil? (get-in (:world r) [:pending-settlements 0]))
        "pending settlement cleared when escalation proceeds")))

(deftest escalate-dispute-not-participant
  (let [w (-> (base-world 0) (with-pending 0 true 5000))
        r (res/escalate-dispute w 0 carol (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :not-participant (:error r)))))

(deftest escalate-dispute-third-party-sponsor-rejected
  "Current model requires appeal caller to be a participant.
   Third-party sponsorship is therefore NOT supported in the current state."
  (let [w0 (-> (base-world 0)
               (with-pending 0 true 5000)
               (assoc-in [:escrow-transfers 0 :amount-after-fee] 10000)
               (assoc-in [:escrow-transfers 0 :token] usdc)
               (assoc-in [:dispute-timestamps 0] 1000)
               (assoc-in [:bond-balances 0] {})
               (assoc-in [:escrow-transfers 0 :from] alice)
               (assoc-in [:escrow-transfers 0 :to] bob))
        r  (res/escalate-dispute w0 0 carol (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :not-participant (:error r)))
    (is (= :disputed (t/escrow-state w0 0))
        "state remains disputed when third-party escalation is rejected")))

(deftest escalate-dispute-not-in-dispute
  (let [w (assoc-in (base-world 0) [:escrow-transfers 0 :escrow-state] :pending)
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(deftest escalate-dispute-no-escalation-fn
  (let [w (-> (base-world 0) (with-pending 0 true 5000))
        r (res/escalate-dispute w 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :escalation-not-configured (:error r)))))

(deftest escalate-dispute-at-max-level-rejected
  (let [w (-> (base-world 0)
              (assoc-in [:dispute-levels 0] t/max-dispute-level)
              (with-pending 0 true 5000))
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :escalation-not-allowed (:error r)))))

(deftest escalate-dispute-module-refusal
  (let [refusing-fn (fn [_w _wf _caller _level] {:ok false :error :module-declined})
        w           (-> (base-world 0) (with-pending 0 true 5000))
        r           (res/escalate-dispute w 0 alice refusing-fn)]
    (is (false? (:ok r)))
    (is (= :module-declined (:error r)))))

(deftest escalate-dispute-level-2-is-final-round
  "After two escalations the level reaches max-dispute-level; a third must be rejected."
  (let [w0 (-> (base-world 0) (with-pending 0 true 5000))
        r1 (res/escalate-dispute w0 0 alice (make-escalation-fn "0xSenior"))
        ;; Cooldown mitigation requires >= 1 day before same caller escalates again.
        w1 (-> (:world r1) (assoc :block-time 87401) (with-pending 0 true 90000))
        r2 (res/escalate-dispute w1 0 alice (make-escalation-fn "0xKleros"))
        w2 (-> (:world r2) (assoc :block-time 173802) (with-pending 0 true 180000))
        r3 (res/escalate-dispute w2 0 alice (make-escalation-fn "0xAnother"))]
    (is (true?  (:ok r1)) "first escalation ok")
    (is (= 1    (t/dispute-level (:world r1) 0)))
    (is (true?  (:ok r2)) "second escalation ok")
    (is (= 2    (t/dispute-level (:world r2) 0)))
    (is (true?  (t/final-round? (:world r2) 0)) "level 2 is final")
    (is (false? (:ok r3)) "third escalation rejected")
    (is (= :escalation-not-allowed (:error r3)))))

;; ---------------------------------------------------------------------------
;; execute-resolution: final-round bypasses appeal window
;; ---------------------------------------------------------------------------

(deftest execute-resolution-final-round-immediate
  "At max-dispute-level, resolution is immediate even when appeal-window-duration > 0."
  (let [w (-> (base-world 1800)            ; appeal-window-duration = 1800
              (assoc-in [:dispute-levels 0] t/max-dispute-level))
        r (res/execute-resolution w 0 resolver true "0xhash" nil)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0))
        "final round executes immediately — no pending settlement")
    (is (not (:exists (t/get-pending (:world r) 0))))))

;; ---------------------------------------------------------------------------
;; replay: escalate_dispute action
;; ---------------------------------------------------------------------------

(deftest replay-escalate-dispute-action
  (let [agents  [{:id "alice"    :address alice    :type "honest"}
                 {:id "bob"      :address bob       :type "honest"}
                 {:id "resolver" :address resolver  :type "resolver"}]
        esc-fn  (make-escalation-fn senior-resolver)
        context (proto/build-execution-context sew/protocol agents
                                               {:resolver-fee-bps 50
                                                :escalation-resolvers {:1 senior-resolver}})
        ;; Build a disputed world manually
        world   (-> (base-world 0)
                    (with-pending 0 true 5000)
                    (assoc-in [:dispute-timestamps 0] 1000))
        event   {:seq 0 :time 1000 :agent "alice" :action "escalate_dispute"
                 :params {:workflow-id 0}}
        step    (replay/process-step sew/protocol context world event)]
    (is (= :ok (get-in step [:trace-entry :result])))
    (is (= 1   (t/dispute-level (:world step) 0)))
    (is (= senior-resolver
           (get-in (:world step) [:escrow-transfers 0 :dispute-resolver])))))

;; ---------------------------------------------------------------------------
;; challenge-resolution (open challenger path)
;; ---------------------------------------------------------------------------

(deftest challenge-resolution-third-party-allowed
  "challenge_resolution is intentionally open to non-participants.
   A third-party challenger can post challenge bond and escalate."
  (let [w0 (-> (base-world 0)
               (with-pending 0 true 5000)
               (assoc-in [:escrow-transfers 0 :amount-after-fee] 10000)
               (assoc-in [:escrow-transfers 0 :token] usdc)
               (assoc-in [:module-snapshots 0 :challenge-bond-bps] 500)
               (assoc-in [:dispute-timestamps 0] 1000)
               (assoc-in [:bond-balances 0] {}))
        r  (res/challenge-resolution w0 0 carol (make-escalation-fn senior-resolver))
        w1 (:world r)]
    (is (true? (:ok r)))
    (is (= 1 (t/dispute-level w1 0)))
    (is (= senior-resolver (get-in w1 [:escrow-transfers 0 :dispute-resolver])))
    (is (pos? (get-in w1 [:bond-balances 0 carol] 0))
        "third-party challenger posted challenge bond")
    (is (= carol (get-in w1 [:challengers 0 0])))))
