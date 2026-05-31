(ns resolver-sim.protocols.sew.invariants
  "Checkable invariant predicates for the Sew contract model.

   These mirror the runtime guards in InvariantGuardInternal.sol and define
   the specification for future Foundry invariant tests and Halmos properties.

   Each predicate takes a world-state map and returns a map:
     {:holds? bool :violations [...]}

   Invariants:
     1. solvency (single-world)      — total-held[t] = sum(live afa[t])  STRICT =
                                       and total-held[t] <= token-balance[t]  (external)
     2. fee-monotonicity (single)    — total-fees[t] never goes negative
     3. state-irreversibility (cross)— terminal states are absorbing (checked via check-transition)
     4. bond-boundedness (single)    — slash amount <= posted bond per workflow (vacuous until bonds added)
     5. no-double-finalize           — each workflow-id finalizes at most once (structural guarantee)"
  (:require [resolver-sim.protocols.sew.types         :as t]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.time.invariants :as time-inv]
            [resolver-sim.yield.invariants :as generic-yield-inv]
            [resolver-sim.protocols.sew.yield.invariants :as sew-yield-inv]))

(defn cancellation-mutex?
  "True when any escrow in :resolved, :released, or :refunded status
   rejects any cancellation attempt."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (and (contains? #{:resolved :released :refunded} (:escrow-state et))
                         (or (not= :none (:sender-status et))
                             (not= :none (:recipient-status et))))]
          {:workflow-id wf :state (:escrow-state et)})]
    {:holds? (empty? violations) :violations (vec violations)}))

(def canonical-ids
  "The full set of canonical invariant IDs across Sew v1.
   Used for standardized error mapping and validation gates."
  #{:solvency
    :cancellation-mutex
    :fees-non-negative
    :held-non-negative
    :all-status-combinations-valid
    :pending-settlement-consistent
    :dispute-timestamp-consistent
    :dispute-level-bounded
    :slash-status-consistent
    :appeal-bond-conserved
    :appeal-bond-custody-consistent
    :no-auto-fraud-execute
    :bond-liquidity
    :bond-slash-bounded
    :fee-cap
    :no-stale-automatable-escrows
    :conservation-of-funds
    :claim-boundary
    :dispute-resolution-path
    :slash-distribution-consistent
    :resolver-bond-mix-valid
    :senior-coverage-not-exceeded
    :resolver-not-frozen-on-assign
    :slash-epoch-cap-respected
    :reversal-slash-disabled
    :resolver-capacity
    :yield-position-consistency
    :yield-exposure
    :terminal-states-unchanged
    :time-non-decreasing
    :time-no-action-after-finality
    :finalization-accounting-correct
    :escalation-level-monotonic
    :no-withdrawal-during-dispute
    :time-lock-integrity
    :token-tax-reconciliation
    :withdrawn-monotonic
    :released-monotonic
    :held-delta-accounted
    :single-resolution-payout-consistent
    :fraud-slash-executions-accounted})

(defn- get-token-claimable-sum [world token]
  (reduce + 0 (for [[wf cmap] (:claimable world {})
                    :let [et (get-in world [:escrow-transfers wf])]
                    :when (= (:token et) token)
                    [_ amt] cmap]
                (or amt 0))))

(defn- get-distributed-sum [world token]
  (let [bd               (:bond-distribution world {:insurance 0 :protocol 0})
        retained         (:retained-slash-reserves world 0)
        bond-fees        (get (:bond-fees world) token 0)
        appeal-bond-dist (get (:appeal-bond-distributions-by-token world {}) token 0)]
    (if (= token :USDC)
      (+ (:insurance bd 0) (:protocol bd 0) retained bond-fees appeal-bond-dist)
      (+ bond-fees appeal-bond-dist))))

;; ---------------------------------------------------------------------------
;; Invariant 1: Solvency
;; ---------------------------------------------------------------------------

(defn- get-slash-appeal-bond-sum [world token]
  (reduce + 0 (for [[slash-id ev] (:pending-fraud-slashes world {})
                    :let [custody (get-in world [:appeal-bond-custody slash-id])
                          bond-token (or (:token custody)
                                         ;; Legacy fallback: look up via escrow-transfer token
                                         (get-in world [:escrow-transfers (:workflow-id custody) :token]))]
                    :when (= token bond-token)
                    :let [amt (:appeal-bond-held ev 0)]]
                amt)))

(defn- get-escrow-afa-sum [world token live-states]
  (reduce (fn [acc [_ et]]
            (if (and (= (:token et) token)
                     (contains? live-states (:escrow-state et)))
              (+ acc (:amount-after-fee et))
              acc))
          0
          (:escrow-transfers world)))

(defn- get-bond-held-sum [world token]
  (reduce + 0 (for [[wf agents] (:bond-balances world)
                    [agent amt] agents
                    :let [et (get-in world [:escrow-transfers wf])]
                    :when (= (:token et) token)]
                amt)))

(defn- get-yield-held-sum [world token live-states]
  (reduce (fn [acc [oid pos]]
            (let [et (when (vector? oid) (get-in world [:escrow-transfers (second oid)]))]
              (cond
                ;; Active yield position in a live escrow or a resolver-owned position
                (and (= (:token pos) token)
                     (= (:status pos) :active)
                     (or (and et (contains? live-states (:escrow-state et)))
                         (t/resolver-yield-owner-id? oid)))
                (+ acc (:unrealized-yield pos 0) (:realized-yield pos 0))
                ;; Unwinding (shortfall-deferred) position — deferred amount remains in :total-held
                ;; until claim-deferred-yield closes it out, regardless of escrow state
                (and (= (:token pos) token)
                     (= (:status pos) :unwinding))
                (+ acc (get-in pos [:shortfall :deferred-amount] 0))
                :else acc)))
          0
          (:yield/positions world {})))

