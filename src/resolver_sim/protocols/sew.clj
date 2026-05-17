(ns resolver-sim.protocols.sew
  "SEWProtocol — implementation of DisputeProtocol for the SEW state machine."
  (:require [clojure.string                              :as str]
            [resolver-sim.protocols.protocol             :as proto]
            [resolver-sim.protocols.sew.types            :as t]
            [resolver-sim.protocols.sew.diff             :as diff]
            [resolver-sim.protocols.sew.state-machine    :as sm]
            [resolver-sim.protocols.sew.lifecycle        :as lc]
            [resolver-sim.protocols.sew.resolution       :as res]
            [resolver-sim.protocols.sew.registry         :as reg]
            [resolver-sim.protocols.sew.accounting       :as acct]
            [resolver-sim.protocols.sew.authority        :as auth]
            [resolver-sim.protocols.sew.trace-metadata   :as meta]
            [resolver-sim.protocols.sew.invariants       :as inv]
            [resolver-sim.protocols.sew.projection       :as sew-proj]
            [resolver-sim.protocols.sew.equilibrium      :as sew-eq]
            [resolver-sim.protocols.sew.advisory         :as sew-adv]
            [resolver-sim.protocols.sew.db               :as sew-db]
            [resolver-sim.contract-model.replay          :as replay]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

;; Maximum safe amount: prevents Long overflow in (* amount fee-bps)
;; fee-bps max = 10000; Long/MAX_VALUE / 10000 ≈ 922_337_203_685_477
(def ^:private max-safe-amount 922337203685477)

;; ---------------------------------------------------------------------------
;; Helpers (Moved from replay.clj)
;; ---------------------------------------------------------------------------

(defn- resolve-address
  "Return {:ok true :address addr} or {:ok false :error :unknown-agent}.
   Never throws."
  [agent-index agent-id]
  (if-let [agent (get agent-index agent-id)]
    {:ok true :address (:address agent)}
    {:ok false :error :unknown-agent :detail {:agent-id agent-id}}))

(defn- check-paused [world]
  (if (:paused? world)
    (t/fail :protocol-paused)
    {:ok true}))

;; Defaults below are the on-chain contract defaults from BaseEscrow/EscrowFactory.
;; Non-zero on-chain defaults:
;;   :resolver-fee-bps   50    → 0.5%  (ESCROW_FEE_DENOMINATOR = 10 000)
;;   :max-dispute-duration 2592000 → 30 days in seconds
;;   :resolver-bond-bps  1000  → 10%   (DR3 resolver bond requirement)
;;   :fraud-slash-bps    5000  → 50%   (DR3 fraud penalty)
;; Everything else defaults to 0 / nil — meaning "feature disabled at module level".
(defn- build-snapshot [pp]
  (t/make-module-snapshot
   {:escrow-fee-bps               (get pp :resolver-fee-bps 50)
    :resolution-module            (get pp :resolution-module nil)
    :appeal-window-duration       (get pp :appeal-window-duration 0)
    :max-dispute-duration         (get pp :max-dispute-duration 2592000)
    :appeal-bond-protocol-fee-bps (get pp :appeal-bond-protocol-fee-bps 0)
    :dispute-resolver             (get pp :dispute-resolver nil)
    :appeal-bond-bps              (get pp :appeal-bond-bps 0)
    :resolver-bond-bps            (get pp :resolver-bond-bps 1000)
    :appeal-bond-amount           (get pp :appeal-bond-amount 0)
    :reversal-slash-bps           (get pp :reversal-slash-bps 0)
    :fraud-slash-bps              (get pp :fraud-slash-bps 5000)
    :challenge-window-duration    (get pp :challenge-window-duration 0)
    :challenge-bond-bps           (get pp :challenge-bond-bps 0)
    :challenge-bounty-bps         (get pp :challenge-bounty-bps 0)
    :default-auto-release-delay   (get pp :default-auto-release-delay 0)
    :default-auto-cancel-delay    (get pp :default-auto-cancel-delay 0)
    :yield-generation-module      (get pp :yield-generation-module nil)
    :yield-distribution-module    (get pp :yield-distribution-module nil)
    :yield-protocol-fee-bps       (get pp :yield-protocol-fee-bps 0)
    :cancellation-strategy        (get pp :cancellation-strategy nil)
    :release-strategy             (get pp :release-strategy nil)
    :incentive-module             (get pp :incentive-module nil)}))

(defn- sender-only-release [world workflow-id caller]
  (let [et (t/get-transfer world workflow-id)]
    {:allowed? (= caller (:from et)) :reason-code 0}))

(defn- has-active-dispute-for-resolver?
  [world resolver-addr]
  (boolean
   (some (fn [[_ et]]
           (and (= :disputed (:escrow-state et))
                (= resolver-addr (:dispute-resolver et))))
         (:escrow-transfers world))))

(defn- governance-actor?
  [agent]
  (let [r (or (:role agent) (:type agent) "")]
    (contains? #{"governance" :governance} r)))

(defn- wf-id
  "Compatibility accessor for workflow identifiers.
   Accepts either {:workflow-id n} (current) or {:id n} (legacy)."
  [event]
  (or (get-in event [:params :workflow-id])
      (get-in event [:params :id])))

;; ---------------------------------------------------------------------------
;; Dispatch (The SEW State Machine Actions)
;; ---------------------------------------------------------------------------

(defmulti apply-action
  "Dispatch on action name (string), converting underscores to hyphens for compatibility."
  (fn [_ctx _world event]
    (-> (:action event)
        name
        (str/replace "_" "-"))))

(defmethod apply-action "create-escrow"
  [{:keys [agent-index snapshot]} world event]
  (let [p       (:params event)
        ar      (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        (let [caller (:address ar)
              token  (:token p)
              to     (:to p)
              amount (:amount p)]
          ;; Guard against Long overflow in fee arithmetic
          (if (or (nil? amount) (<= amount 0) (> amount max-safe-amount))
            {:ok false :error :amount-out-of-safe-range
             :detail {:amount amount :max max-safe-amount}}
            (let [cres     (get p :custom-resolver)
                  settings (t/make-escrow-settings {:custom-resolver cres})
                  result   (lc/create-escrow world caller token to amount settings snapshot)]
              (if (:ok result)
                (assoc result :extra {:workflow-id (:workflow-id result)})
                result))))))))

(defmethod apply-action "raise-dispute"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        (lc/raise-dispute world (wf-id event) (:address ar))))))

(defmethod apply-action "execute-resolution"
  [{:keys [agent-index resolution-module-fn resolution-level-map]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        (let [p               (:params event)
              workflow-id     (:workflow-id p)
              is-release      (get p :is-release true)
              resolution-hash (get p :resolution-hash "0xsimhash")
              ;; Kleros mode: build module fn per-step so it reads the live dispute level
              ;; from world (level changes after each escalation).
              effective-rm-fn (or (when resolution-level-map
                                    (auth/make-kleros-module
                                     resolution-level-map
                                     #(t/dispute-level world %)))
                                  resolution-module-fn)]
          (res/execute-resolution world (or workflow-id (wf-id event)) (:address ar)
                                  is-release resolution-hash effective-rm-fn))))))

(defmethod apply-action "execute-pending-settlement"
  [_ctx world event]
  (if (:paused? world)
    (t/fail :protocol-paused)
    (res/execute-pending-settlement world (wf-id event))))

(defmethod apply-action "automate-timed-actions"
  [_ctx world event]
  (res/automate-timed-actions world (wf-id event)))

(defmethod apply-action "release"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        (lc/release world (wf-id event)
                    (:address ar) sender-only-release)))))

