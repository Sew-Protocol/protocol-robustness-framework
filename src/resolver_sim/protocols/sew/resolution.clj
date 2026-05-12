(ns resolver-sim.protocols.sew.resolution
  "Pure Clojure port of BaseEscrow resolution and appeal-window logic.

   Covers:
     execute-resolution          — _executeResolution (resolver submits outcome)
     execute-pending-settlement  — executePendingSettlement (after appeal deadline)
     automate-timed-actions      — automateTimedActions (keeper dispatch)
     escalate-dispute            — escalateDispute (appeal to next round)

   All functions return {:ok bool :world world' :error keyword}."
  (:require [resolver-sim.protocols.sew.types         :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.authority     :as auth]
            [resolver-sim.protocols.sew.accounting    :as acct]
            [resolver-sim.protocols.sew.registry      :as reg]
            [resolver-sim.economics.payoffs            :as payoffs]))

;; ---------------------------------------------------------------------------
;; Internal: finalize helpers (no accounting — see lifecycle for that)
;; ---------------------------------------------------------------------------

(defn- finalize-release [world workflow-id]
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

(defn- finalize-refund [world workflow-id]
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
;; Internal: slashing helpers
;; ---------------------------------------------------------------------------

(defn- handle-reversal-slashing
  "Handles the outcome of a reversed decision. 
   Matches Solidity's two-track slashing system:
   
   1. Automated Track (slashForReversal):
      If the evidence is identical, it's a verifiable failure of the resolver.
      Slash is EXECUTED immediately and is NOT appealable.
   
   2. Manual Track (proposeSlash):
      If new evidence was provided, governance must decide if it was fraud.
      Slash is PENDING and can be appealed by the resolver."
  [world workflow-id current-is-release]
  (let [level (t/dispute-level world workflow-id)]
    (if (pos? level)
      (let [prev-decision (get-in world [:previous-decisions workflow-id (dec level)])]
        (if (and (some? prev-decision) (not= (:is-release prev-decision) current-is-release))
          (let [prev-resolver (:resolver prev-decision)
                et            (t/get-transfer world workflow-id)
                afa           (:amount-after-fee et)
                snap          (t/get-snapshot world workflow-id)
                
                ;; Evidence Check (Phase M Practicality)
                ;; In a real system, the resolver signs an evidence hash.
                ;; Here we check if the world state indicates "new information".
                new-evidence? (get-in world [:evidence-updated? workflow-id] false)
                
                slash-bps     (:reversal-slash-bps snap 0)
                slash-amt     (payoffs/calculate-reversal-slash afa slash-bps)
                slash-id      (str workflow-id "-reversal")
                now           (:block-time world)]
            
            (if (not new-evidence?)
              ;; TRACK 1: AUTOMATED (Same evidence = Negligence/Bias)
              ;; Slash is EXECUTED immediately, no appeal window.
              (if (pos? slash-amt)
                (let [challenger (get-in world [:challengers workflow-id (dec level)])
                      bounty-bps (:challenge-bounty-bps snap 0)]
                  (-> (reg/slash-resolver-stake world prev-resolver slash-amt challenger bounty-bps)
                      :world
                      (assoc-in [:pending-fraud-slashes slash-id]
                                {:resolver        prev-resolver
                                 :amount          slash-amt
                                 :status          :executed
                                 :reason          :reversal
                                 :proposed-at     now
                                 :appeal-deadline 0
                                 :appeal-bond-held 0
                                 :contest-deadline 0})))
                world)
              
              ;; TRACK 2: MANUAL (New evidence = Honest Disagreement potential)
              ;; Slash is PENDING with appeal window — resolver can contest.
              (let [gov-delay (or (:appeal-window-duration snap) 259200)]
                (assoc-in world [:pending-fraud-slashes slash-id]
                          {:resolver         prev-resolver
                           :amount           slash-amt
                           :reason           :reversal
                           :status           :pending
                           :proposed-at      now
                           :appeal-deadline  (+ now gov-delay)
                           :appeal-bond-held 0
                           :contest-deadline 0}))))
          world))
      world)))

(defn- handle-fraud-slashing
  "Create a PENDING fraud slash for a resolver.
   
   Mirrors the corrected slashForFraud (Fix A): fraud slashes start as PENDING
   with an appeal window, not immediately EXECUTED. This ensures resolvers have
   procedural protection against incorrect fraud allegations.
   
   slash-id     — unique identifier (e.g. workflow-id or a generated key)
   resolver     — the resolver being slashed
   slash-amt    — amount to slash (in stake units)
   appeal-window — seconds the resolver has to appeal"
  [world slash-id resolver slash-amt appeal-window]
  (let [now (:block-time world)]
    (assoc-in world [:pending-fraud-slashes slash-id]
              {:resolver         resolver
               :amount           slash-amt
               :reason           :fraud
               :status           :pending
               :proposed-at      now
               :appeal-deadline  (+ now appeal-window)
               :appeal-bond-held 0
               :contest-deadline 0})))

(defn- update-unavailability
  "Idempotent resolver unavailability accounting + circuit breaker trigger.
   Mirrors Solidity behavior at a model level."
  [world resolver unavailable?]
  (let [prev-unavailable? (contains? (:resolver-unavailable world #{}) resolver)
        world' (cond
                 (and unavailable? (not prev-unavailable?))
                 (-> world
                     (update :resolver-unavailable (fnil conj #{}) resolver)
                     (update-in [:unavailability-stats :unavailable-count] (fnil inc 0)))

                 (and (not unavailable?) prev-unavailable?)
                 (-> world
                     (update :resolver-unavailable disj resolver)
                     (update-in [:unavailability-stats :unavailable-count] (fnil #(max 0 (dec %)) 0)))

                 :else world)
        world'' (assoc-in world' [:unavailability-stats :last-update] (:block-time world))
        total (get-in world'' [:unavailability-stats :total-resolvers] 0)
        unavailable (get-in world'' [:unavailability-stats :unavailable-count] 0)
        threshold (get-in world'' [:circuit-breaker :threshold-bps] 3000)
        pct-bps (if (pos? total) (quot (* unavailable 10000) total) 0)]
    (if (and (pos? total) (>= pct-bps threshold))
      (-> world''
          (assoc-in [:circuit-breaker :active?] true)
          (assoc-in [:circuit-breaker :last-trigger] (:block-time world)))
      world'')))

;; ---------------------------------------------------------------------------
;; execute-resolution
;;
;; Mirrors: BaseEscrow._executeResolution
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. caller must be an authorized dispute resolver
;;   3. state must be :disputed
;;
;; Appeal window logic (SettlementOps.computeResolutionExecution):
;;   If snap.appeal-window-duration > 0:
;;     → store PendingSettlement, do NOT finalize immediately
;;   Else:
;;     → finalize immediately (release or refund)
;;
;; When escalation occurs (dispute is escalated to a higher level):
;;   Any existing pending-settlement is cancelled (see _validateAndPrepareEscalation).
;;   This function models the normal case (no concurrent escalation).
;; ---------------------------------------------------------------------------

(defn execute-resolution
  "Submit a resolution decision for a :disputed escrow.

   caller              — address of msg.sender (must be authorized resolver)
   is-release          — true = release to recipient, false = refund to sender
   resolution-hash     — bytes32 (opaque string in the model)
   resolution-module-fn — (fn [wf caller] → {:authorized? bool}) or nil"
  [world workflow-id caller is-release resolution-hash resolution-module-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not (auth/authorized-resolver? world workflow-id caller resolution-module-fn))
    (t/fail :not-authorized-resolver)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    ;; Prevent resolution flip: reject if a pending settlement already exists
    (:exists (t/get-pending world workflow-id))
    (t/fail :resolution-already-pending)

    :else
    (let [snap           (t/get-snapshot world workflow-id)
          ;; Phase L extension: window is the MAX of appeal-window and challenge-window
          window-dur     (max (:appeal-window-duration snap 0)
                              (:challenge-window-duration snap 0))
          now            (:block-time world)
          ;; Mirrors SettlementOps.computeResolutionExecution:
          ;; if isFinalRound (currentRound >= MAX_ROUND) → shouldExecute = true
          ;; (no appeal window, decision is immediately final)
          final-round?   (t/final-round? world workflow-id)
          ;; DR3 Sync: handle resolver bond staking (record for now)
          bond-bps       (:resolver-bond-bps snap 0)
          et             (t/get-transfer world workflow-id)
          afa            (:amount-after-fee et)
          bond-amt       (t/compute-fee afa bond-bps)
          
          ;; Phase K: handle reversal slashing
          world'         (handle-reversal-slashing world workflow-id is-release)
          
          ;; Record current decision for future reversal checks
          world''        (assoc-in world' [:previous-decisions workflow-id (t/dispute-level world workflow-id)]
                                   {:resolver caller :is-release is-release})]
      (if (or final-round? (not (pos? window-dur)))
        ;; Final round or no windows: execute immediately
        (t/ok (if is-release
                (finalize-release world'' workflow-id)
                (finalize-refund  world'' workflow-id)))

        ;; Window active: defer settlement
        (let [pending (t/make-pending-settlement
                       {:exists          true
                        :is-release      is-release
                        :appeal-deadline (+ now window-dur)
                        :resolution-hash resolution-hash})
              world''' (assoc-in world'' [:pending-settlements workflow-id] pending)]
          (t/ok world'''))))))

;; ---------------------------------------------------------------------------
;; execute-pending-settlement
;;
;; Mirrors: BaseEscrow.executePendingSettlement
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. pending-settlement must exist (pending.exists = true)
;;   3. state must be :disputed
;;   4. block-time >= appeal-deadline
;; ---------------------------------------------------------------------------

(defn execute-pending-settlement
  "Execute a deferred settlement after the appeal window has closed."
  [world workflow-id]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not (:exists (t/get-pending world workflow-id)))
    (t/fail :no-pending-settlement)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (< (:block-time world) (:appeal-deadline (t/get-pending world workflow-id)))
    (t/fail :appeal-window-not-expired)

    :else
    (let [pending (t/get-pending world workflow-id)]
      (t/ok (if (:is-release pending)
              (finalize-release world workflow-id)
              (finalize-refund  world workflow-id))))))

;; ---------------------------------------------------------------------------
;; Internal building block: _validateAndPrepareEscalation deletes
;; pendingSettlements[workflowId] before escalation proceeds.
;; ---------------------------------------------------------------------------

(defn cancel-pending-on-escalation
  "Cancel pending-settlement when a dispute is being escalated.
   Called as a side-effect of escalation before the new level is set.
   Returns the updated world (not a result map)."
  [world workflow-id]
  (update world :pending-settlements dissoc workflow-id))

;; ---------------------------------------------------------------------------
;; challenge-resolution (Phase L)
;;
;; Allows any third party to force an escalation by posting a challenge bond.
;; ---------------------------------------------------------------------------

(defn challenge-resolution
  "Challenge a provisional resolution decision (Phase L).
   Similar to escalate-dispute but can be called by anyone.

   caller — address of the challenger (third party or participant)
   escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})"
  [world workflow-id caller escalation-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (t/final-round? world workflow-id)
    (t/fail :escalation-not-allowed)

    (not (:exists (t/get-pending world workflow-id)))
    (t/fail :no-resolution-to-challenge)

    ;; Appeal window has closed — the pending settlement is now executable.
    (>= (:block-time world) (:appeal-deadline (t/get-pending world workflow-id)))
    (t/fail :appeal-window-expired)

    (nil? escalation-fn)
    (t/fail :escalation-not-configured)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              snap         (t/get-snapshot world workflow-id)
              et           (t/get-transfer world workflow-id)
              ;; Challenge bond amount
              bond-amt     (payoffs/calculate-challenge-bond-amount (:amount-after-fee et) snap)
              
              world'       (-> world
                               (acct/post-appeal-bond workflow-id caller snap (:token et) bond-amt)
                               (assoc-in [:challengers workflow-id current-level] caller)
                               (cancel-pending-on-escalation workflow-id)
                               (assoc-in [:dispute-levels workflow-id] new-level)
                               (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                         new-resolver)
                               (assoc-in [:last-escalation-block-time workflow-id]
                                         (:block-time world)))]
          (assoc (t/ok world')
                 :new-level    new-level
                 :new-resolver new-resolver))))))

;; ---------------------------------------------------------------------------
;; automate-timed-actions
;;
;; Mirrors: BaseEscrow.automateTimedActions
;;
;; Dispatch order (matches the contract's if/else if chain):
;;   1. ACTION_EXECUTE_PENDING  — pending-settlement executable?
;;   2. ACTION_AUTO_RELEASE     — auto-release-time passed?
;;   3. ACTION_AUTO_CANCEL      — auto-cancel-time passed?
;;   4. ACTION_NONE             — no action, return {:ok true :world world :action :none}
;;
;; Returns {:ok bool :world world' :action kw} where action is one of:
;;   :execute-pending :auto-release :auto-cancel :none
;; ---------------------------------------------------------------------------

(defn automate-timed-actions
  "Dispatch timed keeper actions for workflow-id.

   Returns {:ok true :world world' :action kw} — even when action is :none,
   to simplify caller logic."
  [world workflow-id]
  (if (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)
    (cond
      ;; Priority 1: pending settlement ready to execute
      (sm/pending-settlement-executable? world workflow-id)
      (let [r (execute-pending-settlement world workflow-id)]
        (if (:ok r)
          (assoc r :action :execute-pending)
          r))

      ;; Priority 2: auto-release
      (sm/auto-release-due? world workflow-id)
      (let [r (t/ok (finalize-release world workflow-id))]
        (assoc r :action :auto-release))

      ;; Priority 3: auto-cancel
      (sm/auto-cancel-due? world workflow-id)
      (let [r (t/ok (finalize-refund world workflow-id))]
        (assoc r :action :auto-cancel))

      :else
      (assoc (t/ok world) :action :none))))

;; ---------------------------------------------------------------------------
;; escalate-dispute
;;
;; Mirrors: BaseEscrow.escalateDispute
;;          + DisputeOps.computeEscalation
;;          + DecentralizedResolutionModule.executeEscalation
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. state must be :disputed
;;   3. caller must be :from or :to (participant only can appeal)
;;   4. current level must be < max-dispute-level (cannot escalate beyond MAX_ROUND)
;;   5. a pending settlement must exist — escalation is an appeal of a resolver's
;;      decision, not a pre-emptive jump to the next level.  Without this guard
;;      a malicious party can bypass all lower-level resolvers immediately.
;;   6. escalation-fn must return {:ok true :new-resolver addr}
;;
;; Effects (in order, matching Solidity):
;;   a. Clear pending-settlement (_validateAndPrepareEscalation)
;;   b. Increment dispute-level
;;   c. Update et.dispute-resolver to the new resolver
;;
;; escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})
;;   Models the combined DisputeOps.computeEscalation + DRM.executeEscalation call.
;;   Pass nil to simulate "no resolution module / escalation not allowed".
;;
;; Returns {:ok true :world world' :new-level n :new-resolver addr}
;;         or {:ok false :error kw}
;; ---------------------------------------------------------------------------

(defn escalate-dispute
  "Escalate a :disputed escrow to the next resolution round.

   Requires a pending settlement to exist: escalation is an appeal of an
   existing resolver decision, not a unilateral level-skip.

   escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})
                   Pass nil to simulate 'escalation not configured'."
  [world workflow-id caller escalation-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (let [et (t/get-transfer world workflow-id)]
      (and (not= caller (:from et)) (not= caller (:to et))))
    (t/fail :not-participant)

    (t/final-round? world workflow-id)
    (t/fail :escalation-not-allowed)

    ;; Escalation is an appeal: a resolver must have already submitted a
    ;; resolution (creating a pending settlement) before a party may escalate.
    (not (:exists (t/get-pending world workflow-id)))
    (t/fail :no-resolution-to-appeal)

    ;; Appeal window has closed — the pending settlement is now executable.
    (>= (:block-time world) (:appeal-deadline (t/get-pending world workflow-id)))
    (t/fail :appeal-window-expired)

    (nil? escalation-fn)
    (t/fail :escalation-not-configured)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              
              ;; DR3 Sync: handle appeal bond posting
              snap         (t/get-snapshot world workflow-id)
              et           (t/get-transfer world workflow-id)
              bond-amt     (payoffs/calculate-appeal-bond-amount (:amount-after-fee et) snap)
              
              ;; Ensure workflow exists in bond-balances before updating
              world-prepared (if (get-in world [:bond-balances workflow-id])
                               world
                               (assoc-in world [:bond-balances workflow-id] {}))
              
              ;; post-appeal-bond adds to :total-held internally.
              world'       (-> world-prepared
                               (acct/post-appeal-bond workflow-id caller snap (:token et) bond-amt)
                               (cancel-pending-on-escalation workflow-id)
                               (assoc-in [:dispute-levels workflow-id] new-level)
                               (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                         new-resolver)
                               ;; Track when this escalation occurred so time-lock-integrity?
                               ;; can detect two escalations within the same block.
                               (assoc-in [:last-escalation-block-time workflow-id]
                                         (:block-time world)))]
          (assoc (t/ok world')
                 :new-level    new-level
                 :new-resolver new-resolver))))))

