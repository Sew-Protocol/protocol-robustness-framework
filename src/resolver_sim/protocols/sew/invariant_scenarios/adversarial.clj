(ns resolver-sim.protocols.sew.invariant-scenarios.adversarial
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

(def s24
  {:scenario-id        "s24-resolver-stake-depletion-cascade"
   :schema-version     "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents             [{:id "buyer0"   :address "0xbuyer0"   :strategy "honest"}
                        {:id "buyer1"   :address "0xbuyer1"   :strategy "honest"}
                        {:id "buyer2"   :address "0xbuyer2"   :strategy "honest"}
                        {:id "seller"   :address "0xseller"   :strategy "honest"}
                        {:id "resolver" :address "0xresolver" :role "resolver"}
                        {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params    stake-cascade
   :events
   [;; Resolver registers stake before any escrow is opened
    {:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 3000}}
    {:seq 1 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 2 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 3 :time 1000 :agent "buyer2" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 4 :time 1060 :agent "buyer0" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 6 :time 1060 :agent "buyer2" :action "raise_dispute"
     :params {:workflow-id 2}}
    ;; Early attempt: 240 s elapsed, need 300 → rejected
    {:seq 7 :time 1300 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; Timeout reached — full slash: resolver stake 3000 → 1000
    {:seq 8 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; Partial slash: only 1000 remains; actual_slashed=1000, stake 1000 → 0
    {:seq 9 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 1}}
    ;; Zero-stake slash: resolver exhausted; actual_slashed=0, stake stays 0
    {:seq 10 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 2}}]})

;; ---------------------------------------------------------------------------
;; S25 — Profit-Maximizer: fraud-slash lifecycle
;;
;; Adversarial actor: governance that proposes a speculative fraud slash to
;; extract value from a resolver, then tries to execute it after losing the
;; appeal.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120).
;;   2. Buyer raises dispute; resolver submits a release decision
;;      → pending (deadline = 1120+120 = 1240).
;;   3. Governance proposes a fraud slash against the resolver (amount=500)
;;      → slash is :pending (appeal-deadline = 1130+120 = 1250).
;;   4. Resolver appeals the slash within the window.
;;      → slash status: :appealed.
;;   5. Governance resolves the appeal in the resolver's favour (upheld?=true)
;;      → slash status: :reversed.
;;   6. Governance attempts to execute the reversed slash
;;      → rejected (:slash-already-reversed).
;;   7. Keeper executes the original pending settlement after deadline.
;;      → escrow :released.
;;
;; Expected: PASS.
;;
;; Invariants exercised:
;;   slash-status-consistent? — slash transitions pending→appealed→reversed
;;   no-auto-fraud-execute?   — slash went through proper pending window
;;   appeal-bond-conserved?   — no negative bond amounts anywhere
;;   conservation-of-funds?   — 0 held + AFA released + 0 refunded = AFA deposited
;;   terminal-states-unchanged? — once released, stays released
;; ---------------------------------------------------------------------------

