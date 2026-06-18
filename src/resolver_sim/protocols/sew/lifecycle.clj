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
            [resolver-sim.economics.payoffs            :as payoffs]
            [resolver-sim.yield.ops                    :as yield-ops]
            [resolver-sim.yield.accounting             :as yield-acct]
            [resolver-sim.yield.expectations           :as yield-exp]
            [resolver-sim.yield.registry               :as yield-reg]
            [resolver-sim.protocols.sew.yield.policy  :as yield-policy]
            [resolver-sim.util.attribution             :as attr]
            [resolver-sim.time.context                 :as time-ctx]
            [resolver-sim.io.event-evidence            :as evidence]))

;; ---------------------------------------------------------------------------
;; Guard logging helper — returns (t/fail kw) with :guard-context attached
;; so process-step can capture rejection context in trace entries.
;; ---------------------------------------------------------------------------

(defn- guard-fail [error-kw & {:as ctx}]
  (attr/log-with-attr :debug "guard/rejected" (assoc ctx :error error-kw))
  (assoc (t/fail error-kw) :guard-context ctx))

;; ---------------------------------------------------------------------------
;; Internal accounting helpers
;; ---------------------------------------------------------------------------

(defn- add-held [world token amount]
  (update-in world [:total-held token] (fnil + 0) amount))

(defn- token-available? [world token]
  (not (contains? (:token-liquidity-crunch world) token)))

(defn- yield-module-available? [world module-id token]
  (if (nil? module-id)
    true
    (let [mid    (keyword module-id)
          status (get-in world [:yield/module-status mid] :active)
          mode   (get-in world [:yield/risk mid token :liquidity-mode] :available)]
      (and (= status :active)
           (not (contains? yield-acct/liquidity-modes mode))))))

(defn- resolver-available? [world resolver]
  (if (or (nil? resolver) (= resolver t/zero-address))
    true
    (let [capacity-ok? (not (t/resolver-at-capacity? world resolver))
          freeze-expiry (get-in world [:resolver-frozen-until resolver] 0)
          unfrozen?     (<= freeze-expiry (time-ctx/block-ts world))]
      (and capacity-ok? unfrozen?))))

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
;; Yield Accrual
;; ---------------------------------------------------------------------------

(def ^:const seconds-per-year 31536000)

(declare accrue-yield)

(defn resolver-yield-owner-id
  "Canonical yield position owner id for resolver stake."
  [resolver-addr]
  (str "resolver:" resolver-addr))

(defn init-resolver-yield-accrual-time
  "Anchor resolver yield accrual clock at the current block time."
  [world resolver-addr]
  (assoc-in world [:resolver-yield-accrual-times resolver-addr] (time-ctx/block-ts world)))

(defn accrue-resolver-yield
  "Advance yield for a resolver staking position by elapsed time since last accrual."
  [world resolver-addr token]
  (attr/with-attribution {:yield/target-type :resolver
                          :yield/resolver-addr resolver-addr}
    (let [profile-id (reg/get-resolver-yield-profile world resolver-addr)]
      (if profile-id
        (let [{:keys [module-id]} (yield-reg/resolve-yield-profile profile-id)
              owner-id (resolver-yield-owner-id resolver-addr)
              now      (time-ctx/block-ts world)
              last     (get-in world [:resolver-yield-accrual-times resolver-addr] now)
              dt       (- now last)
              tok      (if (keyword? token) token (keyword token))]
          (if (pos? dt)
            (-> world
                (yield-ops/apply-yield-op {:op/type :yield/accrue
                                           :module/id module-id
                                           :owner/id owner-id
                                           :token tok
                                           :dt dt})
                (assoc-in [:resolver-yield-accrual-times resolver-addr] now))
            world))
        world))))

;; ---------------------------------------------------------------------------
;; Internal: finalize helpers (no accounting — see lifecycle for that)
;; ---------------------------------------------------------------------------

