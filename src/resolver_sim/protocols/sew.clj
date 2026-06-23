(ns resolver-sim.protocols.sew
  "SewProtocol — Sew reference adapter/reference implementation of tiered protocol interfaces.

   Boundary note:
   - This namespace is the canonical Sew adapter for the framework substrate.
   - Reusable adapter contracts are defined in protocols/protocol.clj.
   - Domain semantics implemented here remain Sew-specific unless explicitly
     promoted via adapter-facing contracts."
  (:require [resolver-sim.protocols.protocol             :as proto]
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
            [resolver-sim.db.sew                         :as sew-db]
            [resolver-sim.db.temporal                    :as temporal]
            [resolver-sim.protocols.sew.yield.policy     :as yield-policy]
            [resolver-sim.util.attribution            :as attr]
            [resolver-sim.contract-model.replay          :as replay]
            [resolver-sim.contract-model.idempotency     :as idem]
            [resolver-sim.protocols.sew.snapshot         :as sew-snapshot]
            [resolver-sim.yield.protocols                :as yield-proto]
            [resolver-sim.yield.module                   :as yield-module]
            [resolver-sim.yield.risk                     :as yield-risk]
            [resolver-sim.protocols.sew.compat           :as compat]
            [resolver-sim.protocols.sew.action-context   :as actx]
            [resolver-sim.yield.expectations             :as yield-exp]
            [resolver-sim.yield.evidence                 :as yield-evi]
            [resolver-sim.time.context                   :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-safe-amount 922337203685477)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

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

(defn- governance-pred
  "Build a governance predicate from the execution context.
   In :full mode every agent passes; otherwise only governance-role agents pass."
  [context]
  (let [mode (get context :governance-mode :restricted)]
    (fn [agent]
      (or (= :full mode)
          (governance-actor? agent)))))

(defn- event-workflow-id
  [event]
  (let [p (:params event)]
    (or (:workflow-id p) (compat/wf-id event))))

(defn- event-slash-id
  [event]
  (let [p (:params event)]
    (or (:slash-id p) (:workflow-id p) (compat/wf-id event))))

(defn- event-slash-bps
  [event]
  (let [p (:params event)]
    (get p :slash-bps nil)))

(defn- event-id
  "Optional logical event identifier used for replay dedupe."
  [event]
  (compat/event-id event))

(def replay-sensitive-actions
  "Actions that should be replay-idempotent when a logical event-id is provided."
  #{"escalate-dispute"
    "challenge-resolution"
    "execute-resolution"
    "execute-pending-settlement"
    "rotate-dispute-resolver"
    "propose-fraud-slash"
    "resolve-appeal"
    "execute-fraud-slash"})

(defn replay-sensitive-action?
  "True when `event` is subject to replay-boundary dedupe when event-id is present."
  [event]
  (contains? replay-sensitive-actions (compat/canonical-action event)))

(defn- replay-sensitive?
  [event]
  (replay-sensitive-action? event))

(defn- dedupe-op-key
  "Build a stable dedupe key for replay-sensitive events."
  [world event]
  (let [wf  (event-workflow-id event)
        sid (event-slash-id event)
        eid (event-id event)
        action (compat/canonical-action event)
        explicit-hop (compat/hop-id event)
        hop-level (when (and (nil? explicit-hop)
                             (contains? #{"escalate-dispute" "challenge-resolution"} action))
                    (t/dispute-level world wf))
        hop-scope (or explicit-hop hop-level)]
    [:sew
     :replay-dedupe
     action
     (:agent event)
     wf
     sid
     hop-scope
     eid]))

(defn- sew-temporal-rules
  "Protocol-aware temporal guards executed before dispatch.
   These rules are optional and run through replay's generic temporal rule engine."
  []
  [{:id :sew/appeal-window-open
    :check (fn [{:keys [world event event-time]}]
             (if (= "execute_pending_settlement" (:action event))
               (let [wf-id   (compat/wf-id event)
                     pending (t/get-pending world wf-id)]
                 (if (and (:exists pending)
                          (< event-time (:appeal-deadline pending)))
                   {:ok? false :error :appeal-window-not-expired}
                   {:ok? true}))
               {:ok? true}))}])

(defn- sew-event-conflict-domains
  "Conservative serialization/conflict domains for same-timestamp batch replay.
   
   Actions are grouped by what state they modify:
     Group 1 — workflow-scoped (escrow, dispute, resolution state)
              [:workflow wf-id] + optional [:resolver r] + optional [:token t]
              Note: [:token t] only resolves when the event carries :token in
              params (e.g. create-escrow).  Actions like withdraw-escrow or
              auto-cancel-disputed don't carry :token — the token is derived
              from escrow state, not event params — so they remain workflow-only.
     Group 2 — resolver-scoped (stake, bond, slash state)
     Group 3 — token-scoped (liquidity, yield risk)
     Group 4 — global (paused, fees, governance params)
   
   agent-index is used to derive the resolver address from the event's :agent
   field for actions like register-stake where the actor IS the resolver."
  [world event agent-index]
  (let [action (compat/canonical-action event)
        wf-id  (event-workflow-id event)
        token  (some-> (get-in event [:params :token]) keyword)
        agent-addr (some-> agent-index (get (:agent event)) :address)
        resolver (or (get-in event [:params :resolver-addr])
                     (get-in event [:params :resolver])
                     (get-in event [:params :senior-addr])
                     (get-in world [:escrow-transfers wf-id :dispute-resolver]))]
    (cond
      (contains? #{"create-escrow" "raise-dispute" "release" "sender-cancel" "recipient-cancel"
                   "execute-resolution" "execute-pending-settlement" "escalate-dispute"
                   "challenge-resolution" "automate-timed-actions" "withdraw-escrow"
                   "rotate-dispute-resolver" "auto-cancel-disputed" "claim-deferred-yield"
                   "trigger-accrue" "submit-evidence" "execute-reentrant-withdraw"}
                 action)
      (cond-> #{[:workflow wf-id]}
        resolver (conj [:resolver resolver])
        token (conj [:token token]))

      (contains? #{"set-resolver-capacity" "register-stake" "withdraw-stake"
                   "register-resolver-bond" "register-senior-bond"
                   "propose-fraud-slash" "appeal-slash" "resolve-appeal" "execute-fraud-slash"
                   "force-reversal-slash" "delegate-to-senior"}
                 action)
      ;; Group 2: resolver-scoped actions.  Derive the resolver address from
      ;; the performing actor when the event params don't specify it directly
      ;; (e.g. register-stake, withdraw-stake use the acting agent's address).
      (let [g2-resolver (or resolver agent-addr)]
        (cond-> #{}
          g2-resolver (conj [:resolver g2-resolver])
          wf-id (conj [:workflow wf-id])))

      (contains? #{"set-token-liquidity-crunch" "set-yield-risk"} action)
      (if token #{[:token token]} #{[:global :unknown]})

      (contains? #{"set-paused" "withdraw-fees" "governance-update-fee"} action)
      #{[:global :protocol]}

      :else
      #{[:global :unknown]})))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defmulti apply-action
  (fn [_ctx _world event]
    (compat/canonical-action event)))

(defmethod apply-action "create-escrow"
  [{:keys [agent-index snapshot]} world event]
  (let [p (:params event)]
    (if-let [cb-failure (:error (res/circuit-breaker-active? world))]
      (t/fail cb-failure)
      (actx/with-resolved-actor-and-unpaused
        agent-index world event
        (fn [caller]
          (let [token  (keyword (:token p))
                to     (:to p)
                amount (:amount p)]
            (if (or (nil? amount) (<= amount 0) (> amount max-safe-amount))
              {:ok false :error :amount-out-of-safe-range
               :detail {:amount amount :max max-safe-amount}}
              (let [cres     (or (get p :custom-resolver) (get p :dispute-resolver))
                    settings (t/make-escrow-settings
                              {:custom-resolver cres
                               :release-address (:release-address p)
                               :yield-preset (or (:yield-preset p) (:yield_preset p))
                               :auto-release-time (:auto-release-time p)
                               :auto-cancel-time (:auto-cancel-time p)})
                    result   (lc/create-escrow world caller token to amount settings snapshot)]
                (if (:ok result)
                  (assoc result :extra {:workflow-id (:workflow-id result)})
                  result)))))))))

(defmethod apply-action "raise-dispute"
  [{:keys [agent-index]} world event]
  (if-let [cb-failure (:error (res/circuit-breaker-active? world))]
    (t/fail cb-failure)
    (actx/with-resolved-actor-and-unpaused
      agent-index world event
      (fn [addr]
        (lc/raise-dispute world (compat/wf-id event) addr)))))

(defmethod apply-action "execute-resolution"
  [{:keys [agent-index resolution-module-fn resolution-level-map]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (let [p               (:params event)
            workflow-id     (:workflow-id p)
            is-release      (get p :is-release true)
            resolution-hash (get p :resolution-hash "0xsimhash")
            effective-rm-fn (or (when resolution-level-map
                                  (auth/make-kleros-module
                                   resolution-level-map
                                   #(t/dispute-level world %)))
                                resolution-module-fn)]
        (res/execute-resolution world (or workflow-id (compat/wf-id event)) addr
                                is-release resolution-hash effective-rm-fn)))))

(defmethod apply-action "execute-pending-settlement"
  [_ctx world event]
  (if (:paused? world)
    (t/fail :protocol-paused)
    (res/execute-pending-settlement world (compat/wf-id event))))

(defmethod apply-action "automate-timed-actions"
  [_ctx world event]
  (res/automate-timed-actions world (compat/wf-id event)))

(defmethod apply-action "release"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (lc/release world (compat/wf-id event) addr sender-only-release))))

(defmethod apply-action "sender-cancel"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (lc/sender-cancel world (compat/wf-id event) addr nil))))

(defmethod apply-action "recipient-cancel"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (lc/recipient-cancel world (compat/wf-id event) addr nil))))

(defmethod apply-action "auto-cancel-disputed"
  [_ctx world event]
  (lc/auto-cancel-disputed-escrow world (compat/wf-id event)))

(defmethod apply-action "escalate-dispute"
  [{:keys [agent-index escalation-fn]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (let [workflow-id (compat/wf-id event)
            result      (res/escalate-dispute world workflow-id addr escalation-fn)]
        (if (:ok result)
          (assoc result :extra (merge (:extra result)
                                      (when (:new-level result)
                                        {:new-level    (:new-level result)
                                         :new-resolver (:new-resolver result)})))
          result)))))

(defmethod apply-action "rotate-dispute-resolver"
  [{:keys [agent-index] :as context} world event]
  (actx/with-governance-actor
    agent-index event
    (governance-pred context)
    (fn [addr _agent]
      (let [workflow-id   (compat/wf-id event)
            new-resolver  (get-in event [:params :new-resolver])
            result        (res/rotate-dispute-resolver world workflow-id new-resolver)]
        (if (:ok result)
          (assoc result :extra {:old-resolver (:old-resolver result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "set-resolver-capacity"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [max-concurrent (get-in event [:params :max-concurrent] 0)]
        (attr/log-with-attr :debug "set-resolver-capacity"
                            {:resolver addr :max-concurrent max-concurrent})
        (t/ok (t/set-resolver-capacity world addr max-concurrent))))))

(defmethod apply-action "register-stake"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p                (:params event)
            amount           (:amount p 0)
            token            (keyword (:token p "USDC"))
            yield-profile-id (:yield-profile-id p)
            world            (reg/register-stake world addr amount yield-profile-id)
            world            (acct/add-held world token amount)
            world            (update-in world [:total-principal-deposited token] (fnil + 0) amount)]
        (if yield-profile-id
          (let [{:keys [module-id]} (yield-proto/resolve-yield-profile yield-profile-id)
                world' (-> world
                           (yield-proto/apply-op {:op/type :yield/deposit
                                                  :owner/id (lc/resolver-yield-owner-id addr)
                                                  :module/id module-id
                                                  :amount amount
                                                  :token token})
                           (lc/init-resolver-yield-accrual-time addr))]
            (t/ok world'))
          (t/ok world))))))

(defmethod apply-action "withdraw-stake"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [resolver-addr]
      (let [p                    (:params event)
            amount               (:amount p)
            token                (keyword (:token p "USDC"))
            current              (reg/get-stake world resolver-addr)
            yield-profile-id     (reg/get-resolver-yield-profile world resolver-addr)
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

          (> (get-in world [:resolver-frozen-until resolver-addr] 0) (time-ctx/block-ts world))
          (t/fail :resolver-frozen)

          :else
          (let [full-withdraw? (= amount current)
                world-accrued  (if yield-profile-id
                                 (lc/accrue-resolver-yield world resolver-addr token)
                                 world)
                owner-id       (lc/resolver-yield-owner-id resolver-addr)
                pos            (when yield-profile-id
                                 (get-in world-accrued [:yield/positions owner-id]))
                yield-amt      (when pos
                                 (+ (:unrealized-yield pos 0) (:realized-yield pos 0)))
                res            (reg/withdraw-stake world-accrued resolver-addr amount)]
            (if (:ok res)
              (let [world' (-> (:world res)
                               (acct/sub-held token amount)
                               (update-in [:total-withdrawn token] (fnil + 0) amount))]
                (if (and yield-profile-id full-withdraw? (pos? yield-amt))
                  (let [{:keys [module-id]} (yield-proto/resolve-yield-profile yield-profile-id)
                        world'' (-> world'
                                    (acct/sub-held token yield-amt)
                                    (update-in [:total-withdrawn token] (fnil + 0) yield-amt)
                                    (yield-proto/apply-op {:op/type :yield/withdraw
                                                           :owner/id owner-id
                                                           :module/id module-id}))]
                    (t/ok world''))
                  (t/ok world')))
              res)))))))

(defmethod apply-action "claim-deferred-yield"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [yield-id  (or (get-in event [:params :yield-profile-id])
                          (get-in world [:params :yield-generation-module]))
            module-id (:module-id (yield-proto/resolve-yield-profile yield-id))
            raw-owner (get-in event [:params :owner-id])
            owner-id  (cond
                        (nil? raw-owner) (str "resolver:" addr)
                        (and (string? raw-owner) (.startsWith raw-owner "escrow:"))
                        [:sew/escrow (Long/parseLong (subs raw-owner 7))]
                        :else raw-owner)
            pos-key  [:yield/positions owner-id]
            old-pos  (get-in world pos-key)
            world'   (yield-proto/apply-op world {:op/type :yield/claim-deferred
                                                  :owner/id owner-id
                                                  :module/id module-id})
            new-pos  (get-in world' pos-key)
            reclaimed (:reclaimed-amount new-pos 0)]
        (if (pos? reclaimed)
          (let [escrow-id (when (vector? owner-id) (second owner-id))
                world''   (if escrow-id
                            ;; For escrow yield: recover principal and credit recipient
                            (let [et        (t/get-transfer world' escrow-id)
                                  state     (t/escrow-state world' escrow-id)
                                  token     (:token et)
                                  recipient (if (#{:released :resolved-release} state) (:to et) (:from et))]
                              (-> world'
                                  (acct/sub-held token reclaimed)
                                  (acct/record-claimable-v2 escrow-id :settlement/yield recipient reclaimed)))
                            ;; Resolver stake: reclaimed deferred was already in :total-held via
                            ;; register-stake; closing the yield position is sufficient (no stake bump).
                            world')]
            (t/ok world''))
          (t/ok world'))))))

(defmethod apply-action "withdraw-escrow"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (acct/withdraw-escrow world (event-workflow-id event) addr))))

(defmethod apply-action "execute-reentrant-withdraw"
  [ctx world event]
  (let [p        (:params event)
        callback (:callback p)
        cb-res   (apply-action ctx world callback)]
    (if-not (:ok cb-res)
      cb-res
      (let [final-res (apply-action ctx (:world cb-res) (assoc event :action "withdraw-escrow"))]
        final-res))))

(defmethod apply-action "withdraw-fees"
  [{:keys [agent-index] :as context} world event]
  (if (:paused? world)
    (t/fail :protocol-paused)
    (actx/with-governance-actor
      agent-index event
      (governance-pred context)
      (fn [_addr _agent]
        (let [p     (:params event)
              token (:token p)
              token (when token (keyword token))]
          (acct/withdraw-fees world token))))))

(defmethod apply-action "governance-update-fee"
  [{:keys [agent-index] :as context} world event]
  (actx/with-governance-actor
    agent-index event
    (governance-pred context)
    (fn [_addr _agent]
      (let [p         (:params event)
            fee-bps   (or (:fee-bps p) (:resolver-fee-bps p) (:escrow-fee-bps p))]
        (if (nil? fee-bps)
          (t/fail :missing-fee-bps)
          (t/ok (update world :params assoc :resolver-fee-bps fee-bps)))))))

(defmethod apply-action "set-token-liquidity-crunch"
  ;; TODO: should use with-governance-actor — see governance-dispatch-audit test
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [_addr]
      (let [p       (:params event)
            token   (keyword (:token p))
            active? (get p :active? true)]
        (t/ok (update world :token-liquidity-crunch
                      (if active?
                        #(conj (or % #{}) token)
                        #(disj % token))))))))

(defmethod apply-action "set-paused"
  [{:keys [agent-index] :as context} world event]
  (actx/with-governance-actor
    agent-index event
    (governance-pred context)
    (fn [_addr _agent]
      (t/ok (assoc world :paused? (get-in event [:params :paused?] true))))))

(defmethod apply-action "register-resolver-bond"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p      (:params event)
            stable (get p :stable 0)
            sew    (get p :sew 0)]
        (t/ok (assoc-in world [:resolver-bonds addr]
                        {:stable stable :sew sew}))))))

(defmethod apply-action "register-senior-bond"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p            (:params event)
            coverage-max (get p :coverage-max 0)]
        (t/ok (assoc-in world [:senior-bonds addr]
                        {:coverage-max coverage-max :reserved-coverage 0}))))))

(defmethod apply-action "delegate-to-senior"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [_addr]
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
  [{:keys [agent-index] :as context} world event]
  (actx/with-governance-actor
    agent-index event
    (governance-pred context)
    (fn [addr _agent]
      (let [p             (:params event)
            workflow-id   (event-workflow-id event)
            resolver-addr (:resolver-addr p)
            amount        (:amount p)]
        (res/propose-fraud-slash world workflow-id addr resolver-addr amount)))))

(defmethod apply-action "challenge-resolution"
  [{:keys [agent-index escalation-fn]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (res/challenge-resolution world (compat/wf-id event) addr escalation-fn))))

(defmethod apply-action "submit-evidence"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (let [p (:params event)]
        (res/submit-evidence world (event-workflow-id event) addr
                             {:evidence-hash (:evidence-hash p)})))))

(defmethod apply-action "appeal-slash"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (res/appeal-slash world (event-workflow-id event) addr
                        (event-slash-id event)))))

(defmethod apply-action "resolve-appeal"
  [{:keys [agent-index] :as context} world event]
  (actx/with-governance-actor
    agent-index event
    (governance-pred context)
    (fn [addr _agent]
      (let [p (:params event)]
        (res/resolve-appeal world
                            (event-workflow-id event)
                            addr
                            (:upheld? p)
                            (event-slash-id event))))))

(defmethod apply-action "execute-fraud-slash"
  [_ctx world event]
  (res/execute-fraud-slash world (event-workflow-id event)
                           (event-slash-id event)))

(defmethod apply-action "compute-prorata-slash-allocation"
  [_ctx world event]
  (let [p (:params event)]
    (res/compute-prorata-slash-allocation world
                                          {:slash-obligation (:slash-obligation p)
                                           :liable-parties   (:liable-parties p)
                                           :basis            (:basis p)
                                           :cap-field        (:cap-field p)})))

(defmethod apply-action "trigger-accrue"
  [_ctx world event]
  (let [wf (event-workflow-id event)]
    (attr/log-with-attr :debug "trigger-accrue" {:workflow-id wf})
    (t/ok (lc/accrue-yield world wf))))

(defmethod apply-action "force-reversal-slash"
  [_ctx world event]
  (let [wf   (event-workflow-id event)
        bps  (event-slash-bps event)]
    (attr/log-with-attr :debug "force-reversal-slash" {:workflow-id wf :slash-bps bps})
    (t/ok (res/force-reversal-slash world wf
                                    :slash-bps bps
                                    :track :immediate))))

(defmethod apply-action "set-yield-risk"
  ;; TODO: should use with-governance-actor — see governance-dispatch-audit test
  [_ctx world event]
  (let [{:keys [module-id token]} (:params event)
        mid (yield-module/resolve-module-id world module-id)
        tok (keyword token)]
    (t/ok (yield-risk/apply-market-shock world mid tok (:params event)))))

(defmethod apply-action :default
  [_ctx world event]
  (let [consistency (time-ctx/check-temporal-consistency world)]
    (when-not (:holds? consistency)
      (throw (ex-info "Temporal inconsistency detected" consistency)))
    {:ok false :error :unknown-action :detail {:action (:action event)}}))

;; ---------------------------------------------------------------------------
;; Invariant Checks
;; ---------------------------------------------------------------------------

(defn- run-single-invariants [world]
  (let [scenario-id (get-in world [:params :scenario-id])
        r (inv/check-all world scenario-id)
        exp-res (yield-exp/check-expectations world)]
    {:ok?        (and (:all-hold? r) (:ok? exp-res))
     :violations (cond-> (if (:all-hold? r) {} (:results r))
                   (not (:ok? exp-res)) (assoc :expectations (:results exp-res)))}))

(defn- run-transition-invariants [world-before world-after]
  (let [r (inv/check-transition world-before world-after)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

;; ---------------------------------------------------------------------------
;; Trace Snapshot
;; ---------------------------------------------------------------------------

(defn- world-snapshot [world]
  (let [time-ctx (time-ctx/temporal-context world)]
    (merge
     {:block-time         (:block-ts time-ctx)
      :time               time-ctx
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
      :escrow-transfers    (:escrow-transfers world {})
      :resolver-stakes     (:resolver-stakes world)
      :resolver-slash-total (:resolver-slash-total world)
      :bond-distribution   (:bond-distribution world)
      :appeal-bond-distributions-by-token (:appeal-bond-distributions-by-token world {})
      :claimable           (:claimable world {})
      :claimable-v2        (:claimable-v2 world {})
      :bond-balances       (:bond-balances world {})
      :yield-evidence      (yield-evi/get-evidence world)})))

(def ^:private sew-state-error-codes
  ;; State machine / lifecycle transition rejections
  #{:transfer-not-pending
    :transfer-not-in-dispute
    :invalid-state-for-release
    :invalid-state-for-refund
    :resolution-without-settlement
    :invalid-resolver
    :invalid-workflow-id
    :transfer-not-finalized
    :has-pending-settlement
    :dispute-timeout-not-exceeded
    :invalid-token
    :amount-zero
    :invalid-amount
    :invalid-recipient
    :cannot-set-both-auto-times
    :insufficient-module-liquidity
    :token-liquidity-crunch
    :circuit-breaker-active
    :resolver-at-capacity
    :resolver-frozen
    :insufficient-resolver-stake
    :active-disputes-block-withdrawal
    :pending-slash-blocks-withdrawal
    :missing-fee-bps
    :no-fees-to-withdraw
    :liquidity-insufficient
    :no-claimable-balance
    :no-bond-to-slash
    :no-bond-to-return
    :senior-not-registered
    :senior-coverage-exceeded
    :insufficient-stake
    :protocol-paused})

(def ^:private sew-guard-error-codes
  ;; Precondition guard rejections
  #{:no-resolution-to-appeal
    :appeal-window-expired
    :appeal-window-not-expired
    :escalation-not-allowed
    :escalation-not-configured
    :resolution-already-pending
    :resolver-capacity-exceeded
    :insufficient-resolver-stake
    :not-participant
    :not-authorized-resolver
    :not-governance
    :not-resolver
    :not-sender
    :not-recipient
    :no-pending-slash
    :invalid-slash-state
    :slash-not-pending
    :slash-already-pending
    :invalid-slash-amount
    :invalid-resolver-addr
    :slash-resolver-mismatch
    :slash-exceeds-max-per-offense
    :slash-epoch-cap-exceeded
    :timelock-not-expired
    :workflow-not-slashable
    :missing-caller-context
    :invalid-new-resolver})

(def ^:private adversarial-capable-actions
  "Actions that can constitute an attack when performed by a
   labeled adversarial agent. Benign setup/utility actions
   (create_escrow, release, register_stake, etc.) are excluded
   to prevent false positives in attack-attempts counting."
  #{:raise_dispute
    :execute_resolution
    :escalate_dispute
    :slash
    :auto_cancel_disputed
    :slash_resolver
    :freeze_resolver})

;; ---------------------------------------------------------------------------
;; SewProtocol Implementation (Tiered)
;; ---------------------------------------------------------------------------

(defrecord SewProtocol []
  proto/SimulationAdapter

  (protocol-id [_] "sew-v1")

  (init-world [_ scenario]
    (let [init-time    (get scenario :initial-block-time 1000)
          tp           (:token-params scenario)
          pp           (assoc (:protocol-params scenario {})
                              :scenario-id (:scenario-id scenario)
                              :expected-failures (:expected-failures scenario {}))
          fot-bps      (when tp (get tp :fee-on-transfer 0))
          s-tokens     (into #{} (keep #(get-in % [:params :token]) (:events scenario)))
          base         (-> (t/empty-world init-time)
                           (assoc :params pp)
                           (yield-proto/init-world pp (:yield-config scenario)))]
      (if (and fot-bps (pos? fot-bps) (seq s-tokens))
        (reduce (fn [w tok] (assoc-in w [:token-fot-bps tok] fot-bps)) base s-tokens)
        base)))

  (build-execution-context [_ agents protocol-params]
    (let [pp         protocol-params
          snapshot   (sew-snapshot/snapshot-from-protocol-params pp)
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
        :resolution-level-map level-map
        :temporal-rules       (sew-temporal-rules)
        :governance-mode      (get pp :governance-mode :restricted)}))

  (dispatch-action [_ context world event]
    (let [flags       (:replay-flags context {})
          require-id? (:require-event-id? flags false)
          eid         (event-id event)]
      (cond
        (and require-id? (replay-sensitive? event) (nil? eid))
        {:ok false :error :missing-event-id
         :detail {:action (:action event) :seq (:seq event)}}

        (and eid (replay-sensitive? event))
        (idem/apply-once world (dedupe-op-key world event)
                         (fn [w] (apply-action context w event)))

        :else
        (apply-action context world event))))

  (check-invariants-single [_ world]
    (run-single-invariants world))

  (check-invariants-transition [_ world-before world-after]
    (run-transition-invariants world-before world-after))

  (world-snapshot [_ world]
    (world-snapshot world))

  (available-actions [_ world actor]
    (let [wfs (keys (:escrow-transfers world))]
      (mapcat identity
              (for [wf wfs]
                (let [et (t/get-transfer world wf)]
                  (cond
              ;; Terminal escrows produce no available actions (explicit boundary)
                    (t/terminal-state? world wf)
                    []

                    (= :pending (:escrow-state et))
                    (into (cond-> []
                            (or (= actor (:from et)) (= actor (:to et)))
                            (conj {:action "raise-dispute" :params {:workflow-id wf}})

                            (= actor (:from et))
                            (into [{:action "release" :params {:workflow-id wf}}
                                   {:action "sender-cancel" :params {:workflow-id wf}}])

                            (= actor (:to et))
                            (conj {:action "recipient-cancel" :params {:workflow-id wf}})))

             ;; Disputed actions
                    (and (= :disputed (:escrow-state et))
                         (not (and (= actor (:dispute-resolver et))
                                   (> (get-in world [:resolver-frozen-until actor] 0) (time-ctx/block-ts world)))))
                    (into (let [pending (t/get-pending world wf)
                                resolver (:dispute-resolver et)]
                            (cond-> []
                       ;; Resolver verdict
                              (and (not (:exists pending)) (= actor resolver))
                              (into [{:action "execute-resolution" :params {:workflow-id wf :is-release true :resolution-hash "0xrelease"}}
                                     {:action "execute-resolution" :params {:workflow-id wf :is-release false :resolution-hash "0xrefund"}}])

                       ;; Escalation/Challenge (if pending exists and not expired)
                              (and (:exists pending) (< (time-ctx/block-ts world) (:appeal-deadline pending)))
                              (into (cond-> []
                                      (or (= actor (:from et)) (= actor (:to et)))
                                      (conj {:action "escalate-dispute" :params {:workflow-id wf}})

                               ;; Anyone can challenge (Phase L)
                                      true (conj {:action "challenge-resolution" :params {:workflow-id wf}}))))))))))))

  (created-id [_ action extra]
    (when (= (compat/canonical-action {:action action}) "create-escrow")
      (:workflow-id extra)))

  (resolve-id-alias [_ event id-alias-map]
    (tap> {:debug :resolve-id-alias :event event :map id-alias-map})
    (if (empty? id-alias-map)
      {:ok true :event event}
      (let [params   (:params event)
            agent    (:agent event)
            resolved-agent (if (and (string? agent) (contains? id-alias-map agent))
                             (get id-alias-map agent)
                             agent)
            resolved-params (into {} (map (fn [[k v]]
                                            [k (if (and (string? v) (contains? id-alias-map v))
                                                 (get id-alias-map v)
                                                 v)])
                                          params))]
        (tap> {:debug :resolved-agent :agent agent :resolved-agent resolved-agent})
        {:ok true :event (assoc event
                                :agent  resolved-agent
                                :params resolved-params)})))

  (open-entities [_ world]
    (vec (for [[wf et] (:escrow-transfers world)
               :when (= :disputed (:escrow-state et))]
           wf)))

  (project-state [_ world query]
    (case (first query)
      :party/net-position
      (let [params (second query)
            actor  (:party params)
            hold   (get-in world [:total-held (get params :token "USDC")] 0)
            claim  (reduce + 0 (for [[_ wf] (:claimable world {})
                                     :when (contains? wf actor)]
                                 (get wf actor 0)))]
        (+ hold claim))
      nil))

  proto/BatchConflictModel

  (event-conflict-domains [_ world event agent-index]
    (sew-event-conflict-domains world event agent-index))

  proto/EconomicModel

  (adversarial-event? [_ event agent]
    (let [action-kw (keyword (:action event))]
      (boolean (or (:adversarial? event)
                   (and (contains? adversarial-capable-actions action-kw)
                        (or (= "malicious" (:strategy agent))
                            (= "attacker"  (:role agent))
                            (= "attacker"  (:type agent))))))))

  (classify-event [_ event result-kw error-kw]
    (let [action    (:action event)
          accepted? (= result-kw :ok)]
      (cond-> #{}
        (and accepted? (= action "create_escrow"))           (conj :entity-created)
        (and accepted? (= action "raise_dispute"))           (conj :dispute-raised)
        (and accepted? (= action "execute_resolution"))      (conj :dispute-resolved)
        (and accepted? (= action "execute_pending_settlement")) (conj :settlement-executed)
        (and (= result-kw :rejected)
             (contains? sew-state-error-codes error-kw))       (conj :invalid-state-transition)
        (and (= result-kw :rejected)
             (contains? sew-guard-error-codes error-kw))       (conj :invalid-guard-condition))))

  (metric-vocabulary [_]
    #{:total-escrows
      :total-volume
      :disputes-triggered
      :resolutions-executed
      :pending-settlements-executed
      :double-settlements
      :invalid-state-transitions
      :invalid-guard-conditions
      :expected-reverts
      :unexpected-reverts
      :negative-payoff-count
      :coalition-net-profit
      :funds-lost})

  (accum-protocol-metrics [_ metrics event-tags event accepted? attack? world-before world-after]
    (let [double-settle? (and accepted?
                              (or (contains? event-tags :dispute-resolved)
                                  (contains? event-tags :settlement-executed))
                              (pos? (:resolutions-executed metrics)))
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
        (#(if (contains? event-tags :expected-revert)
            (update % :expected-reverts inc)
            (update % :invalid-state-transitions inc)))

        (contains? event-tags :invalid-guard-condition)
        (#(if (contains? event-tags :expected-revert)
            (update % :expected-reverts inc)
            (update % :invalid-guard-conditions (fnil inc 0))))

        (contains? event-tags :unexpected-revert)
        (#(update % :unexpected-reverts inc))

        (and funds-lost-delta (pos? funds-lost-delta))
        (update :funds-lost + funds-lost-delta))))

  (summarise-batch [_ outcomes]
    (sew-db/sew-summarise-batch outcomes))

  (advisory [_ world request-type context]
    (case request-type
      :suggest-actions          (sew-adv/suggest-actions          world context)
      :session-signals          (sew-adv/session-signals          world context)
      :evaluate-payoff          (sew-adv/evaluate-payoff          world context)
      :evaluate-attack-objective (sew-adv/evaluate-attack-objective world context)
      {:not-supported true}))

  proto/AnalysisModule

  (compute-projection [_ world]
    [(diff/projection world) (diff/projection-hash world)])

  (classify-transition [_ action result-kw]
    {:transition/type (meta/transition-type action)
     :resolution/path (meta/resolution-path action)})

  (trace-projection [this result]
    (sew-proj/trace-end-projection this result))

  (io-projection [_ data target-type]
    (case target-type
      :funds-ledger-view
      (sew-proj/funds-ledger-view data)

      :world-view
      {:block-time    (:block-time data)
       :entity-count  (count (:escrow-transfers data {}))
       :pending-count (count (filter #(:exists (val %)) (:pending-settlements data {})))}

      :telemetry-record
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
      (let [scenario (:scenario data)]
        (do
          (require 'resolver-sim.protocols.sew.io.trace-export)
          ((resolve 'resolver-sim.protocols.sew.io.trace-export/export-trace-fixture)
           data scenario)))

      nil))

  (mechanism-property-validators [_]
    sew-eq/mechanism-property-validators)

  (equilibrium-concept-validators [_]
    sew-eq/equilibrium-concept-validators)

  (reference-model [_ scenario]
    nil))

(def protocol (SewProtocol.))

(defn replay-with-sew-protocol
  [scenario]
  (let [scenario*
        (if-let [te (:temporal-evidence scenario)]
          (if (and (:enabled? te) (not (:recorder te)))
            (assoc scenario :temporal-evidence
                   (assoc te :recorder temporal/record-from-replay!
                          :protocol protocol))
            scenario)
          scenario)]
    (replay/replay-with-protocol protocol scenario*)))