(def s25
  {:scenario-id     "s25-profit-maximizer-slash-lifecycle"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"     :strategy "honest"}
                     {:id "seller"     :address "0xseller"    :strategy "honest"}
                     {:id "resolver"   :address "0xresolver"  :role "resolver"}
                     {:id "governance" :address "0xgov"       :role "governance"}
                     {:id "keeper"     :address "0xkeeper"    :role "keeper"}]
   :protocol-params appeal ; appeal-window=120s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes a fraud slash (profit-maximizer trying to penalise resolver)
    ;; slash appeal-deadline = 1130 + 120 (appeal-window-duration from snapshot) = 1250
    {:seq 3 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Resolver appeals the slash within the window
    {:seq 4 :time 1140 :agent "resolver" :action "appeal_slash"
     :params {:workflow-id 0}}
    ;; Governance resolves the appeal: resolver wins (upheld?=true → slash :reversed)
    {:seq 5 :time 1160 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0 :upheld? true}}
    ;; Governance tries to execute the reversed slash → rejected (:slash-already-reversed)
    {:seq 6 :time 1200 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; Keeper executes the pending settlement after its deadline (1240)
    {:seq 7 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S26 — Forking-Strategist: L0→L1 decision reversal
;;
;; Adversarial actor: buyer who escalates strategically after losing at L0,
;; gambling that a different L1 resolver will fork to the opposite outcome.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros appeal-window=60s).
;;   2. Buyer raises dispute.
;;   3. L0 resolver rules :release (in favour of seller).
;;      → pending (deadline = 1120+60 = 1180).
;;   4. Buyer (forking strategist) escalates before the deadline, posting a
;;      challenge bond (100 USDC default) → level 0→1, new-resolver=0xl1.
;;   5. L1 resolver rules :refund (opposite of L0 = the "fork").
;;      → new pending (deadline = 1190+60 = 1250).
;;   6. Keeper executes the pending settlement after the L1 deadline.
;;      → escrow :refunded.
;;
;; Expected: PASS — the protocol handles a cross-level decision fork correctly.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?       — level advances 0→1, never goes back
;;   finalization-accounting-correct?  — L1 refund recorded despite L0 release vote
;;   conservation-of-funds?            — 0 held + 0 released + AFA refunded = AFA deposited
;;   token-tax-reconciliation?         — delta-held matches delta-refunded exactly
;;   fee-cap-holds?                    — escrow-fee + challenge-bond ≤ original amount
;;   terminal-states-unchanged?        — once :refunded, stays :refunded
;; ---------------------------------------------------------------------------

(def s26
  {:scenario-id     "s26-forking-strategist-l1-reversal"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"} ; escalates strategically
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60s, kleros escalation-resolvers
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules in favour of seller (release). Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates before deadline (the fork attempt) → challenge bond posted, level 0→1
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 rules opposite (refund = the adversarial fork). Pending deadline = 1190+60 = 1250.
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
    ;; Keeper executes after L1 deadline → :refunded
    {:seq 5 :time 1255 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S27 — Forking-Strategist: full 3-level ladder, fork at L2
;;
;; Adversarial actor: buyer who keeps escalating even after L1 confirms L0's
;; decision, betting that a different L2 resolver will finally rule in their
;; favour.  The fork materialises at L2.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release (buyer loses).  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates before deadline → level 0→1, new-resolver=0xl1.
;;   5. L1 also rules :release (no fork yet).  Pending deadline = 1190+60 = 1250.
;;   6. Buyer escalates again before deadline → level 1→2, new-resolver=0xl2.
;;   7. L2 rules :refund (the fork finally arrives).  Pending deadline = 1260+60 = 1320.
;;   8. Keeper executes after L2 deadline → escrow :refunded.
;;
;; Expected: PASS — the protocol handles a full 3-level decision reversal.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?      — level advances 0→1→2, never reverses
;;   conservation-of-funds?           — 0 held + 0 released + AFA refunded = AFA deposited
;;   fee-cap-holds?                   — fee + two challenge bonds ≤ original amount
;;   token-tax-reconciliation?        — delta-held matches delta-refunded
;;   terminal-states-unchanged?       — once :refunded, stays :refunded
;; ---------------------------------------------------------------------------

(def s27
  {:scenario-id     "s27-forking-strategist-l2-fork"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"} ; escalates twice
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "l2resolver" :address "0xl2"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60 s, resolvers {0→xl0,1→xl1,2→xl2}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules release (buyer loses). Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates (first attempt). Level 0→1, new-resolver=0xl1.
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 also rules release (still no fork). Pending deadline = 1190+60 = 1250.
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Buyer escalates again (second attempt). Level 1→2, new-resolver=0xl2.
    {:seq 5 :time 1200 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L2 finally forks — rules refund. Pending deadline = 1260+60 = 1320.
    {:seq 6 :time 1260 :agent "l2resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl2hash"}}
    ;; Keeper executes after L2 deadline → :refunded
    {:seq 7 :time 1325 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S28 — Forking-Strategist: late escalation rejected, L0 decision stands
;;
;; Adversarial actor: buyer who waits too long before attempting to escalate.
;; The L0 appeal window expires before they act; the escalation is rejected
;; and the L0 pending settlement executes normally.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release.  Pending deadline = 1120+60 = 1180.
;;   4. Buyer attempts escalation at t=1185 (5 s after deadline) → rejected.
;;   5. Keeper executes the still-live L0 pending settlement at t=1190.
;;      → escrow :released.
;;
;; Expected: PASS — the appeal-window guard correctly rejects the late escalation;
;; no invariant fires; L0 outcome stands.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?    — level never advances (stays at 0)
;;   terminal-states-unchanged?     — once :released via L0, stays :released
;;   conservation-of-funds?         — 0 held + AFA released + 0 refunded = AFA deposited
;;   no-stale-automatable-escrows?  — no automatable work remains after settlement
;; ---------------------------------------------------------------------------

(def s28
  {:scenario-id     "s28-forking-strategist-late-escalation-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :expected-errors [{:seq 3 :action "escalate_dispute" :error :appeal-window-expired}]
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"} ; attempts late escalation
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; appeal-window=60 s
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules release. Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer attempts escalation 5 s after the window closed → rejected
    {:seq 3 :time 1185 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; Keeper executes the L0 pending settlement (still valid, past deadline)
    {:seq 4 :time 1190 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S29 — Forking-Strategist: seller escalates after losing L0 refund
;;
;; Role reversal: it is the seller (not the buyer) who acts as the forking
;; strategist.  L0 rules refund (buyer wins); seller escalates hoping L1
;; will fork to release.  L1 does.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :refund (buyer wins; seller loses).  Pending deadline = 1120+60 = 1180.
;;   4. Seller (forking strategist) escalates before deadline.
;;      → level 0→1, new-resolver=0xl1.
;;   5. L1 forks to :release (seller wins).  Pending deadline = 1190+60 = 1250.
;;   6. Keeper executes after L1 deadline → escrow :released.
;;
;; Expected: PASS — protocol correctly handles a seller-initiated escalation
;; that reverses a buyer-favourable L0 outcome.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?       — level advances 0→1, never reverses
;;   finalization-accounting-correct?  — release recorded despite prior refund vote
;;   conservation-of-funds?            — 0 held + AFA released + 0 refunded = AFA deposited
;;   fee-cap-holds?                    — fee + seller's challenge bond ≤ 6000
;;   terminal-states-unchanged?        — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s29
  {:scenario-id     "s29-forking-strategist-seller-escalates"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"} ; escalates strategically
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60 s
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules refund (buyer wins). Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl0hash"}}
    ;; Seller escalates before deadline (the fork attempt) → level 0→1, new-resolver=0xl1
    {:seq 3 :time 1130 :agent "seller" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 forks to release (seller wins). Pending deadline = 1190+60 = 1250.
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Keeper executes after L1 deadline → :released
    {:seq 5 :time 1255 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S30 — Forking-Strategist: double-loss, fork never materialises
;;
;; Adversarial actor: buyer who escalates to L1 expecting a fork.  L1
;; confirms L0's decision (both rule release); the buyer loses their
;; challenge bond and the escrow settles at the original outcome.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release (buyer loses).  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates before deadline (expensive gamble).
;;      → level 0→1, challenge bond posted, new-resolver=0xl1.
;;   5. L1 also rules :release (no fork — L1 agrees with L0).
;;      Pending deadline = 1190+60 = 1250.
;;   6. Appeal window expires without further escalation.
;;   7. Keeper executes the L1 pending settlement → escrow :released.
;;      Buyer forfeits their challenge bond for nothing.
;;
;; Expected: PASS — no invariant fires; buyer's failed gamble is captured
;; in bond accounting; final outcome is identical to L0 (release).
;;
;; Invariants exercised:
;;   escalation-level-monotonic?      — level advances 0→1 only
;;   conservation-of-funds?           — 0 held + AFA released + 0 refunded = AFA deposited
;;   fee-cap-holds?                   — fee + forfeited challenge bond ≤ 6000
;;   token-tax-reconciliation?        — delta-held == delta-released (no unexplained leak)
;;   terminal-states-unchanged?       — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s30
  {:scenario-id     "s30-forking-strategist-double-loss"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"} ; escalates, but fork never comes
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60 s
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules release (buyer loses). Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates (expensive gamble). Level 0→1, bond posted, new-resolver=0xl1.
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 confirms L0 — also rules release (no fork). Pending deadline = 1190+60 = 1250.
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Buyer does not escalate further. Keeper executes after L1 deadline → :released.
    {:seq 5 :time 1255 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; ---------------------------------------------------------------------------
;; S31 — Forking-Strategist: all three levels confirm — no fork ever materialises
;;
;; Adversarial actor: buyer who keeps escalating even after two consecutive
;; confirming rulings (L0 and L1 both release), then tries a third escalation
;; at the maximum level — which is rejected because the protocol caps escalation
;; at level 2.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release.  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates → level 0→1, new-resolver=0xl1.
;;   5. L1 also rules :release (no fork).  Pending deadline = 1190+60 = 1250.
;;   6. Buyer escalates again → level 1→2, new-resolver=0xl2.
;;   7. L2 also rules :release (still no fork).  Pending deadline = 1260+60 = 1320.
;;   8. Buyer attempts a third escalation → rejected (:escalation-not-allowed,
;;      final-round? = true, max-dispute-level = 2).
;;   9. Keeper executes the L2 pending settlement → escrow :released.
;;      Buyer has lost two challenge bonds for nothing.
;;
;; Expected: PASS — max-level guard fires correctly; two bonds are accounted for
;; without any invariant violation.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?   — level advances 0→1→2, then stays at 2
;;   fee-cap-holds?                — escrow-fee + 2 challenge bonds ≤ 6000
;;   conservation-of-funds?        — 0 held + AFA released + 0 refunded = AFA deposited
;;   token-tax-reconciliation?     — delta-held == delta-released exactly
;;   terminal-states-unchanged?    — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s31
  {:scenario-id     "s31-forking-strategist-all-levels-confirm"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :expected-errors [{:seq 7 :action "escalate_dispute" :error :escalation-not-allowed}]
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"} ; escalates twice; no fork
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "l2resolver" :address "0xl2"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60 s, resolvers {0→xl0,1→xl1,2→xl2}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules release (buyer loses). Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates (first bond). Level 0→1, new-resolver=0xl1.
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 confirms L0 (no fork). Pending deadline = 1190+60 = 1250.
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Buyer escalates again (second bond). Level 1→2, new-resolver=0xl2.
    {:seq 5 :time 1200 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L2 confirms again (still no fork). Pending deadline = 1260+60 = 1320.
    {:seq 6 :time 1260 :agent "l2resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl2hash"}}
    ;; Buyer tries a third escalation → rejected (:escalation-not-allowed: final round)
    {:seq 7 :time 1270 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; Keeper executes after L2 deadline → :released
    {:seq 8 :time 1325 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S32 — Forking-Strategist: fork lands at L1; keeper attempts settlement too
;;       early; appeal window expires without L2 escalation; retry succeeds
;;
;; Two-phase outcome: buyer's fork attempt succeeds (L1 reverses L0), but the
;; keeper tries to execute the pending settlement while the L1 appeal window is
;; still open (rejected :appeal-window-not-expired).  Buyer chooses not to
;; escalate to L2.  After the window closes, the keeper retries and the fork
;; (refund) is finalised.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release.  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates → level 0→1, new-resolver=0xl1.
;;   5. L1 rules :refund (the fork).  Pending deadline = 1190+60 = 1250.
;;   6. Keeper tries execute_pending_settlement at t=1200 (<1250)
;;      → rejected (:appeal-window-not-expired).
;;   7. Appeal window expires; buyer does not escalate to L2.
;;   8. Keeper retries at t=1255 → escrow :refunded.
;;
;; Expected: PASS — the premature settlement attempt is cleanly rejected without
;; corrupting state; the L1 fork is finalised correctly on retry.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?       — level advances 0→1, stays at 1
;;   finalization-accounting-correct?  — refund recorded despite L0 release vote
;;   conservation-of-funds?            — 0 held + 0 released + AFA refunded = AFA deposited
;;   no-stale-automatable-escrows?     — no automatable work remains after settlement
;;   terminal-states-unchanged?        — once :refunded, stays :refunded
;; ---------------------------------------------------------------------------

(def s32
  {:scenario-id     "s32-forking-strategist-premature-settlement-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :expected-errors [{:seq 5 :action "execute_pending_settlement" :error :appeal-window-not-expired}]
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"} ; escalates; fork lands at L1
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60 s
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules release (buyer loses). Pending deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates within the window. Level 0→1, new-resolver=0xl1.
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 forks to refund (buyer wins). Pending deadline = 1190+60 = 1250.
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
    ;; Keeper attempts early settlement while L1 appeal window still open
    ;; (t=1200 < deadline=1250) → rejected (:appeal-window-not-expired)
    {:seq 5 :time 1200 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Buyer does not escalate to L2. Appeal window expires.
    ;; Keeper retries after deadline → :refunded
    {:seq 6 :time 1255 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S33 — Forking-Strategist: two concurrent disputes — fork on wf0, no
;;       escalation on wf1 — tests per-escrow state isolation
;;
;; Two independent escrows opened simultaneously.  buyer0 plays the forking
;; strategist on wf0 (L0 releases → buyer escalates → L1 refunds).  buyer1
;; accepts the L0 outcome on wf1 (no escalation, pending settles normally).
;; The two escrows travel completely different resolution paths and must not
;; contaminate each other's state or accounting.
;;
;; Sequence:
;;   1.  buyer0 creates wf0 (6000 USDC) and buyer1 creates wf1 (4000 USDC).
;;   2.  Both raise disputes.
;;   3.  L0 rules :release on both.
;;       wf0 pending deadline = 1120+60 = 1180.
;;       wf1 pending deadline = 1120+60 = 1180.
;;   4.  buyer0 escalates wf0 before its deadline → level 0→1.
;;       wf1 is not escalated; its pending settlement remains live.
;;   5.  Keeper executes wf1 pending at t=1185 (after wf1 deadline) → wf1 :released.
;;   6.  L1 rules :refund on wf0 (the fork).  wf0 pending deadline = 1190+60 = 1250.
;;   7.  Keeper executes wf0 pending at t=1255 → wf0 :refunded.
;;
;; Expected: PASS — two entirely different outcomes on co-existing escrows
;; without any cross-contamination of held amounts, dispute levels, or
;; settlement state.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?   — wf0 level 0→1; wf1 level never changes
;;   solvency-holds?               — two escrows tracked independently at each step
;;   conservation-of-funds?        — (wf0 AFA refunded) + (wf1 AFA released) = total deposited AFA
;;   fee-cap-holds?                — separate fee-cap checks for each escrow
;;   terminal-states-unchanged?    — both escrows stay in their terminal state
;; ---------------------------------------------------------------------------

(def s33
  {:scenario-id     "s33-forking-strategist-two-escrow-fork-isolation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer0"     :address "0xbuyer0" :strategy "honest"} ; escalates on wf0
                     {:id "buyer1"     :address "0xbuyer1" :strategy "honest"} ; accepts L0 on wf1
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal ; fee-bps=150, appeal-window=60 s
   :events
   [{:seq 0 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 1 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}}
    {:seq 2 :time 1060 :agent "buyer0" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    ;; L0 rules release on both. Each pending deadline = 1120+60 = 1180.
    {:seq 4 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0-wf0-hash"}}
    {:seq 5 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xl0-wf1-hash"}}
    ;; buyer0 escalates wf0 (forking strategist). Level 0→1, new-resolver=0xl1.
    ;; wf1 stays at its L0 pending (not escalated).
    {:seq 6 :time 1130 :agent "buyer0" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; Keeper executes wf1 after its L0 deadline (wf0 appeal window is unrelated).
    {:seq 7 :time 1185 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}
    ;; L1 forks on wf0 (refund). wf0 pending deadline = 1190+60 = 1250.
    {:seq 8 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1-wf0-hash"}}
    ;; Keeper executes wf0 after its L1 deadline → wf0 :refunded
    {:seq 9 :time 1255 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S34 — Profit-Maximizer: unchallenged slash (resolver forfeits)
;;
;; The simplest profit-extraction path: governance proposes a fraud slash,
;; the resolver chooses not to contest it, the appeal window closes, and
;; governance executes.  No appeal is ever filed.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120 s).
;;   2. Buyer raises dispute; resolver submits release decision.
;;      Pending deadline = 1120+120 = 1240.
;;   3. Governance proposes a fraud slash (amount=500).
;;      Slash appeal-deadline = 1130+120 = 1250.
;;   4. Resolver does not appeal.
;;   5. Keeper executes the pending settlement first → escrow :released.
;;   6. Governance executes the slash at t=1255 (>1250) → slash :executed;
;;      resolver stake is debited by min(stake, 500).
;;
;; Expected: PASS — the unchallenged slash lifecycle completes without
;; any invariant violation.  slash and escrow settlement are independent.
;;
;; Invariants exercised:
;;   slash-status-consistent?     — slash pending→executed (no intermediate states)
;;   no-auto-fraud-execute?        — execute happens after explicit timelock
;;   conservation-of-funds?        — AFA released; slash operates on resolver-stakes
;;   terminal-states-unchanged?    — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s34
  {:scenario-id     "s34-profit-maximizer-unchallenged-slash"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; [Resolver does not appeal — forfeits.]
    ;; Settle escrow first (deadline 1240 has passed)
    {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Governance executes after appeal-deadline → slash :executed
    {:seq 6 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S35 — Profit-Maximizer: governance wins the appeal
;;
;; Resolver contests the slash (appeals), but governance rejects the appeal
;; (upheld?=false → slash reverts to :pending).  Governance then executes
;; the confirmed slash after the original appeal-deadline.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120 s).
;;   2. Buyer raises dispute; resolver submits release decision.
;;      Pending deadline = 1120+120 = 1240.
;;   3. Governance proposes slash (amount=500).
;;      Slash appeal-deadline = 1130+120 = 1250.
;;   4. Resolver appeals within the window → slash :appealed.
;;   5. Governance resolves appeal with upheld?=false (appeal rejected).
;;      → slash status reverts to :pending (same appeal-deadline = 1250).
;;   6. Keeper settles the escrow first → :released.
;;   7. Governance executes at t=1255 (>1250) → slash :executed.
;;
;; Expected: PASS — full appeal cycle where governance prevails; resolver
;; cannot replay the appeal; slash executes on the original timelock.
;;
;; Invariants exercised:
;;   slash-status-consistent?     — pending→appealed→pending→executed
;;   appeal-bond-conserved?       — bond amounts stay non-negative throughout
;;   conservation-of-funds?       — AFA released; slash operates on resolver-stakes
;;   terminal-states-unchanged?   — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s35
  {:scenario-id     "s35-profit-maximizer-governance-wins-appeal"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Resolver contests the slash within the window → :appealed
    {:seq 5 :time 1140 :agent "resolver" :action "appeal_slash"
     :params {:workflow-id 0}}
    ;; Governance rejects the appeal (upheld?=false) → slash returns to :pending
    {:seq 6 :time 1160 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0 :upheld? false}}
    ;; Keeper settles the escrow first (pending deadline 1240 has passed)
    {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Governance executes after original appeal-deadline (1255 > 1250) → :executed
    {:seq 8 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S36 — Profit-Maximizer: pre-window execute rejected; retry succeeds
;;
;; Governance attempts to execute the slash before the appeal-deadline (the
;; timelock) has expired.  The attempt is rejected with :timelock-not-expired.
;; After the window closes, governance retries and the slash executes.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120 s).
;;   2. Buyer raises dispute; resolver submits release decision.
;;      Pending deadline = 1120+120 = 1240.
;;   3. Governance proposes slash (amount=500).
;;      Slash appeal-deadline = 1130+120 = 1250.
;;   4. Governance immediately tries to execute at t=1135 (<1250)
;;      → rejected (:timelock-not-expired).
;;   5. Slash appeal-deadline passes; no appeal is filed.
;;   6. Keeper settles the escrow first → :released.
;;   7. Governance retries execute at t=1255 → slash :executed.
;;
;; Expected: PASS — pre-window execution is cleanly rejected; state is
;; unchanged; the retry after the deadline succeeds.
;;
;; Invariants exercised:
;;   slash-status-consistent?     — slash stays :pending throughout (never aborted)
;;   no-auto-fraud-execute?        — slash requires explicit post-timelock call
;;   conservation-of-funds?        — AFA released; slash on resolver-stakes
;;   terminal-states-unchanged?    — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s36
  {:scenario-id     "s36-profit-maximizer-pre-window-execute-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Governance attempts early execution (1135 < 1250) → rejected :timelock-not-expired
    {:seq 5 :time 1135 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; [Resolver does not appeal.  Appeal window passes.]
    ;; Keeper settles escrow first (pending deadline 1240 has passed)
    {:seq 6 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Governance retries after deadline → slash :executed
    {:seq 7 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S37 — Profit-Maximizer: two resolvers slashed simultaneously, split outcomes
;;
;; Governance targets two resolvers in parallel.  Resolver-0 (on wf0) appeals
;; and wins (slash reversed).  Resolver-1 (on wf1) forfeits (no appeal).
;; After the window closes, governance attempts to execute both slashes:
;; wf0 is rejected (:slash-already-reversed); wf1 executes.  Both escrows
;; settle independently.
;;
;; Sequence:
;;   1. Two escrows created with separate custom resolvers.
;;   2. Both disputes raised; each resolver submits a release decision.
;;      Both pending deadlines = 1120+120 = 1240.
;;   3. Governance proposes slashes on both resolvers simultaneously.
;;      Both slash appeal-deadlines = 1130+120 = 1250.
;;   4. Resolver-0 appeals → wf0 slash :appealed.
;;      Resolver-1 does NOT appeal (forfeits).
;;   5. Governance resolves wf0 appeal with upheld?=true → wf0 slash :reversed.
;;   6. Settle both escrows after their pending deadlines.
;;   7. After appeal-deadline:
;;      - Governance tries to execute wf0 slash → rejected (:slash-already-reversed).
;;      - Governance executes wf1 slash → :executed.
;;      (slashes execute on released escrows, avoiding freeze-on-active-dispute violations).
;;
;; Expected: PASS — slash operations on wf0 and wf1 are fully isolated;
;; reversal on wf0 does not affect wf1; both escrows settle correctly.
;;
;; Invariants exercised:
;;   slash-status-consistent?    — wf0: pending→appealed→reversed; wf1: pending→executed
;;   solvency-holds?             — two escrows tracked independently
;;   conservation-of-funds?      — (wf0 AFA + wf1 AFA) released = total deposited AFA
;;   appeal-bond-conserved?      — wf0 bond intact after reversal; wf1 has no bond
;;   terminal-states-unchanged?  — both escrows stay :released
;; ---------------------------------------------------------------------------

(def s37
  {:scenario-id     "s37-profit-maximizer-two-resolver-split-outcomes"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer0"      :address "0xbuyer0"    :strategy "honest"}
                     {:id "buyer1"      :address "0xbuyer1"    :strategy "honest"}
                     {:id "seller"      :address "0xseller"    :strategy "honest"}
                     {:id "resolver0"   :address "0xresolver0" :role "resolver"}
                     {:id "resolver1"   :address "0xresolver1" :role "resolver"}
                     {:id "governance"  :address "0xgov"       :role "governance"}
                     {:id "keeper"      :address "0xkeeper"    :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver0" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "resolver1" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver0"}}
    {:seq 3 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000
              :custom-resolver "0xresolver1"}}
    {:seq 4 :time 1060 :agent "buyer0" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    ;; wf0: resolver0 submits at t=1120 → pending deadline = 1120+120 = 1240.
    ;; wf1: resolver1 submits at t=1125 → pending deadline = 1125+120 = 1245.
    ;; Staggering the deadlines prevents both from being simultaneously stale
    ;; when the keeper runs, satisfying the no-stale-automatable-escrows invariant.
    {:seq 6 :time 1120 :agent "resolver0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xr0hash"}}
    {:seq 7 :time 1125 :agent "resolver1" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xr1hash"}}
    ;; Governance proposes slashes on both. Slash deadlines = 1130+120 = 1250.
    {:seq 8 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver0" :amount 500}}
    {:seq 9 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 1 :resolver-addr "0xresolver1" :amount 300}}
    ;; Resolver-0 appeals within the window → wf0 slash :appealed
    {:seq 10 :time 1140 :agent "resolver0" :action "appeal_slash"
     :params {:workflow-id 0}}
    ;; [Resolver-1 does NOT appeal (forfeits).]
    ;; Governance resolves wf0 appeal in resolver-0's favour → wf0 slash :reversed
    {:seq 11 :time 1160 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0 :upheld? true}}
    ;; Settle wf0 once its pending deadline has passed.
    {:seq 12 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Settle wf1 after its own pending deadline has passed.
    {:seq 13 :time 1246 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}
    ;; After slash deadlines have passed:
    ;; Governance tries to execute wf0 slash → rejected (:slash-already-reversed)
    {:seq 14 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; Governance executes wf1 slash (forfeited, status :pending) → :executed
    {:seq 15 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 1}}]})

;; ---------------------------------------------------------------------------
;; S38 — DR3 resolver bond 80/20 mix invariant holds
;;
;; A resolver registers a valid bond mix (80% stable, 20% Sew) and completes
;; a full dispute lifecycle.  The resolver-bond-mix-valid? invariant is checked
;; after every step and must hold throughout.
;;
;; Invariants exercised:
;;   resolver-bond-mix-valid?  — 8000 stable + 2000 Sew = exactly 80/20
;;   solvency-holds?           — full dispute with appeal window
;;   conservation-of-funds?   — AFA released; resolver stake intact
;; ---------------------------------------------------------------------------

(def s38
  {:scenario-id     "s38-dr3-bond-mix-valid"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "resolver" :action "register_resolver_bond"
     :params {:stable 8000 :sew 2000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S39 — DR3 senior coverage delegation at capacity
;;
;; A senior resolver registers with coverage-max=10000.  A junior delegates
;; in two tranches (8000 + 2000) reaching exactly the maximum.  The
;; senior-coverage-not-exceeded? invariant must hold throughout (reserved
;; equals but never exceeds the maximum).
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded?  — reserved grows to exactly coverage-max
;; ---------------------------------------------------------------------------

(def s39
  {:scenario-id     "s39-dr3-senior-coverage-delegation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "senior" :address "0xsenior" :role "resolver"}
                     {:id "junior" :address "0xjunior" :role "resolver"}]
   :protocol-params dr3
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 10000}}
    {:seq 1 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :coverage 8000}}
    {:seq 2 :time 1001 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :coverage 2000}}]})

;; ---------------------------------------------------------------------------
;; S40 — DR3 freeze recorded after fraud slash (no-assignment after freeze)
;;
;; After a fraud slash is executed, the resolver is frozen until
;; block-time + 259200 (72 h).  No new escrow is assigned to the frozen
;; resolver so resolver-not-frozen-on-assign? holds throughout.  The scenario
;; verifies the freeze is correctly recorded and that the invariant passes when
;; the protocol correctly avoids assigning new work to the frozen resolver.
;;
;; Invariants exercised:
;;   resolver-not-frozen-on-assign?  — frozen resolver has no :disputed escrows
;;   slash-status-consistent?        — slash transitions pending → executed
;;   no-auto-fraud-execute?          — slash required explicit post-deadline call
;; ---------------------------------------------------------------------------

(def s40
  {:scenario-id     "s40-dr3-freeze-post-slash"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Settle the escrow first (pending deadline = 1120+120 = 1240 ≤ 1241).
    ;; Once settled, the escrow is no longer :disputed, so executing the slash
    ;; and freezing the resolver does NOT trigger resolver-not-frozen-on-assign?.
    {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; After appeal window (1250): execute slash → resolver frozen until 1255+259200.
    ;; No :disputed escrows remain, so resolver-not-frozen-on-assign? holds.
    {:seq 6 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S41 — DR3 reversal slash disabled (reversal-slash-bps = 0)
;;
;; A challenge escalates a dispute from L0 to L1.  The L1 resolver issues the
;; opposite decision (is-release=false vs L0's is-release=true), triggering
;; the reversal path.  With reversal-slash-bps=0 (v3 default) no slash amount
;; is created, so reversal-slash-disabled? holds throughout.
;;
;; Invariants exercised:
;;   reversal-slash-disabled?    — no reversal slash entry has amount > 0
;;   escalation-level-monotonic? — level grows 0 → 1 after challenge
;;   solvency-holds?             — full lifecycle through L1 decision + settlement
;; ---------------------------------------------------------------------------

(def s41
  {:scenario-id     "s41-dr3-reversal-slash-disabled"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"     :address "0xseller"  :strategy "honest"}
                     {:id "l0"         :address "0xl0"      :role "resolver"}
                     {:id "l1"         :address "0xl1"      :role "resolver"}
                     {:id "challenger" :address "0xchall"   :strategy "honest"}
                     {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros-appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolves with release → pending settlement, deadline = 1120+60 = 1180
    {:seq 2 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash-l0"}}
    ;; Challenger disputes within the window (1130 < 1180) → level 0→1, new resolver = 0xl1
    {:seq 3 :time 1130 :agent "challenger" :action "challenge_resolution"
     :params {:workflow-id 0}}
    ;; L1 resolves with refund (opposite of L0) → reversal path fires with slash-bps=0
    {:seq 4 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash-l1"}}
    ;; Keeper settles after L1 pending deadline (1200+60=1260)
    {:seq 5 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S42 — Resolver-Buyer Bribery Loop (Economic Collusion Stress)
;;
;; Adversarial interaction: A buyer colludes with a resolver. The buyer pays a
;; bribe (simulated as side-payment, outcome: refund) to the resolver to rule 
;; against the seller. The seller then challenges the biased ruling at L1.
;;
;; Sequence:
;;   1. Buyer creates escrow (10000 USDC, kleros-appeal window=60s).
;;   2. Buyer raises dispute (to trigger the bribe loop).
;;   3. L0 resolver (colluder) rules :refund (buyer wins, seller loses).
;;      → pending (deadline = 1120+60 = 1180).
;;   4. Seller (victim) challenges the biased ruling at t=1150.
;;      → challenge bond posted, level 0→1, new-resolver=0xl1.
;;   5. L1 resolver (honest) rules :release (reverses the bribe).
;;      → new pending (deadline = 1200+60 = 1260).
;;   6. Keeper executes after L1 deadline → escrow :released.
;;
;; Expected: PASS — the protocol's L1 challenge mechanism successfully 
;; neutralizes the bribery attempt. The buyer (colluder) loses the escrow 
;; amount they tried to steal via bribe, plus any side-costs.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?    — level advances 0→1
;;   finalization-accounting-correct? — release recorded despite L0 bribe
;;   conservation-of-funds?         — funds are correctly attributed after reversal
;; ---------------------------------------------------------------------------

(def s42
  {:scenario-id     "s42-resolver-buyer-bribery-loop"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "malicious"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver" :strategy "malicious"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules refund (biased, part of bribe loop). Deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xbribe-hash" :adversarial? true}}
    ;; Seller challenges the bias. Level 0→1, new-resolver=0xl1.
    {:seq 3 :time 1150 :agent "seller" :action "challenge_resolution"
     :params {:workflow-id 0}}
    ;; L1 rules release (honest reversal). Deadline = 1200+60 = 1260.
    {:seq 4 :time 1200 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhonest-hash"}}
    ;; Keeper executes after L1 deadline → :released
    {:seq 5 :time 1265 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S45 — Flash-Loan Stake Inflation
;;
;; Adversarial interaction: A resolver temporarily inflates their stake to gain
;; assignment for a high-volume escrow, then immediately withdraws the stake.
;; This bypasses the 'Skin in the Game' requirement for the remainder of the
;; dispute lifecycle.
;;
;; Sequence:
;;   1. Resolver registers a massive stake (100,000 USDC - simulated flash loan).
;;   2. Buyer creates a large escrow (50,000 USDC) assigned to this resolver.
;;      → Assigned because stake (100k) > amount (50k).
;;   3. Resolver immediately withdraws the stake (100,000 USDC).
;;      → Withdrawal successful (vulnerability: no active-assignment lock).
;;   4. Resolver is now assigned to a 50k escrow with 0 stake.
;;   5. Buyer raises dispute. Resolver has no capital at risk if they timeout.
;;
;; Expected: PASS (demonstrates the vulnerability).
;; Invariants exercised:
;;   solvency-holds?           — total-held tracks the withdrawal correctly.
;;   conservation-of-funds?    — funds accounted for after the simulation.
;; ---------------------------------------------------------------------------

(def s45
  {:scenario-id     "s45-flash-loan-stake-inflation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "resolver" :address "0xadv"    :role "resolver" :strategy "malicious"}
                     {:id "buyer"    :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"   :address "0xseller" :strategy "honest"}]
   :protocol-params (assoc dr3 :resolver-bond-bps 1000) ; 10% bond required
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 100000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 50000
              :custom-resolver "0xadv"}}
    ;; Adversary withdraws stake immediately after assignment
    {:seq 2 :time 1001 :agent "resolver" :action "withdraw_stake"
     :params {:amount 100000}}
    ;; Escrow is now live but resolver has 0 stake
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}]})

    ;; ---------------------------------------------------------------------------
    ;; S46 — Reorg Idempotence
    ;;
    ;; Validates that duplicate (replayed) events from divergent reorg branches
    ;; are handled gracefully (idempotence).
    ;; ---------------------------------------------------------------------------

    (def s46
    {:scenario-id     "s46-reorg-idempotence"
    :schema-version  "1.0"
   :scenario-author "@grifma"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"   :address "0xseller"   :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}]
    :protocol-params dr3
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xbranchA"}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xbranchA"}}
    {:seq 4 :time 1180 :agent "buyer" :action "challenge_resolution"
    :params {:workflow-id 0}}
    {:seq 5 :time 1181 :agent "buyer" :action "challenge_resolution"
    :params {:workflow-id 0}}]})

    ;; ---------------------------------------------------------------------------
    ;; S66 — Cooldown Boundary Reorg
    ;;
    ;; Validates that the Layer A Sybil-mitigation cooldown (1 day) is correctly
    ;; enforced across reorgs where timestamps might shift across the boundary.
    ;; ---------------------------------------------------------------------------

    (def s66
    {:scenario-id     "s66-cooldown-boundary-reorg"
    :schema-version  "1.0"
   :scenario-author "@grifma"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"   :address "0xseller"   :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}]
    :protocol-params dr3
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; First escalation (0→1) at T=1130
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
    :params {:workflow-id 0}}
    ;; Second escalation attempt at T=80000 (rejected: < 86400 cooldown)
    {:seq 4 :time 80000 :agent "buyer" :action "escalate_dispute"
    :params {:workflow-id 0}}
    ;; Third escalation attempt at T=90000 (succeeds: > 86400 cooldown since T=1130)
    {:seq 5 :time 90000 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

    ;; ---------------------------------------------------------------------------
    ;; S67 — Reentrancy Callback
    ;;
    ;; Validates that the protocol is resistant to reentrancy during fund withdrawal.
    ;; The execute_reentrant_withdraw action triggers a callback to withdraw_escrow
    ;; BEFORE finalizing its own state change.
    ;;
    ;; Expected: The main action should be REJECTED with :no-claimable-balance
    ;; because the callback (simulating the attacker receiving funds) correctly
    ;; drains the balance first in the chained state.
    ;; ---------------------------------------------------------------------------

    (def s67
    {:scenario-id     "s67-reentrancy-callback"
    :schema-version  "1.0"
   :scenario-author "@grifma"
    :initial-block-time 1000
    :agents          [{:id "attacker" :address "0xattacker" :strategy "malicious"}
                     {:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
    :protocol-params dr3
    ;; The main action is expected to fail because the callback succeeds first.
    :expected-revert? true
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xattacker" :amount 10000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "attacker" :action "execute_reentrant_withdraw"
      :params {:workflow-id 0
               :callback {:agent "attacker" :action "withdraw_escrow" :params {:workflow-id 0}}}}]})
