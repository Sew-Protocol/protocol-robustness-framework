(ns resolver-sim.protocols.sew.invariant-scenarios
  "S01–S23 deterministic invariant scenarios as Clojure data.

   Each entry in `all-scenarios` is a scenario map accepted by
   resolver-sim.protocols.sew/replay-with-sew-protocol.

   Events use direct integer workflow-ids on create_escrow steps.
   :workflow-id aliases (e.g. \"wf0\") on subsequent steps.  The replay
   engine resolves aliases lazily in the event loop.

   Scenarios marked :expected-fail? true are expected to halt on an
   invariant violation — they document known-fixed bugs and regression
   tests.  The invariant runner treats them as passing when the outcome
   is :fail (i.e. the violation fires as expected).")

;; ---------------------------------------------------------------------------
;; Shared protocol-param sets
;; ---------------------------------------------------------------------------

(def ^:private dr3
  {:resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 2592000})

(def ^:private dr3-module
  {:resolver-fee-bps 150 :resolution-module "0xresolver"
   :appeal-window-duration 0 :max-dispute-duration 2592000})

(def ^:private ieo
  {:resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 2592000})

(def ^:private ieo-timeout
  {:resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 300})

(def ^:private timeout
  {:resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 300})

(def ^:private stake-cascade
  ;; Zero fee so AFA = amount; short timeout for quick testing.
  ;; resolver-bond-bps=0 skips the creation-time stake-capacity guard,
  ;; letting us register stake separately and observe it deplete under slashing.
  {:resolver-fee-bps 0 :max-dispute-duration 300
   :dispute-resolver "0xresolver" :resolver-bond-bps 0})

(def ^:private appeal
  {:resolver-fee-bps 150 :appeal-window-duration 120 :max-dispute-duration 600})

(def ^:private kleros
  {:resolver-fee-bps 150
   :resolution-module "0xkleros-proxy"
   :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}
   :appeal-window-duration 0
   :max-dispute-duration 2592000})

(def ^:private kleros-appeal
  {:resolver-fee-bps 150
   :resolution-module "0xkleros-proxy"
   :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}
   :appeal-window-duration 60
   :max-dispute-duration 2592000})

;; ---------------------------------------------------------------------------
;; S01 — baseline happy path
;; ---------------------------------------------------------------------------

