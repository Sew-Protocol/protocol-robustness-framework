(ns resolver-sim.protocols.sew.properties-test
  "Property-based tests for the Sew contract model using test.check.

   Single-step properties (1–8) verify individual invariants.
   Multi-step / sequence properties (9–14) generate operation chains and verify
   invariants hold at every step — making the simulator a complete behavioral model.

   Properties:
     1.  Solvency             — any op sequence holds all-hold? on the result world
     2.  Irreversibility      — terminal states cannot be changed by any op
     3.  Fee monotonicity     — ops (except withdraw-fees) never decrease total-fees
     4.  Resolver exclusivity — custom-resolver blocks all other addresses
     5.  Appeal enforcement   — execute-pending before deadline always fails
     6.  Status combinations  — all escrow states have valid status combinations
     7.  Escalation monotonic — dispute level increases by exactly 1 per step
     8.  Pending consistency  — pending settlement only exists on :disputed escrows
     9.  Full escalation chain— 0→1→2 with appeal window; max-level + final-round
     10. Multi-step lifecycle — sequence generator: 0–2 escalations × appeal window
     11. Interrupted flow     — dispute timeout before resolver; late action rejected
     12. Delayed resolver     — second resolution after finalization is rejected
     13. Conflicting actions  — escalation mid-pending clears it; execute-pending fails
     14. Repeated escalation  — max level / non-participant / terminal all rejected"
  (:require [resolver-sim.generators.yield.core :as gen-yield]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.authority  :as auth]
            [resolver-sim.protocols.sew.invariants :as inv]))

(def ^:private num-trials 200)

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def gen-amount (gen/large-integer* {:min 1 :max 1000000}))
(def gen-bps    (gen/large-integer* {:min 0 :max 500}))
(def gen-time   (gen/large-integer* {:min 1 :max 9999}))
(def gen-addr   (gen/elements ["0xAlice" "0xBob" "0xCarol" "0xDave"]))

(def gen-yield-preset
  "Distribution preset for escrow yield routing (matches `normalize-yield-preset`)."
  (gen/elements [:off :to-sender :to-recipient :split-50-50]))

(def gen-yield-profile gen-yield/gen-yield-profile)

(defn gen-snapshot
  "Generate a random module snapshot."
  []
  (gen/fmap (fn [[fee-bps dur]]
              (snap-fix/escrow-snapshot {:escrow-fee-bps          fee-bps
                                       :max-dispute-duration     dur
                                       :appeal-window-duration   0}))
            (gen/tuple gen-bps
                       (gen/large-integer* {:min 100 :max 86400}))))

;; ---------------------------------------------------------------------------
;; World builders
;; ---------------------------------------------------------------------------

(defn make-base-world-with-escrow
  "Create a world with one :pending escrow. No custom resolver."
  [amount fee-bps block-time from to]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps fee-bps :max-dispute-duration 3600})
        w0   (t/empty-world block-time)
        r    (lc/create-escrow w0 from "0xUSDC" to amount
                               (t/make-escrow-settings {}) snap)]
    (when (:ok r) {:world (:world r) :snap snap})))

(defn make-disputed-world
  "Create a world with one :disputed escrow using a custom-resolver.
   custom-resolver takes Priority-1 in authorized-resolver? — no escalation possible.
   Use make-disputed-world-for-escalation when multi-round resolution is needed."
  [amount fee-bps resolver-addr]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps fee-bps :max-dispute-duration 3600})
        cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" amount
                               (t/make-escrow-settings {:custom-resolver resolver-addr}) snap)
        dr   (when (:ok cr) (lc/raise-dispute (:world cr) 0 "0xAlice"))]
    (when (and (:ok cr) (:ok dr))
      {:world (:world dr) :snap snap})))