(defmethod apply-action "sender-cancel"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        ;; nil cancel-strategy → mutual-consent path only
        (lc/sender-cancel world (wf-id event) (:address ar) nil)))))

(defmethod apply-action "recipient-cancel"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        (lc/recipient-cancel world (wf-id event) (:address ar) nil)))))

(defmethod apply-action "auto-cancel-disputed"
  [_ctx world event]
  (lc/auto-cancel-disputed-escrow world (wf-id event)))

(defmethod apply-action "escalate-dispute"
  [{:keys [agent-index escalation-fn]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (if (:paused? world)
        (t/fail :protocol-paused)
        (let [workflow-id (wf-id event)
              result      (res/escalate-dispute world workflow-id (:address ar) escalation-fn)]
          (if (:ok result)
            (assoc result :extra {:new-level    (:new-level result)
                                  :new-resolver (:new-resolver result)})
            result))))))

(defmethod apply-action "rotate-dispute-resolver"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [workflow-id   (wf-id event)
            new-resolver  (get-in event [:params :new-resolver])
            result        (res/rotate-dispute-resolver world workflow-id new-resolver)]
        (if (:ok result)
          (assoc result :extra {:old-resolver (:old-resolver result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "register-stake"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [amount (get-in event [:params :amount] 0)]
        (t/ok (reg/register-stake world (:address ar) amount))))))

(defmethod apply-action "withdraw-stake"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [resolver-addr (:address ar)
            amount       (get-in event [:params :amount])
            current      (reg/get-stake world resolver-addr)
            pending-slash-amount (reduce + 0
                                  (for [[_ slash] (:pending-fraud-slashes world)
                                        :when (and (= (:resolver slash) resolver-addr)
                                                   (#{:pending :appealed} (:status slash)))]
                                    (:amount slash)))]
        (cond
          (or (nil? amount) (not (number? amount)) (<= amount 0))
          (t/fail :invalid-amount)

          (has-active-dispute-for-resolver? world resolver-addr)
          (t/fail :active-disputes-block-withdrawal)

          (> pending-slash-amount (- current amount))
          (t/fail :pending-slash-blocks-withdrawal)

          (> (get-in world [:resolver-frozen-until resolver-addr] 0) (:block-time world))
          (t/fail :resolver-frozen)

          :else
          (reg/withdraw-stake world resolver-addr amount))))))

(defmethod apply-action "withdraw-escrow"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p           (:params event)
            workflow-id (or (:workflow-id p) (wf-id event))]
        (acct/withdraw-escrow world workflow-id (:address ar))))))