(defn- finalize
  "Internal: transition escrow to terminal state, release accounting.
   direction — :released (to recipient) or :refunded (to sender).

   Yield shortfall handling: when the yield module can only fulfil a fraction of
   the gross (principal + unrealized yield), only that fulfilled amount is moved
   from :total-held to :claimable.  The deferred remainder stays in :total-held
   as an outstanding obligation until claim-deferred-yield closes it out."
  [world workflow-id direction]
  (let [et        (t/get-transfer world workflow-id)
        token     (:token et)
        amt       (:amount-after-fee et)
        fot-bps   (get-in world [:token-fot-bps token] 0)
        net-amt   (- amt (t/compute-fee amt fot-bps))
        snap      (t/get-snapshot world workflow-id)
        mid       (:yield-generation-module snap)
        owner-id  (t/escrow-yield-owner-id workflow-id)
        recipient (if (= direction :released) (:to et) (:from et))
        record-fn (if (= direction :released) acct/record-released acct/record-refunded)
        ;; Run accrue + withdraw first so we can inspect the shortfall result
        world-after-yield
        (-> world
            (accrue-yield workflow-id)
            (cond-> (and mid (contains? (:yield/modules world) mid))
              (yield-ops/apply-yield-op {:op/type :yield/withdraw
                                        :module/id mid
                                        :owner/id owner-id})))
        ;; Under a liquidity shortfall, only fulfilled-amount is immediately settleable.
        ;; Use net-amt when no yield module is involved or no shortfall occurred.
        pos           (when mid (get-in world-after-yield [:yield/positions owner-id]))
        pos-shortfall (:shortfall pos)
        partial-yield? (and pos pos-shortfall
                            (yield-acct/partial-yield-shortfall? pos pos-shortfall))
        ;; Partial-yield shortfall: principal is immediate; liquid yield is settled in policy.
        principal-immediate (if partial-yield? (:principal pos 0) net-amt)
        settled-amt   (if pos-shortfall
                        (if partial-yield?
                          principal-immediate
                          (:fulfilled-amount pos-shortfall 0))
                        net-amt)
        world-after-policy
        (yield-policy/apply-yield-policy world-after-yield workflow-id direction)
        ;; Sub-held (computed after policy so accrual-in-held is reconciled):
        ;; - no-shortfall: remove gross afa (amt)
        ;; - partial-yield shortfall: held after policy minus deferred obligation
        ;; - gross shortfall: fulfilled only (deferred remains in :total-held)
        sub-held-amt  (if pos-shortfall
                        (let [fulfilled (:fulfilled-amount pos-shortfall 0)
                              deferred  (:deferred-amount pos-shortfall 0)
                              haircut   (:haircut-amount pos-shortfall 0)
                              held      (get-in world-after-policy [:total-held token] 0)]
                          (cond
                            partial-yield?
                            (- held deferred)

                            (pos? deferred) fulfilled
                            (>= held (+ fulfilled haircut)) (+ fulfilled haircut)
                            :else fulfilled))
                        amt)]
    (let [result (-> world-after-policy
                     (acct/sub-held token sub-held-amt)
                     (record-fn token settled-amt)
                     ;; Track outbound FoT fee
                     (update-in [:total-fot-fees token] (fnil + 0) (- amt net-amt))
                     ;; Principal claimable
                     (acct/record-claimable-v2 workflow-id :settlement/principal recipient settled-amt)
                     (update :pending-settlements dissoc workflow-id)
                     (sm/apply-transition! workflow-id direction)
                     ;; Reset dispute/cancel statuses
                     (update-in [:escrow-transfers workflow-id] assoc :sender-status :none :recipient-status :none))
          evidence-reason (if (= direction :released) :escrow-released :escrow-refunded)]
      (attr/with-attribution {:subject/type :escrow
                              :subject/id workflow-id
                              :action/type (keyword "escrow" (name direction))
                              :evidence/reason evidence-reason}
         (evidence/capture-event-evidence!
           evidence-reason
           {:finalize/before
            {:workflow-state (t/escrow-state world workflow-id)
             :total-held (get-in world [:total-held token])
             :resolver (:dispute-resolver et)}}
           {:finalize/after
            {:workflow-state (t/escrow-state result workflow-id)
             :total-held (get-in result [:total-held token])}}
           {:finalize/workflow-id workflow-id
            :finalize/direction direction
            :finalize/recipient recipient
            :finalize/settled-amount settled-amt
            :finalize/sub-held-amount sub-held-amt
            :finalize/partial-yield? (boolean partial-yield?)
            :finalize/shortfall? (boolean pos-shortfall)
            :finalize/resolver (:dispute-resolver et)}
           nil
           {:world-before world
            :world-after result}))
      result)))