(defn make-disputed-world-for-escalation
  "Create a :disputed world where resolver authority tracks et.dispute-resolver
   (no custom-resolver in settings, no resolution module in snapshot).

   After each escalate-dispute call, et.dispute-resolver is updated to the new
   resolver. authorized-resolver? Priority-3 then naturally authorises the
   current round's resolver without any module callback.

   snap-params      — full map passed to snap-fix/escrow-snapshot
   initial-resolver — address stored as et.dispute-resolver at level 0"
  [amount snap-params initial-resolver]
  (let [snap (snap-fix/escrow-snapshot snap-params)
        cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" amount
                               (t/make-escrow-settings {}) snap)
        ;; Set initial dispute-resolver on the transfer.
        ;; In the contract, the DRM sets et.disputeResolver at createEscrow time
        ;; when resolution-module is configured. We mirror that here.
        w0   (when (:ok cr)
               (assoc-in (:world cr) [:escrow-transfers 0 :dispute-resolver] initial-resolver))
        dr   (when w0 (lc/raise-dispute w0 0 "0xAlice"))]
    (when (and (:ok cr) (:ok dr))
      {:world (:world dr) :snap snap})))

;; ---------------------------------------------------------------------------
;; Sequence runner (used by multi-step properties)
;;
;; Applies a seq of escalations to a disputed world, checking check-all and
;; check-transition at every step. Returns {:ok bool :world w :violations [...]}
;; ---------------------------------------------------------------------------

