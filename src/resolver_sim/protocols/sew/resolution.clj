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
            [resolver-sim.protocols.sew.lifecycle     :as lc]
            [resolver-sim.protocols.sew.yield.policy  :as yield-policy]
            [resolver-sim.economics.payoffs           :as payoffs]
            [resolver-sim.yield.ops                   :as yield-ops]
            [resolver-sim.util.attribution            :as attr]
            [resolver-sim.util.attributed-monad      :as am]
            [resolver-sim.time.context               :as time-ctx]
            [resolver-sim.evidence.capture             :as cap]
            [resolver-sim.evidence.slashing           :as slashing-ev]
            [resolver-sim.io.event-evidence           :as evidence]))

(declare finalize handle-reversal-slashing handle-fraud-slashing update-unavailability)

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
          same-resolver? (= old-resolver new-resolver)
          last-rotation (last (get-in world [:resolver-rotations workflow-id]))
          same-rotation? (and (not same-resolver?)
                              (some? last-rotation)
                              (= (:from last-rotation) old-resolver)
                              (= (:to last-rotation) new-resolver))]
      (if (or same-resolver? same-rotation?)
        (assoc (t/ok world)
               :old-resolver old-resolver
               :new-resolver new-resolver
               :idempotent? true)
        (let [rotation {:from old-resolver :to new-resolver :at (time-ctx/block-ts world)}
              world'   (-> world
                           (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                     new-resolver)
                           (update-in [:resolver-rotations workflow-id]
                                      (fnil conj []) rotation))]
          (assoc (t/ok world') :old-resolver old-resolver :new-resolver new-resolver))))))

;; ---------------------------------------------------------------------------
;; Internal: slashing helpers
;; ---------------------------------------------------------------------------

(defn- make-reversal-slash-entry
  [slash-id prev-resolver prev-stake slash-bps slash-amt workflow-id status now appeal-deadline reversal-prob]
  {:resolver                          prev-resolver
   :basis-amount                      prev-stake
   :basis-kind                        :stake
   :slash-bps                         slash-bps
   :amount                            slash-amt
   :workflow-id                       workflow-id
   :reason                            :reversal
   :status                            status
   :proposed-at                       now
   :appeal-deadline                   appeal-deadline
   :appeal-bond-held                  0
   :contest-deadline                  0
   :reversal-detection-probability    reversal-prob})

;; ---------------------------------------------------------------------------
;; Guard logging helper — returns (t/fail kw) with :guard-context attached
;; so process-step can capture rejection context in trace entries.
;; ---------------------------------------------------------------------------

(defn- guard-fail [error-kw & {:as ctx}]
  (attr/log-with-attr :debug "guard/rejected" (assoc ctx :error error-kw))
  (assoc (t/fail error-kw) :guard-context ctx))

(defn- handle-reversal-slashing
  "Handles the outcome of a reversed decision.

   Matches Solidity's two-track slashing system:

   1. Automated Track (slashForReversal):
      Same evidence as prior level — deterministic immediate slash (not appealable).

   2. Manual Track (proposeSlash):
       New evidence submitted — slash is :pending with appeal window (governance path).

   Limitation: Track 2 :pending reversal slashes are NOT automatically resolved
   when the escrow finalizes.  If a pending reversal slash outlives the escrow
   (appeal window expires without resolution), the entry remains in
   :pending-fraud-slashes indefinitely.  This is a state inconsistency — the
   slash cannot execute because the escrow is no longer :disputed, but the entry
   is never garbage-collected."
  [world workflow-id current-is-release]
  (let [level (t/dispute-level world workflow-id)]
    (if-not (pos? level)
      world
      (let [prev-decision (get-in world [:previous-decisions workflow-id (dec level)])]
        (if-not (and (some? prev-decision)
                     (not= (:is-release prev-decision) current-is-release))
          world
          (let [prev-resolver   (:resolver prev-decision)
                snap            (t/get-snapshot world workflow-id)
                new-evidence?   (get-in world [:evidence-updated? workflow-id] false)
                slash-bps       (:reversal-slash-bps snap 0)
                prev-stake      (reg/get-stake world prev-resolver)
                slash-amt       (payoffs/calculate-slash-amount-from-basis prev-stake slash-bps)
                slash-id        (str workflow-id "-reversal-" (dec level))
                now             (time-ctx/block-ts world)
                appeal-window   (:appeal-window-duration snap 0)
                reversal-prob   (or (:reversal-detection-probability snap) 0.0)
                challenger      (get-in world [:challengers workflow-id (dec level)])
                bounty-bps      (:challenge-bounty-bps snap 0)]
            (if-not (pos? slash-amt)
              world
              (if new-evidence?
                (assoc-in world [:pending-fraud-slashes slash-id]
                          (make-reversal-slash-entry slash-id prev-resolver prev-stake slash-bps
                                                     slash-amt workflow-id :pending now
                                                     (+ now appeal-window) reversal-prob))
                (-> (reg/slash-resolver-stake world prev-resolver slash-amt challenger bounty-bps workflow-id)
                    :world
                    (assoc-in [:pending-fraud-slashes slash-id]
                              (make-reversal-slash-entry slash-id prev-resolver prev-stake
                                                         slash-bps slash-amt workflow-id :executed
                                                          now 0 reversal-prob)))))))))))

(defn force-reversal-slash
  "Force a reversal slash on a workflow without going through the full resolution
   pipeline.  Allows isolated testing of reversal-slash accounting, stake
   deduction, and bond distribution.

   When optional `:slash-bps` is provided, it overrides the snapshot's
   `:reversal-slash-bps`.  When `:track` is `:immediate`, the slash executes
   immediately (Track 1).  When `:track` is `:pending`, a pending slash with
   appeal window is created (Track 2, default).

   Idempotent: if a force-reversal entry already exists for this workflow-id,
   returns the world unchanged to prevent double-slashing.

   Returns the updated world.

   Short-circuit analogue of `handle-reversal-slashing` — same slash-entry
   schema, same `slash-resolver-stake` call, same invariants apply."
  [world workflow-id & {:keys [slash-bps track]
                        :or   {track :pending}}]
   (let [level       (t/dispute-level world workflow-id)
         prev-decision (when (pos? level)
                         (get-in world [:previous-decisions workflow-id (dec level)]))
         prev-resolver (or (:resolver prev-decision) (get-in world [:escrow-transfers workflow-id :sender]))
         snap          (t/get-snapshot world workflow-id)
         bps           (long (or slash-bps (:reversal-slash-bps snap 0)))
         prev-stake    (reg/get-stake world prev-resolver)
         slash-amt     (payoffs/calculate-slash-amount-from-basis (or prev-stake 0) bps)
         slash-id      (str workflow-id "-force-reversal-0")
         now           (time-ctx/block-ts world)
         appeal-window (:appeal-window-duration snap 0)
         reversal-prob (or (:reversal-detection-probability snap) 0.0)]
     (if (or (nil? prev-resolver) (not (pos? slash-amt)))
       world
       (if (= :immediate track)
         (-> (reg/slash-resolver-stake world prev-resolver slash-amt nil 0 workflow-id)
             :world
             (assoc-in [:pending-fraud-slashes slash-id]
                       (make-reversal-slash-entry slash-id prev-resolver prev-stake bps
                                                  slash-amt workflow-id :executed
                                                  now 0 reversal-prob)))
         (assoc-in world [:pending-fraud-slashes slash-id]
                   (make-reversal-slash-entry slash-id prev-resolver prev-stake bps
                                              slash-amt workflow-id :pending now
                                              (+ now appeal-window) reversal-prob))))))

(defn submit-evidence
  "Record that new evidence was submitted for workflow-id (Track 2 reversal slashing).
   May be called while :disputed before the reversing resolution is executed."
  [world workflow-id _caller & [{:keys [evidence-hash]}]]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    :else
    (t/ok (cond-> (assoc-in world [:evidence-updated? workflow-id] true)
            evidence-hash (assoc-in [:evidence-hashes workflow-id] evidence-hash)))))

(defn- fraud-slash-workflow-eligible?
  "Manual fraud slash requires the workflow to have entered the dispute path:
   in-flight dispute, pending settlement after resolution, or a recorded decision."
  [world workflow-id]
  (let [wf-id (t/normalize-workflow-id workflow-id)]
    (or (= :disputed (t/escrow-state world wf-id))
        (:exists (t/get-pending world wf-id))
        (seq (vals (get-in world [:previous-decisions wf-id] {}))))))

(defn- active-manual-fraud-slash?
  [world workflow-id]
  (let [existing (get-in world [:pending-fraud-slashes (t/normalize-workflow-id workflow-id)])]
    (and existing (#{:pending :appealed} (:status existing)))))

(defn- handle-fraud-slashing
  "Create a PENDING fraud slash for a resolver.

   Mirrors the corrected slashForFraud (Fix A): fraud slashes start as PENDING
   with an appeal window, not immediately EXECUTED.
   
   Captures the reversal-detection-probability from the module snapshot to track
   the likelihood that an appeal will succeed."
  [world slash-id workflow-id resolver slash-amt appeal-window reversal-prob]
  (let [now (time-ctx/block-ts world)]
    (attr/log-with-attr :debug "handle-fraud-slashing" {:now now :appeal-window appeal-window})
    (assoc-in world [:pending-fraud-slashes slash-id]
              {:resolver                      resolver
               :amount                        slash-amt
               :workflow-id                   workflow-id
               :reason                        :fraud
               :status                        :pending
               :proposed-at                   now
               :appeal-deadline               (+ now appeal-window)
               :appeal-bond-held              0
               :contest-deadline              0
               :reversal-detection-probability reversal-prob})))

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
        world'' (assoc-in world' [:unavailability-stats :last-update] (time-ctx/block-ts world))
        total (get-in world'' [:unavailability-stats :total-resolvers] 0)
        unavailable (get-in world'' [:unavailability-stats :unavailable-count] 0)
        threshold (get-in world'' [:circuit-breaker :threshold-bps] 3000)
        pct-bps (if (pos? total) (quot (* unavailable 10000) total) 0)]
    (cond
      ;; Threshold exceeded — activate (or keep) the circuit breaker
      (and (pos? total) (>= pct-bps threshold))
      (-> world''
          (assoc-in [:circuit-breaker :active?] true)
          (assoc-in [:circuit-breaker :last-trigger] (time-ctx/block-ts world)))

      ;; Below threshold but breaker still active — deactivate after cooldown
      (and (get-in world'' [:circuit-breaker :active?] false)
           (let [cooldown (get-in world'' [:circuit-breaker :cooldown] 3600)
                 elapsed (- (time-ctx/block-ts world)
                            (get-in world'' [:circuit-breaker :last-trigger] 0))]
             (>= elapsed cooldown)))
      (assoc-in world'' [:circuit-breaker :active?] false)

      :else
      world'')))

(defn circuit-breaker-active?
  "True when the circuit breaker is active (blocking new escrows/disputes).
   Returns {:ok true} or {:ok false :error :circuit-breaker-active}."
  [world]
  (if (get-in world [:circuit-breaker :active?] false)
    (t/fail :circuit-breaker-active)
    (t/ok true)))

(declare pick-eligible-superseded-pending)

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

(defn- clear-stale-settlement-principal
  "Remove abnormal :settlement/principal claimables while escrow is still :disputed.
   Normal flow records principal only at finalize; stale entries can appear from
   superseded pending write-sets or legacy regressions. Does not clear :settlement/yield."
  [world workflow-id]
  (acct/clear-claimable-v2-kind world workflow-id :settlement/principal))

(defn- clear-pending-settlement [world workflow-id]
  (let [pending (t/get-pending world workflow-id)]
    (if (:exists pending)
      (-> world
          (clear-stale-settlement-principal workflow-id)
          (assoc-in [:pending-settlements workflow-id] t/empty-pending-settlement))
      world)))


(defn execute-resolution
  "Submit a resolution decision for a :disputed escrow.

   caller              — address of msg.sender (must be authorized resolver)
   is-release          — true = release to recipient, false = refund to sender
   resolution-hash     — bytes32 (opaque string in the model)
   resolution-module-fn — (fn [wf caller] → {:authorized? bool}) or nil"
  [world workflow-id caller is-release resolution-hash resolution-module-fn]
  (let [ctx (attr/make-context {:workflow-id workflow-id})]
    (attr/log-annotated! :debug "Submitting resolution" ctx {:caller caller})
    (cond
      (not (t/valid-workflow-id? world workflow-id))
      (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    ;; State is checked before auth so any caller—authorized or not—receives
    ;; :transfer-not-in-dispute on a terminal escrow rather than a misleading
    ;; :not-authorized-resolver that obscures the real cause of failure.
    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (not (auth/authorized-resolver? world workflow-id caller resolution-module-fn))
    (guard-fail :not-authorized-resolver
                :caller caller
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    :else
    (let [world          (clear-pending-settlement world workflow-id)
          world          (lc/accrue-yield world workflow-id)
          snap           (t/get-snapshot world workflow-id)
          ;; Phase L extension: window is the MAX of appeal-window and challenge-window
          window-dur     (max (:appeal-window-duration snap 0)
                              (:challenge-window-duration snap 0))
          now            (time-ctx/block-ts world)
          ;; Mirrors SettlementOps.computeResolutionExecution:
          ;; if isFinalRound (currentRound >= MAX_ROUND) → shouldExecute = true
          ;; (no appeal window, decision is immediately final)
          final-round?   (t/final-round? world workflow-id)
          ;; Phase K: handle reversal slashing (Track 1 auto-slash OR Track 2 pending).
          ;; Called BEFORE the final-round check below so that the prior resolver's
          ;; stake is deducted before the current decision is recorded — the current
          ;; resolver cannot be slashed for their own decision.
          world'         (handle-reversal-slashing world workflow-id is-release)

          ;; Record current decision for future reversal checks
          world''        (assoc-in world' [:previous-decisions workflow-id (t/dispute-level world workflow-id)]
                                   {:resolver caller :is-release is-release})

          ;; Store resolution metadata on the escrow-transfer so terminal-world
          ;; consumers can query who resolved, what the outcome was, and the hash
          world'''       (assoc-in world'' [:escrow-transfers workflow-id :resolution]
                                   {:resolved-by caller
                                    :is-release is-release
                                    :resolution-hash resolution-hash})]
      (if (or final-round? (not (pos? window-dur)))
        ;; Final round or no windows: execute immediately
        (t/ok (if is-release
                (finalize world''' workflow-id :released)
                (finalize world''' workflow-id :refunded)))

        ;; Window active: defer settlement
        (let [pending (t/make-pending-settlement
                       {:exists          true
                        :is-release      is-release
                        :appeal-deadline (+ now window-dur)
                        :resolution-hash resolution-hash})
              world'''' (assoc-in world''' [:pending-settlements workflow-id] pending)]
          (t/ok world'''')))))))

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
  (if-not (t/valid-workflow-id? world workflow-id)
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)
    (let [active-pending (t/get-pending world workflow-id)
          now-ts         (time-ctx/block-ts world)
          pending        (if (:exists active-pending)
                           active-pending
                           (pick-eligible-superseded-pending world workflow-id now-ts))]
      (cond
        (not (:exists pending))
        (guard-fail :no-pending-settlement :workflow-id workflow-id)

        (not= :disputed (t/escrow-state world workflow-id))
        (guard-fail :transfer-not-in-dispute
                    :escrow-state (t/escrow-state world workflow-id)
                    :workflow-id workflow-id)

        (< now-ts (:appeal-deadline pending))
        (guard-fail :appeal-window-not-expired
                    :block-time now-ts
                    :appeal-deadline (:appeal-deadline pending)
                    :workflow-id workflow-id)

        :else
        (t/ok (if (:is-release pending)
                (finalize world workflow-id :released)
                (finalize world workflow-id :refunded)))))))

;; ---------------------------------------------------------------------------
;; Internal building block: _validateAndPrepareEscalation deletes
;; pendingSettlements[workflowId] before escalation proceeds.
;; ---------------------------------------------------------------------------

(defn- archive-pending-on-escalation
  "Archive the current pending settlement as superseded and clear active pending.
   This preserves a fallback execution path for edge-cases where escalation/challenge
   clears pending near the deadline but no replacement decision is produced in time."
  [world workflow-id]
  (let [pending (t/get-pending world workflow-id)]
    (if (:exists pending)
      (-> world
          (clear-stale-settlement-principal workflow-id)
          (update-in [:superseded-pending-settlements workflow-id]
                     (fnil conj [])
                     {:pending pending
                      :superseded-at (time-ctx/block-ts world)
                      :level (t/dispute-level world workflow-id)})
          (update :pending-settlements dissoc workflow-id))
      world)))

(defn- pick-eligible-superseded-pending
  "Select the latest superseded pending that is executable at now-ts.
   Returns a pending-settlement map or nil."
  [world workflow-id now-ts]
  (->> (get-in world [:superseded-pending-settlements workflow-id] [])
       (map :pending)
       (filter :exists)
       (filter #(<= (:appeal-deadline %) now-ts))
       (sort-by :appeal-deadline)
       last))

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
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (t/final-round? world workflow-id)
    (guard-fail :escalation-not-allowed
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    (not (:exists (t/get-pending world workflow-id)))
    (guard-fail :no-resolution-to-challenge
                :pending-exists false
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    ;; Appeal window has closed — the pending settlement is now executable.
    (>= (time-ctx/block-ts world) (:appeal-deadline (t/get-pending world workflow-id)))
    (guard-fail :appeal-window-expired
                :block-time (time-ctx/block-ts world)
                :appeal-deadline (:appeal-deadline (t/get-pending world workflow-id))
                :workflow-id workflow-id)

    (nil? escalation-fn)
    (guard-fail :escalation-not-configured :workflow-id workflow-id)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              snap         (t/get-snapshot world workflow-id)
              et           (t/get-transfer world workflow-id)
              
               ;; Sybil Mitigation Layer B: Linear Bond Scaling (1.1x per escalation)
               esc-count    (get-in world [:escalation-counts-per-addr caller] 0)

               ;; Challenge bond amount
               base-bond    (payoffs/calculate-challenge-bond-amount (:amount-after-fee et) snap)
               bond-amt     (quot (* base-bond (+ 10000 (* esc-count 1000))) 10000)

               world'       (-> world
                                (acct/post-appeal-bond workflow-id caller snap (:token et) bond-amt)
                               (assoc-in [:challengers workflow-id current-level] caller)
                               (archive-pending-on-escalation workflow-id)
                               (assoc-in [:dispute-levels workflow-id] new-level)
                               (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                         new-resolver)
                                ;; Track last escalation timestamp per address (used by
                                ;; challenge-resolution cooldown for open challengers).
                               (assoc-in [:last-escalation-block-time-per-addr caller]
                                         (time-ctx/block-ts world))
                               ;; Increment escalation count for this address (Layer B)
                               (update-in [:escalation-counts-per-addr caller] (fnil inc 0))
                               (assoc-in [:last-escalation-block-time workflow-id]
                                         (time-ctx/block-ts world)))]
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
    (let [world (lc/accrue-yield world workflow-id)]
      (cond
        ;; Priority 1: pending settlement ready to execute
        (sm/pending-settlement-executable? world workflow-id)
        (let [r (execute-pending-settlement world workflow-id)]
          (if (:ok r)
            (assoc r :action :execute-pending)
            r))

        ;; Priority 2: auto-release
        (sm/auto-release-due? world workflow-id)
        (let [r (t/ok (finalize world workflow-id :released))]
          (assoc r :action :auto-release))

        ;; Priority 3: auto-cancel
        (sm/auto-cancel-due? world workflow-id)
        (let [r (t/ok (finalize world workflow-id :refunded))]
          (assoc r :action :auto-cancel))

        :else
        (assoc (t/ok world) :action :none)))))

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
    (guard-fail :invalid-workflow-id :workflow-id workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (guard-fail :transfer-not-in-dispute
                :escrow-state (t/escrow-state world workflow-id)
                :workflow-id workflow-id)

    (let [et (t/get-transfer world workflow-id)]
      (and (not= caller (:from et)) (not= caller (:to et))))
    (guard-fail :not-participant :caller caller :workflow-id workflow-id)

    (t/final-round? world workflow-id)
    (guard-fail :escalation-not-allowed
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    ;; Escalation is an appeal: a resolver must have already submitted a
    ;; resolution (creating a pending settlement) before a party may escalate.
    (not (:exists (t/get-pending world workflow-id)))
    (guard-fail :no-resolution-to-appeal
                :pending-exists false
                :dispute-level (t/dispute-level world workflow-id)
                :workflow-id workflow-id)

    ;; Appeal window has closed — the pending settlement is now executable.
    (>= (time-ctx/block-ts world) (:appeal-deadline (t/get-pending world workflow-id)))
    (guard-fail :appeal-window-expired
                :block-time (time-ctx/block-ts world)
                :appeal-deadline (:appeal-deadline (t/get-pending world workflow-id))
                :workflow-id workflow-id)

    (nil? escalation-fn)
    (guard-fail :escalation-not-configured :workflow-id workflow-id)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              
               ;; Sybil Mitigation Layer B: Linear Bond Scaling (1.1x per escalation)
               esc-count    (get-in world [:escalation-counts-per-addr caller] 0)

               ;; DR3 Sync: handle appeal bond posting
               snap         (t/get-snapshot world workflow-id)
               et           (t/get-transfer world workflow-id)
               base-bond    (payoffs/calculate-appeal-bond-amount (:amount-after-fee et) snap)
               bond-amt     (quot (* base-bond (+ 10000 (* esc-count 1000))) 10000)

               ;; Ensure workflow exists in bond-balances before updating
              world-prepared (if (get-in world [:bond-balances workflow-id])
                               world
                               (assoc-in world [:bond-balances workflow-id] {}))
              
              ;; post-appeal-bond adds to :total-held internally.
              world'       (-> world-prepared
                               (acct/post-appeal-bond workflow-id caller snap (:token et) bond-amt)
                               (archive-pending-on-escalation workflow-id)
                               (assoc-in [:challengers workflow-id current-level] caller)
                               (assoc-in [:dispute-levels workflow-id] new-level)
                               (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                         new-resolver)
                               ;; Track when this escalation occurred for this address (Layer A)
                               (assoc-in [:last-escalation-block-time-per-addr caller]
                                         (time-ctx/block-ts world))
                               ;; Increment escalation count for this address (Layer B)
                               (update-in [:escalation-counts-per-addr caller] (fnil inc 0))
                               ;; Track when this escalation occurred for this workflow (Invariant check)
                               (assoc-in [:last-escalation-block-time workflow-id]
                                         (time-ctx/block-ts world)))]
          (assoc (t/ok world')
                 :new-level    new-level
                 :new-resolver new-resolver))))))

(defn appeal-slash
  "Resolver appeals a PENDING manual slash (Phase M).
   slash-id defaults to workflow-id; pass the level-scoped string slash-id for
   reversal slashes (e.g. \"0-reversal-0\" for the reversal at level 1 on workflow 0).
   Mirrors: ResolverSlashingModuleV1.appealSlash"
  ([world workflow-id caller] (appeal-slash world workflow-id caller workflow-id))
  ([world workflow-id caller slash-id]
   (let [pending (get-in world [:pending-fraud-slashes slash-id])]
     (cond
       (nil? pending)
       (t/fail :no-pending-slash)

       (nil? (:status pending))
       (t/fail :invalid-slash-state)

       (not= :pending (:status pending))
       (t/fail :slash-not-pending)

       (> (time-ctx/block-ts world) (:appeal-deadline pending))
       (t/fail :appeal-window-expired)

       (not= caller (:resolver pending))
       (t/fail :not-resolver)

       :else
       (let [snap        (t/get-snapshot world workflow-id)
             et          (t/get-transfer world workflow-id)
             token       (:token et)
             bond-amount (payoffs/calculate-appeal-bond-amount (:amount-after-fee et) snap)
             world'      (if (pos? bond-amount)
                           ;; Fraud-slash appeals track custody via :appeal-bond-held (solvency),
                           ;; not :bond-balances — post-appeal-bond would double-count liabilities.
                           (-> world
                               (assoc-in [:pending-fraud-slashes slash-id :appeal-bond-held] bond-amount)
                               (assoc-in [:appeal-bond-custody slash-id]
                                         {:resolver caller :workflow-id workflow-id :amount bond-amount :token token})
                               (acct/add-held token bond-amount)
                               (update-in [:total-bonds-posted token] (fnil + 0) bond-amount)
                               (update-in [:bond-posted-by-workflow workflow-id] (fnil + 0) bond-amount))
                           world)]
         (t/ok (assoc-in world' [:pending-fraud-slashes slash-id :status] :appealed)))))))

(defn propose-fraud-slash
  "Governance (TIMELOCK) proposes a manual fraud slash for a resolver (Phase M).
   Mirrors: ResolverSlashingModuleV1.proposeSlash.

   Preconditions: workflow exists, entered dispute/resolution path, resolver matches
   escrow dispute-resolver, positive amount, no other pending/appealed slash on workflow-id.
   Timelock length uses :appeal-window-duration from the escrow module snapshot."
  [world workflow-id caller resolver-addr amount]
  (let [wf-id (t/normalize-workflow-id workflow-id)]
    (cond
      (or (nil? caller) (= "" caller))
      (t/fail :missing-caller-context)

      (not (t/valid-workflow-id? world wf-id))
      (t/fail :invalid-workflow-id)

      (not (fraud-slash-workflow-eligible? world wf-id))
      (t/fail :workflow-not-slashable)

      (active-manual-fraud-slash? world wf-id)
      (t/fail :slash-already-pending)

      (or (nil? amount) (not (number? amount)) (<= amount 0))
      (t/fail :invalid-slash-amount)

      :else
      (let [dispute-resolver (get-in world [:escrow-transfers wf-id :dispute-resolver])]
        (cond
          (or (nil? dispute-resolver) (= "" dispute-resolver))
          (t/fail :invalid-resolver-addr)

          (not= dispute-resolver resolver-addr)
          (t/fail :slash-resolver-mismatch)

          :else
          (let [current-stake (reg/get-stake world dispute-resolver)
                max-bps       (get-in world [:params :max-slash-per-offense-bps] 5000)]
            (cond
              (<= current-stake 0)
              (t/fail :insufficient-resolver-stake)

              ;; Per-offense cap: governance slash may not exceed 50% of current stake
              (> (* amount 10000) (* current-stake max-bps))
              (t/fail :slash-exceeds-max-per-offense)

              :else
              (let [snap              (t/get-snapshot world wf-id)
                    appeal-days       (get-in world [:params :appeal-window-days] 7)
                    gov-delay         (or (:appeal-window-duration snap) (* appeal-days 86400))
                    reversal-prob     (or (:reversal-detection-probability snap) 0.0)]
                (t/ok (handle-fraud-slashing world wf-id wf-id resolver-addr amount gov-delay reversal-prob))))))))))

(defn resolve-appeal
  "Governance (TIMELOCK) resolves a slashing appeal.
   Naming note: `appeal-upheld?` means the APPEAL is upheld (accepted),
   not that the slash is upheld.
   - appeal-upheld? = true  -> slash is reversed and cannot be executed.
   - appeal-upheld? = false -> appeal is rejected; slash returns to pending.
   Mirrors: ResolverSlashingModuleV1.resolveAppeal

   3-arity calls default slash-id to workflow-id (integer).  For reversal slashes
   (which use level-scoped string slash-ids like \"0-reversal-0\"), the 4-arity
   version with explicit slash-id MUST be used."
  ([world workflow-id caller appeal-upheld?]
   (resolve-appeal world workflow-id caller appeal-upheld? workflow-id))
  ([world _workflow-id caller appeal-upheld? slash-id]
   (let [ctx (attr/make-context {:workflow-id _workflow-id :slash-id slash-id})
         pending (get-in world [:pending-fraud-slashes slash-id])]
     (attr/log-annotated! :debug "Resolving appeal" ctx {:appeal-upheld? appeal-upheld?})
     (cond
       (or (nil? caller) (= "" caller))
       (t/fail :missing-caller-context)

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
             ;; Prefer custody workflow-id (an integer); fall back to pending's resolver context.
             ;; Do NOT fall back to slash-id which may be a string like "0-reversal-0".
             wf-id       (or (:workflow-id custody) (:workflow-id pending))
             bond-token  (or (:token custody) "USDC")
             world-base  (-> world
                             (assoc-in [:pending-fraud-slashes slash-id :appeal-bond-held] 0)
                             (update :appeal-bond-custody dissoc slash-id))]
         (cond
           appeal-upheld?
           (t/ok (cond-> world-base
                  (pos? bond-held)
                  (-> (acct/sub-held bond-token bond-held)
                      (acct/record-claimable-v2 wf-id :bond/refund resolver bond-held))
                  :always
                  (assoc-in [:pending-fraud-slashes slash-id :status] :reversed)))

           (pos? bond-held)
           (t/ok (-> world-base
                     (acct/sub-held bond-token bond-held)
                     (assoc-in [:pending-fraud-slashes slash-id :status] :pending)
                     (update-in [:appeal-bond-distributions-by-token bond-token] (fnil + 0) bond-held)
                     (update :appeal-bonds-forfeited-insurance (fnil + 0) bond-held)))

           :else
           (t/ok (assoc-in world-base [:pending-fraud-slashes slash-id :status] :pending))))))))

(defn execute-fraud-slash
  "Execute a previously proposed fraud slash after the timelock/appeal window.
   slash-id defaults to workflow-id; pass the level-scoped string slash-id for
   reversal slashes (e.g. \"0-reversal-0\" for the reversal at level 1 on workflow 0).
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

       (< (time-ctx/block-ts world) (:appeal-deadline pending))
       (t/fail :timelock-not-expired)

        :else
        (let [resolver        (:resolver pending)
              amount          (:amount pending)
              current-stake   (reg/get-stake world resolver)
              epoch-cap-bps   (get-in world [:params :slash-epoch-cap-bps] 2000)
              epoch-slashed   (get-in world [:resolver-epoch-slashed resolver :amount] 0)
              total-epoch     (+ epoch-slashed amount)]
          (cond
            ;; Epoch cap: total slashing in the epoch may not exceed the percentage cap
            (and (pos? current-stake)
                 (> (* total-epoch 10000) (* current-stake epoch-cap-bps)))
            (t/fail :slash-epoch-cap-exceeded)

            :else
            (let [freeze-duration 259200   ; 72 hours in seconds
                  wf-for-token    (or (:workflow-id pending) workflow-id)
                  world-slashed   (-> world
                                      (assoc-in [:pending-fraud-slashes slash-id :status] :executed)
                                      (reg/slash-resolver-stake resolver amount nil 0 wf-for-token)
                                      :world)
                  world'          (-> world-slashed
                                      (assoc-in [:resolver-frozen-until resolver]
                                                (+ (time-ctx/block-ts world) freeze-duration))
                                      (assoc-in [:resolver-epoch-slashed resolver :amount] total-epoch)
                                      (update-unavailability resolver true))
                  allocation      (payoffs/calculate-prorata-slash-allocation
                                    {:slash-obligation amount
                                     :liable-parties
                                     [{:id resolver
                                       :slashable-stake current-stake
                                       :available-slashable current-stake}]})]
              (let [evidence (slashing-ev/build-prorata-slash-evidence
                               {:world world
                                :slash-id slash-id
                                :workflow-id workflow-id
                                :epoch (get-in world [:resolver-epoch-slashed resolver :epoch-start] 0)
                                :trigger :fraud-slash
                                :allocation-input {:slash-obligation amount :resolver resolver}
                                :allocation-result allocation
                                :transition-dependencies []
                                :attribution (attr/current-attribution)})]
                (evidence/capture-event-evidence! evidence))
              (t/ok world'))))))))

(defn compute-prorata-slash-allocation
  "Non-governance query action: compute pro-rata allocation for a slash obligation.
   
   Accepts {:slash-obligation N :liable-parties [...]} with optional
   :basis and :cap-field overrides.
   
   Pure read — no world mutation.
   Returns {:ok true :allocation <result-map>} with the full allocation
   including :allocations, :recovered-total, :unmet-total."
  [world {:keys [slash-obligation basis cap-field liable-parties]}]
  (let [allocation (payoffs/calculate-prorata-slash-allocation
                    (cond-> {:slash-obligation slash-obligation
                             :liable-parties liable-parties}
                      basis (assoc :basis basis)
                      cap-field (assoc :cap-field cap-field)))]
    {:ok true :world world :extra {:allocation allocation}}))

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

(defn- finalize
  "Internal: transition escrow to terminal state, release accounting.
   direction — :released (to recipient) or :refunded (to sender)."
  [world workflow-id direction]
  (let [resolver (:dispute-resolver (t/get-transfer world workflow-id))]
    (-> world
        (lc/finalize-escrow-accounting workflow-id direction)
        (t/decrement-resolver-capacity resolver))))

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
          same-resolver? (= old-resolver new-resolver)
          last-rotation (last (get-in world [:resolver-rotations workflow-id]))
          same-rotation? (and (not same-resolver?)
                              (some? last-rotation)
                              (= (:from last-rotation) old-resolver)
                              (= (:to last-rotation) new-resolver))]
      (if (or same-resolver? same-rotation?)
        (assoc (t/ok world)
               :old-resolver old-resolver
               :new-resolver new-resolver
               :idempotent? true)
        (let [rotation {:from old-resolver :to new-resolver :at (time-ctx/block-ts world)}
              world'   (-> world
                           (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                     new-resolver)
                           (update-in [:resolver-rotations workflow-id]
                                      (fnil conj []) rotation))]
          (assoc (t/ok world') :old-resolver old-resolver :new-resolver new-resolver))))))

;; ── Monadic Transitions ──────────────────────────────────────────────────────

(defn execute-resolution-m
  "Monadic version of execute-resolution."
  [workflow-id caller is-release resolution-hash resolution-module-fn]
  (am/update-with-result execute-resolution workflow-id caller is-release resolution-hash resolution-module-fn))

(defn execute-pending-settlement-m
  "Monadic version of execute-pending-settlement."
  [workflow-id]
  (am/update-with-result execute-pending-settlement workflow-id))

(defn escalate-dispute-m
  "Monadic version of escalate-dispute."
  [workflow-id caller escalation-fn]
  (am/update-with-result escalate-dispute workflow-id caller escalation-fn))
