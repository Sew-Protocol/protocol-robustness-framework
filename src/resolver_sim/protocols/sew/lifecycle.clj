(ns resolver-sim.protocols.sew.lifecycle
  "Pure Clojure port of BaseEscrow escrow lifecycle operations.

   Covers:
     create-escrow     — createEscrow (fee deduction, snapshot, auto-times)
     release           — release (release strategy consulted via stub)
     sender-cancel     — senderCancel (mutual consent or unilateral)
     recipient-cancel  — recipientCancel (mutual consent or unilateral)
     auto-cancel-disputed-escrow — autoCancelDisputedEscrow (dispute timeout)

   raise-dispute is in state_machine.clj (transition-to-disputed) since it
   delegates entirely to the state transition; the lifecycle wrapper is here.

   All functions return {:ok bool :world world' :error keyword}.
   Arithmetic: uint256 integer division (no rounding)."
  (:require [resolver-sim.protocols.sew.types         :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.accounting    :as acct]
            [resolver-sim.protocols.sew.registry      :as reg]
            [resolver-sim.economics.payoffs            :as payoffs]))

;; ---------------------------------------------------------------------------
;; Internal accounting helpers
;; ---------------------------------------------------------------------------

(defn- add-held [world token amount]
  (update-in world [:total-held token] (fnil + 0) amount))

(defn- sub-held [world token amount]
  (update-in world [:total-held token] (fnil - 0) amount))

(defn- add-fee [world token amount]
  (update-in world [:total-fees token] (fnil + 0) amount))

;; ---------------------------------------------------------------------------
;; Internal: _cancelAndRefund + _releaseEscrowTransfer
;;
;; Both clear pending-settlement, subtract total-held, then transition state.
;; The push/fallback transfer distinction is abstracted — the model records
;; either a state change or a claimable balance entry.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Internal: finalize helpers (no accounting — see lifecycle for that)
;; ---------------------------------------------------------------------------

(defn- finalize-release
  "Internal: transition to :released, update accounting.
   If the token has a Fee-on-Transfer (FoT) tax configured on the world, only the
   net amount (after tax) is recorded as released; the full AFA is still removed
   from held.  The gap is the unexplained drain that token-tax-reconciliation? catches."
  [world workflow-id]
  (let [et      (t/get-transfer world workflow-id)
        token   (:token et)
        amt     (:amount-after-fee et)
        fot-bps (get-in world [:token-fot-bps token] 0)
        net-amt (- amt (t/compute-fee amt fot-bps))]
    (-> world
        (acct/sub-held token amt)
        (acct/record-released token net-amt)
        (acct/record-claimable workflow-id (:to et) net-amt)
        (update :pending-settlements dissoc workflow-id)
        (sm/apply-transition! workflow-id :released))))

(defn- finalize-refund
  "Internal: transition to :refunded, update accounting.
   If the token has a Fee-on-Transfer (FoT) tax configured on the world, only the
   net amount (after tax) is recorded as refunded; the gap triggers token-tax-reconciliation?."
  [world workflow-id]
  (let [et      (t/get-transfer world workflow-id)
        token   (:token et)
        amt     (:amount-after-fee et)
        fot-bps (get-in world [:token-fot-bps token] 0)
        net-amt (- amt (t/compute-fee amt fot-bps))]
    (-> world
        (acct/sub-held token amt)
        (acct/record-refunded token net-amt)
        (acct/record-claimable workflow-id (:from et) net-amt)
        (update :pending-settlements dissoc workflow-id)
        (sm/apply-transition! workflow-id :refunded))))

;; ---------------------------------------------------------------------------
;; create-escrow
;;
;; Mirrors: BaseEscrow.createEscrow
;;
;; Guards:
;;   1. token must be non-nil
;;   2. to must be non-nil
;;   3. amount must be positive
;;   4. Cannot set both autoReleaseTime and autoCancelTime (CannotSetBothAutoTimes)
;;
;; Accounting:
;;   fee             = amount * escrow-fee-bps / 10000 (integer division)
;;   amount-after-fee = amount - fee
;;   total-held[token] += amount-after-fee
;;   total-fees[token] += fee
;;
;; Auto-times logic (_applyEscrowSettings):
;;   If both settings times are 0 and defaults exist, apply defaults.
;;   Auto-release and auto-cancel are mutually exclusive.
;;
;; Returns {:ok true :world world' :workflow-id id} on success.
;; ---------------------------------------------------------------------------

(defn create-escrow
  "Create a new escrow, assign next workflow-id.

   world       — current world state
   caller      — address of msg.sender (:from)
   token       — ERC20 token address
   to          — recipient address
   amount      — gross amount (uint256)
   settings    — EscrowSettings map (see types/make-escrow-settings)
   snapshot    — ModuleSnapshot map (pre-computed by caller, mirrors _snapshotModulesForEscrow)

   The snapshot is passed in rather than derived internally so the model
   remains pure: callers supply the governance config state they want to test."
  [world caller token to amount settings snapshot]
  (cond
    (nil? token)
    (t/fail :invalid-token)

    (nil? to)
    (t/fail :invalid-recipient)

    (<= amount 0)
    (t/fail :amount-zero)

    (and (pos? (:auto-release-time settings 0))
         (pos? (:auto-cancel-time settings 0)))
    (t/fail :cannot-set-both-auto-times)

    :else
    (let [workflow-id   (count (:escrow-transfers world))
          fee-bps       (:escrow-fee-bps snapshot 0)
          fee           (payoffs/calculate-escrow-fee amount fee-bps)
          afa           (- amount fee)
          ;; _applyEscrowSettings: compute effective auto times
          snap-rel      (:default-auto-release-delay snapshot 0)
          snap-can      (:default-auto-cancel-delay snapshot 0)
          use-defaults? (and (zero? (:auto-release-time settings 0))
                             (zero? (:auto-cancel-time settings 0)))
          auto-rel      (cond
                          (pos? (:auto-release-time settings 0)) (:auto-release-time settings)
                          (and use-defaults? (pos? snap-rel))    (+ (:block-time world) snap-rel)
                          :else                                   0)
          auto-can      (cond
                          (pos? (:auto-cancel-time settings 0)) (:auto-cancel-time settings)
                          (and use-defaults? (pos? snap-can))   (+ (:block-time world) snap-can)
                          :else                                  0)
          ;; Resolver: custom-resolver takes precedence over snapshot
          resolver      (or (:custom-resolver settings)
                            (:dispute-resolver snapshot))
          ;; Bonding guard: only enforce when resolver-bond-bps is configured
          bond-bps      (:resolver-bond-bps snapshot 0)
          stake         (if resolver (reg/get-stake world resolver) 0)]
      (if (and resolver (pos? bond-bps) (pos? stake) (not (reg/can-handle-escrow? world resolver afa)))
        (t/fail :insufficient-resolver-stake)
        (let [et            (t/make-escrow-transfer
                             {:token             token
                              :to                to
                              :from              caller
                              :amount-after-fee  afa
                              :initial-fee       fee
                              :dispute-resolver  resolver
                              :auto-release-time auto-rel
                              :auto-cancel-time  auto-can
                              :escrow-state      :pending})
              world'        (-> world
                                (assoc-in [:escrow-transfers workflow-id] et)
                                (assoc-in [:escrow-settings workflow-id]
                                          (t/make-escrow-settings settings))
                                (assoc-in [:module-snapshots workflow-id] snapshot)
                                (add-held token afa)
                                (add-fee token fee))]
          (assoc (t/ok world') :workflow-id workflow-id))))))

;; ---------------------------------------------------------------------------
;; raise-dispute
;;
;; Thin wrapper around transition-to-disputed.
;; ---------------------------------------------------------------------------

(defn raise-dispute
  "Raise a dispute on a :pending escrow.
   Caller must be :from or :to.

   Also checks DRM resolver capacity: if the escrow has a dispute-resolver assigned
   and that resolver is at maxConcurrentDisputes, the call fails with
   :resolver-capacity-exceeded — mirroring DRM.initializeDispute behaviour.
   On success, increments the resolver's current-active counter."
  [world workflow-id caller]
  (let [result (sm/transition-to-disputed world workflow-id caller)]
    (if-not (:ok result)
      result
      (let [resolver (get-in (:world result) [:escrow-transfers workflow-id :dispute-resolver])]
        (if (and resolver (t/resolver-at-capacity? world resolver))
          (t/fail :resolver-capacity-exceeded)
          (t/ok (t/increment-resolver-capacity (:world result) resolver)))))))

;; ---------------------------------------------------------------------------
;; release
;;
;; Mirrors: BaseEscrow.release
;;
;; The release strategy is modelled as a function:
;;   (release-strategy-fn world workflow-id caller) → {:allowed? bool :reason-code uint8}
;;
;; When strategy-fn is nil (no strategy configured), the call reverts:
;; this matches the contract's ReleaseStrategyNotSet revert.
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. state must be :pending
;;   3. release-strategy-fn must be non-nil
;;   4. strategy must return {:allowed? true}
;; ---------------------------------------------------------------------------

(defn release
  "Release a :pending escrow to :to.

   release-strategy-fn — (fn [world workflow-id caller] → {:allowed? bool :reason-code n})
                         Pass nil to simulate 'no strategy configured'."
  [world workflow-id caller release-strategy-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (t/fail :transfer-not-pending)

    (nil? release-strategy-fn)
    (t/fail :release-strategy-not-set)

    :else
    (let [{:keys [allowed? reason-code]} (release-strategy-fn world workflow-id caller)]
      (if-not allowed?
        (t/fail (if (= 1 reason-code) :not-sender :release-not-allowed))
        (t/ok (finalize-release world workflow-id))))))

;; ---------------------------------------------------------------------------
;; sender-cancel
;;
;; Mirrors: BaseEscrow.senderCancel
;;
;; The cancellation strategy is modelled as a map:
;;   {:can-cancel?          bool  — canCancel result
;;    :unilateral-cancel?   bool  — canCancelUnilaterally result}
;; or nil when no strategy is configured (mutual-consent-only path).
;;
;; Logic:
;;   1. Guard: caller = :from, state = :pending
;;   2. If strategy set:
;;      a. If !canCancel → revert :not-authorized-to-cancel-yet
;;      b. If canCancelUnilaterally → immediate refund
;;   3. Else: set senderStatus = :agree-to-cancel; refund if both agreed
;; ---------------------------------------------------------------------------

(defn sender-cancel
  "Attempt to cancel escrow as sender.

   cancel-strategy — {:can-cancel? bool :unilateral-cancel? bool} or nil."
  [world workflow-id caller cancel-strategy]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= caller (get-in world [:escrow-transfers workflow-id :from]))
    (t/fail :not-sender)

    (not= :pending (t/escrow-state world workflow-id))
    (t/fail :transfer-not-pending)

    ;; Strategy set and blocks the call
    (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy)))
    (t/fail :not-authorized-to-cancel-yet)

    ;; Strategy permits unilateral cancel
    (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy))
    (t/ok (finalize-refund world workflow-id))

    :else
    ;; Mutual-consent path: set sender status
    (let [r (sm/set-sender-agree-to-cancel world workflow-id caller)]
      (if-not (:ok r)
        r
        (if (sm/both-agreed-to-cancel? (:world r) workflow-id)
          (t/ok (finalize-refund (:world r) workflow-id))
          r)))))

;; ---------------------------------------------------------------------------
;; recipient-cancel
;;
;; Mirrors: BaseEscrow.recipientCancel
;; Same logic as sender-cancel but for :to.
;; ---------------------------------------------------------------------------

(defn recipient-cancel
  "Attempt to cancel escrow as recipient.

   cancel-strategy — {:can-cancel? bool :unilateral-cancel? bool} or nil."
  [world workflow-id caller cancel-strategy]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= caller (get-in world [:escrow-transfers workflow-id :to]))
    (t/fail :not-recipient)

    (not= :pending (t/escrow-state world workflow-id))
    (t/fail :transfer-not-pending)

    (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy)))
    (t/fail :not-authorized-to-cancel-yet)

    (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy))
    (t/ok (finalize-refund world workflow-id))

    :else
    (let [r (sm/set-recipient-agree-to-cancel world workflow-id caller)]
      (if-not (:ok r)
        r
        (if (sm/both-agreed-to-cancel? (:world r) workflow-id)
          (t/ok (finalize-refund (:world r) workflow-id))
          r)))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-escrow
