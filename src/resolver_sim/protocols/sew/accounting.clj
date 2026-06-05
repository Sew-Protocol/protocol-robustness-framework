(ns resolver-sim.protocols.sew.accounting
  "Pure Clojure port of EscrowVault balance and fee accounting, plus
   BondCollector fee deduction logic.

   Covers:
     - total-held-per-token tracking (add on create, sub on release/refund)
     - total-fees-per-token (monotonically increasing; withdraw-fees resets)
     - claimable-balances (pull-settlement entitlements; cleared on withdrawEscrow)
     - withdraw-fees
     - BondCollector appeal bond accounting

   All arithmetic uses integer division (uint256 truncation semantics)."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.economics.payoffs :as payoffs]))

(declare sub-held record-fee record-claimable)

;; ---------------------------------------------------------------------------
;; total-held tracking
;; ---------------------------------------------------------------------------

(defn add-held
  "Increase total-held for token by amount. Called on createEscrow."
  [world token amount]
  (update-in world [:total-held token] (fnil + 0) amount))

(defn sub-held
  "Decrease total-held for token by amount. Called on release/refund.
   Callers must have validated state. Throws a catchable ex-info on underflow
   so process-step's (catch Exception) handler converts it to :dispatch-exception
   rather than propagating an AssertionError past the catch boundary."
  [world token amount]
  (let [current (get-in world [:total-held token] 0)]
    (when (< current amount)
      (throw (ex-info "sub-held underflow"
                      {:type   :sub-held-underflow
                       :token  token
                       :held   current
                       :amount amount})))
    (update-in world [:total-held token] - amount)))

;; ---------------------------------------------------------------------------
;; total-fees tracking
;; ---------------------------------------------------------------------------

(defn record-fee
  "Accumulate fee into total-fees. Monotonically increasing.
   Mirrors FeeRecordingLibrary.recordFee in EscrowVault."
  [world token amount]
  (update-in world [:total-fees token] (fnil + 0) amount))

(defn withdraw-fees
  "Withdraw all accumulated fees for token.
   Sets total-fees[token] = 0 and returns {:ok true :world world' :amount amount}.
   Mirrors EscrowVault.withdrawFees.

   Guard: amount must be > 0.
   Guard: token must not be in a liquidity-crunch."
  [world token]
  (let [amount (get-in world [:total-fees token] 0)]
    (cond
      (zero? amount)
      (t/fail :no-fees-to-withdraw)

      (contains? (:token-liquidity-crunch world #{}) token)
      (t/fail :liquidity-insufficient)

      :else
      (let [world' (-> world
                       (assoc-in [:total-fees token] 0)
                       (update-in [:total-withdrawn token] (fnil + 0) amount))]
        (assoc (t/ok world') :amount amount)))))

;; ---------------------------------------------------------------------------
;; Claimable balances (pull-settlement model)
;;
;; Settlement creates claimableBalances[workflowId][addr] entitlements.
;; Funds are delivered explicitly via withdrawEscrow().
;; ---------------------------------------------------------------------------

(defn record-released
  "Track amount released to recipient. Called alongside sub-held on finalize-release."
  [world token amount]
  (update-in world [:total-released token] (fnil + 0) amount))

(defn record-refunded
  "Track amount refunded to sender. Called alongside sub-held on finalize-refund."
  [world token amount]
  (update-in world [:total-refunded token] (fnil + 0) amount))

;; ---------------------------------------------------------------------------
;; Claimable balances (pull-settlement model)
;;
;; Settlement creates claimableBalances[workflowId][addr] entitlements.
;; Funds are delivered explicitly via withdrawEscrow().
;; ---------------------------------------------------------------------------

(defn record-claimable
  "Record amount as claimable by addr for workflow-id.
   Depreciated: use record-claimable-v2 instead.
   Mirrors: claimableBalances[workflowId][recipient] += amount"
  [world workflow-id addr amount]
  (let [;; Fallback domain for legacy calls: settle to settlement/principal
        world' (update-in world [:claimable workflow-id addr] (fnil + 0) amount)]
    (update-in world' [:claimable-v2 workflow-id :settlement/principal addr] (fnil + 0) amount)))

(defn record-claimable-v2
  "Record amount as claimable by addr for workflow-id in a specific domain.
   Also updates the legacy :claimable map for temporary backward compatibility."
  [world workflow-id domain addr amount]
  (let [world' (update-in world [:claimable-v2 workflow-id domain addr] (fnil + 0) amount)]
    ;; Maintain dual-write to legacy map for settlement claimables (withdraw path)
    (if (#{:settlement/principal :settlement/yield} domain)
      (update-in world' [:claimable workflow-id addr] (fnil + 0) amount)
      world')))

(defn clear-claimable-v2-kind
  "Clear all v2 claimables for a workflow + kind.
   Idempotent by construction (dissoc-based), so repeated calls do not create negatives.
   For :settlement/principal, clears legacy :claimable for backward-compat parity.
   This function never infers claimants and never creates nil claimant keys."
  [world workflow-id kind]
  (let [world' (update-in world [:claimable-v2 workflow-id] dissoc kind)]
    (if (= kind :settlement/principal)
      (update world' :claimable dissoc workflow-id)
      world')))

(defn clear-claimable-v2-domain
  "Backward-compatible alias for clear-claimable-v2-kind."
  [world workflow-id domain]
  (clear-claimable-v2-kind world workflow-id domain))

(defn- clear-claimable-v2-for-addr
  "Zero claimable-v2 balances for addr on workflow-id (all domains)."
  [world wf-id addr]
  (if-let [domains (get-in world [:claimable-v2 wf-id])]
    (update-in world [:claimable-v2 wf-id]
                 (fn [domain-map]
                   (into {}
                         (for [[domain addr-map] domain-map]
                           [domain (assoc addr-map addr 0)]))))
    world))

(defn withdraw-escrow
  "Claim claimable balance for addr on workflow-id.
   Mirrors: BaseEscrow.withdrawEscrow.

   Guard: escrow must be in terminal state (:released/:refunded/:resolved).
   Guard: claimable balance must be > 0.
   Guard: token must not be in a liquidity-crunch."
  [world workflow-id addr]
  (if (nil? workflow-id)
    (t/fail :invalid-workflow-id)
    (let [wf-id (t/normalize-workflow-id workflow-id)]
      (cond
        (not (t/valid-workflow-id? world wf-id))
        (t/fail :invalid-workflow-id)

        (not (t/terminal-state? world wf-id))
        (t/fail :transfer-not-finalized)

        :else
        (let [amount (get-in world [:claimable wf-id addr] 0)
              et     (t/get-transfer world wf-id)
              token  (:token et)]
          (cond
            (zero? amount)
            (t/fail :no-claimable-balance)

            (contains? (:token-liquidity-crunch world #{}) token)
            (t/fail :liquidity-insufficient)

            :else
            (let [world' (-> world
                             (assoc-in [:claimable wf-id addr] 0)
                             (clear-claimable-v2-for-addr wf-id addr)
                             (update-in [:total-withdrawn token] (fnil + 0) amount))]
              (assoc (t/ok world') :amount amount))))))))

;; ---------------------------------------------------------------------------
;; BondCollector appeal bond accounting
;;
;; When an appeal is raised, the appellant posts a bond.
;; Protocol fee is deducted: bond * appeal-bond-protocol-fee-bps / 10000
;; Remainder goes to the incentive module.
;;
;; BondCollector storage (modelled in world):
;;   :bond-balances {workflow-id {addr amount}}   ; posted bonds per escrow/poster
;;   :bond-fees     {token amount}                 ; accumulated protocol fees from bonds
;; ---------------------------------------------------------------------------

(defn post-appeal-bond
  "Record an appeal bond posted by appellant for workflow-id.
   Deducts protocol fee into :bond-fees; records net in :bond-balances.
   Also updates :total-held and :total-bonds-posted (cumulative).

   NOTE: Bond inflow is tracked exclusively via :total-bonds-posted.
   Do NOT also increment :total-principal-deposited — that double-counts
   inflow in the conservation-of-funds and held-delta-accounted invariants."
  [world workflow-id appellant snap token amount]
  (let [fee-bps (or (:appeal-bond-protocol-fee-bps snap) 0)
        {:keys [fee net]} (payoffs/calculate-appeal-bond-fee amount fee-bps)]
    (-> world
        (update-in [:bond-balances workflow-id appellant] (fnil + 0) net)
        (update-in [:bond-fees token] (fnil + 0) fee)
        (update-in [:total-bonds-posted token] (fnil + 0) amount)
        (add-held token net))))

(defn distribute-slashed-funds
  "Internal: distribute slashed funds according to 50/30/20 split.
   If a challenger is provided (Phase L), they receive a bounty from the slashed amount.
   50% -> insurance, 30% -> protocol, 20% -> retained reserves.
   Bounty is subtracted from the 'insurance' and 'protocol' portions proportionally.
   Returns updated world."
  ([world amount] (distribute-slashed-funds world amount nil 0 nil))
  ([world amount challenger bounty-bps]
   (distribute-slashed-funds world amount challenger bounty-bps nil))
  ([world amount challenger bounty-bps workflow-id]
   (let [bounty (payoffs/calculate-bounty amount bounty-bps)
         dist   (payoffs/calculate-slashing-distribution amount bounty)]
     (-> world
         (update-in [:bond-distribution :insurance] (fnil + 0) (:insurance dist))
         (update-in [:bond-distribution :protocol]  (fnil + 0) (:protocol dist))
         (update-in [:retained-slash-reserves]      (fnil + 0) (:retained dist))
         (cond-> (and challenger (pos? bounty) (some? workflow-id))
           (record-claimable-v2 workflow-id :liability/challenge-bounty challenger bounty))))))

(defn slash-bond
  "Slash the posted bond for a losing appellant.
   Moves balance from :bond-balances to :bond-slashed (for incentive distribution)
   and applies the 50/30/20 split logic.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)
        et     (t/get-transfer world workflow-id)
        token  (:token et)]
    (if (zero? amount)
      (t/fail :no-bond-to-slash)
      (let [world' (-> world
                       (sub-held token amount)
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (update-in [:bond-slashed workflow-id] (fnil + 0) amount)
                       (distribute-slashed-funds amount))]
        (assoc (t/ok world') :slashed amount)))))

(defn return-bond
  "Return the posted bond to a winning appellant.
   Clears :bond-balances entry and credits :claimable.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)
        et     (t/get-transfer world workflow-id)
        token  (:token et)]
    (if (zero? amount)
      (t/fail :no-bond-to-return)
      (let [world' (-> world
                       (sub-held token amount)
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (record-claimable workflow-id appellant amount))]
        (assoc (t/ok world') :returned amount)))))

