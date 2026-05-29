(ns resolver-sim.protocols.sew.yield.policy
  "Sew-specific rules for yield distribution and fee capture."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.accounting :as acct]))

(defn apply-yield-policy
  "Allocate realized yield to claimable balances and protocol fees based on policy.
   settlement-outcome: :released, :refunded, :resolved-release, :resolved-refund, etc."
  [world escrow-id settlement-outcome]
  (let [et       (t/get-transfer world escrow-id)
        snap     (t/get-snapshot world escrow-id)
        settings (t/get-settings world escrow-id)
        token    (:token et)
        owner-id [:sew/escrow escrow-id]
        pos-key  [:yield/positions owner-id]
        position (get-in world pos-key)
        yield    (+ (:realized-yield position 0) (:unrealized-yield position 0))]
    (if (or (nil? position) (zero? yield))
      world
      (let [fee-bps (or (:yield-protocol-fee-bps snap) 0)
            fee     (t/compute-fee yield fee-bps)
            net     (- yield fee)
            preset  (:yield-preset settings :off)
            
            ;; Determine who gets the net yield based on outcome and preset
            [sender-amt recipient-amt]
            (case [settlement-outcome preset]
              ;; If released, recipient usually gets it if enabled
              [:released :to-recipient] [0 net]
              [:released :to-sender]    [net 0]
              [:released :split-50-50]  [(quot net 2) (- net (quot net 2))]
              
              ;; If refunded, sender usually gets it
              [:refunded :to-sender]    [net 0]
              [:refunded :to-recipient] [0 net]
              [:refunded :split-50-50]  [(quot net 2) (- net (quot net 2))]

              ;; Defaults for :off or other combinations
              [0 0])]
        (let [world' (-> world
                        (acct/record-fee token fee)
                        (cond-> (pos? sender-amt)
                          (acct/record-claimable escrow-id (:from et) sender-amt))
                        (cond-> (pos? recipient-amt)
                          (acct/record-claimable escrow-id (:to et) recipient-amt))
                        ;; Yield pool reduction: funds move from "held" to "claimable/fees"
                        (acct/sub-held token yield)
                        ;; Capture any remaining yield (not allocated to participants) as additional protocol fees
                        (cond-> (> net (+ sender-amt recipient-amt))
                          (acct/record-fee token (- net (+ sender-amt recipient-amt)))))]
          (-> world'
              ;; If there's a shortfall, keep the position active for later recovery
              (cond->
                (:shortfall position)
                (assoc-in pos-key (assoc position :realized-yield 0 :unrealized-yield 0))

                (not (:shortfall position))
                (update-in pos-key assoc :status :settled :realized-yield 0 :unrealized-yield 0))))))))