(defn appeal-slash
  "Resolver appeals a PENDING manual slash (Phase M).
   slash-id defaults to workflow-id; pass the string slash-id for reversal slashes
   (e.g. \"0-reversal\").
   Mirrors: ResolverSlashingModuleV1.appealSlash"
  ([world workflow-id caller] (appeal-slash world workflow-id caller workflow-id))
  ([world workflow-id caller slash-id]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (nil? pending)
       (t/fail :no-pending-slash)

       (not= :pending (:status pending))
       (t/fail :slash-not-pending)

       (> (:block-time world) (:appeal-deadline pending))
       (t/fail :appeal-window-expired)

       (not= caller (:resolver pending))
       (t/fail :not-resolver)

       :else
       (let [snap        (t/get-snapshot world workflow-id)
             et          (t/get-transfer world workflow-id)
             token       (:token et)
             bond-amount (max 0 (or (:appeal-bond-amount snap) 0))
             world'      (if (pos? bond-amount)
                           (-> world
                               (assoc-in [:pending-fraud-slashes slash-id :appeal-bond-held] bond-amount)
                               (assoc-in [:appeal-bond-custody slash-id]
                                         {:resolver caller :workflow-id workflow-id :amount bond-amount :token token}))
                           world)]
         (t/ok (assoc-in world' [:pending-fraud-slashes slash-id :status] :appealed)))))))

(defn propose-fraud-slash
  "Governance (TIMELOCK) proposes a manual fraud slash for a resolver (Phase M).
   Mirrors: ResolverSlashingModuleV1.proposeSlash.
   Marks status as :pending to allow for appeal."
  [world workflow-id caller resolver-addr amount]
  (if-not (t/valid-workflow-id? world workflow-id)
    (t/fail :invalid-workflow-id)
    (let [snap (t/get-snapshot world workflow-id)
          gov-delay (or (:appeal-window-duration snap) 259200)] ; 3 days default
      (t/ok (assoc-in world [:pending-fraud-slashes workflow-id]
                      {:resolver         resolver-addr
                       :amount           amount
                        :reason           :fraud
                       :status           :pending
                       :proposed-at      (:block-time world)
                       :appeal-deadline  (+ (:block-time world) gov-delay)
                       :appeal-bond-held 0
                       :contest-deadline 0})))))

(defn resolve-appeal
  "Governance (TIMELOCK) resolves a slashing appeal.
   Naming note: `appeal-upheld?` means the APPEAL is upheld (accepted),
   not that the slash is upheld.
   - appeal-upheld? = true  -> slash is reversed and cannot be executed.
   - appeal-upheld? = false -> appeal is rejected; slash returns to pending.
   Mirrors: ResolverSlashingModuleV1.resolveAppeal"
  ([world workflow-id caller appeal-upheld?]
   (resolve-appeal world workflow-id caller appeal-upheld? workflow-id))
  ([world _workflow-id _caller appeal-upheld? slash-id]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (nil? pending)
       (t/fail :no-pending-slash)

       (not= :appealed (:status pending))
       (t/fail (case (:status pending)
                 :executed :cannot-reverse-executed-slash
                 :no-active-appeal))

       :else
       (let [bond-held   (get-in world [:pending-fraud-slashes slash-id :appeal-bond-held] 0)
             custody     (get-in world [:appeal-bond-custody slash-id])
             resolver    (or (:resolver custody) (:resolver pending))
             workflow-id (:workflow-id custody slash-id)
             world'      (-> world
                             (assoc-in [:pending-fraud-slashes slash-id :appeal-bond-held] 0)
                             (update :appeal-bond-custody dissoc slash-id))
             world''     (if appeal-upheld?
                           (if (pos? bond-held)
                             (-> world'
                                 (assoc-in [:pending-fraud-slashes slash-id :status] :reversed)
                                 (acct/record-claimable workflow-id resolver bond-held))
                             (assoc-in world' [:pending-fraud-slashes slash-id :status] :reversed))
                           (if (pos? bond-held)
                             (-> world'
                                 (assoc-in [:pending-fraud-slashes slash-id :status] :pending)
                                 (update-in [:bond-distribution :insurance] (fnil + 0) bond-held)
                                 (update :appeal-bonds-forfeited-insurance (fnil + 0) bond-held))
                             (assoc-in world' [:pending-fraud-slashes slash-id :status] :pending)))]
         (t/ok world''))))))

(defn execute-fraud-slash
  "Execute a previously proposed fraud slash after the timelock/appeal window.
   slash-id defaults to workflow-id; pass the string slash-id for reversal slashes
   (e.g. \"0-reversal\").
   Mirrors: ResolverSlashingModuleV1.executeSlash"
  ([world workflow-id] (execute-fraud-slash world workflow-id workflow-id))
  ([world workflow-id slash-id]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (nil? pending)
       (t/fail :no-pending-slash)

       (not= :pending (:status pending))
       (t/fail (case (:status pending)
                 :appealed :appeal-in-progress
                 :reversed :slash-already-reversed
                 :executed :already-executed
                 :unknown-status))

       (< (:block-time world) (:appeal-deadline pending))
       (t/fail :timelock-not-expired)

       :else
       (let [resolver        (:resolver pending)
             amount          (:amount pending)
             freeze-duration 259200                ; 72 hours in seconds
             world'          (-> world
                                 (assoc-in [:pending-fraud-slashes slash-id :status] :executed)
                                 (reg/slash-resolver-stake resolver amount)
                                 :world
                                 (assoc-in [:resolver-frozen-until resolver]
                                            (+ (:block-time world) freeze-duration))
                                  (update-unavailability resolver true))]
         (t/ok world'))))))

(defn unfreeze-resolver
  "Governance unfreezes resolver and idempotently clears unavailability mark."
  [world resolver]
  (-> world
      (assoc-in [:resolver-frozen-until resolver] 0)
      (update-unavailability resolver false)
      t/ok))

;; ---------------------------------------------------------------------------
;; rotate-dispute-resolver
;;
;; Models a governance-triggered resolver rotation on an in-flight dispute.
;; Guards: workflow must exist + be in :disputed state; new-resolver non-nil.
;; Effects:
;;   - Updates et.dispute-resolver to new-resolver
;;   - Appends to world :resolver-rotations {workflow-id [{:from :to :at}]}
;; ---------------------------------------------------------------------------

(defn rotate-dispute-resolver
  "Governance-triggered resolver rotation for an in-flight dispute.
   Records the rotation so invariants and scenarios can detect governance attacks."
  [world workflow-id new-resolver]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (:exists (t/get-pending world workflow-id))
    (t/fail :resolution-already-pending)

    (or (nil? new-resolver) (= "" new-resolver))
    (t/fail :invalid-new-resolver)

    :else
    (let [old-resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])
          rotation     {:from old-resolver :to new-resolver :at (:block-time world)}
          world'       (-> world
                           (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                     new-resolver)
                           (update-in [:resolver-rotations workflow-id]
                                      (fnil conj []) rotation))]
      (assoc (t/ok world') :old-resolver old-resolver :new-resolver new-resolver))))