(defmethod apply-action "execute-reentrant-withdraw"
  [ctx world event]
  (let [p        (:params event)
        callback (:callback p)
        ;; 1. Simulate the "External Call" gap by executing the callback action first.
        ;; This models the control-flow handover to the attacker.
        cb-res   (apply-action ctx world callback)]
    (if-not (:ok cb-res)
      cb-res
      ;; 2. Execute the main withdrawal on the world returned by the callback.
      ;; In a safe implementation (Checks-Effects-Interactions), the callback's
      ;; state changes (zeroing the balance) will cause this second call to fail
      ;; with :no-claimable-balance.
      (let [final-res (apply-action ctx (:world cb-res) (assoc event :action "withdraw-escrow"))]
        ;; We return the final world but we expect the specific result of the main call
        ;; to be a failure if the reentrancy was successfully blocked.
        final-res))))

(defmethod apply-action "withdraw-fees"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p     (:params event)
            token (:token p)]
        (acct/withdraw-fees world token)))))

(defmethod apply-action "set-token-liquidity-crunch"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [agent (get agent-index (:agent event))]
        ;; Only governance or a 'system' actor should be able to trigger this in a real simulation,
        ;; but for deterministic scenarios we allow the caller to be anyone if they have the strategy.
        (let [p       (:params event)
              token   (:token p)
              active? (get p :active? true)]
          (t/ok (update world :token-liquidity-crunch
                        (if active?
                          #(conj (or % #{}) token)
                          #(disj % token)))))))))

(defmethod apply-action "set-paused"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (t/ok (assoc world :paused? (get-in event [:params :paused?] true))))))

(defmethod apply-action "register-resolver-bond"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p      (:params event)
            stable (get p :stable 0)
            sew    (get p :sew 0)]
        (t/ok (assoc-in world [:resolver-bonds (:address ar)]
                        {:stable stable :sew sew}))))))

(defmethod apply-action "register-senior-bond"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p            (:params event)
            coverage-max (get p :coverage-max 0)]
        (t/ok (assoc-in world [:senior-bonds (:address ar)]
                        {:coverage-max coverage-max :reserved-coverage 0}))))))

(defmethod apply-action "delegate-to-senior"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p           (:params event)
            senior-addr (:senior-addr p)
            coverage    (:coverage p 0)
            senior-bond (get-in world [:senior-bonds senior-addr])]
        (if (nil? senior-bond)
          (t/fail :senior-not-registered)
          (let [new-reserved (+ (:reserved-coverage senior-bond) coverage)
                max-coverage (:coverage-max senior-bond)]
            (if (> new-reserved max-coverage)
              (t/fail :senior-coverage-exceeded)
              (t/ok (assoc-in world [:senior-bonds senior-addr :reserved-coverage]
                              new-reserved)))))))))

(defmethod apply-action "propose-fraud-slash"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [agent (get agent-index (:agent event))]
        (if-not (governance-actor? agent)
          (t/fail :not-governance)
          (let [p             (:params event)
                workflow-id   (or (:workflow-id p) (wf-id event))
                resolver-addr (:resolver-addr p)
                amount        (:amount p)]
            (res/propose-fraud-slash world workflow-id (:address ar) resolver-addr amount)))))))