;;
;; Mirrors: BaseEscrow.autoCancelDisputedEscrow
;;
;; Guards:
;;   1. state must be :disputed
;;   2. no pending-settlement exists  (CRIT-3: don't override resolver decision)
;;   3. dispute-raised-timestamp set + max-dispute-duration elapsed
;; ---------------------------------------------------------------------------

(defn auto-cancel-disputed-escrow
  "Cancel a :disputed escrow after max-dispute-duration has elapsed.
   Performs full accounting reconciliation: slashes the resolver (as a timeout)
   and distributes funds."
  [world workflow-id]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (:exists (t/get-pending world workflow-id))
    (t/fail :has-pending-settlement)

    (not (sm/dispute-timeout-exceeded? world workflow-id))
    (t/fail :dispute-timeout-not-exceeded)

    :else
    (let [et             (t/get-transfer world workflow-id)
          resolver        (:dispute-resolver et)
          slash-amt       (:amount-after-fee et)
          has-resolver?   (and resolver
                               (not= resolver "0x0000000000000000000000000000000000000000"))
          ;; slash-resolver-stake returns {:ok bool :world world'} — unwrap before threading
          world-slashed   (if has-resolver?
                            (:world (reg/slash-resolver-stake world resolver slash-amt))
                            world)
          world'          (-> world-slashed
                              (acct/distribute-slashed-funds slash-amt)
                              (finalize-refund workflow-id)
                              (t/decrement-resolver-capacity resolver)
                              (update :dispute-timestamps dissoc workflow-id))]
      (t/ok world'))))
