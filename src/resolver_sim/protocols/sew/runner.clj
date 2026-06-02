(ns resolver-sim.protocols.sew.runner
  "Contract-model trial runner and divergence detector.

   Provides a full-lifecycle escrow runner that drives state through the
   contract model (lifecycle → resolution → timed-actions) and exposes
   the same profit fields as the idealised dispute.clj model.

   The divergence detector compares idealized outcomes (from dispute/resolve-dispute)
   against contract-model outcomes and emits a :divergence map when they differ.

   Key design:
     - run-trial returns the same keys as dispute/resolve-dispute so it can be
       dropped into run-batch without changing the aggregation code.
     - divergence fields are extra — existing stats ignore them.
     - run-trial is pure: takes an explicit rng-fn for random decisions.
     - Escalation is modelled with Priority-3 authority (no custom-resolver):
       et.dispute-resolver tracks the current-round resolver through escalations."
  (:require [resolver-sim.protocols.sew.snapshot  :as snap]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.accounting :as ac]
            [resolver-sim.protocols.sew.authority  :as auth]
            [resolver-sim.protocols.sew.invariants :as inv]))

;; ---------------------------------------------------------------------------
;; Canonical snapshot for simulation trials
;;
;; Mirrors the parameter knobs exposed by the batch runner.
;; ---------------------------------------------------------------------------

(defn make-trial-snapshot
  "Build a ModuleSnapshot from standard batch params.

   Params:
     :resolver-fee-bps             — protocol fee (default 50 = 0.5%)
     :appeal-window-duration       — seconds (default 0 = no appeal window)
     :max-dispute-duration         — seconds (default 2592000 = 30 days)
     :appeal-bond-protocol-fee-bps — protocol cut of appeal bond (default 0)"
  [{:keys [resolver-fee-bps appeal-window-duration max-dispute-duration
           appeal-bond-protocol-fee-bps]
    :or   {resolver-fee-bps             50
           appeal-window-duration       0
           max-dispute-duration         2592000
           appeal-bond-protocol-fee-bps 0}}]
  (snap/make-escrow-snapshot
   {:escrow-fee-bps               resolver-fee-bps
    :appeal-window-duration       appeal-window-duration
    :max-dispute-duration         max-dispute-duration
    :appeal-bond-protocol-fee-bps appeal-bond-protocol-fee-bps}))

;; ---------------------------------------------------------------------------
;; Resolver chain: Priority-3 path through escalation rounds
;;
;; Level 0 = initial resolver, 1 = senior resolver, 2 = Kleros (final round).
;; ---------------------------------------------------------------------------

(defn- resolver-chain [base-resolver]
  [base-resolver "0xSeniorResolver" "0xKleros"])

;; ---------------------------------------------------------------------------
;; Internal: single-trial lifecycle with multi-round escalation
;; ---------------------------------------------------------------------------

