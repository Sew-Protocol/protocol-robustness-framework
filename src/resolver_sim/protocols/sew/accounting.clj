(ns resolver-sim.protocols.sew.accounting
  "Pure Clojure port of EscrowVault balance and fee accounting, plus
   BondCollector fee deduction logic.

   Covers:
     - total-held-per-token tracking (add on create, sub on release/refund)
     - total-fees-per-token (monotonically increasing; withdraw-fees resets)
     - claimable-balances (push-transfer fallback; cleared on withdrawEscrow)
     - withdraw-fees
     - BondCollector appeal bond accounting

   All arithmetic uses integer division (uint256 truncation semantics)."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.economics.payoffs :as payoffs]))

;; ---------------------------------------------------------------------------
;; total-held tracking
;; ---------------------------------------------------------------------------

(defn add-held
  "Increase total-held for token by amount. Called on createEscrow."
  [world token amount]
  (update-in world [:total-held token] (fnil + 0) amount))

(defn sub-held
  "Decrease total-held for token by amount. Called on release/refund.
   Callers must have validated state. Asserts no underflow as a trip-wire."
  [world token amount]
  (let [current (get-in world [:total-held token] 0)]
    (assert (>= current amount)
            (format "sub-held underflow: token=%s held=%d amount=%d" token current amount))
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

   Guard: amount must be > 0."
  [world token]
  (let [amount (get-in world [:total-fees token] 0)]
    (if (zero? amount)
      (t/fail :no-fees-to-withdraw)
      (let [world' (assoc-in world [:total-fees token] 0)]
        (assoc (t/ok world') :amount amount)))))

;; ---------------------------------------------------------------------------
;; Claimable balances (push-transfer fallback)
;;
;; When a direct token transfer fails (e.g. recipient is a non-receiving contract),
;; the amount is recorded in claimableBalances[workflowId][addr].
;; The recipient calls withdrawEscrow() to claim it later.
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
;; Claimable balances (push-transfer fallback)
;;
;; When a direct token transfer fails (e.g. recipient is a non-receiving contract),
;; the amount is recorded in claimableBalances[workflowId][addr].
;; The recipient calls withdrawEscrow() to claim it later.
;; ---------------------------------------------------------------------------

(defn record-claimable
  "Record amount as claimable by addr for workflow-id.
   Mirrors: claimableBalances[workflowId][recipient] += amount"
  [world workflow-id addr amount]
  (update-in world [:claimable workflow-id addr] (fnil + 0) amount))

(defn withdraw-escrow
  "Claim claimable balance for addr on workflow-id.
   Mirrors: BaseEscrow.withdrawEscrow.

   Guard: escrow must be in terminal state (:released/:refunded/:resolved).
   Guard: claimable balance must be > 0."
  [world workflow-id addr]
  (if (nil? workflow-id)
    (t/fail :invalid-workflow-id)
    (let [wf-id (if (string? workflow-id) workflow-id (str workflow-id))]
      (cond
        (not (t/valid-workflow-id? world wf-id))
        (t/fail :invalid-workflow-id)

        (not (t/terminal-state? world wf-id))
        (t/fail :transfer-not-finalized)

        :else
        (let [amount (get-in world [:claimable wf-id addr] 0)]
          (if (zero? amount)
            (t/fail :no-claimable-balance)
            (let [world' (assoc-in world [:claimable wf-id addr] 0)]
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
   Also updates :total-held and :total-bonds-posted (cumulative)."
  [world workflow-id appellant snap token amount]
  (let [fee-bps (or (:appeal-bond-protocol-fee-bps snap) 0)
        {:keys [fee net]} (payoffs/calculate-appeal-bond-fee amount fee-bps)]
    (-> world
        (update-in [:bond-balances workflow-id appellant] (fnil + 0) net)
        (update-in [:bond-fees token] (fnil + 0) fee)
        (update-in [:total-bonds-posted token] (fnil + 0) amount)
        (add-held token amount))))

(defn distribute-slashed-funds
  "Internal: distribute slashed funds according to 50/30/20 split.
   If a challenger is provided (Phase L), they receive a bounty from the slashed amount.
   50% -> insurance, 30% -> protocol, 20% -> retained reserves.
   Bounty is subtracted from the 'insurance' and 'protocol' portions proportionally.
   Returns updated world."
  ([world amount] (distribute-slashed-funds world amount nil 0))
  ([world amount challenger bounty-bps]
   (let [bounty (payoffs/calculate-bounty amount bounty-bps)
         dist   (payoffs/calculate-slashing-distribution amount bounty)]
     (-> world
         (update-in [:bond-distribution :insurance] (fnil + 0) (:insurance dist))
         (update-in [:bond-distribution :protocol]  (fnil + 0) (:protocol dist))
         (update-in [:retained-slash-reserves]      (fnil + 0) (:retained dist))
         (cond-> (and challenger (pos? bounty))
           (update-in [:claimable challenger] (fnil + 0) bounty))))))

(defn slash-bond
  "Slash the posted bond for a losing appellant.
   Moves balance from :bond-balances to :bond-slashed (for incentive distribution)
   and applies the 50/30/20 split logic.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)]
    (if (zero? amount)
      (t/fail :no-bond-to-slash)
      (let [world' (-> world
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (update-in [:bond-slashed workflow-id] (fnil + 0) amount)
                       (distribute-slashed-funds amount))]
        (assoc (t/ok world') :slashed amount)))))

(defn return-bond
  "Return the posted bond to a winning appellant.
   Clears :bond-balances entry and credits :claimable.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)]
    (if (zero? amount)
      (t/fail :no-bond-to-return)
      (let [world' (-> world
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (record-claimable workflow-id appellant amount))]
        (assoc (t/ok world') :returned amount)))))