(defmethod apply-action "challenge-resolution"
  [{:keys [agent-index escalation-fn]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (res/challenge-resolution world (wf-id event) (:address ar) escalation-fn))))

(defmethod apply-action "appeal-slash"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p (:params event)]
        (res/appeal-slash world (or (:workflow-id p) (wf-id event)) (:address ar)
                          (or (:slash-id p) (:workflow-id p) (wf-id event)))))))

(defmethod apply-action "resolve-appeal"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [agent (get agent-index (:agent event))]
        (if-not (governance-actor? agent)
          (t/fail :not-governance)
          (let [p (:params event)]
            (res/resolve-appeal world
            ;; NOTE: event param key remains :upheld? for wire compatibility.
            ;; Semantics: this flag is about the APPEAL outcome.
            ;;   true  => appeal upheld (accepted)  => slash reversed
            ;;   false => appeal rejected           => slash remains pending
                                (or (:workflow-id p) (wf-id event))
                                (:address ar)
                                (:upheld? p)
                                (or (:slash-id p) (:workflow-id p) (wf-id event)))))))))

(defmethod apply-action "execute-fraud-slash"
  [_ctx world event]
  (let [p (:params event)]
    (res/execute-fraud-slash world (or (:workflow-id p) (wf-id event))
                             (or (:slash-id p) (:workflow-id p) (wf-id event)))))

(defmethod apply-action "advance-time"
  [_ctx world _event]
  ;; Time is already advanced before dispatch — this is a pure no-op.
  (t/ok world))

(defmethod apply-action :default
  [_ctx _world event]
  {:ok false :error :unknown-action :detail {:action (:action event)}})

;; ---------------------------------------------------------------------------
;; Invariant Checks
;; ---------------------------------------------------------------------------

