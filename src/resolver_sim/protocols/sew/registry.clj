(ns resolver-sim.protocols.sew.registry
  "Resolver registration and staking registry for the Sew protocol.
   Supports Phase K (Tiered Authority) by tracking resolver 'Skin in the Game'.

   Key concepts:
     - Resolver Stake: Amount of capital a resolver has locked in the protocol.
     - Escrow Cap: The maximum escrow amount a resolver is permitted to handle.
     - Slash Capacity: The amount a resolver can be slashed before being disabled.

   Constraints:
     - MAX_SLASH_PER_OFFENSE = 50%
     - RESOLVER_SLASH_CAP_BPS = 20% (per epoch/period)"
  (:require [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.economics.payoffs            :as payoffs]))

;; ---------------------------------------------------------------------------
;; Stake management
;; ---------------------------------------------------------------------------

(defn register-stake
  "Deposit stake for a resolver address.
   Returns updated world.
   
   If yield-profile-id is provided, the stake will be managed by that yield module."
  ([world resolver-addr amount] (register-stake world resolver-addr amount nil))
  ([world resolver-addr amount yield-profile-id]
   (let [world (update-in world [:resolver-stakes resolver-addr] (fnil + 0) amount)]
     (if yield-profile-id
       (assoc-in world [:resolver-yield-profiles resolver-addr] yield-profile-id)
       world))))

(defn get-resolver-yield-profile
  "Returns the yield profile ID assigned to a resolver."
  [world resolver-addr]
  (get-in world [:resolver-yield-profiles resolver-addr]))

(defn get-stake
  "Get the current stake for a resolver address."
  [world resolver-addr]
  (get-in world [:resolver-stakes resolver-addr] 0))

(defn withdraw-stake
  "Withdraw stake for a resolver address.

   Guards:
     - amount must be positive
     - resolver must have enough stake

   Returns {:ok bool :world world' :error kw}."
  [world resolver-addr amount]
  (let [current (get-stake world resolver-addr)]
    (cond
      (or (nil? amount) (not (number? amount)) (<= amount 0))
      (t/fail :invalid-amount)

      (> amount current)
      (t/fail :insufficient-stake)

      :else
      (t/ok (update-in world [:resolver-stakes resolver-addr] (fnil - 0) amount)))))

(defn can-handle-escrow?
  "True if the resolver's stake is sufficient for the given escrow amount."
  [world resolver-addr escrow-amount]
  (let [stake (get-stake world resolver-addr)
        multiplier (get-in world [:params :capacity-multiplier] 1.0)
        cap   (payoffs/calculate-escrow-cap stake multiplier)]
    (>= cap escrow-amount)))

;; ---------------------------------------------------------------------------
;; Slashing
;; ---------------------------------------------------------------------------

(defn slash-resolver-stake
  "Slash a portion of a resolver's stake.
   Returns updated world with the slashed amount removed from registry
   and distributed according to protocol rules.
   
   Matches DR3 slashing distribution (50/30/20).
   Supports optional challenger bounty for Phase L."
  ([world resolver-addr amount] (slash-resolver-stake world resolver-addr amount nil 0 nil))
  ([world resolver-addr amount challenger bounty-bps]
   (slash-resolver-stake world resolver-addr amount challenger bounty-bps nil))
  ([world resolver-addr amount challenger bounty-bps workflow-id]
   (let [current (get-stake world resolver-addr)
         actual  (min current amount)
         token   (if workflow-id
                   (keyword (or (:token (t/get-transfer world workflow-id)) "USDC"))
                   :USDC)
         held-available (get-in world [:total-held token] 0)
         ;; Reduce held only when slash amount is backed by on-hand custody (avoids underflow
         ;; after settlement has already drained :total-held for this token).
         sub-held?      (and (pos? actual)
                             (>= held-available actual))
         world'  (-> world
                     (update-in [:resolver-stakes resolver-addr] (fnil - 0) actual)
                     (update-in [:resolver-slash-total resolver-addr] (fnil + 0) actual)
                     (acct/distribute-slashed-funds actual challenger bounty-bps workflow-id)
                     (cond-> sub-held? (acct/sub-held token actual)))]
     (assoc (t/ok world') :slashed-from-stake actual))))