(defn- run-escalation-sequence
  "Apply n escalations from world 0, installing resolvers[1..n] in order.
   Returns {:ok bool :world w-final :violations [...]}.
   :ok is false and :violations is non-empty if any invariant check fails."
  [world resolvers n]
  (reduce
   (fn [{:keys [ok world violations]} i]
     (if-not ok
       {:ok false :world world :violations violations}
       (let [next-res (get resolvers (inc i))
             er       (res/escalate-dispute world 0 "0xAlice"
                                            (fn [_ _ _ _] {:ok true :new-resolver next-res}))]
         (if-not (:ok er)
           {:ok false :world world
            :violations (conj violations {:step i :error (:error er)})}
           (let [w'   (:world er)
                 inv1 (inv/check-all w')
                 inv2 (inv/check-transition world w')]
             {:ok         (and (:all-hold? inv1) (:all-hold? inv2))
              :world      w'
              :violations (cond-> violations
                            (not (:all-hold? inv1))
                            (conj {:step i :check :all     :results (:results inv1)})
                            (not (:all-hold? inv2))
                            (conj {:step i :check :trans   :results (:results inv2)}))})))))
   {:ok true :world world :violations []}
   (range n)))

;; ============================================================================
;; Properties 1–8: single-step invariant checks
;; ============================================================================

;; ---------------------------------------------------------------------------
;; Property 1: check-all holds after create, cancel, and release
;; ---------------------------------------------------------------------------

(def prop-solvency
  (prop/for-all
   [amount   gen-amount
    fee-bps  gen-bps
    block-t  gen-time]
   (when-some [res (make-base-world-with-escrow amount fee-bps block-t "0xAlice" "0xBob")]
     (let [{:keys [world]} res]
     (and
      (:all-hold? (inv/check-all world))
      (let [r2 (lc/sender-cancel world 0 "0xAlice" nil)]
        (if (:ok r2)
          (and (:all-hold? (inv/check-all (:world r2)))
               (:all-hold? (inv/check-transition world (:world r2))))
          true))
      (let [r3 (lc/release world 0 "0xAlice"
                           (fn [_ _ _] {:allowed? true :reason-code 0}))]
        (if (:ok r3)
          (and (:all-hold? (inv/check-all (:world r3)))
               (:all-hold? (inv/check-transition world (:world r3))))
          true))))))

(deftest property-solvency
  (let [result (tc/quick-check num-trials prop-solvency)]
    (is (:pass? result)
        (str "Invariant suite violated after create/cancel/release: "
             (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 2: Terminal states are absorbing (irreversibility)
;; ---------------------------------------------------------------------------

(def prop-irreversibility
  (prop/for-all
   [amount gen-amount]
   (when-some [res (make-base-world-with-escrow amount 50 1000 "0xAlice" "0xBob")]
     (let [{:keys [world]} res
           rr (lc/release world 0 "0xAlice" (fn [_ _ _] {:allowed? true :reason-code 0}))]
       (when (:ok rr)
         (let [w-released (:world rr)]
           (and
            (:all-hold? (inv/check-all w-released))
            (:all-hold? (inv/check-transition world w-released))
            (false? (:ok (lc/raise-dispute w-released 0 "0xAlice")))
            (false? (:ok (lc/release w-released 0 "0xAlice"
                                     (fn [_ _ _] {:allowed? true :reason-code 0}))))))))))

(deftest property-irreversibility
  (let [result (tc/quick-check num-trials prop-irreversibility)]
    (is (:pass? result)
        (str "Irreversibility violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 3: Fee monotonicity — total-fees never decreases between creates
;; ---------------------------------------------------------------------------

(def prop-fee-monotonicity
  (prop/for-all
   [amount1 gen-amount
    amount2 gen-amount
    fee-bps gen-bps]
   (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps fee-bps :max-dispute-duration 3600})
         w0   (t/empty-world 1000)
         r1   (lc/create-escrow w0 "0xAlice" "0xUSDC" "0xBob" amount1
                                (t/make-escrow-settings {}) snap)]
     (if-not (:ok r1)
       true
       (let [w1  (:world r1)
             r2  (lc/create-escrow w1 "0xCarol" "0xUSDC" "0xDave" amount2
                                   (t/make-escrow-settings {}) snap)]
         (if-not (:ok r2)
           true
           (let [w2 (:world r2)]
             (and (:all-hold? (inv/check-all w1))
                  (:all-hold? (inv/check-all w2))
                  (:holds? (inv/fee-increased-or-equal? w1 w2))))))))))

(deftest property-fee-monotonicity
  (let [result (tc/quick-check num-trials prop-fee-monotonicity)]
    (is (:pass? result)
        (str "Fee monotonicity violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 4: Custom-resolver exclusivity
;; ---------------------------------------------------------------------------

(def prop-resolver-exclusivity
  (prop/for-all
   [custom-addr gen-addr
    other-addr  gen-addr]
   (if (= custom-addr other-addr)
     true
     (let [sett (t/make-escrow-settings {:custom-resolver custom-addr})
           snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :max-dispute-duration 3600})
           cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob"
                                  1000 sett snap)
           dr   (when (:ok cr) (lc/raise-dispute (:world cr) 0 "0xAlice"))
           w    (when (:ok dr) (:world dr))]
       (when w
         (and (:all-hold? (inv/check-all w))
              (auth/authorized-resolver? w 0 custom-addr nil)
              (not (auth/authorized-resolver? w 0 other-addr nil))))))))

(deftest property-resolver-exclusivity
  (let [result (tc/quick-check num-trials prop-resolver-exclusivity)]
    (is (:pass? result)
        (str "Resolver exclusivity violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 5: Appeal window enforcement
;; ---------------------------------------------------------------------------

(def prop-appeal-window
  (prop/for-all
   [appeal-dur (gen/large-integer* {:min 100 :max 86400})
    time-delta (gen/large-integer* {:min 1 :max 99})]
   (let [snap     (snap-fix/escrow-snapshot {:escrow-fee-bps        0
                                           :appeal-window-duration appeal-dur
                                           :max-dispute-duration   200000})
         cr       (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" 1000
                                    (t/make-escrow-settings {:custom-resolver "0xRes"}) snap)
         dr       (when (:ok cr) (lc/raise-dispute (:world cr) 0 "0xAlice"))
         w1       (when (:ok dr) (:world dr))
         rr       (when w1 (res/execute-resolution w1 0 "0xRes" true "0xhash" nil))
         w2       (when (:ok rr) (:world rr))
         deadline (when w2 (get-in w2 [:pending-settlements 0 :appeal-deadline]))
         w-early  (when deadline (assoc w2 :block-time (- deadline time-delta)))
         r-early  (when w-early (res/execute-pending-settlement w-early 0))]
     (when (and (:ok dr) (:ok rr) r-early)
       (and (false? (:ok r-early))
            (:all-hold? (inv/check-all w1))
            (:all-hold? (inv/check-all w2)))))))

(deftest property-appeal-window-enforcement
  (let [result (tc/quick-check num-trials prop-appeal-window)]
    (is (:pass? result)
        (str "Appeal window enforcement violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 6: Status combinations are valid after every lifecycle step
;; ---------------------------------------------------------------------------

(def prop-status-combinations-valid
  (prop/for-all
   [amount  gen-amount
    fee-bps gen-bps]
   (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps fee-bps :max-dispute-duration 3600})
         cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" amount
                                (t/make-escrow-settings {:custom-resolver "0xResolver"}) snap)]
     (when (:ok cr)
       (let [w-pending  (:world cr)
             inv-create (inv/all-status-combinations-valid? w-pending)
             dr         (lc/raise-dispute w-pending 0 "0xAlice")
             inv-disp   (when (:ok dr) (inv/all-status-combinations-valid? (:world dr)))
             rr         (when (:ok dr)
                          (res/execute-resolution (:world dr) 0 "0xResolver" true "0xhash" nil))
             inv-final  (when (and rr (:ok rr))
                          (inv/all-status-combinations-valid? (:world rr)))]
         (and (:holds? inv-create)
              (or (nil? inv-disp)  (:holds? inv-disp))
              (or (nil? inv-final) (:holds? inv-final))))))))

(deftest property-status-combinations-valid
  (let [result (tc/quick-check num-trials prop-status-combinations-valid)]
    (is (:pass? result)
        (str "Status combinations violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 7: Escalation level increases by exactly 1 and never decreases
;; ---------------------------------------------------------------------------

(def prop-escalation-monotonic
  (prop/for-all
   [amount  gen-amount
    fee-bps gen-bps]
   (when-some [res (make-disputed-world amount fee-bps "0xResolver")]
     (let [{:keys [world]} res
           esc-fn       (fn [_ _ _ _] {:ok true :new-resolver "0xSenior"})
           level-before (t/dispute-level world 0)
           er           (res/escalate-dispute world 0 "0xAlice" esc-fn)]
       (when (:ok er)
         (let [w-after     (:world er)
               level-after (t/dispute-level w-after 0)]
           (and (= 1 (- level-after level-before))
                (:holds? (inv/escalation-level-monotonic? world w-after))
                (:holds? (inv/dispute-level-bounded? w-after))
                (:all-hold? (inv/check-all w-after)))))))))

(deftest property-escalation-monotonic
  (let [result (tc/quick-check num-trials prop-escalation-monotonic)]
    (is (:pass? result)
        (str "Escalation monotonicity violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 8: Pending settlement only exists for :disputed escrows
;; ---------------------------------------------------------------------------

(def prop-pending-settlement-consistent
  (prop/for-all
   [appeal-dur (gen/large-integer* {:min 100 :max 86400})
    amount     gen-amount
    fee-bps    gen-bps]
   (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps         fee-bps
                                       :max-dispute-duration   200000
                                       :appeal-window-duration appeal-dur})
         cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" amount
                                (t/make-escrow-settings {:custom-resolver "0xResolver"}) snap)
         dr   (when (:ok cr) (lc/raise-dispute (:world cr) 0 "0xAlice"))]
     (when (and (:ok cr) (:ok dr))
       (let [w-disputed (:world dr)
             rr         (res/execute-resolution w-disputed 0 "0xResolver" true "0xhash" nil)
             w-pending  (when (:ok rr) (:world rr))
             inv1       (when w-pending (inv/pending-settlement-consistency? w-pending))
             deadline   (when w-pending (get-in w-pending [:pending-settlements 0 :appeal-deadline]))
             w-expired  (when deadline (assoc w-pending :block-time (+ deadline 1)))
             er         (when w-expired (res/execute-pending-settlement w-expired 0))
             w-final    (when (and er (:ok er)) (:world er))
             inv2       (when w-final (inv/pending-settlement-consistency? w-final))]
         (and (:ok rr)
              (or (nil? inv1) (:holds? inv1))
              (or (nil? inv2) (:holds? inv2))))))))

(deftest property-pending-settlement-consistent
  (let [result (tc/quick-check num-trials prop-pending-settlement-consistent)]
    (is (:pass? result)
        (str "Pending settlement consistency violated: " (pr-str (:shrunk result))))))

;; ============================================================================
;; Properties 9–14: multi-step / sequence / adversarial
;; ============================================================================

;; ---------------------------------------------------------------------------
;; Property 9: Full escalation chain
;;
;; Exercises the complete 0→1→2 escalation path with an appeal window active.
;; Verifies:
;;   - Level increments at each step
;;   - Third escalation (at max level) is rejected
;;   - Final-round resolution is IMMEDIATE even when appeal-window-duration > 0
;;   - check-all + check-transition hold at every step
;; ---------------------------------------------------------------------------

(def prop-full-escalation-chain
  (prop/for-all
   [amount     gen-amount
    fee-bps    gen-bps
    appeal-dur (gen/large-integer* {:min 100 :max 86400})]
   (let [snap-params {:escrow-fee-bps         fee-bps
                      :max-dispute-duration   3600
                      :appeal-window-duration appeal-dur}]
     (when-some [res (make-disputed-world-for-escalation amount snap-params "0xRes0")]
       (let [{:keys [world]} res]
       (let [;; Round 0 → 1
             er1 (res/escalate-dispute world 0 "0xAlice"
                                       (fn [_ _ _ _] {:ok true :new-resolver "0xRes1"}))
             ;; Round 1 → 2  (reaches max-dispute-level = 2)
             er2 (when (:ok er1)
                   (res/escalate-dispute (:world er1) 0 "0xAlice"
                                         (fn [_ _ _ _] {:ok true :new-resolver "0xRes2"})))
             w2  (when (:ok er2) (:world er2))
             ;; Round 2 → ? must be rejected
             er3 (when w2
                   (res/escalate-dispute w2 0 "0xAlice"
                                         (fn [_ _ _ _] {:ok true :new-resolver "0xRes3"})))
             ;; Final-round resolution by "0xRes2" — must be immediate
             rr  (when w2
                   (res/execute-resolution w2 0 "0xRes2" true "0xhash" nil))]
         (when (and (:ok er1) (:ok er2) er3 (:ok rr))
           (and
            ;; Level tracking
            (= 1 (t/dispute-level (:world er1) 0))
            (= 2 (t/dispute-level w2 0))
            (true? (t/final-round? w2 0))
            ;; Third escalation rejected
            (false? (:ok er3))
            (= :escalation-not-allowed (:error er3))
            ;; Final-round resolution is immediate (state :released, no pending)
            (= :released (t/escrow-state (:world rr) 0))
            (not (:exists (t/get-pending (:world rr) 0)))
            ;; Invariants at every step
            (:all-hold? (inv/check-all (:world er1)))
            (:all-hold? (inv/check-all w2))
            (:all-hold? (inv/check-all (:world rr)))
            (:all-hold? (inv/check-transition world (:world er1)))
            (:all-hold? (inv/check-transition (:world er1) w2))
            (:all-hold? (inv/check-transition w2 (:world rr))))))))))

(deftest property-full-escalation-chain
  (let [result (tc/quick-check num-trials prop-full-escalation-chain)]
    (is (:pass? result)
        (str "Full escalation chain violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 10: Multi-step lifecycle — sequence generator
;;
;; Generates: escalation-count ∈ {0,1,2} × appeal-window ∈ [0,1800] × outcome
;; Produces a full chain:
;;   create → raise-dispute → [escalate × n] → execute-resolution
;;   → [execute-pending if deferred]
;;
;; check-all + check-transition verified at every step.
;; Final state is always terminal.
;; ---------------------------------------------------------------------------

(def prop-multi-step-lifecycle
  (prop/for-all
   [amount     gen-amount
    fee-bps    gen-bps
    esc-count  (gen/large-integer* {:min 0 :max 2})
    appeal-dur (gen/large-integer* {:min 0 :max 1800})
    is-release gen/boolean]
   (let [;; Resolver address at each level — Priority-3 auth tracks et.dispute-resolver
         resolvers  ["0xRes0" "0xRes1" "0xRes2"]
         snap-params {:escrow-fee-bps         fee-bps
                      :max-dispute-duration   3600
                      :appeal-window-duration appeal-dur}]
     (when-some [res (make-disputed-world-for-escalation amount snap-params "0xRes0")]
       (let [{:keys [world]} res]
       ;; Phase 1: apply esc-count escalations, checking invariants at each step
       (let [{:keys [ok world violations]}
             (run-escalation-sequence world resolvers esc-count)]
         (when (and ok (empty? violations))
           ;; Phase 2: execute resolution with the current round's resolver
           (let [curr-res (resolvers esc-count)
                 rr       (res/execute-resolution world 0 curr-res is-release "0xhash" nil)]
             (when (:ok rr)
               (let [w-res (:world rr)]
                 (if (t/terminal-state? w-res 0)
                   ;; Immediate path (no appeal window or final round)
                   (and (:all-hold? (inv/check-all w-res))
                        (:all-hold? (inv/check-transition world w-res)))
                   ;; Deferred path — advance past deadline and execute pending
                   (let [deadline (get-in w-res [:pending-settlements 0 :appeal-deadline])
                         w-exp    (assoc w-res :block-time (+ deadline 1))
                         er       (res/execute-pending-settlement w-exp 0)]
                     (when (:ok er)
                       (and (:all-hold? (inv/check-all w-res))
                            (:all-hold? (inv/check-transition world w-res))
                            (:all-hold? (inv/check-all (:world er)))
                            (:all-hold? (inv/check-transition w-res (:world er)))
                            (t/terminal-state? (:world er) 0))))))))))))))

(deftest property-multi-step-lifecycle
  (let [result (tc/quick-check num-trials prop-multi-step-lifecycle)]
    (is (:pass? result)
        (str "Multi-step lifecycle violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 11: Interrupted flow — dispute timeout
;;
;; Dispute is raised but resolver never acts. Time advances past
;; max-dispute-duration. Keeper auto-cancels the escrow.
;; A late resolver call after cancellation must be rejected.
;; ---------------------------------------------------------------------------

(def prop-interrupted-flow-timeout
  (prop/for-all
   [amount  gen-amount
    fee-bps gen-bps
    max-dur (gen/large-integer* {:min 100 :max 3600})]
   (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps        fee-bps
                                       :max-dispute-duration  max-dur
                                       :appeal-window-duration 0})
         cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" amount
                                (t/make-escrow-settings {:custom-resolver "0xResolver"}) snap)
         dr   (when (:ok cr) (lc/raise-dispute (:world cr) 0 "0xAlice"))]
     (when (and (:ok cr) (:ok dr))
       (let [w-disp      (:world dr)
             ;; Advance time past dispute timeout
             w-timed-out (assoc w-disp :block-time (+ 1000 max-dur 1))
             ;; Keeper triggers auto-cancel
             ac          (lc/auto-cancel-disputed-escrow w-timed-out 0)
             w-cancelled (when (:ok ac) (:world ac))
             ;; Late resolver arrives and tries to submit a resolution
             late-rr     (when w-cancelled
                           (res/execute-resolution w-cancelled 0 "0xResolver"
                                                   true "0xhash" nil))]
         (when (and (:ok ac) late-rr)
           (and
            ;; Auto-cancel produced :refunded
            (= :refunded (t/escrow-state w-cancelled 0))
            ;; Late resolution rejected — escrow is no longer disputed
            (false? (:ok late-rr))
            (= :transfer-not-in-dispute (:error late-rr))
            ;; Invariants hold on the cancelled world
            (:all-hold? (inv/check-all w-cancelled))
            (:all-hold? (inv/check-transition w-disp w-cancelled)))))))))

(deftest property-interrupted-flow-timeout
  (let [result (tc/quick-check num-trials prop-interrupted-flow-timeout)]
    (is (:pass? result)
        (str "Interrupted flow (timeout) violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 12: Adversarial — delayed resolver
;;
;; Resolver submits resolution (deferred), appeal window expires, keeper
;; executes the pending settlement. The resolver then tries to submit
;; a conflicting resolution — must be rejected with :transfer-not-in-dispute.
;; ---------------------------------------------------------------------------

(def prop-adversarial-delayed-resolver
  (prop/for-all
   [amount     gen-amount
    fee-bps    gen-bps
    appeal-dur (gen/large-integer* {:min 100 :max 3600})]
   (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps         fee-bps
                                       :max-dispute-duration   10000
                                       :appeal-window-duration appeal-dur})
         cr   (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" amount
                                (t/make-escrow-settings {:custom-resolver "0xResolver"}) snap)
         dr   (when (:ok cr) (lc/raise-dispute (:world cr) 0 "0xAlice"))]
     (when (and (:ok cr) (:ok dr))
       (let [w-disp   (:world dr)
             ;; Resolver submits → deferred to pending settlement
             rr       (res/execute-resolution w-disp 0 "0xResolver" true "0xhash" nil)
             w-pend   (when (:ok rr) (:world rr))
             ;; Appeal window expires; keeper executes
             deadline (when w-pend (get-in w-pend [:pending-settlements 0 :appeal-deadline]))
             w-exp    (when deadline (assoc w-pend :block-time (+ deadline 1)))
             er       (when w-exp (res/execute-pending-settlement w-exp 0))
             w-final  (when (:ok er) (:world er))
             ;; Resolver arrives late and tries to flip the outcome
             late-rr  (when w-final
                        (res/execute-resolution w-final 0 "0xResolver"
                                                false "0xhash-flip" nil))]
         (when (and (:ok rr) (:ok er) late-rr)
           (and
            ;; Original resolution produced :released
            (= :released (t/escrow-state w-final 0))
            ;; Late flip rejected — escrow is already terminal
            (false? (:ok late-rr))
            (= :transfer-not-in-dispute (:error late-rr))
            ;; Invariants hold at finalization
            (:all-hold? (inv/check-all w-final))
            (:all-hold? (inv/check-transition w-pend w-final)))))))))

(deftest property-adversarial-delayed-resolver
  (let [result (tc/quick-check num-trials prop-adversarial-delayed-resolver)]
    (is (:pass? result)
        (str "Adversarial delayed resolver violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 13: Adversarial — conflicting actions (escalation mid-pending)
;;
;; Resolver submits resolution → pending settlement created.
;; Participant escalates the dispute before the appeal window closes.
;; Escalation must:
;;   (a) clear the pending settlement
;;   (b) reject subsequent execute-pending-settlement
;; The new resolver can then proceed with a fresh resolution.
;; ---------------------------------------------------------------------------

(def prop-adversarial-escalation-clears-pending
  (prop/for-all
   [amount     gen-amount
    fee-bps    gen-bps
    appeal-dur (gen/large-integer* {:min 100 :max 3600})]
   ;; Must use make-disputed-world-for-escalation (not custom-resolver).
   ;; custom-resolver locks Priority-1 permanently, so an escalation resolver
   ;; can never become authorised. et.dispute-resolver tracks escalation via Priority-3.
   (let [snap-params {:escrow-fee-bps         fee-bps
                      :max-dispute-duration   10000
                      :appeal-window-duration appeal-dur}]
     (when-some [res (make-disputed-world-for-escalation amount snap-params "0xRes0")]
       (let [{:keys [world]} res]
       (let [w-disp  world
             ;; Resolver submits → creates pending settlement
             rr      (res/execute-resolution w-disp 0 "0xRes0" true "0xhash" nil)
             w-pend  (when (:ok rr) (:world rr))
             ;; Participant escalates before appeal window closes
             er      (when w-pend
                       (res/escalate-dispute w-pend 0 "0xAlice"
                                             (fn [_ _ _ _] {:ok true :new-resolver "0xRes1"})))
             w-esc   (when (:ok er) (:world er))
             ;; Trying to execute the stale pending settlement must fail
             stale   (when w-esc
                       (res/execute-pending-settlement
                        (assoc w-esc :block-time 99999) 0))
             ;; New resolver submits a fresh resolution (no appeal window at level 1
             ;; if not final round — but snap still has appeal-dur, so may defer)
             new-rr  (when w-esc
                       (res/execute-resolution w-esc 0 "0xRes1" true "0xhash2" nil))]
         (when (and (:ok rr) (:ok er) stale new-rr)
           (and
            ;; Pending settlement was cleared by escalation
            (not (:exists (t/get-pending w-esc 0)))
            ;; Stale execute-pending rejected
            (false? (:ok stale))
            (= :no-pending-settlement (:error stale))
            ;; Fresh resolution by new resolver was accepted
            (:ok new-rr)
            ;; Invariants hold at escalation and after new resolution
            (:all-hold? (inv/check-all w-esc))
            (:all-hold? (inv/check-all (:world new-rr)))
            (:all-hold? (inv/check-transition w-pend w-esc))
            (:all-hold? (inv/check-transition w-esc (:world new-rr))))))))))

(deftest property-adversarial-escalation-clears-pending
  (let [result (tc/quick-check num-trials prop-adversarial-escalation-clears-pending)]
    (is (:pass? result)
        (str "Adversarial escalation-clears-pending violated: " (pr-str (:shrunk result))))))

;; ---------------------------------------------------------------------------
;; Property 14: Adversarial — repeated escalation attempts
;;
;; Three rejection cases, verified across all generated amounts:
;;   (a) at max dispute level    → :escalation-not-allowed
;;   (b) from a non-participant  → :not-participant
;;   (c) after terminal state    → :transfer-not-in-dispute
;; ---------------------------------------------------------------------------

(def prop-adversarial-repeated-escalation
  (prop/for-all
   [amount  gen-amount
    fee-bps gen-bps]
   (let [snap-params {:escrow-fee-bps       fee-bps
                      :max-dispute-duration 3600}]
     (when-some [res (make-disputed-world-for-escalation amount snap-params "0xRes0")]
       (let [{:keys [world]} res]
         (let [esc-fn (fn [_ _ _ _] {:ok true :new-resolver "0xSenior"})
               ;; Escalate to level 1, then level 2 (max)
               er1    (res/escalate-dispute world 0 "0xAlice" esc-fn)
               er2    (when (:ok er1) (res/escalate-dispute (:world er1) 0 "0xAlice" esc-fn))
               w-max  (when (:ok er2) (:world er2))]
           (when (and (:ok er1) (:ok er2) w-max)
             (let [;; (a) Escalation at max level — same participant, same fn
                   at-max    (res/escalate-dispute w-max 0 "0xAlice" esc-fn)
                   ;; (b) Non-participant escalation attempt
                   non-part  (res/escalate-dispute w-max 0 "0xCarol" esc-fn)
                   ;; Finalize the dispute (final-round → immediate)
                   rr        (res/execute-resolution w-max 0 "0xSenior" true "0xhash" nil)
                   w-final   (when (:ok rr) (:world rr))
                   ;; (c) Escalation after terminal state
                   after-fin (when w-final
                               (res/escalate-dispute w-final 0 "0xAlice" esc-fn))]
               (when (and at-max non-part (:ok rr) after-fin)
                 (and
                  ;; (a) Max-level rejection
                  (false? (:ok at-max))
                  (= :escalation-not-allowed (:error at-max))
                  ;; (b) Non-participant rejection
                  (false? (:ok non-part))
                  (= :not-participant (:error non-part))
                  ;; (c) Terminal-state rejection
                  (false? (:ok after-fin))
                  (= :transfer-not-in-dispute (:error after-fin))
                  ;; Invariants hold at max level and after final resolution
                  (:all-hold? (inv/check-all w-max))
                  (:all-hold? (inv/check-all w-final))
                  (:all-hold? (inv/check-transition w-max w-final))))))))))))))

(deftest property-adversarial-repeated-escalation
  (let [result (tc/quick-check num-trials prop-adversarial-repeated-escalation)]
    (is (:pass? result)
        (str "Adversarial repeated escalation violated: " (pr-str (:shrunk result)))))))
