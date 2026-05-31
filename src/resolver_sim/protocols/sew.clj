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
            [resolver-sim.contract-model.replay          :as replay]
            [resolver-sim.yield.protocols                :as yield-proto]
            [resolver-sim.protocols.sew.compat           :as compat]
            [resolver-sim.protocols.sew.action-context   :as actx]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-safe-amount 922337203685477)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- build-snapshot [pp]
  (let [yield-id (or (get pp :yield-generation-module nil)
                     (get pp :yield-profile nil))
        {:keys [profile-id archetype module-id]} (yield-proto/resolve-yield-profile yield-id)]
    (t/make-module-snapshot
     ;; NOTE: :resolver-fee-bps in protocol-params maps to :escrow-fee-bps in the snapshot.
     ;; Setting :escrow-fee-bps directly in protocol-params has no effect; use :resolver-fee-bps.
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
      ;; NOTE: :fraud-slash-bps is intentionally NOT stored in the snapshot.
      ;; It is a sim-layer param read from params directly (kernel_bridge, batch, etc).
      :challenge-window-duration    (get pp :challenge-window-duration 0)
      :challenge-bond-bps           (get pp :challenge-bond-bps 0)
      :challenge-bounty-bps         (get pp :challenge-bounty-bps 0)
      :default-auto-release-delay   (get pp :default-auto-release-delay 0)
      :default-auto-cancel-delay    (get pp :default-auto-cancel-delay 0)
      ;; NOTE: escrow-modules mirrors :resolution-module for legacy compat.
      :escrow-modules               {:resolution (get pp :resolution-module nil)
                                     :yield      profile-id
                                     :release    (get pp :release-strategy nil)
                                     :cancel     (get pp :cancellation-strategy nil)}
      :yield-module-id              (get pp :yield-module-id :module/aave-yield)
      :yield-profile                profile-id
      :yield-archetype              archetype
      :yield-generation-module      module-id
      :yield-distribution-module    (get pp :yield-distribution-module nil)
      :yield-protocol-fee-bps       (get pp :yield-protocol-fee-bps 0)
      :cancellation-strategy        (get pp :cancellation-strategy nil)
      :release-strategy             (get pp :release-strategy nil)
      :incentive-module             (get pp :incentive-module nil)})))

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

(defn- event-workflow-id
  [event]
  (let [p (:params event)]
    (or (:workflow-id p) (compat/wf-id event))))

(defn- event-slash-id
  [event]
  (let [p (:params event)]
    (or (:slash-id p) (:workflow-id p) (compat/wf-id event))))

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

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defmulti apply-action
  (fn [_ctx _world event]
    (compat/canonical-action event)))

(defmethod apply-action "create-escrow"
  [{:keys [agent-index snapshot]} world event]
  (let [p (:params event)]
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
                              :yield-preset (:yield-preset p)
                              :auto-release-time (:auto-release-time p)
                              :auto-cancel-time (:auto-cancel-time p)})
                  result   (lc/create-escrow world caller token to amount settings snapshot)]
              (if (:ok result)
                (assoc result :extra {:workflow-id (:workflow-id result)})
                result))))))))