(defn finalize-escrow-accounting
  "Shared finalize accounting for release/refund and resolution paths."
  [world workflow-id direction]
  (finalize world workflow-id direction))

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
  (let [token (keyword token)]
    (cond
    (nil? token)
    (t/fail :invalid-token)

    (not (token-available? world token))
    (t/fail :token-liquidity-crunch)

    (nil? to)
    (t/fail :invalid-recipient)

    (<= amount 0)
    (t/fail :amount-zero)

    (let [ymid (:yield-generation-module snapshot)
          yield-enabled? (and ymid (t/yield-preset-yield-enabled? (:yield-preset settings)))]
      (and yield-enabled? (not (yield-module-available? world ymid token))))
    (t/fail :insufficient-module-liquidity)

    (and (pos? (:auto-release-time settings 0))
         (pos? (:auto-cancel-time settings 0)))
    (t/fail :cannot-set-both-auto-times)

    :else
    (let [workflow-id   (get world :next-workflow-id 0)]
      (let [fee-bps       (:escrow-fee-bps snapshot 0)
            fee           (payoffs/calculate-escrow-fee amount fee-bps)
            afa           (- amount fee)
            ;; _applyEscrowSettings: compute effective auto times
            snap-rel      (:default-auto-release-delay snapshot 0)
            snap-can      (:default-auto-cancel-delay snapshot 0)
            use-defaults? (and (zero? (:auto-release-time settings 0))
                               (zero? (:auto-cancel-time settings 0)))
            auto-rel      (cond
                            (pos? (:auto-release-time settings 0)) (:auto-release-time settings)
                            (and use-defaults? (pos? snap-rel))    (+ (time-ctx/block-ts world) snap-rel)
                            :else                                   0)
            auto-can      (cond
                            (pos? (:auto-cancel-time settings 0)) (:auto-cancel-time settings)
                            (and use-defaults? (pos? snap-can))   (+ (time-ctx/block-ts world) snap-can)
                            :else                                  0)
            ;; Resolver: custom-resolver takes precedence over snapshot
            resolver      (or (:custom-resolver settings)
                              (:dispute-resolver snapshot))
            ;; Bonding guard: only enforce when resolver-bond-bps is configured
            bond-bps      (:resolver-bond-bps snapshot 0)
            stake         (if resolver (reg/get-stake world resolver) 0)]
            (cond
            (and resolver (t/resolver-at-capacity? world resolver))
            (t/fail :resolver-at-capacity)

            (and resolver (> (get-in world [:resolver-frozen-until resolver] 0) (time-ctx/block-ts world)))
            (t/fail :resolver-frozen)

            (and resolver (pos? bond-bps) (pos? stake)
                 (not (reg/can-handle-escrow? world resolver afa)))
            (t/fail :insufficient-resolver-stake)

            :else
            (let [et            (t/make-escrow-transfer
                               {:token             token
                                :to                to
                                :from              caller
                                :amount-after-fee  afa
                                :initial-fee       fee
                                :dispute-resolver  resolver
                                :auto-release-time auto-rel
                                :auto-cancel-time  auto-can
                                :last-accrual-time (time-ctx/block-ts world)
                                :escrow-state      :pending})
                ymid          (:yield-generation-module snapshot)
                world'        (-> world
                                  (assoc :next-workflow-id (inc workflow-id))
                                  (assoc-in [:escrow-transfers workflow-id] et)
                                  (assoc-in [:escrow-settings workflow-id]
                                            (t/make-escrow-settings settings))
                                  (assoc-in [:module-snapshots workflow-id] snapshot)
                                  (update-in [:total-principal-deposited token] (fnil + 0) amount)
                                  (add-held token afa)
                                  (add-fee token fee)
                                  (update-in [:total-fot-fees token] (fnil + 0) (- amount afa fee)))
                ;; Trigger yield deposit if module is configured
                world''       (if (and ymid
                                       (t/yield-preset-yield-enabled? (:yield-preset settings))
                                       (contains? (:yield/modules world') ymid))
                                   (yield-ops/apply-yield-op world' {:op/type :yield/deposit
                                                                     :module/id ymid
                                                                     :owner/id (t/escrow-yield-owner-id workflow-id)
                                                                     :amount afa
                                                                     :token token})
                                   world')]
             ;; Evidence capture with canonical attribution + rich domain payload
             (let [yield-deposit-applied? (and ymid
                                               (t/yield-preset-yield-enabled? (:yield-preset settings))
                                               (contains? (:yield/modules world') ymid))
                   ;; Normalize settings to artifact-safe fields only
                   settings-ev {:yield-preset (:yield-preset settings)
                                :profile-id (:profile-id settings)
                                :protection-profile-id (:protection-profile-id settings)
                                :auto-release (:auto-release settings)
                                :auto-cancel (:auto-cancel settings)
                                :custom-resolver (:custom-resolver settings)}
                   created-wf (get-in world'' [:escrow-transfers workflow-id])]
               (attr/with-attribution {:subject/type :escrow
                                       :subject/id workflow-id
                                       :action/type :escrow/create
                                       :evidence/reason :escrow-created}
                  (evidence/capture-event-evidence!
                    :escrow-created
                    {:escrow/before
                     {:next-workflow-id (:next-workflow-id world)
                      :total-held (get-in world [:total-held token])
                      :resolver-stake (when resolver (reg/get-stake world resolver))}}
                    {:escrow/after
                     {:next-workflow-id (:next-workflow-id world'')
                      :total-held (get-in world'' [:total-held token])
                      :resolver-stake (when resolver (reg/get-stake world'' resolver))
                      :created-workflow (select-keys created-wf
                                                      [:token :to :from :amount-after-fee
                                                       :initial-fee :dispute-resolver
                                                       :auto-release-time :auto-cancel-time
                                                       :escrow-state :last-accrual-time])}}
                    {:escrow/workflow-id workflow-id
                     :escrow/token token
                     :escrow/amount amount
                     :escrow/fee fee
                     :escrow/amount-after-fee afa
                     :escrow/resolver resolver
                     :escrow/auto-release auto-rel
                     :escrow/auto-cancel auto-can
                     :escrow/yield-module ymid
                     :escrow/yield-deposit-applied? yield-deposit-applied?
                     :escrow/settings settings-ev}
                    nil
                    {:world-before world
                     :world-after world''})))
             (assoc (t/ok world'') :workflow-id workflow-id))))))))

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
          (let [world' (t/increment-resolver-capacity (:world result) resolver)]
            (attr/with-attribution {:subject/type :dispute
                                    :subject/id workflow-id
                                    :action/type :dispute/raise
                                    :evidence/reason :dispute-raised}
              (evidence/capture-event-evidence!
                :dispute-raised
                {:dispute/before {:escrow-state (t/escrow-state world workflow-id)
                                  :resolver resolver}}
                {:dispute/after  {:escrow-state (t/escrow-state world' workflow-id)
                                  :resolver-capacity (get-in world' [:resolver-capacities resolver :current-active])}}
                {:dispute/workflow-id workflow-id
                 :dispute/caller caller
                 :dispute/resolver resolver
                 :dispute/level (t/dispute-level world' workflow-id)}
                nil
                {:world-before world
                 :world-after world'}))
            (t/ok world')))))))

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
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-pending
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (nil? release-strategy-fn)
    (guard-fail :release-strategy-not-set :workflow-id workflow-id)

    :else
    (let [{:keys [allowed? reason-code]} (release-strategy-fn world workflow-id caller)]
      (if-not allowed?
        (guard-fail (if (= 1 reason-code) :not-sender :release-not-allowed)
                    :reason-code reason-code :workflow-id workflow-id)
        (t/ok (finalize world workflow-id :released))))))

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
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= caller (get-in world [:escrow-transfers workflow-id :from]))
    (guard-fail :not-sender :caller caller :workflow-id workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-pending
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    ;; Strategy set and blocks the call
    (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy)))
    (guard-fail :not-authorized-to-cancel-yet
                :cancel-strategy cancel-strategy :workflow-id workflow-id)

    ;; Strategy permits unilateral cancel
    (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy))
    (t/ok (finalize world workflow-id :refunded))

    :else
    ;; Mutual-consent path: set sender status
    (let [r (sm/set-sender-agree-to-cancel world workflow-id caller)]
      (if-not (:ok r)
        r
        (if (sm/both-agreed-to-cancel? (:world r) workflow-id)
          (t/ok (finalize (:world r) workflow-id :refunded))
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
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= caller (get-in world [:escrow-transfers workflow-id :to]))
    (guard-fail :not-recipient :caller caller :workflow-id workflow-id)

    (not= :pending (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-pending
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (and (some? cancel-strategy) (not (:can-cancel? cancel-strategy)))
    (guard-fail :not-authorized-to-cancel-yet
                :cancel-strategy cancel-strategy :workflow-id workflow-id)

    (and (some? cancel-strategy) (:unilateral-cancel? cancel-strategy))
    (t/ok (finalize world workflow-id :refunded))

    :else
    (let [r (sm/set-recipient-agree-to-cancel world workflow-id caller)]
      (if-not (:ok r)
        r
        (if (sm/both-agreed-to-cancel? (:world r) workflow-id)
          (t/ok (finalize (:world r) workflow-id :refunded))
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
          token           (:token et)
           has-resolver?   (and resolver
                                (not= resolver t/zero-address))
          
          ;; Ensure finalize, slash, and distribution are handled 
          ;; as a single, atomic state transition to satisfy invariants.
          world-finalized (finalize world workflow-id :refunded)
          world-slashed   (if has-resolver?
                            (:world (reg/slash-resolver-stake world-finalized resolver slash-amt
                                                              nil 0 workflow-id))
                            world-finalized)
          world-result    (-> world-slashed
                              (t/decrement-resolver-capacity resolver)
                              (update :dispute-timestamps dissoc workflow-id))]
      (t/ok world-result))))

;; ── Monadic Transitions ──────────────────────────────────────────────────────

(require '[resolver-sim.util.state-monad :as monad]
         '[resolver-sim.util.attributed-monad :as am])

(defn create-escrow-m
  "Monadic version of create-escrow."
  [caller token to amount settings snapshot]
  (am/update-with-result create-escrow caller token to amount settings snapshot))

(defn raise-dispute-m
  "Monadic version of raise-dispute."
  [workflow-id caller]
  (am/update-with-result raise-dispute workflow-id caller))

(defn release-m
  "Monadic version of release."
  [workflow-id caller release-strategy-fn]
  (am/update-with-result release workflow-id caller release-strategy-fn))

(defn sender-cancel-m
  "Monadic version of sender-cancel."
  [workflow-id caller cancel-strategy]
  (am/update-with-result sender-cancel workflow-id caller cancel-strategy))

(defn recipient-cancel-m
  "Monadic version of recipient-cancel."
  [workflow-id caller cancel-strategy]
  (am/update-with-result recipient-cancel workflow-id caller cancel-strategy))

(defn auto-cancel-disputed-escrow-m
  "Monadic version of auto-cancel-disputed-escrow."
  [workflow-id]
  (am/update-with-result auto-cancel-disputed-escrow workflow-id))

(defn init-resolver-yield-accrual-time-m
  "Monadic version of init-resolver-yield-accrual-time."
  [resolver-addr]
  (am/update-attributed #(init-resolver-yield-accrual-time % resolver-addr)))

(defn accrue-resolver-yield-m
  "Monadic version of accrue-resolver-yield."
  [resolver-addr token]
  (am/update-attributed #(accrue-resolver-yield % resolver-addr token)))

(defn accrue-yield-monadic
  "Monadic implementation of accrue-yield, threading AttributedState."
  [workflow-id]
  (monad/update-state
   (fn [attributed-state]
     (let [world (attr/unwrap-state attributed-state)
           snap (t/get-snapshot world workflow-id)
           mid  (:yield-generation-module snap)]
       (if (and mid (contains? (:yield/modules world) mid))
         (let [et    (t/get-transfer world workflow-id)
               now   (time-ctx/block-ts world)
               last  (:last-accrual-time et now)
               dt    (- now last)]
           (if (pos? dt)
             (let [world' (yield-ops/apply-yield-op world {:op/type :yield/accrue
                                                           :module/id mid
                                                           :owner/id (t/escrow-yield-owner-id workflow-id)
                                                           :token (:token et)
                                                           :dt dt})
                   oid    (t/escrow-yield-owner-id workflow-id)
                   pos    (get-in world' [:yield/positions oid])
                   accrued (+ (:unrealized-yield pos 0) (:realized-yield pos 0))
                   world'' (-> world'
                               (assoc-in [:escrow-transfers workflow-id :last-accrual-time] now)
                               (assoc-in [:escrow-transfers workflow-id :accumulated-yield] accrued))]
               (attr/wrap-state world'' (attr/get-attribution attributed-state)))
             attributed-state))
         attributed-state)))))

(defn accrue-yield
  "Calculate and update accrued yield for an escrow based on time delta."
  [world workflow-id]
  (let [attributed (attr/wrap-state world (attr/current-attribution))]
    (attr/unwrap-state (monad/exec-state (accrue-yield-monadic workflow-id) attributed))))