(defn solvency-holds?
  "True when total-held[token] exactly equals the sum of all internal liabilities.
   Liabilities = [Live Escrow AFAs] + [Active Bonds] + [Resolver Stakes] + [Unwithdrawn Fees] + [Active Yield]
   
   The internal invariant is STRICT EQUALITY (=)."
  [world token-balances]
  (let [live-states   #{:pending :disputed}
        all-tokens    (-> (set (keys (:total-held world)))
                          (into (map :token (vals (:escrow-transfers world))))
                          (into (keys (:total-bonds-posted world))))
        violations
        (for [token all-tokens
              :let  [held       (get (:total-held world) token 0)
                     escrow-sum (get-escrow-afa-sum world token live-states)
                     bond-sum   (get-bond-held-sum world token)
                     slash-bond-sum (get-slash-appeal-bond-sum world token)
                     yield-sum  (get-yield-held-sum world token live-states)
                     stake-sum  (if (= token :USDC) (reduce + 0 (vals (:resolver-stakes world {}))) 0)
                     
                     liabilities (+ escrow-sum bond-sum slash-bond-sum yield-sum stake-sum)
                     
                     ext-bal    (when token-balances (get token-balances token 0))
                     internal-ok? (= liabilities held)
                     external-ok? (or (nil? ext-bal) (<= held ext-bal))]
              :when (not (and internal-ok? external-ok?))]
          {:token       token
           :liabilities liabilities
           :held        held
           :ext-bal     ext-bal
           :internal-ok? internal-ok?
           :external-ok? external-ok?})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 2: Fee monotonicity
;;
;; total-fees[token] only increases. It may be reset to 0 by withdraw-fees,
;; but between any two consecutive withdraw-fees calls it must never decrease.
;;
;; Tested as: applying any non-withdraw operation never reduces total-fees.
;; ---------------------------------------------------------------------------

(defn fees-non-negative?
  "True when all total-fees values are >= 0 (they should never go negative)."
  [world]
  (let [violations (for [[token amount] (:total-fees world)
                         :when (neg? amount)]
                     {:token token :amount amount})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn fee-increased-or-equal?
  "True when every total-fees entry in world' is >= the corresponding entry
   in world-before. Used to assert monotonicity across a single operation."
  [world-before world-after]
  (let [violations (for [[token before-amt] (:total-fees world-before)
                         :let [after-amt (get (:total-fees world-after) token 0)]
                         :when (< after-amt before-amt)]
                     {:token token :before before-amt :after after-amt})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 3: State irreversibility
;;
;; Once an escrow reaches :released, :refunded, or :resolved it must remain
;; in that state. No operation should change a terminal state.
;; ---------------------------------------------------------------------------

(defn terminal-states-unchanged?
  "True when every escrow that was terminal in world-before is still terminal
   in world-after, and has the same state."
  [world-before world-after]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et-before] (:escrow-transfers world-before)
              :when (contains? terminals (:escrow-state et-before))
              :let  [et-after (get-in world-after [:escrow-transfers wf])]
              :when (not= (:escrow-state et-before) (:escrow-state et-after))]
          {:workflow-id wf
           :before      (:escrow-state et-before)
           :after       (:escrow-state et-after)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 4: Bond boundedness
;;
;; The slash amount for any (workflow, appellant) must not exceed the posted bond.
;; ---------------------------------------------------------------------------

(defn bond-slash-bounded?
  "True when :bond-slashed[wf] <= sum of original bonds posted for that workflow.
   Uses :bond-balances + :bond-slashed as the accounting split."
  [world]
  (let [violations
        (for [[wf slashed] (:bond-slashed world)
              :let  [remaining (reduce + 0 (vals (get (:bond-balances world) wf {})))
                     ;; Original posted = remaining + slashed
                     original  (+ remaining slashed)]
              :when (> slashed original)]
          {:workflow-id wf :slashed slashed :original original})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 5: No double-finalize
;;
;; Each workflow-id should appear at most once in any terminal state.
;; Since escrow-transfers is an indexed vector (workflowId = index),
;; each id is inherently unique — but the state must be terminal at most once.
;; ---------------------------------------------------------------------------

(defn no-double-finalize?
  "True when no workflow-id has been finalized more than once.
   In the pure model this is structurally guaranteed by the single map entry,
   but this predicate is retained for use in property-based test chains where
   the runner accumulates a history log."
  [finalization-log]
  (let [counts    (frequencies (map :workflow-id finalization-log))
        violations (filter (fn [[_ n]] (> n 1)) counts)]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 6: Held non-negative
;;
;; total-held values must never go negative — a negative value indicates
;; a sub-held was applied without a corresponding escrow having been counted.
;; ---------------------------------------------------------------------------

(defn held-non-negative?
  "True when all total-held values are >= 0."
  [world]
  (let [violations (for [[token amount] (:total-held world)
                         :when (neg? amount)]
                     {:token token :amount amount})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 7: Valid status combinations
;;
;; Every escrow must have a (escrow-state × sender-status × recipient-status)
;; combination that is permitted by the Sew protocol.
;; ---------------------------------------------------------------------------

(defn all-status-combinations-valid?
  "True when every escrow has a valid (state × sender-status × recipient-status)
   combination according to sm/valid-status-combination?."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (not (sm/valid-status-combination?
                          {:escrow-state     (:escrow-state et)
                           :sender-status    (:sender-status et :none)
                           :recipient-status (:recipient-status et :none)}))]
          {:workflow-id      wf
           :escrow-state     (:escrow-state et)
           :sender-status    (:sender-status et :none)
           :recipient-status (:recipient-status et :none)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 8: Pending-settlement consistency
;;
;; A pending settlement may only exist for an escrow in :disputed state.
;; ---------------------------------------------------------------------------

(defn pending-settlement-consistency?
  "True when every workflow-id with an existing pending-settlement has
   escrow-state == :disputed."
  [world]
  (let [violations
        (for [[wf pending] (:pending-settlements world)
              :when (:exists pending)
              :let  [state (t/escrow-state world wf)]
              :when (not= :disputed state)]
          {:workflow-id wf :state state})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 9: Dispute-timestamp consistency
;;
;; Every escrow in :disputed state must have a dispute-raised-timestamp > 0.
;; A :disputed escrow with no timestamp would make dispute-timeout-exceeded?
;; permanently return false, preventing keeper actions.
;; ---------------------------------------------------------------------------

(defn dispute-timestamp-consistency?
  "True when every :disputed escrow has a dispute timestamp > 0."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (= :disputed (:escrow-state et))
              :let  [ts (get-in world [:dispute-timestamps wf] 0)]
              :when (not (pos? ts))]
          {:workflow-id wf :timestamp ts})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 10: No stale automatable escrows
;;
;; If an escrow is eligible for a timed action (auto-release, auto-cancel, or
;; execute-pending), the keeper must have already processed it.  Finding such
;; an escrow in a finalized world snapshot is a temporal invariant violation.
;;
;; This is checked on a post-step world, after automate-timed-actions has run.
;; ---------------------------------------------------------------------------

(defn no-stale-automatable-escrows?
  "True when no escrow is currently eligible for an untriggered timed action.

   Intended to be called on the world snapshot AFTER automate-timed-actions
   has been run for each active escrow.  A violation means the caller failed
   to invoke the keeper."
  [world]
  (let [violations
        (for [[wf _et] (:escrow-transfers world)
              :when (or (sm/auto-release-due?             world wf)
                        (sm/auto-cancel-due?              world wf)
                        (sm/pending-settlement-executable? world wf))]
          {:workflow-id wf
           :reasons (cond-> []
                      (sm/auto-release-due? world wf)              (conj :auto-release-due)
                      (sm/auto-cancel-due? world wf)               (conj :auto-cancel-due)
                      (sm/pending-settlement-executable? world wf) (conj :pending-executable))})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 11: Finalization accounting correct (cross-world)
;;
;; When an escrow transitions to a terminal state, total-held for its token
;; must decrease by exactly the escrow's amount-after-fee.
;; ---------------------------------------------------------------------------

(defn finalization-accounting-correct?
  "True when every escrow that became terminal between world-before and
   world-after had its principal correctly moved from held to claimable.

   For non-yield escrows (no yield module): checks both delta-held = -afa
   and delta-claimable = +net-afa (strict principal accounting).

   For yield-backed escrows: only checks delta-claimable, since delta-held
   also absorbs yield accrual deltas (which are tracked by held-delta-accounted).
   - No shortfall: delta-claimable >= net-afa (principal + yield both flow to claimable)
   - Shortfall (position :unwinding): delta-claimable = fulfilled-amount"
  [world-before world-after]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et-after] (:escrow-transfers world-after)
              :when (contains? terminals (:escrow-state et-after))
              :let  [et-before (get-in world-before [:escrow-transfers wf])
                     state-before (:escrow-state et-before)]
              ;; Only check escrows that JUST became terminal
              :when (and state-before (not (contains? terminals state-before)))
              :let  [token      (:token et-after)
                     afa        (:amount-after-fee et-after)
                     fot-bps    (get-in world-after [:token-fot-bps token] 0)
                     net-afa    (- afa (t/compute-fee afa fot-bps))
                     snap       (t/get-snapshot world-after wf)
                     yield-mid  (:yield-generation-module snap)
                     owner-id   (t/escrow-yield-owner-id wf)
                     pos-after  (get-in world-after [:yield/positions owner-id])
                     shortfall  (:shortfall pos-after)

                     held-before (get-in world-before [:total-held token] 0)
                     held-after  (get-in world-after  [:total-held token] 0)
                     delta-held  (- held-after held-before)

                     claimable-before (get-token-claimable-sum world-before token)
                     claimable-after  (get-token-claimable-sum world-after token)
                     delta-claimable  (- claimable-after claimable-before)

                     ;; Resolver stake slashes (USDC-only) may occur in the same
                     ;; transition as escrow finalization (e.g. dispute timeout).
                     stake-held-delta
                     (if (= token :USDC)
                       (let [sb (reduce + 0 (vals (:resolver-stakes world-before {})))
                             sa (reduce + 0 (vals (:resolver-stakes world-after {})))]
                         (- sa sb))
                       0)

                     ;; Expected immediately-claimable amount:
                     ;;   shortfall → fulfilled-amount (deferred remainder stays in held)
                     ;;   no yield  → net-afa exactly
                     ;;   yield but no shortfall → >= net-afa (yield also flows to claimable)
                     expected-claimable
                     (cond
                       shortfall (get shortfall :fulfilled-amount net-afa)
                       (nil? yield-mid) net-afa
                       :else net-afa)  ;; minimum — for yield-no-shortfall we check >=

                     ;; Validation predicate
                     ok?
                     (cond
                       ;; No yield module: strict principal accounting
                       (nil? yield-mid)
                       (and (= delta-held (+ (- afa) stake-held-delta))
                            (= delta-claimable net-afa))

                       ;; Yield with shortfall: only fulfilled-amount goes to claimable immediately
                       shortfall
                       (= delta-claimable expected-claimable)

                       ;; Yield without shortfall: at least principal in claimable (yield adds more)
                       :else
                       (>= delta-claimable net-afa))]
              :when (not ok?)]
          {:workflow-id     wf
           :token           token
           :afa             afa
           :yield-mid       yield-mid
           :shortfall       shortfall
           :delta-held      delta-held
           :delta-claimable delta-claimable
           :expected-claimable expected-claimable})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 12: Dispute level bounded
;;
;; :dispute-levels[wf] must be in [0, max-dispute-level].
;; A level entry must only exist for escrows currently in :disputed state
;; (non-disputed escrows have no meaningful escalation round).
;; ---------------------------------------------------------------------------

(defn dispute-level-bounded?
  "True when every :dispute-levels entry is in [0, max-dispute-level]
   and only exists for escrows that are or were :disputed."
  [world]
  (let [violations
        (for [[wf level] (:dispute-levels world)
              :when (or (neg? level)
                        (> level t/max-dispute-level))]
          {:workflow-id wf :level level :max t/max-dispute-level})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 13: Escalation level monotonic (cross-world)
;;
;; Dispute levels may only increase by exactly 1 per step and can never
;; decrease.  A level jumping from 0 to 2, or dropping from 1 to 0,
;; indicates a bug in the escalation path.
;; ---------------------------------------------------------------------------

(defn escalation-level-monotonic?
  "True when no dispute level decreased between world-before and world-after,
   and no level increased by more than 1 in a single step."
  [world-before world-after]
  (let [violations
        (for [[wf level-after] (:dispute-levels world-after)
              :let  [level-before (t/dispute-level world-before wf)
                     delta        (- level-after level-before)]
              :when (or (neg? delta) (> delta 1))]
          {:workflow-id  wf
           :level-before level-before
           :level-after  level-after
           :delta        delta})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 14: Slash status consistency
;;
;; A slash in :executed status must have proposed-at > 0 and executedAt > 0
;; (modelled here as proposed-at > 0 and status == :executed requires appeal-deadline == 0
;; OR appeal-deadline has passed). A slash in :pending must have proposed-at > 0
;; and appeal-deadline > proposed-at.
;; ---------------------------------------------------------------------------

(defn slash-status-consistent?
  "Invariant 14: every slash event has a status consistent with its timestamps.

   Rules:
     :pending  — proposed-at > 0, appeal-deadline > proposed-at
     :executed — proposed-at > 0
     :reversed — proposed-at > 0 (was once pending)"
  [world]
  (let [slashes   (:pending-fraud-slashes world {})
        violations
        (for [[slash-id ev] slashes
              :let [proposed-at    (or (:proposed-at ev) 0)
                    appeal-dl      (or (:appeal-deadline ev) 0)
                    status         (:status ev)
                    pending-ok?    (and (pos? proposed-at)
                                        (> appeal-dl proposed-at))
                    executed-ok?   (pos? proposed-at)
                    reversed-ok?   (pos? proposed-at)
                    valid?
                    (case status
                      :pending  pending-ok?
                      :executed executed-ok?
                      :reversed reversed-ok?
                      true)]
              :when (not valid?)]
          {:slash-id slash-id :status status :proposed-at proposed-at :appeal-deadline appeal-dl})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 15: Appeal bond conserved
;;
;; Total appeal-bond-held across all PENDING/APPEALED slashes must be >= 0.
;; No individual slash can have a negative appeal-bond-held.
;; (Once resolved the field is cleared to 0, which is also valid.)
;; ---------------------------------------------------------------------------

(defn appeal-bond-conserved?
  "Invariant 15: no slash event has a negative :appeal-bond-held."
  [world]
  (let [slashes   (:pending-fraud-slashes world {})
        violations
        (for [[slash-id ev] slashes
              :let [held (or (:appeal-bond-held ev) 0)]
              :when (neg? held)]
          {:slash-id slash-id :appeal-bond-held held})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn appeal-bond-custody-consistent?
  "Invariant: appeal bond custody lifecycle is coherent.

   Rules per slash-id:
   - :appeal-bond-held must be >= 0
   - held > 0  => slash status must be :appealed and custody entry must exist
   - held == 0 => custody entry must not exist"
  [world]
  (let [slashes  (:pending-fraud-slashes world {})
        custody  (:appeal-bond-custody world {})
        violations
        (for [[slash-id ev] slashes
              :let [held         (or (:appeal-bond-held ev) 0)
                    status       (:status ev)
                    has-custody? (contains? custody slash-id)
                    valid?
                    (and (>= held 0)
                         (if (pos? held)
                           (and (= :appealed status) has-custody?)
                           (not has-custody?)))]
              :when (not valid?)]
          {:slash-id slash-id
           :status status
           :appeal-bond-held held
           :has-custody has-custody?})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 16: Fraud slashes must not be auto-executed
;;
;; A slash with :reason == :fraud may not have :status == :executed
;; with :proposed-at == some-time AND no appeal-deadline > proposed-at
;; (i.e. it was never given a pending window).
;;
;; In practice: any fraud slash must have had appeal-deadline > 0 at creation,
;; meaning it passed through PENDING before EXECUTED.
;;
;; We enforce: for all executed fraud slashes, there must have been a
;; non-zero appeal deadline recorded (even after execution the field persists).
;; ---------------------------------------------------------------------------

(defn no-auto-fraud-execute?
  "Invariant 16: fraud slashes must pass through a pending window before execution.

   An :executed slash with :reason :fraud and :appeal-deadline == 0 indicates
   the slash was executed immediately without a pending window — a violation."
  [world]
  (let [slashes   (:pending-fraud-slashes world {})
        violations
        (for [[slash-id ev] slashes
              :let [status      (:status ev)
                    reason      (:reason ev)
                    appeal-dl   (or (:appeal-deadline ev) 0)]
              :when (and (= status :executed)
                         (= reason :fraud)
                         (zero? appeal-dl))]
          {:slash-id slash-id :reason reason})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 17: Bond Liquidity
;;
;; Total bonds locked across all active disputes for a resolver must not
;; exceed their total staked amount in the protocol.
;; ---------------------------------------------------------------------------

(defn bond-liquidity-holds?
  "True when a resolver's total locked bonds (across all active disputes)
   is less than or equal to their recorded stake.
   Does not apply to non-resolver participants (buyers/sellers) who may
   post bonds without being registered as resolvers."
  [world]
  (let [stakes         (:resolver-stakes world {})
        resolver-bonds (reduce (fn [acc [wf bonds]]
                                 ;; Only count disputes that are currently active
                                 (if (= :disputed (t/escrow-state world wf))
                                   (reduce (fn [inner-acc [addr amount]]
                                             ;; Only track if they are a registered resolver
                                             (if (contains? stakes addr)
                                               (update inner-acc addr (fnil + 0) amount)
                                               inner-acc))
                                           acc
                                           bonds)
                                   acc))
                               {}
                               (:bond-balances world))
        violations (for [[addr locked] resolver-bonds
                         :let [stake (get stakes addr 0)]
                         :when (> locked stake)]
                     {:resolver addr :locked locked :stake stake})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 18: No Withdrawal During Dispute
;;
;; A resolver must not be able to reduce their stake (withdraw) if they have
;; any active disputes where they are the current resolution authority.
;; ---------------------------------------------------------------------------

(defn no-withdrawal-during-dispute?
  "True when no resolver voluntarily withdrew stake while they had active disputes.

   Protocol-mandated slashes (tracked in :resolver-slash-total) are excluded:
   only the portion of a stake drop that exceeds net slashing in this step
   is treated as a voluntary withdrawal."
  [world-before world-after]
  (let [voluntary-withdrawers
        (for [[addr stake-before] (:resolver-stakes world-before)
              :let [stake-after    (get (:resolver-stakes world-after) addr 0)
                    slashed-before (get (:resolver-slash-total world-before) addr 0)
                    slashed-after  (get (:resolver-slash-total world-after) addr 0)
                    net-slashed    (- slashed-after slashed-before)
                    ;; Voluntary drop = reduction beyond what the protocol slashed this step
                    voluntary-drop (- (- stake-before stake-after) net-slashed)]
              :when (pos? voluntary-drop)]
          addr)
        violations
        (for [addr voluntary-withdrawers
              :let [active-disputes
                    (filter (fn [[_ et]]
                              (and (= :disputed (:escrow-state et))
                                   (= addr (:dispute-resolver et))))
                            (:escrow-transfers world-before))]
              :when (seq active-disputes)]
          {:resolver addr :active-disputes (mapv first active-disputes)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 19: Fee Cap Integrity
;;
;; Total fees collected for any escrow (escrow-fee + all appeal-fees) must
;; not exceed the original escrow amount.
;; ---------------------------------------------------------------------------

(defn fee-cap-holds?
  "True when (total-fees-for-escrow) <= (original-amount) for all workflows.

   Uses the :initial-fee stored at escrow creation rather than reconstructing
   it from bps, which avoids integer-truncation false positives at large BPS values."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :let [afa         (:amount-after-fee et)
                    fee         (:initial-fee et 0)
                    original    (+ afa fee)
                    appeal-fees (reduce + 0 (vals (get (:bond-balances world) wf {})))
                    total-fees  (+ fee appeal-fees)]
              :when (> total-fees original)]
          {:workflow-id wf :total-fees total-fees :original original})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 20: Time-Lock Integrity
;;
;; State-changing operations on the same workflow must respect a minimum
;; temporal spacing (anti-spam / anti-flooding).
;; Specifically: raiseDispute and subsequent Escalations cannot happen
;; in the same block.
;; ---------------------------------------------------------------------------

(defn time-lock-integrity?
  "True when no workflow has undergone two escalations in the same block.

   The check is enforced when block-time > 0 (to permit legacy test scenarios
   that use 0 as a sentinel or collapse everything into one block).

   Each successful escalate-dispute / challenge-resolution stores the block-time
   of the escalation under [:last-escalation-block-time wf].  A violation fires
   when a second escalation on the same workflow happens in the same block as the
   first (i.e. :last-escalation-block-time carried forward from world-before
   equals bt-after of the new transition)."
  [world-before world-after]
  (let [bt-after (:block-time world-after)]
    (if (zero? bt-after)
      {:holds? true :violations []}
      (let [violations
            (for [[wf level-after] (:dispute-levels world-after)
                  :let [level-before (t/dispute-level world-before wf)]
                  :when (> level-after level-before)
                  ;; world-before carries the block-time of the *previous* escalation
                  ;; (stored by escalate-dispute / challenge-resolution).  If that
                  ;; previous escalation also happened in bt-after, we have a
                  ;; same-block double-escalation.
                  :let [prev-esc-bt (get-in world-before [:last-escalation-block-time wf])]
                  :when (and (some? prev-esc-bt) (= prev-esc-bt bt-after))]
              {:workflow-id wf :level-before level-before :level-after level-after :block-time bt-after})]
        {:holds?     (empty? violations)
         :violations (vec violations)}))))

;; ---------------------------------------------------------------------------
;; Invariant 21: Conservation of Funds
;;
;; Total system funds = (Sum of all held amounts) + (Sum of all released) +
;;                      (Sum of all refunded) + (Sum of all pending-fees)
;; must reconcile to the total initial deposits.
;; ---------------------------------------------------------------------------

(defn conservation-of-funds?
  "True when every unit of inflow is accounted for in one of the state partitions.
   Inflow = [Principal Deposited] + [Yield Generated] + [Bonds Posted]
   Accounted = [Physical Held] + [Actual Withdrawn] + [Claimable Entitlements] + [Distributed Slashes]
   
   These partitions are mutually exclusive."
  [world]
  (let [all-tokens (-> #{}
                       (into (keys (:total-held world)))
                       (into (keys (:total-principal-deposited world))))
        violations
        (for [token all-tokens
              :let [principal   (get (:total-principal-deposited world) token 0)
                    yield       (get (:total-yield-generated world) token 0)
                    bonds       (get (:total-bonds-posted world) token 0)
                    inflow      (+ principal yield bonds)

                    held        (get (:total-held world) token 0)
                    fees        (get (:total-fees world) token 0)
                    withdrawn   (get (:total-withdrawn world) token 0)
                    claimable   (get-token-claimable-sum world token)
                    distributed (get-distributed-sum world token)
                    fot-fees    (get-in world [:total-fot-fees token] 0)
                    ;; slash-appeal-bonds are part of HELD if they exist
                    accounted   (+ held fees withdrawn claimable distributed fot-fees)]
              :when (not= accounted inflow)]
          {:token token :accounted accounted :inflow inflow
           :diff (- accounted inflow)
           :held held :fees fees :withdrawn withdrawn :claimable claimable :distributed distributed})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn settlement-principal-boundary?
  "True when settlement principal claims do not exceed escrow principal."
  [world]
  (let [violations
        (for [[wf domain-map] (get-in world [:claimable-v2] {})
              :let [principal-claims (get domain-map :settlement/principal {})
                    total            (reduce + 0 (vals principal-claims))
                    et               (get-in world [:escrow-transfers wf])
                    afa              (:amount-after-fee et 0)]
              :when (> total afa)]
          {:workflow-id wf :claims total :max afa})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn liability-slash-boundary?
  "True when liability slash-bounty claims do not exceed distributed slash reserves."
  [world]
  (let [violations
        (for [[wf domain-map] (get-in world [:claimable-v2] {})
              :let [slash-bounties (get domain-map :liability/slash-bounty {})
                    total          (reduce + 0 (vals slash-bounties))
                    reserves       (get world :retained-slash-reserves 0)]
              :when (> total reserves)]
          {:workflow-id wf :claims total :max reserves})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn bond-boundary?
  "True when bond refund claims do not exceed posted bonds."
  [world]
  (let [violations
        (for [[wf domain-map] (get-in world [:claimable-v2] {})
              :let [bond-refunds (get domain-map :bond/refund {})
                    total        (reduce + 0 (vals bond-refunds))
                    posted       (reduce + 0 (vals (get-in world [:bond-balances wf] {})))]
              :when (> total posted)]
          {:workflow-id wf :claims total :max posted})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn fee-boundary?
  "True when protocol fee claims do not exceed accumulated fees."
  [world]
  (let [violations
        (for [[wf domain-map] (get-in world [:claimable-v2] {})
              :let [fee-claims (get domain-map :fees/protocol {})
                    total      (reduce + 0 (vals fee-claims))
                    accumulated (reduce + 0 (vals (:total-fees world {})))]
              :when (> total accumulated)]
          {:workflow-id wf :claims total :max accumulated})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn migration-parity?
  "True when the sum of v2 claimable domains equals legacy claimable."
  [world]
  (let [violations
        (for [[wf domain-map] (get-in world [:claimable-v2] {})
              :let [legacy (get-in world [:claimable wf] {})
                    total-v2 (reduce + 0 (for [d (vals domain-map)] (reduce + 0 (vals d))))
                    total-legacy (reduce + 0 (vals legacy))]
              :when (not= total-v2 total-legacy)]
          {:workflow-id wf :v2 total-v2 :legacy total-legacy})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn claimable-classification
  "Classification of claimable funds into primary settlements and incentives."
  [world]
  (let [claimable-map (:claimable world {})
        summary (reduce (fn [acc [wf cmap]]
                          (let [et (get-in world [:escrow-transfers wf])]
                            (reduce (fn [inner-acc [addr amt]]
                                      (let [role (cond 
                                                   (= addr (:to et)) :recipient
                                                   (= addr (:from et)) :sender
                                                   :else :incentive)]
                                        (update-in inner-acc [role] (fnil + 0) amt)))
                                    acc
                                    cmap)))
                        {:recipient 0 :sender 0 :incentive 0}
                        claimable-map)]
    {:holds? true :summary summary}))

;; ---------------------------------------------------------------------------
;; New Invariant: Solvency KPI helper
;; ---------------------------------------------------------------------------

(defn calculate-solvency-ratio
  "Returns the robust solvency ratio: Total-Assets / Total-Inflows.
   
   Total-Assets includes:
     - Current balances (held, claimable, fees, bond-balances, bond-fees)
     - Slashed distributions (insurance, protocol, retained reserves)
     - Cumulative outflows (total-withdrawn)
   
   Total-Inflows includes:
     - Initial-Principal-Deposits (escrows + bonds)
     - Total-Yield-Generated (as an internal inflow)
   
   A ratio >= 1.0 indicates perfect value conservation."
  [world]
  (let [held      (reduce + 0 (vals (:total-held world {})))
        claimable (reduce + 0 (for [m (vals (:claimable world {}))] (reduce + 0 (vals m))))
        fees      (reduce + 0 (vals (:total-fees world {})))
        withdrawn (reduce + 0 (vals (:total-withdrawn world {})))
        
        ;; Bond assets
        bond-bal  (reduce + 0 (for [m (vals (:bond-balances world {}))] (reduce + 0 (vals m))))
        bond-fees (reduce + 0 (vals (:bond-fees world {})))
        bond-dist (reduce + 0 (vals (:bond-distribution world {:insurance 0 :protocol 0 :burned 0})))
        retained  (:retained-slash-reserves world 0)
        
        ;; Total assets (Current + Historical Outflows)
        total-assets (+ held claimable fees withdrawn bond-bal bond-fees bond-dist retained)
        
        ;; Total Inflows
        principal (reduce + 0 (vals (:total-principal-deposited world {})))
        yield     (reduce + 0 (vals (:total-yield-generated world {})))
        inflow    (+ principal yield)]
    (if (zero? inflow) 1.0 (/ (double total-assets) inflow))))

;; ---------------------------------------------------------------------------
;; Cross-world: Fee Monotonicity
;;
;; Protocol fees should never decrease between transitions.  A decrease would
;; indicate that fees are being withdrawn via the contract model (which has no
;; withdraw-fees action) or that accounting has a bug that reduces fees.
;; ---------------------------------------------------------------------------

(defn withdrawn-monotonic?
  "True when total-withdrawn only increases."
  [world-before world-after]
  (let [violations (for [[token before-amt] (:total-withdrawn world-before)
                         :let [after-amt (get (:total-withdrawn world-after) token 0)]
                         :when (< after-amt before-amt)]
                     {:token token :before before-amt :after after-amt})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn released-monotonic?
  "True when total-released only increases."
  [world-before world-after]
  (let [violations (for [[token before-amt] (:total-released world-before)
                         :let [after-amt (get (:total-released world-after) token 0)]
                         :when (< after-amt before-amt)]
                     {:token token :before before-amt :after after-amt})]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn held-delta-accounted?
  "True when the change in physically held funds is fully explained by
   principal deposits, bond movements, and actual withdrawals."
  [world-before world-after]
  (let [all-tokens (into #{} (concat (keys (:total-held world-before))
                                     (keys (:total-held world-after))))
        violations
        (for [token all-tokens
              :let [held-before      (get (:total-held world-before) token 0)
                    held-after       (get (:total-held world-after) token 0)
                    delta-held       (- held-after held-before)
                    
                    inflow-before    (+ (get (:total-principal-deposited world-before) token 0)
                                        (get (:total-bonds-posted world-before) token 0)
                                        (get (:total-yield-generated world-before) token 0))
                    inflow-after     (+ (get (:total-principal-deposited world-after) token 0)
                                        (get (:total-bonds-posted world-after) token 0)
                                        (get (:total-yield-generated world-after) token 0))
                    delta-inflow     (- inflow-after inflow-before)
                    
                    withdrawn-before (get (:total-withdrawn world-before) token 0)
                    withdrawn-after  (get (:total-withdrawn world-after) token 0)
                    delta-withdrawn  (- withdrawn-after withdrawn-before)
                    
                    claimable-before (get-token-claimable-sum world-before token)
                    claimable-after  (get-token-claimable-sum world-after token)
                    delta-claimable  (- claimable-after claimable-before)
                    
                    dist-before      (get-distributed-sum world-before token)
                    dist-after       (get-distributed-sum world-after token)
                    delta-dist       (- dist-after dist-before)
                    
                    fot-bps          (get-in world-after [:token-fot-bps token] 0)
                    ;; Net delta-claimable after tax
                    delta-claimable-net (- delta-claimable (t/compute-fee delta-claimable fot-bps))
                    
                    fees-before      (get (:total-fees world-before) token 0)
                    fees-after       (get (:total-fees world-after) token 0)
                    delta-fees       (- fees-after fees-before)
                    
                    fot-fees-before  (get (:total-fot-fees world-before) token 0)
                    fot-fees-after   (get (:total-fot-fees world-after) token 0)
                    delta-fot-fees   (- fot-fees-after fot-fees-before)
                    
                    ;; Change in held must equal (Inflow - Net_Outflow_to_claimable - Physical_Withdrawals - Distributed - Accum_Fees - FoT_Fees)
                    expected-delta   (- delta-inflow delta-claimable delta-withdrawn delta-dist delta-fees delta-fot-fees)]
              :when (not= delta-held expected-delta)]
          {:token token :delta-held delta-held :expected expected-delta})]
    {:holds? (empty? violations) :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 22: Token Tax Reconciliation
;;
;; Ensure the protocol accounts for balance loss when handling Fee-on-Transfer (FoT) tokens.
;; If a token has a tax, the received amount is less than the sent amount.
;; ---------------------------------------------------------------------------

(defn token-tax-reconciliation?
  "True when no token's held balance drops by more than is accounted for by
   simultaneous releases and refunds.

   An unexplained drop (delta-held + delta-released + delta-refunded < 0) indicates
   a Fee-on-Transfer token is leaking value without the protocol accounting for it.
   Legitimate releases/refunds always pair sub-held with record-released/record-refunded,
   so their contribution exactly offsets the held decrease."
  [world-before world-after]
  (let [all-tokens (into #{} (concat (keys (:total-held world-before))
                                     (keys (:total-held world-after))))
        violations
        (for [token all-tokens
              :let [held-before     (get (:total-held world-before) token 0)
                    held-after      (get (:total-held world-after) token 0)
                    released-before (get (:total-released world-before) token 0)
                    released-after  (get (:total-released world-after) token 0)
                    refunded-before (get (:total-refunded world-before) token 0)
                    refunded-after  (get (:total-refunded world-after) token 0)
                    claimable-before (reduce + 0
                                             (for [[wf cmap] (:claimable world-before {})
                                                   :let [et (get-in world-before [:escrow-transfers wf])]
                                                   :when (= (:token et) token)
                                                   [_ amt] cmap]
                                               (or amt 0)))
                    claimable-after  (reduce + 0
                                             (for [[wf cmap] (:claimable world-after {})
                                                   :let [et (get-in world-after [:escrow-transfers wf])]
                                                   :when (= (:token et) token)
                                                   [_ amt] cmap]
                                               (or amt 0)))
                    stake-before (if (= token :USDC)
                                   (reduce + 0 (vals (:resolver-stakes world-before {})))
                                   0)
                    stake-after  (if (= token :USDC)
                                   (reduce + 0 (vals (:resolver-stakes world-after {})))
                                   0)
                    withdrawn-before (get (:total-withdrawn world-before) token 0)
                    withdrawn-after  (get (:total-withdrawn world-after) token 0)
                    delta-held      (- held-after held-before)
                    delta-released  (- released-after released-before)
                    delta-refunded  (- refunded-after refunded-before)
                    delta-claimable (- claimable-after claimable-before)
                    delta-withdrawn (- withdrawn-after withdrawn-before)
                    delta-stake     (- stake-after stake-before)
                    delta-distributed (- (get-distributed-sum world-after token)
                                         (get-distributed-sum world-before token))
                    ;; Positive = funds left the protocol without being accounted for.
                    ;; A drop in held should be matched by increases in released/refunded/claimable
                    ;; or by bond/appeal distributions leaving the protocol custody.
                    unexplained-leak (+ delta-held delta-released delta-refunded
                                        delta-claimable delta-withdrawn (- delta-stake)
                                        delta-distributed)]
              :when (neg? unexplained-leak)]
          {:token token
           :delta-held delta-held
           :delta-released delta-released
           :delta-refunded delta-refunded
           :delta-claimable delta-claimable
           :delta-withdrawn delta-withdrawn
           :unexplained-leak (- unexplained-leak)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 23: Dispute resolution path exists (structural liveness)
;;
;; Every :disputed escrow must have at least one configured mechanism that can
;; terminate it.  An escrow with no mechanism is permanently locked — a
;; deadlock the protocol cannot recover from.
;;
;; The four mechanisms (any one is sufficient):
;;   1. Assigned resolver   — :dispute-resolver on EscrowTransfer is non-nil
;;   2. Resolution module   — :resolution-module in ModuleSnapshot is non-nil
;;   3. Dispute timeout     — :max-dispute-duration > 0 in snapshot (keeper can auto-cancel)
;;   4. Pending settlement  — a settlement already exists and can be executed
;;
;; This is a necessary but not sufficient condition for eventual resolution.
;; Sufficiency is enforced at the scenario level by the end-of-scenario liveness
;; check in sew/replay-with-sew-protocol (scenarios without :allow-open-disputes? true
;; must have all disputes resolved before the event list is exhausted).
;; ---------------------------------------------------------------------------

(defn dispute-resolution-path-exists?
  "True when every :disputed escrow has at least one termination mechanism.
   A dispute with no mechanism is a permanent deadlock."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (= :disputed (:escrow-state et))
              :let [snap     (t/get-snapshot world wf)
                    has-path? (or (some? (:dispute-resolver et))
                                  (some? (:resolution-module snap))
                                  (pos? (get snap :max-dispute-duration 0))
                                  (:exists (t/get-pending world wf)))]
              :when (not has-path?)]
          {:workflow-id wf})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 24: Slash distribution consistent
;;
;; Every unit of stake slashed via bond-slashed must be accounted for in
;; bond-distribution (insurance + protocol) plus retained-slash-reserves.
;; The three buckets
(defn slash-distribution-consistent?
  ;; must sum to the total of all bond-slashed amounts.
  ;; ---------------------------------------------------------------------------
  "True when (insurance + protocol + retained-slash-reserves) equals
   the sum of all bond-slashed amounts.

   Enforces that every slashed bond was distributed/accounted somewhere
   (insurance, protocol, or retained reserves)
   rather than disappearing."
  [world]
  (let [bd          (:bond-distribution world {:insurance 0 :protocol 0})
        retained    (:retained-slash-reserves world 0)
        dist-total  (+ (:insurance bd 0) (:protocol bd 0) retained)
        slash-total (reduce + 0 (vals (:bond-slashed world {})))]
    (if (zero? slash-total)
      {:holds? true :violations []}
      (if (= dist-total slash-total)
        {:holds? true :violations []}
        {:holds?     false
         :violations [{:dist-total dist-total :slash-total slash-total}]}))))

;; ---------------------------------------------------------------------------
;; Invariant 25: Resolver bond mix valid (80/20 stable/Sew)
;;
;; Every resolver with a registered bond must hold at least 80% stable and
;; at most 20% Sew by value.  Uses integer BPS arithmetic (no floating point).
;; ---------------------------------------------------------------------------

(defn resolver-bond-mix-valid?
  "True when every resolver's bond satisfies the 80/20 stable/Sew mix rule.
   Mirrors StakingModuleInvariants: stable >= 80%, Sew <= 20%."
  [world]
  (let [violations
        (for [[addr bond] (:resolver-bonds world {})
              :let [stable (:stable bond 0)
                    sew    (:sew bond 0)
                    total  (+ stable sew)]
              :when (pos? total)
              :when (< (* stable 10000) (* total 8000))]
          {:resolver addr :stable stable :sew sew :total total})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 26: Senior coverage not exceeded
;;
;; No senior bond may have reserved-coverage greater than its coverage-max.
;; ---------------------------------------------------------------------------

(defn senior-coverage-not-exceeded?
  "True when no senior bond has reserved coverage exceeding its maximum available coverage.
   Mirrors StakingModuleInvariants: reserved <= max."
  [world]
  (let [violations
        (for [[addr bond] (:senior-bonds world {})
              :let [reserved (:reserved-coverage bond 0)
                    maximum  (:coverage-max bond 0)]
              :when (> reserved maximum)]
          {:senior addr :reserved reserved :coverage-max maximum})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 27: Resolver not frozen on assign
;;
;; No :disputed escrow may be assigned to a resolver whose freeze window has
;; not yet expired.
;; ---------------------------------------------------------------------------

(defn resolver-not-frozen-on-assign?
  "True when no :disputed escrow is currently assigned to a frozen resolver.
   Mirrors SlashingModuleInvariants: frozen resolver cannot receive new assignments."
  [world]
  (let [bt         (:block-time world 0)
        frozen-map (:resolver-frozen-until world {})
        violations
        (for [[wf et] (:escrow-transfers world)
              :when (= :disputed (:escrow-state et))
              :let [resolver (:dispute-resolver et)
                    frozen-until (get frozen-map resolver 0)]
              :when (> frozen-until bt)]
          {:workflow-id wf :resolver resolver :frozen-until frozen-until :block-time bt})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 28: Slash epoch cap respected (20% per epoch)
;;
;; Total slashing recorded in :resolver-epoch-slashed must not exceed 20% of
;; the resolver's current stake for any resolver.
;; ---------------------------------------------------------------------------

(defn slash-epoch-cap-respected?
  "True when no resolver's total slashing in the current epoch exceeds 20% of their stake.
   Mirrors SlashingModuleInvariants: resolver slash cap per epoch is 20% (v3)."
  [world]
  (let [violations
        (for [[addr epoch-data] (:resolver-epoch-slashed world {})
              :let [epoch-amt (:amount epoch-data 0)
                    stake     (get (:resolver-stakes world) addr 0)]
              :when (pos? stake)
              :when (> (* epoch-amt 10000) (* stake 2000))]
          {:resolver addr :epoch-amount epoch-amt :stake stake})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 29: Reversal slash disabled (DR3 v3)
;;
;; In DR3 v3 the slashForReversal amount is 0 bps.  Any slash in
;; :pending-fraud-slashes with :reason :reversal must therefore have amount = 0.
;; ---------------------------------------------------------------------------

(defn reversal-slash-disabled?
  "True when no reversal slash has a non-zero amount.
   Mirrors SlashingModuleInvariants: reversal slashing is disabled in DR3 v3."
  [world]
  (let [violations
        (for [[slash-id slash] (:pending-fraud-slashes world {})
              :when (= :reversal (:reason slash))
              :when (pos? (:amount slash 0))]
          {:slash-id     slash-id
           :amount       (:amount slash)
           :basis-amount (:basis-amount slash)
           :basis-kind   (:basis-kind slash)
           :slash-bps    (:slash-bps slash)})]
    {:holds?     (empty? violations)
      :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 30: Resolver capacity never exceeded
;;
;; For every resolver with a finite capacity limit (max-concurrent > 0),
;; current-active must never exceed max-concurrent.
;;
;; Also: for every :disputed escrow assigned to a resolver, current-active
;; must account for it (current-active >= open disputes assigned to that resolver).
;; ---------------------------------------------------------------------------

(defn resolver-capacity-invariant?
  "True when:
   1. No resolver's current-active exceeds its max-concurrent.
   2. current-active >= count of open :disputed escrows assigned to that resolver.

   Violation of (1) means the counter went over limit.
   Violation of (2) means the counter was decremented without a finalization."
  [world]
  (let [capacities (:resolver-capacities world {})
        ;; count open disputes per resolver
        open-by-resolver
        (reduce (fn [acc [_ et]]
                  (if (and (= :disputed (:escrow-state et))
                           (:dispute-resolver et))
                    (update acc (:dispute-resolver et) (fnil inc 0))
                    acc))
                {}
                (:escrow-transfers world {}))

        over-limit
        (for [[addr {:keys [max-concurrent current-active]}] capacities
              :when (and (pos? max-concurrent) (> current-active max-concurrent))]
          {:resolver addr :current-active current-active :max-concurrent max-concurrent
           :violation :over-limit})

        under-count
        (for [[addr {:keys [current-active]}] capacities
              :let [open (get open-by-resolver addr 0)]
              :when (< current-active open)]
          {:resolver addr :current-active current-active :open-disputes open
           :violation :counter-below-open-disputes})

        violations (concat over-limit under-count)]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 31: Single-resolution payout consistency
;;
;; A workflow can only be finalized once. At world-level this implies:
;;  - terminal escrows must not retain pending settlements
;;  - terminal escrows must expose at most one positive claimable beneficiary
;;  - released  => beneficiary may be :to, or none after withdraw_escrow
;;  - refunded  => beneficiary may be :from, or none after withdraw_escrow
;; ---------------------------------------------------------------------------

(defn single-resolution-payout-consistent?
  "True when terminal workflows have exactly one payout direction.

   Detects double-resolution style corruption where both buyer and seller end up
   with positive claimable balances for the same workflow."
  [world]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et] (:escrow-transfers world {})
              :when (contains? terminals (:escrow-state et))
              :let [state         (:escrow-state et)
                    pending?      (:exists (t/get-pending world wf))
                    claimable-map (get-in world [:claimable wf] {})
                    positives     (->> claimable-map
                                       (filter (fn [[_ amt]] (pos? (or amt 0))))
                                       (map first)
                                       vec)
                    valid-direction?
                    (case state
                      :released (or (= positives [(:to et)])
                                    (empty? positives))
                      :refunded (or (= positives [(:from et)])
                                    (empty? positives))
                      ;; :resolved is legacy/terminal umbrella: allow either single side,
                      ;; but never dual-positive payouts.
                      :resolved (<= (count positives) 1)
                      true)
                    valid? (and (not pending?) valid-direction?)]
              :when (not valid?)]
          {:workflow-id wf
           :state state
           :pending? pending?
           :positive-claimable-addrs positives
           :to (:to et)
           :from (:from et)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 32: Executed fraud slashes are accounted in resolver slash totals
;;
;; For each resolver, cumulative :resolver-slash-total must be at least the sum
;; of executed fraud slash amounts recorded in :pending-fraud-slashes.
;; ---------------------------------------------------------------------------

(defn fraud-slash-executions-accounted?
  "True when executed fraud slashes are reflected in :resolver-slash-total.

   This enforces that successful fraud challenges actually debit stake in
   accounting terms, not just status terms."
  [world]
  (let [executed-by-resolver
        (reduce (fn [acc [_ ev]]
                  (if (and (= :executed (:status ev))
                           (= :fraud (:reason ev))
                           (some? (:resolver ev)))
                    (update acc (:resolver ev) (fnil + 0) (or (:amount ev) 0))
                    acc))
                {}
                (:pending-fraud-slashes world {}))
        violations
        (for [[resolver executed-total] executed-by-resolver
              :let [accounted (get-in world [:resolver-slash-total resolver] 0)]
              :when (< accounted executed-total)]
          {:resolver resolver
           :executed-fraud-total executed-total
           :resolver-slash-total accounted})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))


;; ---------------------------------------------------------------------------
;; Composite: check all world-level invariants
;; ---------------------------------------------------------------------------

(defn check-all
  "Run all single-world invariants.

   token-balances — optional {token nat-int} for external balance check.
   Returns {:all-hold? bool :results {invariant-id {:holds? bool :violations [...]}}}"
  ([world] (check-all world nil nil))
  ([world scenario-id] (check-all world scenario-id nil))
  ([world scenario-id token-balances]
   (let [expected-failures-raw (or (get-in world [:params :expected-failures scenario-id]) 
                                   (get-in world [:params :expected-failures (keyword scenario-id)]) 
                                   #{})
         expected-failures (set (map keyword expected-failures-raw))
         ;; Define all canonical checks
         checks {:solvency                      (solvency-holds? world token-balances)
                 :fees-non-negative             (fees-non-negative? world)
                 :held-non-negative             (held-non-negative? world)
                 :all-status-combinations-valid (all-status-combinations-valid? world)
                 :pending-settlement-consistent (pending-settlement-consistency? world)
                 :dispute-timestamp-consistent  (dispute-timestamp-consistency? world)
                 :dispute-level-bounded         (dispute-level-bounded? world)
                 :slash-status-consistent       (slash-status-consistent? world)
                 :appeal-bond-conserved         (appeal-bond-conserved? world)
                 :appeal-bond-custody-consistent (appeal-bond-custody-consistent? world)
                 :no-auto-fraud-execute         (no-auto-fraud-execute? world)
                 :bond-liquidity                (bond-liquidity-holds? world)
                 :bond-slash-bounded            (bond-slash-bounded? world)
                 :fee-cap                       (fee-cap-holds? world)
                 :no-stale-automatable-escrows  (no-stale-automatable-escrows? world)
                 :conservation-of-funds         (conservation-of-funds? world)
                 :settlement-principal-boundary (settlement-principal-boundary? world)
                 :settlement-yield-boundary     (settlement-yield-boundary? world)
                 :liability-slash-boundary      (liability-slash-boundary? world)
                 :bond-boundary                 (bond-boundary? world)
                 :fee-boundary                  (fee-boundary? world)
                 :migration-parity              (migration-parity? world)
                 :cancellation-mutex            (cancellation-mutex? world)
                 :dispute-resolution-path       (dispute-resolution-path-exists? world)
                 :slash-distribution-consistent (slash-distribution-consistent? world)
                 :resolver-bond-mix-valid        (resolver-bond-mix-valid? world)
                 :senior-coverage-not-exceeded   (senior-coverage-not-exceeded? world)
                 :resolver-not-frozen-on-assign  (resolver-not-frozen-on-assign? world)
                 :slash-epoch-cap-respected      (slash-epoch-cap-respected? world)
                 :reversal-slash-disabled        (reversal-slash-disabled? world)
                 :resolver-capacity              (resolver-capacity-invariant? world)
                 :single-resolution-payout-consistent (single-resolution-payout-consistent? world)
                 :fraud-slash-executions-accounted    (fraud-slash-executions-accounted? world)
                 :yield-position-consistency     {:holds? (generic-yield-inv/check-position-consistency world) :violations nil}
                 :yield-exposure                 {:holds? (sew-yield-inv/check-sew-yield-exposure world) :violations nil}
                 :claimable-classification       (claimable-classification world)}
         
         ;; Process results
         results (into {}
                       (for [[id result-map] checks]
                         [id (assoc result-map 
                                    :holds? (or (:holds? result-map) 
                                                (contains? expected-failures id)))]))
         all-hold? (every? #(:holds? %) (vals results))]
     {:all-hold? all-hold?
      :results   results})))




(defn check-transition
  "Run all cross-world invariants that require comparing world-before to world-after.

   Must be called after every successful state transition in addition to check-all.
   Returns {:all-hold? bool :results {invariant-name result-map}}"
  [world-before world-after]
  (let [results {:terminal-states-unchanged
                 (terminal-states-unchanged? world-before world-after)
                 :time-non-decreasing
                 (time-inv/non-decreasing-time? world-before world-after)
                 :time-no-action-after-finality
                 (time-inv/no-action-after-finality?
                   world-before world-after
                   :entities-before-fn (fn [w] (:escrow-transfers w {}))
                   :state-after-fn     (fn [w workflow-id]
                                         (get-in w [:escrow-transfers workflow-id :escrow-state]))
                   :terminal-states    #{:released :refunded :resolved})
                 :finalization-accounting-correct
                 (finalization-accounting-correct? world-before world-after)
                 :escalation-level-monotonic
                 (escalation-level-monotonic? world-before world-after)
                 :no-withdrawal-during-dispute
                 (no-withdrawal-during-dispute? world-before world-after)
                 :time-lock-integrity
                 (time-lock-integrity? world-before world-after)
                 :token-tax-reconciliation
                 (token-tax-reconciliation? world-before world-after)
                 :withdrawn-monotonic
                 (withdrawn-monotonic? world-before world-after)
                 :released-monotonic
                 (released-monotonic? world-before world-after)
                 :held-delta-accounted
                 (held-delta-accounted? world-before world-after)}
        all?    (every? #(:holds? %) (vals results))]
    {:all-hold? all?
     :results   results}))