(defmethod apply-action "raise-dispute"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor-and-unpaused
    agent-index world event
    (fn [addr]
      (lc/raise-dispute world (compat/wf-id event) addr))))

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
          (assoc result :extra {:new-level    (:new-level result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "rotate-dispute-resolver"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [_addr]
      (let [workflow-id   (compat/wf-id event)
            new-resolver  (get-in event [:params :new-resolver])
            result        (res/rotate-dispute-resolver world workflow-id new-resolver)]
        (if (:ok result)
          (assoc result :extra {:old-resolver (:old-resolver result)
                                :new-resolver (:new-resolver result)})
          result)))))

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
                world' (yield-proto/apply-op world {:op/type :yield/deposit
                                                     :owner/id (str "resolver:" addr)
                                                     :module/id module-id
                                                     :amount amount
                                                     :token token})]
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

          (> (get-in world [:resolver-frozen-until resolver-addr] 0) (:block-time world))
          (t/fail :resolver-frozen)

          :else
          (let [res (reg/withdraw-stake world resolver-addr amount)]
            (if (:ok res)
              (let [world' (-> (:world res)
                               (acct/sub-held token amount)
                               (update-in [:total-withdrawn token] (fnil + 0) amount))]
                (if yield-profile-id
                  (let [{:keys [module-id]} (yield-proto/resolve-yield-profile yield-profile-id)
                        world'' (yield-proto/apply-op world'
                                                      {:op/type :yield/withdraw
                                                       :owner/id (str "resolver:" resolver-addr)
                                                       :module/id module-id})]
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
                                  (acct/record-claimable escrow-id recipient reclaimed)))
                            ;; For resolver stake yield: credit the resolver's stake balance
                            (update-in world' [:resolver-stakes addr] (fnil + 0) reclaimed))]
            (t/ok world''))
          (t/ok world'))))))

(defmethod apply-action "withdraw-escrow"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
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
  [{:keys [agent-index]} world event]
  (actx/with-governance-actor
    agent-index event
    governance-actor?
    (fn [_addr _agent]
      (let [p     (:params event)
            token (:token p)
            token (when token (keyword token))]
        (acct/withdraw-fees world token)))))

(defmethod apply-action "set-token-liquidity-crunch"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [_addr]
      (let [agent (get agent-index (:agent event))]
        (let [p       (:params event)
              token   (keyword (:token p))
              active? (get p :active? true)]
          (t/ok (update world :token-liquidity-crunch
                        (if active?
                          #(conj (or % #{}) token)
                          #(disj % token)))))))))

(defmethod apply-action "set-paused"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [_addr]
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
  [{:keys [agent-index]} world event]
  (actx/with-governance-actor
    agent-index event
    governance-actor?
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

(defmethod apply-action "appeal-slash"
  [{:keys [agent-index]} world event]
  (actx/with-resolved-actor
    agent-index event
    (fn [addr]
      (res/appeal-slash world (event-workflow-id event) addr
                        (event-slash-id event)))))

(defmethod apply-action "resolve-appeal"
  [{:keys [agent-index]} world event]
  (actx/with-governance-actor
    agent-index event
    governance-actor?
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

(defmethod apply-action "time_advance"
  [_ctx world _event]
  (t/ok world))

(defmethod apply-action "trigger-accrue"
  [_ctx world event]
  (t/ok (lc/accrue-yield world (event-workflow-id event))))

(defmethod apply-action "set-yield-risk"
  ;; Inject a yield risk update mid-scenario (e.g. to simulate a market shock).
  ;; Params:
  ;;   :module-id      — yield module id (string, will be keywordised and alias-resolved)
  ;;   :token          — token symbol (string, will be keywordised)
  ;;   :liquidity-mode — :available | :shortfall | :haircut | :frozen | :paused
  ;;   :failure-modes  — vector of strings, e.g. ["negative-yield"]
  ;;   :apy            — new APY (double), optional
  ;;   :shortfall      — map e.g. {:available-ratio 0.8}
  [_ctx world event]
  (let [{:keys [module-id token liquidity-mode failure-modes apy shortfall]} (:params event)
        raw-mid (keyword module-id)
        ;; Resolve through module aliases so "aave-v3" → :yield.provider/liquid-lending
        mid (get-in world [:yield/module-aliases raw-mid] raw-mid)
        tok (keyword token)]
    (t/ok (cond-> world
            liquidity-mode
            (assoc-in [:yield/risk mid tok :liquidity-mode] (keyword liquidity-mode))
            failure-modes
            (assoc-in [:yield/risk mid tok :failure-modes] (into #{} (map keyword failure-modes)))
            apy
            (assoc-in [:yield/rates mid tok] (double apy))
            shortfall
            (assoc-in [:yield/risk mid tok :shortfall] shortfall)))))

(defmethod apply-action :default
  [_ctx _world event]
  {:ok false :error :unknown-action :detail {:action (:action event)}})

;; ---------------------------------------------------------------------------
;; Invariant Checks
;; ---------------------------------------------------------------------------

(defn- run-single-invariants [world]
  (let [scenario-id (get-in world [:params :scenario-id])
        r (inv/check-all world scenario-id)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

(defn- run-transition-invariants [world-before world-after]
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
   :escrow-transfers    (:escrow-transfers world {})
   :resolver-stakes     (:resolver-stakes world)
   :resolver-slash-total (:resolver-slash-total world)
   :bond-distribution   (:bond-distribution world)
   :appeal-bond-distributions-by-token (:appeal-bond-distributions-by-token world {})
   :claimable           (:claimable world {})
   :bond-balances       (:bond-balances world {})
   :yield-positions     (when-let [pos (:yield/positions world)]
                          (into {} (map (fn [[oid p]]
                                          [oid (select-keys p [:status :principal :shares
                                                               :unrealized-yield :realized-yield
                                                               :shortfall :reclaimed-amount
                                                               :token :module/id])])
                                        pos)))})

(def ^:private sew-state-error-codes
  #{:transfer-not-pending
    :transfer-not-in-dispute
    :invalid-state-for-release
    :invalid-state-for-refund
    :resolution-without-settlement})

(def ^:private sew-guard-error-codes
  #{:no-resolution-to-appeal
    :appeal-window-expired
    :appeal-window-not-expired
    :escalation-not-allowed
    :resolution-already-pending
    :not-participant
    :not-authorized-resolver})

;; ---------------------------------------------------------------------------
;; SewProtocol Implementation (Tiered)
;; ---------------------------------------------------------------------------

(defrecord SewProtocol []
  proto/SimulationAdapter

  (protocol-id [_] "sew-v1")

  (init-world [_ scenario]
    (let [init-time    (get scenario :initial-block-time 1000)
          tp           (:token-params scenario)
          pp           (assoc (:protocol-params scenario {}) :scenario-id (:scenario-id scenario))
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
        :resolution-level-map level-map
        :temporal-rules       (sew-temporal-rules)}))

  (dispatch-action [_ context world event]
    (apply-action context world event))

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
           (cond-> []
             ;; Pending actions
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
                            (> (get-in world [:resolver-frozen-until actor] 0) (:block-time world)))))
             (into (let [pending (t/get-pending world wf)
                         resolver (:dispute-resolver et)]
                     (cond-> []
                       ;; Resolver verdict
                       (and (not (:exists pending)) (= actor resolver))
                       (into [{:action "execute-resolution" :params {:workflow-id wf :is-release true :resolution-hash "0xrelease"}}
                              {:action "execute-resolution" :params {:workflow-id wf :is-release false :resolution-hash "0xrefund"}}])

                       ;; Escalation/Challenge (if pending exists and not expired)
                       (and (:exists pending) (< (:block-time world) (:appeal-deadline pending)))
                       (into (cond-> []
                               (or (= actor (:from et)) (= actor (:to et)))
                               (conj {:action "escalate-dispute" :params {:workflow-id wf}})

                               ;; Anyone can challenge (Phase L)
                               true (conj {:action "challenge-resolution" :params {:workflow-id wf}}))))))))))))

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

  proto/EconomicModel

  (adversarial-event? [_ event agent]
    (boolean (or (:adversarial? event)
                 (= "malicious" (:strategy agent))
                 (= "attacker"  (:role agent))
                 (= "attacker"  (:type agent)))))

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
        (update :invalid-state-transitions inc)

        (contains? event-tags :invalid-guard-condition)
        (update :invalid-guard-conditions (fnil inc 0))

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