(def s01
  {:scenario-id     "s01-baseline-happy-path"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S02 — DR3 dispute → release
;; ---------------------------------------------------------------------------

(def s02
  {:scenario-id     "s02-dr3-dispute-release"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S03 — DR3 dispute → refund
;; ---------------------------------------------------------------------------

(def s03
  {:scenario-id     "s03-dr3-dispute-refund"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S04 — dispute timeout → auto-cancel
;; Dispute raised at t=1060; max-dispute-duration=300 → eligible at t≥1360.
;; ---------------------------------------------------------------------------

(def s04
  {:scenario-id     "s04-dispute-timeout-autocancel"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params timeout
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Early attempt → rejected (240 s elapsed, need 300)
    {:seq 2 :time 1300 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; After timeout (1360 − 1060 = 300 ≥ 300) → ok
    {:seq 3 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S05 — pending settlement → execute
;; Resolution at t=1120; appeal-deadline = 1120+120 = 1240.
;; ---------------------------------------------------------------------------

(def s05
  {:scenario-id     "s05-pending-settlement-execute"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver votes; creates pending-settlement (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Early → rejected (appeal window still open)
    {:seq 3 :time 1180 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; At deadline → ok
    {:seq 4 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S06 — mutual cancel
;; ---------------------------------------------------------------------------

(def s06
  {:scenario-id     "s06-mutual-cancel"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    {:seq 1 :time 1060 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S07 — unauthorised resolver rejected; authorised resolver succeeds
;; ---------------------------------------------------------------------------

(def s07
  {:scenario-id     "s07-unauthorized-resolver-rejected"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"      :address "0xseller"      :strategy "honest"}
                     {:id "badresolver" :address "0xbadresolver" :strategy "malicious" :role "resolver"}
                     {:id "resolver"    :address "0xresolver"    :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Wrong resolver → rejected
    {:seq 2 :time 1120 :agent "badresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xfake"}}
    ;; Authorised resolver → ok
    {:seq 3 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}]})

;; ---------------------------------------------------------------------------
;; S08 — state machine attack gauntlet
;; Every invalid transition is attempted; all are rejected; no violations fire.
;; ---------------------------------------------------------------------------

(def s08
  {:scenario-id     "s08-state-machine-attack-gauntlet"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :custom-resolver "0xresolver"}}
    ;; Execute-resolution before dispute → rejected
    {:seq 1 :time 1060 :agent "buyer" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xh"}}
    ;; Raise dispute → ok
    {:seq 2 :time 1120 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Release while disputed → rejected
    {:seq 3 :time 1180 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    ;; Double-dispute → rejected
    {:seq 4 :time 1240 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Authorised resolver resolves → ok (no appeal window → finalized immediately)
    {:seq 5 :time 1300 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}
    ;; Resolve on terminal state → rejected
    {:seq 6 :time 1360 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xdup"}}
    ;; Dispute on terminal state → rejected
    {:seq 7 :time 1420 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Release on terminal state → rejected
    {:seq 8 :time 1480 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S09 — multi-escrow solvency (3 concurrent escrows, mixed outcomes)
;; wf0: direct release; wf1/wf2: disputed and resolved.
;; ---------------------------------------------------------------------------

(def s09
  {:scenario-id     "s09-multi-escrow-solvency"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer0"   :address "0xbuyer0"   :strategy "honest"}
                     {:id "buyer1"   :address "0xbuyer1"   :strategy "honest"}
                     {:id "buyer2"   :address "0xbuyer2"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000}}
    {:seq 1 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1000 :agent "buyer2" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 3 :time 1060 :agent "buyer0" :action "release"
     :params {:workflow-id 0}}
    {:seq 4 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 5 :time 1060 :agent "buyer2" :action "raise_dispute"
     :params {:workflow-id 2}}
    {:seq 6 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xh1"}}
    {:seq 7 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xh2"}}]})

;; ---------------------------------------------------------------------------
;; S10 — double-finalize rejected
;; wf0: release × 3 (2 rejected); wf1: dispute + resolve × 2 (1 rejected).
;; ---------------------------------------------------------------------------

(def s10
  {:scenario-id     "s10-double-finalize-rejected"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 500}}
    ;; resolver acts as buyer for wf1 (creates to seller2, sets itself as resolver)
    {:seq 1 :time 1000 :agent "resolver" :action "create_escrow"
     :params {:token "USDC" :to "0xseller2" :amount 500
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    {:seq 3 :time 1060 :agent "resolver" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xok"}}
    ;; Double-release → rejected (wf0 is :released terminal)
    {:seq 5 :time 1180 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    {:seq 6 :time 1240 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    ;; Double-resolve → rejected (wf1 is :released terminal)
    {:seq 7 :time 1300 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xdup"}}]})

;; ---------------------------------------------------------------------------
;; S11 — zero fee edge case
;; ---------------------------------------------------------------------------

(def s11
  {:scenario-id     "s11-zero-fee-edge-case"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params {:resolver-fee-bps 0 :appeal-window-duration 0
                     :max-dispute-duration 2592000}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S12 — governance snapshot isolation
;; Two independent sessions with different fee_bps must not cross-contaminate.
;; Defined as two scenarios; the runner reports them jointly as S12.
;; ---------------------------------------------------------------------------

(def s12a
  {:scenario-id     "s12a-snapshot-isolation-fee-zero"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 0}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

(def s12b
  {:scenario-id     "s12b-snapshot-isolation-fee-500"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 500}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S13 — pending settlement → refund
;; Same as S05 but resolver votes is-release=false.
;; ---------------------------------------------------------------------------

(def s13
  {:scenario-id     "s13-pending-settlement-refund"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Refund vote; creates pending-settlement (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    ;; Early → rejected
    {:seq 3 :time 1180 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; At deadline → ok (finalizes as :refunded)
    {:seq 4 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S14 — DR3 module (Priority 2): authorised resolver succeeds
;; No custom-resolver; authority falls through to the resolution module.
;; ---------------------------------------------------------------------------

(def s14
  {:scenario-id     "s14-dr3-module-authorized"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3-module
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S15 — DR3 module: unauthorised resolver rejected; authorised succeeds
;; ---------------------------------------------------------------------------

(def s15
  {:scenario-id     "s15-dr3-module-unauthorized-rejected"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"      :address "0xseller"      :strategy "honest"}
                     {:id "badresolver" :address "0xbadresolver" :strategy "malicious" :role "resolver"}
                     {:id "resolver"    :address "0xresolver"    :role "resolver"}]
   :protocol-params dr3-module
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Module rejects 0xbadresolver → revert
    {:seq 2 :time 1120 :agent "badresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xfake"}}
    ;; Module authorizes 0xresolver → ok
    {:seq 3 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}]})

;; ---------------------------------------------------------------------------
;; S16 — IEO base: create + release (no resolver, zero fee)
;; ---------------------------------------------------------------------------

(def s16
  {:scenario-id     "s16-ieo-create-release"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params ieo
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S17 — IEO: dispute with no resolver → auto-cancel on timeout
;; Dispute at t=1060; max-dispute-duration=300 → eligible at t≥1360.
;; ---------------------------------------------------------------------------

(def s17
  {:scenario-id     "s17-ieo-dispute-no-resolver-timeout"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "resolver"}]
   :protocol-params ieo-timeout
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Early → rejected
    {:seq 2 :time 1300 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; Timeout elapsed → ok
    {:seq 3 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S18 — DR3 Kleros: L0 resolver resolves at level 0
;; ---------------------------------------------------------------------------

(def s18
  {:scenario-id     "s18-dr3-kleros-l0-resolves"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}]
   :protocol-params kleros
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Kleros module authorizes 0xl0 at level 0 → finalized immediately
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S19 — DR3 Kleros: preemptive escalation rejected; L0 resolves
;; No appeal window → no pending settlement → escalation reverts before l0 acts.
;; The escalation attempt is rejected (:no-resolution-to-appeal); l0 then resolves.
;; ---------------------------------------------------------------------------

(def s19
  {:scenario-id     "s19-dr3-kleros-escalation-rejected-l0-resolves"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}]
   :protocol-params kleros
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Escalation before any resolution → rejected (:no-resolution-to-appeal)
    {:seq 2 :time 1060 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolver resolves immediately (no appeal window)
    {:seq 3 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; L1 tries on terminal state → rejected
    {:seq 4 :time 1180 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}]})

;; ---------------------------------------------------------------------------
;; S20 — DR3 Kleros: max-escalation guard
;; All escalation attempts rejected (no pending settlement in no-appeal-window mode).
;; L2 resolver also rejected (level 0 authorizes 0xl0, not 0xl2).
;; Dispute unresolved at end — no invariant violations.
;; ---------------------------------------------------------------------------

(def s20
  {:scenario-id          "s20-dr3-kleros-max-escalation-guard"
   :schema-version       "1.0"
   :allow-open-disputes? true ; intentionally tests guard logic; dispute is never resolved
   :initial-block-time   1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l2resolver" :address "0xl2"     :role "resolver"}]
   :protocol-params kleros
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Three escalation attempts, all rejected (:no-resolution-to-appeal)
    {:seq 2 :time 1060 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1180 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L2 tries to resolve at level 0 → rejected (module authorizes 0xl0 not 0xl2)
    {:seq 5 :time 1240 :agent "l2resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl2hash"}}]})

;; ---------------------------------------------------------------------------
;; S21 — DR3 Kleros: pending settlement cleared on escalation
;; Requires appeal window so a pending settlement exists for escalation to clear.
;; Sequence: L0 resolves → pending; buyer escalates (clears pending, level→1);
;;           L1 resolves → pending; execute_pending_settlement after deadline.
;; ---------------------------------------------------------------------------

(def s21
  {:scenario-id     "s21-dr3-kleros-pending-cleared-on-escalation"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "resolver"}]
   :protocol-params kleros-appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolves → creates pending (deadline = 1120+60 = 1180)
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates before deadline → clears pending, level→1, new-resolver=0xl1
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 resolves → new pending (deadline = 1190+60 = 1250)
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Keeper executes after deadline → ok
    {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S22 — REGRESSION: transition-to-disputed clears counterparty agree-to-cancel
;; Bug: raise_dispute was not clearing recipient's :agree-to-cancel status.
;; Fix: transition-to-disputed now resets counterparty status to :none.
;; Expected: PASS (no invariant violation with the fix applied).
;; ---------------------------------------------------------------------------

(def s22
  {:scenario-id          "s22-status-leak-agree-cancel-over-dispute"
   :schema-version       "1.0"
   :allow-open-disputes? true ; intentionally stops after raising the dispute (regression test for status clearing)
   :initial-block-time   1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    ;; Seller agrees to cancel (sets recipient :agree-to-cancel, state stays :pending)
    {:seq 1 :time 1000 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}
    ;; Buyer raises dispute — must clear seller's :agree-to-cancel status
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S23 — REGRESSION: preemptive escalation rejected (no pending settlement)
;; Bug: escalate_dispute had no pending-settlement guard → buyer could skip levels.
;; Fix: escalation now requires a pending settlement (it is an appeal, not a skip).
;; Sequence: buyer disputes; seller tries to escalate (both attempts rejected);
;;           L0 resolver resolves immediately.
;; Expected: PASS (both seller escalations rejected without violation).
;; ---------------------------------------------------------------------------

(def s23
  {:scenario-id     "s23-preemptive-escalation-blocked"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}]
   :protocol-params kleros
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Seller attempts preemptive escalation → rejected (:no-resolution-to-appeal)
    {:seq 2 :time 1060 :agent "seller" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolver resolves immediately (no appeal window)
    {:seq 3 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Seller tries again on terminal state → rejected (:transfer-not-in-dispute)
    {:seq 4 :time 1180 :agent "seller" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S24 — resolver stake depletion cascade
;;
;; One resolver (stake=3000) is assigned to three concurrent escrows (AFA=2000 each).
;; All three disputes timeout. Each auto-cancel slashes the resolver's remaining stake:
;;   wf0: slashes 2000 → stake 3000→1000  (full slash)
;;   wf1: slashes 2000 → stake 1000→0     (partial: only 1000 available)
;;   wf2: slashes 2000 → stake 0→0        (zero: nothing left to slash)
;;
;; Exercises:
;;   - solvency-holds? at each step (strict = on total-held vs live-sum)
;;   - conservation-of-funds? after all three auto-cancels
;;     (0 held + 0 released + 6000 refunded = 6000 deposited)
;;   - held-non-negative? / sub-held assert survives all three finalizations
;;   - slash-resolver-stake saturation: min(stake, slash) never goes negative
;; ---------------------------------------------------------------------------

(def s24
  {:scenario-id        "s24-resolver-stake-depletion-cascade"
   :schema-version     "1.0"
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

     ;; ---------------------------------------------------------------------------
     ;; S68 — Yield Pool Insolvency
     ;;
     ;; Validates that the protocol correctly handles external liquidity shortages.
     ;; A 'liquidity crunch' is triggered for USDC, and a subsequent withdrawal
     ;; attempt is rejected.
     ;;
     ;; Expected: The withdrawal action should be REJECTED with :liquidity-insufficient.
     ;; ---------------------------------------------------------------------------

     (def s68
     {:scenario-id     "s68-yield-pool-insolvency"
     :schema-version  "1.0"
     :initial-block-time 1000
     :agents          [{:id "buyer"    :address "0xbuyer"  :strategy "honest"}
                      {:id "seller"   :address "0xseller" :strategy "honest"}
                      {:id "governance" :address "0xgov"  :role "governance"}]
     :protocol-params dr3
     :events
     [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 5000}}
     {:seq 1 :time 1060 :agent "buyer" :action "release"
      :params {:workflow-id 0}}
     ;; Trigger liquidity crunch for USDC
     {:seq 2 :time 1100 :agent "governance" :action "set_token_liquidity_crunch"
      :params {:token "USDC" :active? true}}
     ;; Attempt withdrawal while crunch is active
     {:seq 3 :time 1120 :agent "seller" :action "withdraw_escrow"
      :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S49 — B2 Appeal window persistence
;; Test that appeal window is enforced when escalating.
;; Sequence: L0 resolves → pending (60s window) → L1 escalates within window.
;; Expected: Escalation accepted, pending settlement cleared, level advanced.
;; ---------------------------------------------------------------------------

(def s49
  {:scenario-id     "s49-appeal-deadline-boundary"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                    {:id "seller"     :address "0xseller" :strategy "honest"}
                    {:id "l0resolver" :address "0xl0"     :role "resolver"}
                    {:id "l1resolver" :address "0xl1"     :role "resolver"}
                    {:id "keeper"     :address "0xkeeper" :role "resolver"}]
   :protocol-params kleros-appeal
   :notes "Validate appeal window boundary enforcement. L0 resolves at t=1120 (deadline=1180), buyer escalates at t=1150 (within 60s window) successfully. L1 then resolves, and settlement executes after deadline."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolves → creates pending (deadline = 1120+60 = 1180)
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates at t=1150 (within 60s appeal window) → accepted
    {:seq 3 :time 1150 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 resolves → new pending (deadline = 1210+60 = 1270)
    {:seq 4 :time 1210 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Keeper executes settlement after deadline
    {:seq 5 :time 1270 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S50 — D1 False assertion unchallenged (monitoring assumption validation)
;; Resolver makes clearly wrong decision (refunds when release is correct).
;; No one challenges or appeals → bad resolution finalizes.
;; Purpose: Validate that protocol DETECTS if monitoring assumption fails.
;; ---------------------------------------------------------------------------

(def s50
  {:scenario-id     "s50-false-assertion-unchallenged"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"   :address "0xseller"   :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver" :strategy "malicious"}]
   :protocol-params appeal
   :expected-fail? false
   :notes "This is NOT a protocol failure; it demonstrates the monitoring assumption: if no watchdog challenges a malicious resolution, the bad outcome settles. The scenario outcome is 'pass' iff all invariants hold even under malicious settlement."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    ;; Buyer then raises dispute claiming refund (lies)
    {:seq 2 :time 1080 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Malicious resolver sides with buyer (refund), even though release already occurred
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    ;; Appeal window closes; no one escalates
    {:seq 4 :time 1240 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S51 — D7 Same-block challenge/finalize race
;; Appeal window just closing; two txs in same block.
;; One finalizes, one escalates → deterministic ordering.
;; ---------------------------------------------------------------------------

(def s51
  {:scenario-id     "s51-same-block-challenge-finalize-race"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"   :address "0xseller"   :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "executor" :address "0xexecutor" :role "keeper"}
                    {:id "appealer" :address "0xappealer" :role "keeper"}]
   :protocol-params appeal
   :notes "Same-block ordering: executor and appealer both act at t=1240 (deadline). Deterministic ordering ensures one succeeds and other fails."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver decides at t=1120 → appeal deadline = 1240
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; At t=1240, two transactions in same block:
    ;; First: finalize (succeeds, locks settlement)
    {:seq 3 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Second: escalate (fails, appeal window closed and finalized)
    {:seq 4 :time 1240 :agent "appealer" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S52 — I4 Yield accrued during dispute
;; Escrow earning yield while disputed; yield should be distributed per outcome.
;; ---------------------------------------------------------------------------

(def s52
  {:scenario-id     "s52-yield-accrued-during-dispute"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"   :address "0xseller"   :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params appeal
   :notes "Escrow principal: 5000 USDC. Yield accrues at 0.1%/block. During dispute (1060–1120 = 60 blocks), yield ≈ 3 USDC. On release, seller receives principal + yield."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"
             :yield-rate 0.001}}
    ;; Yield accrues from t=1000 → t=1060 (60 blocks, 0.06% yield → ~3 USDC)
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; More yield accrues during dispute (t=1060 → t=1120)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Execute settlement
    {:seq 3 :time 1240 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Seller withdraws: principal (5000) + yield (≥3)
    {:seq 4 :time 1250 :agent "seller" :action "withdraw_escrow"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S53 — I2 Reentrant withdrawal guard
;; Buyer and seller attempt to withdraw the same escrow simultaneously
;; Expected: Only one succeeds; second is rejected (already-withdrawn or similar)
;; Validates: Atomicity of settlement ledger; no double-payout
;; ---------------------------------------------------------------------------

(def s53
  {:scenario-id     "s53-reentrant-withdrawal-guard"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"   :address "0xseller"   :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :notes "Settlement ledger isolation: buyer and seller attempt withdrawal at same time. One succeeds; second is rejected to prevent ledger duplication."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    ;; Resolver decides → pending (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Keeper executes settlement after deadline
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    ;; Buyer withdraws (succeeds)
    {:seq 4 :time 1250 :agent "buyer" :action "withdraw_escrow"
    :params {:workflow-id 0}}
    ;; Seller attempts withdrawal at same time (rejected—already withdrawn)
    {:seq 5 :time 1250 :agent "seller" :action "withdraw_escrow"
    :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S54 — I6 Multi-claim ledger isolation
;; Two parallel escrows settled by same parties; verify claims stay isolated
;; Expected: Each party gets correct escrow amounts; no ledger bleed
;; Validates: Claim isolation; per-escrow accounting
;; ---------------------------------------------------------------------------

(def s54
  {:scenario-id     "s54-multi-claim-ledger-isolation"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer1"    :address "0xbuyer1"   :strategy "honest"}
                    {:id "buyer2"    :address "0xbuyer2"   :strategy "honest"}
                    {:id "seller"    :address "0xseller"   :strategy "honest"}
                    {:id "resolver"  :address "0xresolver" :role "resolver"}
                    {:id "keeper"    :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :notes "Two independent escrows (wf0=5000, wf1=3000). First disputed and settled; second released. Tests that ledger isolation holds."
   :events
   [{:seq 0 :time 1000 :agent "buyer1" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1000 :agent "buyer2" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 3000
             :custom-resolver "0xresolver"}}
    {:seq 2 :time 1020 :agent "buyer2" :action "release"
    :params {:workflow-id 1}}
    {:seq 3 :time 1060 :agent "buyer1" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 5 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s55
  {:scenario-id     "s55-resolver-unavailable-timeout-fallback"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver becomes paused during dispute. Tests fallback to timeout if resolver unavailable. Dispute should auto-cancel at timeout."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1100 :agent "keeper" :action "set_paused"
    :params {:paused? true}}
    {:seq 3 :time 2100 :agent "keeper" :action "auto_cancel_disputed"
    :params {:workflow-id 0}}]})

(def s56
  {:scenario-id     "s56-resolver-diversity"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"     :address "0xbuyer"     :strategy "honest"}
                    {:id "seller"    :address "0xseller"    :strategy "honest"}
                    {:id "resolver1" :address "0xresolver1" :role "resolver"}
                    {:id "keeper"    :address "0xkeeper"    :role "keeper"}]
   :protocol-params appeal
   :notes "Tests standard dispute resolution flow with no special governance actions. Baseline for resolver authority tests."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver1"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver1" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s57
  {:scenario-id     "s57-corruption-cost-vs-profit"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"     :address "0xbuyer"    :strategy "honest"}
                    {:id "seller"    :address "0xseller"   :strategy "honest"}
                    {:id "attacker"  :address "0xattacker" :strategy "profit-maximizer"}
                    {:id "resolver"  :address "0xresolver" :role "resolver"}
                    {:id "keeper"    :address "0xkeeper"   :role "keeper"}]
   :protocol-params dr3
   :notes "Profit-maximizer corrupts resolution to capture full escrow value. Tests cost-of-corruption analysis. Attacker uses external bribe (epsilon)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xattacker" :amount 10000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 4 :time 1250 :agent "attacker" :action "withdraw_escrow"
    :params {:workflow-id 0}}]})

(def s58
  {:scenario-id     "s58-watchdog-valid-challenge"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog challenges a bad resolution. Tests challenge_resolution and bounty allocation. Validator accepts challenge."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release false :resolution-hash "0xbad_hash"}}
    {:seq 3 :time 1130 :agent "watchdog" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}
    {:seq 4 :time 1200 :agent "keeper" :action "resolve_appeal"
    :params {:workflow-id 0 :appeal-winner "watchdog"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s59
  {:scenario-id     "s59-watchdog-false-challenge-loss"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "watchdog" :address "0xwatchdog" :strategy "profit-maximizer"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog falsely challenges a valid resolution. Tests that false challenger loses bond. Validator rejects challenge."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xcorrect_hash"}}
    {:seq 3 :time 1130 :agent "watchdog" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xfalse_evidence"}}
    {:seq 4 :time 1200 :agent "keeper" :action "resolve_appeal"
    :params {:workflow-id 0 :appeal-winner "resolver"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s60
  {:scenario-id     "s60-resolver-abstention-timeout-griefing"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :strategy "profit-maximizer"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver abstains from resolving (doesn't call execute_resolution). Dispute times out and auto-cancels. Tests resolver timeout penalties."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 2100 :agent "keeper" :action "auto_cancel_disputed"
    :params {:workflow-id 0}}]})

(def s61
  {:scenario-id     "s61-fee-on-transfer-token-handling"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Tests settlement with a fee-on-transfer token (e.g., USDT variants). Ensures ledger accounts for transfer fees."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDT_FEE" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s62
  {:scenario-id     "s62-multi-appeal-escalation-chain"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros
   :notes "Tests multi-level appeal chain: resolution → challenge → escalation. Tests Kleros-style jurisdiction transitions."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    {:seq 3 :time 1130 :agent "watchdog" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}
    {:seq 4 :time 1140 :agent "buyer" :action "escalate_dispute"
    :params {:workflow-id 0}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

;; Additional under-dispute-load scenarios to explicitly cover cross-token isolation and stress conditions.
(def s62-cross-token-isolation-under-dispute-load
  {:scenario-id "s62-cross-token-isolation-under-dispute-load"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "buyer1" :address "0xbuyer1" :strategy "honest"}
            {:id "seller1" :address "0xseller1" :strategy "honest"}
            {:id "buyer2" :address "0xbuyer2" :strategy "honest"}
            {:id "seller2" :address "0xseller2" :strategy "honest"}
            {:id "buyer3" :address "0xbuyer3" :strategy "honest"}
            {:id "seller3" :address "0xseller3" :strategy "honest"}
            {:id "resolver" :address "0xresolver" :role "resolver"}
            {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
            {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros
   :notes "Stress test: concurrent disputes across multiple tokens ensure no balance or state leakage between escrows under high dispute activity. Developers: check ledger isolation, accounting, and resolver routing under concurrent appeals."
   :events
   [;; create three escrows on different tokens
    {:seq 0 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller1" :amount 2000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1002 :agent "buyer2" :action "create_escrow"
     :params {:token "DAI" :to "0xseller2" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 2 :time 1004 :agent "buyer3" :action "create_escrow"
     :params {:token "USDT_FEE" :to "0xseller3" :amount 2500 :custom-resolver "0xresolver"}}

    ;; raise disputes concurrently
    {:seq 3 :time 1060 :agent "buyer1" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1061 :agent "buyer2" :action "raise_dispute" :params {:workflow-id 1}}
    {:seq 5 :time 1062 :agent "buyer3" :action "raise_dispute" :params {:workflow-id 2}}

    ;; resolver processes resolutions in interleaved order
    {:seq 6 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 7 :time 1122 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash0"}}

    ;; watchdog challenges one, buyer escalates another, keeper settles others
    {:seq 8 :time 1130 :agent "watchdog" :action "challenge_resolution" :params {:workflow-id 0 :evidence-hash "0xchall_e0"}}
    {:seq 9 :time 1140 :agent "buyer3" :action "escalate_dispute" :params {:workflow-id 2}}
    {:seq 10 :time 1300 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 11 :time 1310 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}
    {:seq 12 :time 1320 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 2}}]})

(def s62-cross-token-fee-on-transfer-under-dispute-load
  {:scenario-id "s62-cross-token-fee-on-transfer-under-dispute-load"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "buyerA" :address "0xbuyerA" :strategy "honest"}
            {:id "sellerA" :address "0xsellerA" :strategy "honest"}
            {:id "buyerB" :address "0xbuyerB" :strategy "honest"}
            {:id "sellerB" :address "0xsellerB" :strategy "honest"}
            {:id "resolver" :address "0xresolver" :role "resolver"}
            {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros
   :notes "Under-dispute-load variant that mixes fee-on-transfer tokens (e.g., USDT_FEE) with normal tokens to verify ledger accounting under concurrent appeal activity. Developers: verify fee deductions, net recipient balances, and that fee logic is escrow-local."
   :events
   [;; create two escrows, one fee-on-transfer token and one normal token
    {:seq 0 :time 1000 :agent "buyerA" :action "create_escrow"
     :params {:token "USDT_FEE" :to "0xsellerA" :amount 5000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1005 :agent "buyerB" :action "create_escrow"
     :params {:token "USDC" :to "0xsellerB" :amount 4000 :custom-resolver "0xresolver"}}

    ;; both raise disputes in quick succession
    {:seq 2 :time 1060 :agent "buyerA" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 3 :time 1061 :agent "buyerB" :action "raise_dispute" :params {:workflow-id 1}}

    ;; resolver resolves wfB immediately, wfA goes through challenge and appeal
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution" :params {:workflow-id 1 :is-release true :resolution-hash "0xhashB"}}
    {:seq 5 :time 1130 :agent "watchdog" :action "challenge_resolution" :params {:workflow-id 0 :evidence-hash "0xchallA"}}
    {:seq 6 :time 1140 :agent "buyerA" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 7 :time 1300 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 8 :time 1310 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}]})

(def s62-cross-token-parallel-appeal-depths-under-dispute-load
  {:scenario-id "s62-cross-token-parallel-appeal-depths-under-dispute-load"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "buyerX" :address "0xbuyerX" :strategy "honest"}
            {:id "sellerX" :address "0xsellerX" :strategy "honest"}
            {:id "buyerY" :address "0xbuyerY" :strategy "honest"}
            {:id "sellerY" :address "0xsellerY" :strategy "honest"}
            {:id "resolver-l1" :address "0xresolverL1" :role "resolver"}
            {:id "resolver-l2" :address "0xresolverL2" :role "resolver"}
            {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros
   :notes "Simultaneous disputes with differing appeal depths to ensure escalation paths remain escrow-local and do not interfere when several appeal-chains are active. Developers: verify jurisdiction/state transitions remain scoped to the escrow."
   :events
   [;; create two escrows
    {:seq 0 :time 1000 :agent "buyerX" :action "create_escrow" :params {:token "USDC" :to "0xsellerX" :amount 6000 :custom-resolver "0xresolverL1"}}
    {:seq 1 :time 1002 :agent "buyerY" :action "create_escrow" :params {:token "DAI" :to "0xsellerY" :amount 3500 :custom-resolver "0xresolverL1"}}

    ;; both raise disputes
    {:seq 2 :time 1060 :agent "buyerX" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 3 :time 1061 :agent "buyerY" :action "raise_dispute" :params {:workflow-id 1}}

    ;; wfx goes through multiple appeals (deeper chain), wfy resolves quickly
    {:seq 4 :time 1120 :agent "resolver-l1" :action "execute_resolution" :params {:workflow-id 1 :is-release true :resolution-hash "0xhashY"}}
    {:seq 5 :time 1130 :agent "buyerX" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 6 :time 1200 :agent "resolver-l2" :action "execute_resolution" :params {:workflow-id 0 :is-release false :resolution-hash "0xhashX"}}
    {:seq 7 :time 1300 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 8 :time 1310 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}]})

(def s63
  {:scenario-id     "s63-frivolous-appeal-slashing"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "profit-maximizer"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Buyer appeals a correct resolution (frivolous appeal). Tests that frivolous appeals lose bond. Models UX fairness / access-to-justice constraint."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xcorrect_hash"}}
    {:seq 3 :time 1130 :agent "buyer" :action "appeal_slash"
    :params {:workflow-id 0 :appeal-reason "frivolous"}}
    {:seq 4 :time 1300 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s64
  {:scenario-id     "s64-minimal-bond-edge-case"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Escrow with minimal allowed bond. Tests bond constraints don't break resolution. Models F1: bond sensitivity."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 100
             :custom-resolver "0xresolver" :bond-amount 10}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s65
  {:scenario-id     "s65-appeal-after-settlement-rejected"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Buyer attempts to appeal after pending settlement has already executed. Tests that post-settlement appeals are rejected. Models B3: deadline edge cases."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 4 :time 1250 :agent "buyer" :action "appeal_slash"
    :params {:workflow-id 0 :appeal-reason "late"}}]})

(def s68
  {:scenario-id     "s68-double-settlement-guard"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Execute pending settlement twice on same escrow. Tests idempotence and double-settlement guard. Second attempt should be rejected."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s69
  {:scenario-id     "s69-stale-dispute-cleanup"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Dispute created then left unresolved beyond appeal window. Tests stale dispute cleanup and state recovery."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 2000 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s70
  {:scenario-id     "s70-large-escrow-resolution"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Large-value escrow with significant fees. Tests settlement accuracy at scale and fee precision."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 1000000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s71
  {:scenario-id     "s71-zero-fee-settlement"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Escrow with zero fee. Tests settlement with no fee extraction. Edge case for financial calculations."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver" :fee-amount 0}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s72
  {:scenario-id     "s72-challenge-during-appeal-window"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog challenges during open appeal window. Tests that challenges within deadline are accepted."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    {:seq 3 :time 1200 :agent "watchdog" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}
    {:seq 4 :time 1250 :agent "keeper" :action "resolve_appeal"
    :params {:workflow-id 0 :appeal-winner "watchdog"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s73
  {:scenario-id     "s73-challenge-after-appeal-window-closed"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog attempts to challenge after appeal window has closed. Challenge should be rejected (E3)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 4 :time 1260 :agent "watchdog" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}]})

(def s74
  {:scenario-id     "s74-multi-escrow-parallel-disputes"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller1"  :address "0xseller1"  :strategy "honest"}
                    {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Two sequential escrows, both with disputes and resolutions. Tests resolver handling of multiple workflows. First escrow fully settled before second escrow disputed."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller1" :amount 2000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 4 :time 1300 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller2" :amount 3000
             :custom-resolver "0xresolver"}}
    {:seq 5 :time 1360 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 1}}
    {:seq 6 :time 1420 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 7 :time 1560 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 1}}]})

(def s75
  {:scenario-id     "s75-receiver-cancels-after-dispute"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "After dispute raised, receiver attempts to cancel escrow. Tests authorization: can receiver override dispute?"
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1080 :agent "seller" :action "recipient_cancel"
    :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 4 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s76
  {:scenario-id     "s76-sender-cancel-during-appeal"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Sender attempts to cancel during appeal window. Tests that cancel is blocked during appeal period."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1200 :agent "buyer" :action "sender_cancel"
    :params {:workflow-id 0}}
    {:seq 4 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s77
  {:scenario-id     "s77-escalation-rejected-wrong-layer"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params ieo
   :notes "Escalate in a protocol (IEO) that doesn't support escalation. Tests layer enforcement (E4)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
    :params {:workflow-id 0}}
    {:seq 4 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s78
  {:scenario-id     "s78-many-appeals-eventually-rejects"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "profit-maximizer"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros
   :notes "Buyer appeals multiple times, each time losing. Tests that repeated frivolous appeals accumulate losses."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1140 :agent "buyer" :action "appeal_slash"
    :params {:workflow-id 0 :appeal-reason "wrong-outcome"}}
    {:seq 4 :time 1250 :agent "buyer" :action "appeal_slash"
    :params {:workflow-id 0 :appeal-reason "unfair"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s79
  {:scenario-id     "s79-partial-resolution-release"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver issues partial release (not full refund/full release). Tests settlement flexibility."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xpartial" :amount-released 5000}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s80
  {:scenario-id     "s80-disputed-escrow-expiry-settlement"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Escrow with expiry during dispute. Tests settlement after escrow-level deadline."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver" :expiry-time 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1500 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s81
  {:scenario-id     "s81-appeal-deadline-boundary-before"
   :schema-version  "1.0"
   :initial-block-time 1000
   :expected-fail?   true
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Boundary test: execute settlement 1 second before appeal deadline (should reject)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1239 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s82
  {:scenario-id     "s82-appeal-deadline-boundary-exact"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Boundary test: execute settlement exactly at appeal deadline (should succeed)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s83
  {:scenario-id     "s83-appeal-deadline-boundary-after"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Boundary test: execute settlement 1 second after appeal deadline (should succeed)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1241 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s84
  {:scenario-id     "s84-false-assertion-unchallenged"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Honest resolver accepts a dispute resolution without challenge. Nobody disputes the resolution. Tests assumption: honest majority prevents wrong resolutions."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash_accepted"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s85
  {:scenario-id     "s85-watchdog-challenges-resolution"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog observes resolution and challenges it with evidence. Tests watchdog challenge flow."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release false :resolution-hash "0xwrong_call"}}
    {:seq 3 :time 1150 :agent "watchdog" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xcorrect_evidence"}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s86
  {:scenario-id     "s86-reentrant-withdrawal-guard"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Tests that settlement guards against reentrant calls. Escrow reaches terminal state atomically."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 4 :time 1260 :agent "seller" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s87
  {:scenario-id     "s87-cross-escrow-isolation"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller1"  :address "0xseller1"  :strategy "honest"}
                    {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Two escrows: one disputed, one released. Ensures settlement of disputed escrow doesn't affect un-disputed."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller1" :amount 3000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1020 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller2" :amount 2000
             :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 3 :time 1100 :agent "seller2" :action "recipient_release"
    :params {:workflow-id 1}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s88
  {:scenario-id     "s88-resolution-with-conflicting-evidence"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Dispute with multiple evidence entries. Resolver picks one resolution. Tests evidence handling in resolution."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1090 :agent "seller" :action "challenge_resolution"
    :params {:workflow-id 0 :evidence-hash "0xseller_evidence"}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release false :resolution-hash "0xresolver_finds_buyer_right"}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s89
  {:scenario-id     "s89-dispute-resolution-with-zero-appeal-window"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller"   :address "0xseller"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros
   :notes "Resolution in kleros mode (zero appeal window). Settlement executes immediately. Tests fast-path resolution."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000
             :custom-resolver "0xresolver" :resolution-module "0xkleros-proxy"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1121 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}]})

(def s90
  {:scenario-id     "s90-resolver-capacity-stress"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                    {:id "seller1"  :address "0xseller1"  :strategy "honest"}
                    {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                    {:id "seller3"  :address "0xseller3"  :strategy "honest"}
                    {:id "resolver" :address "0xresolver" :role "resolver"}
                    {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver handles 3 large escrows in dispute simultaneously. Tests capacity under load."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller1" :amount 50000
             :custom-resolver "0xresolver"}}
    {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller2" :amount 40000
             :custom-resolver "0xresolver"}}
    {:seq 2 :time 1020 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller3" :amount 30000
             :custom-resolver "0xresolver"}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 0}}
    {:seq 4 :time 1070 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 1}}
    {:seq 5 :time 1080 :agent "buyer" :action "raise_dispute"
    :params {:workflow-id 2}}
    {:seq 6 :time 1140 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 7 :time 1145 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 8 :time 1150 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 2 :is-release true :resolution-hash "0xhash2"}}
    {:seq 9 :time 1260 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 0}}
    {:seq 10 :time 1265 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 1}}
    {:seq 11 :time 1270 :agent "keeper" :action "execute_pending_settlement"
    :params {:workflow-id 2}}]})

(def s91
   {:scenario-id     "s91-governance-snapshot-dispute-state"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}
                     {:id "gov"      :address "0xgov"      :role "governance"}]
    :protocol-params appeal
    :notes "Dispute created, then governance changes fee params. Dispute outcome unaffected by governance change. Tests snapshot isolation."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1080 :agent "gov" :action "set-fee-bps"
     :params {:new-fee-bps 500}}
     {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; S92: Settlement with zero-amount escrow
(def s92
   {:scenario-id     "s92-settlement-zero-amount-edge"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Escrow with 1 USDC (minimum). Settlement of tiny amount. Tests ledger precision."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; S93: Multiple appeals with refund resolution
(def s93
   {:scenario-id     "s93-multiple-appeals-with-refund"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Resolution is refund (not release). Followed by appeals and appeals. Tests refund path with appeals."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xrefund_hash"}}
     {:seq 3 :time 1150 :agent "seller" :action "resolve_appeal"
     :params {:workflow-id 0 :appeal-winner "resolver"}}
     {:seq 4 :time 1200 :agent "buyer" :action "appeal_slash"
     :params {:workflow-id 0 :appeal-reason "wrong"}}
     {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; S94: Dispute with automatic timeout (no resolver action)
(def s94
   {:scenario-id     "s94-dispute-timeout-auto-refund"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params timeout
    :notes "Dispute raised but resolver never acts. Dispute timeout passes (300s). Auto-cancel at t=1360 succeeds. Tests resolver inaction fallback."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})


;; S95: Dispute with conflicting resolution challenge
(def s95
   {:scenario-id     "s95-resolution-challenge-and-counter"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "adversarial"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Resolver releases, seller challenges, multiple counter-evidence. Tests evidence complexity."
    :adversary? true
    :adversary/type :evidence-manipulator
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xresolver_decision"}}
     {:seq 3 :time 1150 :agent "seller" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xseller_contra_evidence_1"}}
     {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; S96: Multi-token escrow scenario
(def s96
   {:scenario-id     "s96-multi-token-dispute"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Two escrows same token. Both disputed separately. Tests multi-escrow settlement isolation."
    :expected-fail? true
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
     {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 3 :time 1070 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
     {:seq 4 :time 1130 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
     {:seq 5 :time 1140 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release false :resolution-hash "0xhash1"}}
     {:seq 6 :time 1270 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
     {:seq 7 :time 1280 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}]})


;; S97: Appeal after settlement boundary
(def s97
   {:scenario-id     "s97-appeal-after-settlement-attempt"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Settlement executed, escrow terminal. Subsequent actions rejected. Tests settlement finality."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; S98: Receiver initiates cancel after auto-cancel deadline
(def s98
   {:scenario-id     "s98-receiver-cancel-after-auto-cancel"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params dr3
    :notes "Auto-cancel deadline passes but before keeper executes. Receiver tries to cancel. Tests race condition."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1200 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}]})


;; S99: Large escrow with multiple fee tiers
(def s99
   {:scenario-id     "s99-large-escrow-fee-impact"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Very large escrow (1M USDC). Fee impact on settlement. Tests ledger arithmetic at scale."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})


;; S100: Receiver denies, then dispute is resolved as release
(def s100
   {:scenario-id     "s100-deny-then-resolver-releases"
    :schema-version  "1.0"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Receiver denies receipt, then dispute resolves as release anyway. Tests denial override."
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1050 :agent "seller" :action "recipient_deny"
     :params {:workflow-id 0}}
     {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})



    ;; ---------------------------------------------------------------------------
    ;; Scenario registry
    ;; ---------------------------------------------------------------------------
   (def all-scenarios
    "Ordered list of [display-name scenario-or-pair] entries.
    Pairs (S12) are two scenarios that must both pass to count as one passing test.
    Single entries are plain scenario maps."
    [["S01  baseline-happy-path"                      s01]
    ["S02  dr3-dispute-release"                      s02]
    ["S03  dr3-dispute-refund"                       s03]
    ["S04  dispute-timeout-autocancel"               s04]
    ["S05  pending-settlement-execute"               s05]
    ["S06  mutual-cancel"                            s06]
    ["S07  unauthorized-resolver-rejected"           s07]
    ["S08  state-machine-attack-gauntlet"            s08]
    ["S09  multi-escrow-solvency"                    s09]
    ["S10  double-finalize-rejected"                 s10]
    ["S11  zero-fee-edge-case"                       s11]
    ["S12  governance-snapshot-isolation"            [s12a s12b]]
    ["S13  pending-settlement-refund"                s13]
    ["S14  dr3-module-authorized"                    s14]
    ["S15  dr3-module-unauthorized-rejected"         s15]
    ["S16  ieo-create-release"                       s16]
    ["S17  ieo-dispute-no-resolver-timeout"          s17]
    ["S18  dr3-kleros-l0-resolves"                   s18]
    ["S19  dr3-kleros-escalation-rejected-l0-resolves" s19]
    ["S20  dr3-kleros-max-escalation-guard"          s20]
    ["S21  dr3-kleros-pending-cleared-on-escalation" s21]
    ["S22  status-leak-agree-cancel-over-dispute"    s22]
    ["S23  preemptive-escalation-blocked"            s23]
    ["S24  resolver-stake-depletion-cascade"         s24]
    ["S25  profit-maximizer-slash-lifecycle"         s25]
    ["S26  forking-strategist-l1-reversal"           s26]
    ["S27  forking-strategist-l2-fork"               s27]
    ["S28  forking-strategist-late-escalation-rejected" s28]
    ["S29  forking-strategist-seller-escalates"      s29]
    ["S30  forking-strategist-double-loss"           s30]
    ["S31  forking-strategist-all-levels-confirm"    s31]
    ["S32  forking-strategist-premature-settlement-rejected" s32]
    ["S33  forking-strategist-two-escrow-fork-isolation" s33]
    ["S34  profit-maximizer-unchallenged-slash"          s34]
    ["S35  profit-maximizer-governance-wins-appeal"      s35]
    ["S36  profit-maximizer-pre-window-execute-rejected" s36]
    ["S37  profit-maximizer-two-resolver-split-outcomes" s37]
    ["S38  dr3-bond-mix-valid"                           s38]
    ["S39  dr3-senior-coverage-delegation"               s39]
    ["S40  dr3-freeze-post-slash"                        s40]
    ["S41  dr3-reversal-slash-disabled"                  s41]
    ["S42  resolver-buyer-bribery-loop"                  s42]
    ["S45  flash-loan-stake-inflation"                   s45]
    ["S46  reorg-idempotence"                            s46]
    ["S49  appeal-deadline-boundary"                     s49]
    ["S50  false-assertion-unchallenged"                 s50]
    ["S51  same-block-challenge-finalize-race"          s51]
    ["S52  yield-accrued-during-dispute"                s52]
    ["S53  reentrant-withdrawal-guard"                  s53]
    ["S54  multi-claim-ledger-isolation"                s54]
    ["S55  resolver-unavailable-timeout-fallback"       s55]
    ["S56  rapid-resolver-rotation"                     s56]
    ["S57  corruption-cost-vs-profit"                   s57]
    ["S58  watchdog-valid-challenge"                    s58]
    ["S59  watchdog-false-challenge-loss"               s59]
    ["S60  resolver-abstention-timeout-griefing"        s60]
    ["S61  fee-on-transfer-token-handling"              s61]
    ["S62  multi-appeal-escalation-chain"               s62]
    ["S62_cross-token-isolation-under-dispute-load"     s62-cross-token-isolation-under-dispute-load]
    ["S62_cross-token-fee-on-transfer-under-dispute-load" s62-cross-token-fee-on-transfer-under-dispute-load]
    ["S62_cross-token-parallel-appeal-depths-under-dispute-load" s62-cross-token-parallel-appeal-depths-under-dispute-load]
    ["S63  frivolous-appeal-slashing"                   s63]
    ["S64  minimal-bond-edge-case"                      s64]
    ["S65  appeal-after-settlement-rejected"            s65]
    ["S68  double-settlement-guard"                     s68]
    ["S69  stale-dispute-cleanup"                       s69]
    ["S70  large-escrow-resolution"                     s70]
    ["S71  zero-fee-settlement"                         s71]
    ["S72  challenge-during-appeal-window"              s72]
    ["S73  challenge-after-appeal-window-closed"        s73]
    ["S74  multi-escrow-parallel-disputes"              s74]
    ["S75  receiver-cancels-after-dispute"              s75]
    ["S76  sender-cancel-during-appeal"                s76]
    ["S77  escalation-rejected-wrong-layer"             s77]
    ["S78  many-appeals-eventually-rejects"             s78]
    ["S79  partial-resolution-release"                 s79]
    ["S80  disputed-escrow-expiry-settlement"           s80]
    ["S81  appeal-deadline-boundary-before"             s81]
    ["S82  appeal-deadline-boundary-exact"              s82]
    ["S83  appeal-deadline-boundary-after"              s83]
    ["S84  false-assertion-unchallenged"                s84]
    ["S85  watchdog-challenges-resolution"              s85]
    ["S86  reentrant-withdrawal-guard"                  s86]
    ["S87  cross-escrow-isolation"                      s87]
    ["S88  resolution-with-conflicting-evidence"        s88]
    ["S89  dispute-resolution-with-zero-appeal-window"  s89]
    ["S90  resolver-capacity-stress"                    s90]
    ["S91  governance-snapshot-dispute-state"           s91]
    ["S92  settlement-zero-amount-edge"                 s92]
    ["S93  multiple-appeals-with-refund"                s93]
    ["S94  dispute-timeout-auto-refund"                 s94]
    ["S95  resolution-challenge-and-counter"            s95]
    ["S96  multi-token-dispute"                         s96]
    ["S97  appeal-after-settlement-attempt"             s97]
    ["S98  receiver-cancel-after-auto-cancel"           s98]
    ["S99  large-escrow-fee-impact"                     s99]
    ["S100 deny-then-resolver-releases"                 s100]
    ["S66  cooldown-boundary-reorg"                      s66]
    ["S67  reentrancy-callback"                          s67]])

    ;; ---------------------------------------------------------------------------
    ;; Scenario type registry
    ;;
    ;; Maps scenario-id → {:scenario/type kw :adversary? bool
    ;;                      :adversary/type kw :adversary/traits #{kw}}
    ;;
    ;; This is the authoritative source for scenario classification metadata.
    ;; invariant-runner merges this into run results for queryable output.
    ;; trace_metadata/classify-scenario uses scenario-id as a fallback signal.
    ;; ---------------------------------------------------------------------------

    (def scenario-type-registry
    "Type metadata for all S01–S67 invariant scenarios, keyed by scenario-id."
    {;; ── Baseline: standard happy-path protocol flows ───────────────────────
    "s01-baseline-happy-path"                {:scenario/type :baseline}
    "s02-dr3-dispute-release"                {:scenario/type :baseline}
    "s03-dr3-dispute-refund"                 {:scenario/type :baseline}
    "s04-dispute-timeout-autocancel"         {:scenario/type :baseline}
    "s05-pending-settlement-execute"         {:scenario/type :baseline}
    "s06-mutual-cancel"                      {:scenario/type :baseline}
    "s13-pending-settlement-refund"          {:scenario/type :baseline}
    "s16-ieo-create-release"                 {:scenario/type :baseline}
    "s17-ieo-dispute-no-resolver-timeout"    {:scenario/type :baseline}
    "s18-dr3-kleros-l0-resolves"             {:scenario/type :baseline}
    "s46-reorg-idempotence"                  {:scenario/type :baseline}

    ;; ── Edge-case: permission checks, boundary conditions, state guards ────
    "s07-unauthorized-resolver-rejected"     {:scenario/type :edge-case}
    "s08-state-machine-attack-gauntlet"      {:scenario/type :edge-case}
    "s10-double-finalize-rejected"           {:scenario/type :edge-case}
    "s11-zero-fee-edge-case"                 {:scenario/type :edge-case}
    "s12a-snapshot-isolation-fee-zero"             {:scenario/type :edge-case}
    "s12b-snapshot-isolation-fee-500"              {:scenario/type :edge-case}
    "s14-dr3-module-authorized"              {:scenario/type :edge-case}
    "s15-dr3-module-unauthorized-rejected"   {:scenario/type :edge-case}
    "s19-dr3-kleros-escalation-rejected-l0-resolves" {:scenario/type :edge-case}
    "s20-dr3-kleros-max-escalation-guard"    {:scenario/type :edge-case}
    "s21-dr3-kleros-pending-cleared-on-escalation"   {:scenario/type :edge-case}
    "s22-status-leak-agree-cancel-over-dispute" {:scenario/type :edge-case}
    "s23-preemptive-escalation-blocked"      {:scenario/type :edge-case}
    "s66-cooldown-boundary-reorg"            {:scenario/type :edge-case}

    ;; ── Stress: solvency, multi-escrow, depletion ─────────────────────────
    "s09-multi-escrow-solvency"              {:scenario/type :stress}
    "s24-resolver-stake-depletion-cascade"   {:scenario/type :stress}
    "s38-dr3-bond-mix-valid"                 {:scenario/type :stress}
    "s39-dr3-senior-coverage-delegation"     {:scenario/type :stress}
    "s40-dr3-freeze-post-slash"              {:scenario/type :stress}
    "s41-dr3-reversal-slash-disabled"        {:scenario/type :stress}

    ;; ── Adversarial: profit-maximizer ─────────────────────────────────────
    "s25-profit-maximizer-slash-lifecycle"
    {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:multi-step :capital-efficient}}

    "s34-profit-maximizer-unchallenged-slash"
    {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:stealthy :capital-efficient}}

    "s35-profit-maximizer-governance-wins-appeal"
    {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:adaptive :multi-step}}

    "s36-profit-maximizer-pre-window-execute-rejected"
    {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:capital-efficient}}

    "s37-profit-maximizer-two-resolver-split-outcomes"
    {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:multi-step :high-capital}}

    "s67-reentrancy-callback"
    {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:reentrancy :callback}}

    ;; ── Adversarial: forking-strategist ───────────────────────────────────
   "s26-forking-strategist-l1-reversal"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :adaptive}}

   "s27-forking-strategist-l2-fork"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :adaptive}}

   "s28-forking-strategist-late-escalation-rejected"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step}}

   "s29-forking-strategist-seller-escalates"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :reactive}}

   "s30-forking-strategist-double-loss"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :adaptive}}

   "s31-forking-strategist-all-levels-confirm"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :high-capital}}

   "s32-forking-strategist-premature-settlement-rejected"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step}}

   "s33-forking-strategist-two-escrow-fork-isolation"
   {:scenario/type    :adversarial
    :adversary/type   :forking-strategist
    :adversary/traits #{:multi-step :capital-efficient}}
   
   ;; ── Adversarial: Collusion Stress ───────────────────────────────────
   "s42-resolver-buyer-bribery-loop"
   {:scenario/type    :adversarial
    :adversary/type   :colluder
    :adversary/traits #{:multi-agent :bribery}}
   
   ;; ── Adversarial: Stake Stress ───────────────────────────────────
   "s45-flash-loan-stake-inflation"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:flash-loan :stake-inflation}}

   ;; ── Boundary & Timing Tests ──────────────────────────────────────
   "s49-appeal-deadline-boundary"
   {:scenario/type :boundary-test
    :tests #{:appeal-deadline :timing :determinism}}

   "s50-false-assertion-unchallenged"
   {:scenario/type :monitoring-assumption
    :tests #{:assumption-validation :monitoring-failure}}

   "s51-same-block-challenge-finalize-race"
   {:scenario/type :mev-ordering
    :tests #{:ordering-determinism :finality :race-condition}}

   "s52-yield-accrued-during-dispute"
   {:scenario/type :accounting
    :tests #{:yield-accounting :fund-conservation :settlement}}

   "s53-reentrant-withdrawal-guard"
   {:scenario/type :settlement-ledger
    :tests #{:atomicity :ledger-isolation :idempotence}}

   "s54-multi-claim-ledger-isolation"
   {:scenario/type :settlement-ledger
    :tests #{:ledger-isolation :per-escrow-accounting :claim-independence}}

   "s55-resolver-unavailable-timeout-fallback"
   {:scenario/type :governance
    :tests #{:resolver-availability :timeout-fallback :state-recovery}}

   "s56-rapid-resolver-rotation"
   {:scenario/type :governance
    :tests #{:resolver-rotation :state-continuity :multi-authority}}

   "s57-corruption-cost-vs-profit"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:corruption :economic-attack}}

   "s58-watchdog-valid-challenge"
   {:scenario/type :challenge-mechanism
    :tests #{:challenge-acceptance :appeal-flow :bounty-allocation}}

   "s59-watchdog-false-challenge-loss"
   {:scenario/type :challenge-mechanism
    :tests #{:false-challenge :bond-forfeiture :appeal-rejection}}

   "s60-resolver-abstention-timeout-griefing"
   {:scenario/type :timeout-mechanism
    :tests #{:resolver-abstention :timeout-fallback :auto-cancel}}

   "s61-fee-on-transfer-token-handling"
   {:scenario/type :token-handling
    :tests #{:fee-on-transfer :settlement-accuracy :ledger-balance}}

   "s62-multi-appeal-escalation-chain"
   {:scenario/type    :escalation-mechanism
    :adversary/type   :colluder
    :tests #{:challenge-chain :escalation-flow :multi-level-appeal}}

   "s63-frivolous-appeal-slashing"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:frivolous-appeal :bond-forfeiture}}

   "s64-minimal-bond-edge-case"
   {:scenario/type :parameter-sensitivity
    :tests #{:bond-constraints :minimal-amounts :edge-case-resolution}}

   "s65-appeal-after-settlement-rejected"
   {:scenario/type :timing-boundary
    :tests #{:post-settlement-appeal :deadline-enforcement :late-appeal-rejection}}

   "s68-double-settlement-guard"
   {:scenario/type :idempotence
    :tests #{:double-settlement-rejection :idempotent-settlement :guard-mechanics}}

   "s69-stale-dispute-cleanup"
   {:scenario/type :state-management
    :tests #{:stale-dispute-handling :state-recovery :old-dispute-cleanup}}

   "s70-large-escrow-resolution"
   {:scenario/type :scale-test
    :tests #{:large-amounts :fee-precision :high-value-settlement}}

   "s71-zero-fee-settlement"
   {:scenario/type :edge-case
    :tests #{:zero-fee-handling :fee-extraction :minimal-fees}}

   "s72-challenge-during-appeal-window"
   {:scenario/type :challenge-mechanism
    :tests #{:appeal-window-challenge :in-window-acceptance :appeal-timing}}

   "s73-challenge-after-appeal-window-closed"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window-expiry :late-challenge-rejection :deadline-enforcement}}

   "s74-multi-escrow-parallel-disputes"
   {:scenario/type :stress-test
    :tests #{:parallel-disputes :resolver-capacity :independence :multi-workflow}}

   "s75-receiver-cancels-after-dispute"
   {:scenario/type :authorization-check
    :tests #{:receiver-cancel-during-dispute :authorization-enforcement}}

   "s76-sender-cancel-during-appeal"
   {:scenario/type :authorization-check
    :tests #{:cancel-during-appeal-window :cancel-blocking}}

   "s77-escalation-rejected-wrong-layer"
   {:scenario/type :protocol-enforcement
    :tests #{:escalation-availability :layer-enforcement :unsupported-features}}

   "s78-many-appeals-eventually-rejects"
   {:scenario/type    :adversarial
    :adversary/type   :profit-maximizer
    :adversary/traits #{:frivolous-appeals :repeated-attacks}}

   "s79-partial-resolution-release"
   {:scenario/type :settlement-variants
    :tests #{:partial-release :settlement-flexibility :split-funds}}

   "s80-disputed-escrow-expiry-settlement"
   {:scenario/type :timing-integration
    :tests #{:escrow-expiry-during-dispute :post-expiry-settlement :deadline-ordering}}

   "s81-appeal-deadline-boundary-before"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :settlement-timing :pre-deadline}}

   "s82-appeal-deadline-boundary-exact"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :boundary-exact :settlement-eligible}}

   "s83-appeal-deadline-boundary-after"
   {:scenario/type :timing-boundary
    :tests #{:appeal-window :settlement-timing :post-deadline}}

   "s84-false-assertion-unchallenged"
   {:scenario/type :monitoring-assumption
    :tests #{:honest-majority :unchallenged-resolution :assumption-validation}}

   "s85-watchdog-challenges-resolution"
   {:scenario/type :watchdog-flow
    :tests #{:challenge-mechanics :evidence-handling :watchdog-protection}}

   "s86-reentrant-withdrawal-guard"
   {:scenario/type :settlement-security
    :tests #{:reentrancy-guard :atomic-settlement :double-execution}}

   "s87-cross-escrow-isolation"
   {:scenario/type :settlement-isolation
    :tests #{:cross-escrow-ledger :dispute-release-mix :ledger-integrity}}

   "s88-resolution-with-conflicting-evidence"
   {:scenario/type :evidence-handling
    :tests #{:evidence-types :conflicting-evidence :resolver-discretion}}

   "s89-dispute-resolution-with-zero-appeal-window"
   {:scenario/type :protocol-variant
    :tests #{:fast-path-resolution :zero-appeal-window :kleros-mode}}

   "s90-resolver-capacity-stress"
   {:scenario/type :stress
    :tests #{:multi-dispute-resolution :capacity-limits :parallel-workload}}

   "s91-governance-snapshot-dispute-state"
   {:scenario/type :governance
    :tests #{:snapshot-isolation :dispute-state :governance-update}}

   "s92-settlement-zero-amount-edge"
   {:scenario/type :edge-case
    :tests #{:minimum-amount :ledger-precision :tiny-escrow}}

   "s93-multiple-appeals-with-refund"
   {:scenario/type :settlement-variants
    :tests #{:refund-path :multiple-appeals :appeal-refund}}

   "s94-dispute-timeout-auto-refund"
   {:scenario/type :timing-integration
    :tests #{:resolver-timeout :auto-refund :inaction-fallback}}

   "s95-resolution-challenge-and-counter"
   {:scenario/type :adversarial
    :adversary? true
    :adversary/type :evidence-manipulator
    :tests #{:evidence-complexity :challenge-counter :adversarial-evidence}}

   "s96-multi-token-dispute"
   {:scenario/type :stress
    :tests #{:multi-token :token-isolation :ledger-per-token}}

   "s97-appeal-after-settlement-attempt"
   {:scenario/type :edge-case
    :tests #{:settlement-finality :post-settlement-appeal :boundary-enforcement}}

   "s98-receiver-cancel-after-auto-cancel"
   {:scenario/type :race-condition
    :tests #{:auto-cancel-deadline :receiver-cancel :race-condition}}

   "s99-large-escrow-fee-impact"
   {:scenario/type :stress
    :tests #{:large-amount :fee-arithmetic :scale-limits}}

   "s100-deny-then-resolver-releases"
   {:scenario/type :settlement-variants
    :tests #{:recipient-deny :denial-override :release-override}}})