(defn- run-single-invariants
  "Single-world invariants (solvency, fee non-negative).
   Returns {:ok? bool :violations map-or-nil}."
  [world]
  (let [r (inv/check-all world)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

(defn- run-transition-invariants
  "Cross-world invariants (terminal state irreversibility).
   Returns {:ok? bool :violations map-or-nil}."
  [world-before world-after]
  (let [r (inv/check-transition world-before world-after)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

;; ---------------------------------------------------------------------------
;; Trace Snapshot
;; ---------------------------------------------------------------------------

(defn- world-snapshot [world]
  {:block-time         (:block-time world)
   :escrow-count       (count (:escrow-transfers world))
   :total-held         (:total-held world)
   :total-fees         (:total-fees world)
   :pending-count      (count (filter #(:exists (val %)) (:pending-settlements world)))
   :live-states        (into {} (map (fn [[id et]] [id (:escrow-state et)])
                                     (:escrow-transfers world)))
   :dispute-levels     (into {} (:dispute-levels world))
   :dispute-resolvers  (into {} (map (fn [[id et]] [id (:dispute-resolver et)])
                                     (:escrow-transfers world)))
   :resolver-rotations (into {} (:resolver-rotations world))
   :escrow-amounts     (into {} (map (fn [[id et]] [id (:amount-after-fee et)])
                                     (:escrow-transfers world)))
   :resolver-stakes     (:resolver-stakes world)
   :resolver-slash-total (:resolver-slash-total world)
   :bond-distribution   (:bond-distribution world)
   :claimable           (:claimable world {})
   :bond-balances       (:bond-balances world {})})

;; SEW-specific error codes that indicate a state-logic failure (as opposed to
;; authorisation or parameter failures).  Used by classify-event to tag events
;; for the :invalid-state-transitions metric.
(def ^:private sew-state-error-codes
  #{:transfer-not-pending
    :transfer-not-in-dispute
    :invalid-state-for-release
    :invalid-state-for-refund
    :resolution-without-settlement})

;; ---------------------------------------------------------------------------
;; SEWProtocol Implementation
;; ---------------------------------------------------------------------------

(deftype SEWProtocol []
  proto/DisputeProtocol

  (build-execution-context [_ agents protocol-params]
    (let [pp         protocol-params
          snapshot   (build-snapshot pp)
          rm-addr    (get pp :resolution-module nil)
          esc-map    (get pp :escalation-resolvers nil)
          level-map  (when esc-map
                       (into {} (map (fn [[k v]] [(parse-long (name k)) v]) esc-map)))
          rm-fn      (when (and rm-addr (not= rm-addr "") (nil? level-map))
                       (auth/make-default-resolution-module rm-addr))
          esc-fn     (when level-map
                       (fn [_world _wf-id _caller current-level]
                         (let [next-level   (inc current-level)
                               new-resolver (get level-map next-level)]
                           (if new-resolver
                             {:ok true :new-resolver new-resolver}
                             {:ok false :error :escalation-not-allowed}))))]
      {:agent-index          (into {} (map (juxt :id identity) agents))
       :snapshot             snapshot
       :escalation-fn        esc-fn
       :resolution-module-fn rm-fn
       :resolution-level-map level-map}))

  (dispatch-action [_ context world event]
    (apply-action context world event))

  (check-invariants-single [_ world]
    (run-single-invariants world))

  (check-invariants-transition [_ world-before world-after]
    (run-transition-invariants world-before world-after))

  (world-snapshot [_ world]
    (world-snapshot world))

  (init-world [_ scenario]
    (let [init-time    (get scenario :initial-block-time 1000)
          tp           (:token-params scenario)
          fot-bps      (when tp (get tp :fee-on-transfer 0))
          s-tokens     (into #{} (keep #(get-in % [:params :token]) (:events scenario)))
          base         (t/empty-world init-time)]
      (if (and fot-bps (pos? fot-bps) (seq s-tokens))
        (reduce (fn [w tok] (assoc-in w [:token-fot-bps tok] fot-bps)) base s-tokens)
        base)))

  (compute-projection [_ world]
    [(diff/projection world) (diff/projection-hash world)])

  (classify-transition [_ action result-kw]
    {:transition/type (meta/transition-type action)
     :resolution/path (meta/resolution-path action)})

  (resolve-id-alias [_ event id-alias-map]
    (let [wf-val (get-in event [:params :workflow-id])]
      (if (string? wf-val)
        (if-let [int-id (get id-alias-map wf-val)]
          {:ok true :event (assoc-in event [:params :workflow-id] int-id)}
          {:ok false :error :unresolved-alias :alias wf-val :seq (:seq event)})
        {:ok true :event event})))

  (created-id [_ action extra]
    (when (= action "create_escrow")
      (:workflow-id extra)))

  (open-entities [_ world]
    (vec (for [[wf et] (:escrow-transfers world)
               :when (= :disputed (:escrow-state et))]
           wf)))

  (classify-event [_ event result-kw error-kw]
    (let [action    (:action event)
          accepted? (= result-kw :ok)]
      (cond-> #{}
        (and accepted? (= action "create_escrow"))           (conj :entity-created)
        (and accepted? (= action "raise_dispute"))           (conj :dispute-raised)
        (and accepted? (= action "execute_resolution"))      (conj :dispute-resolved)
        (and accepted? (= action "execute_pending_settlement")) (conj :settlement-executed)
        (and (= result-kw :rejected)
             (contains? sew-state-error-codes error-kw))    (conj :invalid-state-transition))))

  (metric-vocabulary [_]
    #{:total-escrows
      :total-volume
      :disputes-triggered
      :resolutions-executed
      :pending-settlements-executed
      :double-settlements
      :invalid-state-transitions
      :negative-payoff-count
      :coalition-net-profit
      ;; Financial metric: decrease in total-held from accepted adversarial events.
      ;; Declared here (not in base-metrics) because non-financial protocols don't track it.
      :funds-lost})

  (adversarial-event? [_ event agent]
    ;; Per-event :adversarial? flag takes precedence over agent role/strategy/type
    ;; to allow mixed-role actors to mark individual calls adversarial.
    (boolean (or (:adversarial? event)
                 (= "malicious" (:strategy agent))
                 (= "attacker"  (:role agent))
                 (= "attacker"  (:type agent)))))

  (accum-protocol-metrics [_ metrics event-tags event accepted? attack? world-before world-after]
    ;; double-settle? checks :resolutions-executed BEFORE this event's increment
    ;; (the metrics arg is the pre-event state), so order matters here.
    (let [double-settle? (and accepted?
                              (or (contains? event-tags :dispute-resolved)
                                  (contains? event-tags :settlement-executed))
                              (pos? (:resolutions-executed metrics)))
          ;; funds-lost: decrease in total-held caused by an accepted adversarial
          ;; action.  Computed as max(0, held-before - held-after) so refunds into
          ;; held don't produce negative deltas.
          held-fn        (fn [w]
                           (let [held (:total-held w {})]
                             (if (map? held) (apply + (vals held)) 0)))
          funds-lost-delta (when (and attack? accepted?)
                             (max 0 (- (held-fn world-before) (held-fn world-after))))]
      (cond-> metrics
        (contains? event-tags :entity-created)
        (-> (update :total-escrows inc)
            (update :total-volume + (get-in event [:params :amount] 0)))

        (contains? event-tags :dispute-raised)
        (update :disputes-triggered inc)

        (contains? event-tags :dispute-resolved)
        (update :resolutions-executed inc)

        (contains? event-tags :settlement-executed)
        (update :pending-settlements-executed inc)

        double-settle?
        (update :double-settlements inc)

        (contains? event-tags :invalid-state-transition)
        (update :invalid-state-transitions inc)

        (and funds-lost-delta (pos? funds-lost-delta))
        (update :funds-lost + funds-lost-delta))))

  (trace-projection [_ result]
    (sew-proj/trace-end-projection result))

  (mechanism-property-validators [_]
    sew-eq/mechanism-property-validators)

  (equilibrium-concept-validators [_]
    sew-eq/equilibrium-concept-validators)

  (protocol-id [_] "sew-v1")

  (summarise-batch [_ outcomes]
    (sew-db/sew-summarise-batch outcomes))

  (io-projection [_ data target-type]
    (case target-type
      :world-view
      {:block-time    (:block-time data)
       :entity-count  (count (:escrow-transfers data {}))
       :pending-count (count (filter #(:exists (val %)) (:pending-settlements data {})))}

      :telemetry-record
      ;; data is a run-trial or run-with-divergence-check result map.
      ;; Returns the SEW-specific metrics blob for db/telemetry/insert-trial-result!.
      (let [cm  (if (contains? data :contract) (:contract data) data)
            div (get data :divergence {})]
        {:strategy          (get cm :strategy :honest)
         :dispute-correct?  (boolean (get cm :dispute-correct?))
         :appeal-triggered? (boolean (get cm :appeal-triggered?))
         :slashed?          (boolean (get cm :slashed?))
         :profit-honest     (long (get cm :profit-honest 0))
         :profit-malice     (long (get cm :profit-malice 0))
         :cm-fee            (long (get cm :cm/fee 0))
         :cm-afa            (long (get cm :cm/afa 0))
         :diffs             (get div :diffs)})

      :event-records
      ;; data is {:trial-id string :params map :result run-trial-result}.
      ;; Reconstructs the 3-event SEW lifecycle timeline.
      (let [{:keys [trial-id params result]} data
            cm    (if (contains? result :contract) (:contract result) result)
            btime (long (get params :block-time 1000))
            fstate (get cm :cm/final-state :pending)
            mk    (fn [step etype estate t]
                    {:id           (str trial-id "-" (name step))
                     :trial-id     trial-id
                     :entity-id    "0"
                     :event-type   etype
                     :entity-state estate
                     :block-time   t
                     :valid-from   (java.util.Date. (* ^long t 1000))})]
        [(mk :created  :sew/escrow-created   :pending  btime)
         (mk :disputed :sew/dispute-raised   :disputed (+ btime 10))
         (mk :final    :sew/escrow-finalized  fstate    (+ btime 200))])

      :forge-trace
      ;; data is a replay result map (contains :trace :scenario :metrics etc.)
      (let [scenario (:scenario data)]
        (do
          (require 'resolver-sim.protocols.sew.io.trace-export)
          ((resolve 'resolver-sim.protocols.sew.io.trace-export/export-trace-fixture)
           data scenario)))

      nil))

  (advisory [_ world request-type context]
    (case request-type
      :suggest-actions          (sew-adv/suggest-actions          world context)
      :session-signals          (sew-adv/session-signals          world context)
      :evaluate-payoff          (sew-adv/evaluate-payoff          world context)
      :evaluate-attack-objective (sew-adv/evaluate-attack-objective world context)
      {:not-supported true}))

) ; end SEWProtocol deftype

(def protocol (SEWProtocol.))

(defn replay-with-sew-protocol
  "SEW convenience entry point: replays a scenario using SEWProtocol.
   This is the canonical SEW-specific replay function; it lives here
   (in the SEW protocol namespace) rather than in the generic replay engine.
   For protocol-agnostic replay use replay/replay-with-protocol directly."
  [scenario]
  (replay/replay-with-protocol protocol scenario))