(defn- run-lifecycle
  "Drive one escrow trial through the contract model with optional escalation.

   Uses Priority-3 authority (no custom-resolver): et.dispute-resolver is set
   directly on the escrow transfer and tracks through escalation rounds.

   Parameters:
     rng-fn                   — (fn [] → [0,1)) for probabilistic decisions
     escrow-amount            — gross wei
     from / to / resolver-addr / token — participant addresses
     snap                     — ModuleSnapshot
     appeal-bond-bps          — bond expressed as bps of amount-after-fee
     strategy                 — :honest | :lazy | :malicious | :collusive
     escalation-prob-correct  — probability party escalates a correct verdict
     escalation-prob-wrong    — probability party escalates a wrong verdict
     detection-prob           — probability malicious act is detected

   Returns a map with the same shape as dispute/resolve-dispute plus :cm/* extras."
  [rng-fn escrow-amount from to resolver-addr token snap appeal-bond-bps strategy
   escalation-prob-correct escalation-prob-wrong detection-prob]
  (let [;; Step 1: Create escrow WITHOUT custom-resolver (use Priority-3 path)
        settings  (t/make-escrow-settings {})
        w0        (t/empty-world 1000)
        cr        (lc/create-escrow w0 from token to escrow-amount settings snap)]
    (when-not (:ok cr) (throw (ex-info "create-escrow failed" cr)))
    (let [wf-id     0
          ;; Attach the initial resolver via Priority-3 (et.dispute-resolver)
          w1        (assoc-in (:world cr) [:escrow-transfers wf-id :dispute-resolver] resolver-addr)
          fee       (get-in w1 [:total-fees token] 0)
          afa       (get-in w1 [:escrow-transfers wf-id :amount-after-fee])
          resolvers (resolver-chain resolver-addr)

          ;; Step 2: Dispute is always raised in adversarial trials
          dr    (lc/raise-dispute w1 wf-id from)
          _     (when-not (:ok dr) (throw (ex-info "raise-dispute failed" dr)))
          w2    (:world dr)

          ;; Step 3-N: Resolution loop with possible multi-round escalation.
          ;;
          ;; Each iteration:
          ;;   a. Current resolver submits verdict (execute-resolution)
          ;;   b. If verdict is deferred (pending-settlement created):
          ;;      - Probabilistically escalate (escalate-dispute) → new resolver
          ;;      - Or finalize by advancing time past appeal deadline
          ;;   c. If verdict is immediate (final round or no appeal window):
          ;;      - Done
          {w-final          :world-after
           verdict-correct? :verdict-correct?
           escalated?       :escalated?
           escalation-level :escalation-level}
          (loop [w     w2
                 level 0]
            (let [current-resolver (get-in w [:escrow-transfers wf-id :dispute-resolver])

                  verdict-correct?
                  (case strategy
                    :honest    true
                    :lazy      (< (rng-fn) 0.5)
                    :malicious (< (rng-fn) 0.3)
                    :collusive (< (rng-fn) 0.8))

                  is-release verdict-correct?

                  ;; Submit resolution — deferred if appeal-window > 0 and not final round
                  rr           (res/execute-resolution w wf-id current-resolver is-release "0xhash" nil)
                  w-resolved   (if (:ok rr) (:world rr) w)
                  has-pending? (and (:ok rr) (:exists (t/get-pending w-resolved wf-id)))

                  ;; Escalation only possible when verdict is deferred
                  esc-prob    (if verdict-correct? escalation-prob-correct escalation-prob-wrong)
                  escalate?   (and has-pending? (< (rng-fn) esc-prob))]

              (if escalate?
                ;; Party escalates: clear pending, assign next resolver, continue loop
                (let [next-resolver (get resolvers (inc level) "0xKleros")
                      er            (res/escalate-dispute w-resolved wf-id from
                                                          (fn [_ _ _ _]
                                                            {:ok true :new-resolver next-resolver}))]
                  (if (:ok er)
                    (recur (:world er) (inc level))
                    ;; Escalation refused (level cap or other guard) — fall through to finalize
                    (let [w-fin (if has-pending?
                                  (let [dl  (:appeal-deadline (t/get-pending w-resolved wf-id))
                                        w-t (assoc w-resolved :block-time (+ dl 1))
                                        er2 (res/execute-pending-settlement w-t wf-id)]
                                    (if (:ok er2) (:world er2) w-t))
                                  w-resolved)]
                      {:world-after      w-fin
                       :verdict-correct? verdict-correct?
                       :escalated?       (> level 0)
                       :escalation-level level})))

                ;; No escalation: finalize this round
                (let [w-fin (if has-pending?
                              ;; Advance time past appeal deadline and execute pending
                              (let [dl  (:appeal-deadline (t/get-pending w-resolved wf-id))
                                    w-t (assoc w-resolved :block-time (+ dl 1))
                                    er  (res/execute-pending-settlement w-t wf-id)]
                                (if (:ok er) (:world er) w-t))
                              w-resolved)]
                  {:world-after      w-fin
                   :verdict-correct? verdict-correct?
                   :escalated?       (> level 0)
                   :escalation-level level}))))

          ;; Step 4: Detection + slashing (independent of escalation path)
          detected?     (and (not verdict-correct?) (< (rng-fn) detection-prob))
          bond-amt      (long (/ (* afa appeal-bond-bps) 10000))
          profit-honest (long fee)
          profit-malice (if detected? (long (- fee bond-amt)) (long fee))

          ;; Step 5: Final invariant check
          final-state  (t/escrow-state w-final wf-id)
          inv-result   (inv/check-all w-final)]

      {:dispute-correct?      verdict-correct?
       ;; appeal-triggered? is an alias for escalated? — same concept
       :appeal-triggered?     escalated?
       :escalated?            escalated?
       :escalation-level      escalation-level
       :slashed?              detected?
       :frozen?               detected?
       :escaped?              false
       :slashing-pending?     false
       :slashing-delay-weeks  0
       :slashing-reason       (when detected? :fraud)
       :profit-honest         profit-honest
       :profit-malice         profit-malice
       :strategy              strategy
       ;; Contract-model extras
       :cm/final-state        final-state
       :cm/fee                fee
       :cm/afa                afa
       :cm/invariants-ok?     (:all-hold? inv-result)
       :cm/inv-violations     (when-not (:all-hold? inv-result) (:results inv-result))})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run-trial
  "Run one contract-model trial. Signature mirrors dispute/resolve-dispute.

   params keys (same as batch runner params map):
     :escrow-size                      — gross wei (default 10000)
     :resolver-fee-bps                 — protocol fee bps (default 50)
     :appeal-bond-bps                  — appeal bond bps (default 0)
     :appeal-window-duration           — seconds (default 0)
     :max-dispute-duration             — seconds (default 2592000)
     :slashing-detection-probability
     :escalation-probability-if-correct — prob party escalates correct verdict (default 0.05)
     :escalation-probability-if-wrong   — prob party escalates wrong verdict (default 0.60)
     :strategy                         — :honest | :lazy | :malicious | :collusive

   rng-fn — (fn [] → double in [0,1))"
  [rng-fn params]
  (let [snap    (make-trial-snapshot params)
        strategy (get params :strategy :honest)]
    (run-lifecycle
     rng-fn
     (get params :escrow-size 10000)
     "0xAlice"
     "0xBob"
     "0xResolver"
     "0xUSDC"
     snap
     (get params :appeal-bond-bps 0)
     strategy
     (get params :escalation-probability-if-correct 0.05)
     (get params :escalation-probability-if-wrong   0.60)
     (get params :slashing-detection-probability    0.1))))

;; ---------------------------------------------------------------------------
;; Divergence detector
;; ---------------------------------------------------------------------------

(defn compare-outcomes
  "Compare an idealised trial result with a contract-model trial result.

   Both maps must have :profit-honest, :profit-malice, :slashed?,
   :dispute-correct?, :appeal-triggered?, :escalation-level.

   Returns {:divergence? bool :diffs [...]} where each diff is a map:
     {:field kw :idealized val :contract-model val}"
  [idealized cm-result]
  (let [fields [:profit-honest :profit-malice :slashed? :dispute-correct? :appeal-triggered? :escalation-level]
        diffs  (for [f fields
                     :let [iv (get idealized f)
                           cv (get cm-result f)]
                     :when (not= iv cv)]
                 {:field f :idealized iv :contract-model cv})]
    {:divergence? (seq diffs)
     :diffs       (vec diffs)}))

(defn run-with-divergence-check
  "Run both the idealised model (via dispute-fn) and the contract model,
   then return both results plus a divergence report.

   dispute-fn — (fn [params] → idealized-result) e.g. (partial dispute/resolve-dispute rng ...)
   rng-fn     — (fn [] → double) for the contract model

   Returns:
     {:idealized  idealized-result
      :contract   cm-result
      :divergence compare-outcomes-result}"
  [dispute-fn rng-fn params]
  (let [ideal  (dispute-fn params)
        cm     (run-trial rng-fn params)
        diff   (compare-outcomes ideal cm)]
    {:idealized  ideal
     :contract   cm
     :divergence diff}))
