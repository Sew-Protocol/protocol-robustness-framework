(ns resolver-sim.protocols.yield
  "Thin yield-provider SimulationAdapter — market layer only, no Sew escrow/dispute.

   Use with `contract-model.replay/simple-replay` or explicit minimal flags.
   Scenarios use schema 1.0, :yield-config, and yield-only event actions."
  (:require [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.yield.protocols :as yield-proto]
            [resolver-sim.yield.ops :as yield-ops]
            [resolver-sim.yield.module :as ymodule]
            [resolver-sim.yield.risk :as yield-risk]
            [resolver-sim.yield.invariants :as yield-inv]
            [resolver-sim.yield.registry :as yreg]))

(defn- action-name [event]
  (let [a (:action event)]
    (if (keyword? a) (name a) (str a))))

(defn- param [event k]
  (get-in event [:params k]))

(defn- owner-id [event]
  (or (param event :owner-id) (:agent event)))

(defn- module-id [world event]
  (let [raw (or (param event :module-id)
                (get-in world [:params :yield-generation-module])
                (get-in world [:params :yield-profile])
                :aave-v3)]
    (ymodule/resolve-module-id world raw)))

(defn- token-kw [event]
  (let [t (param event :token)]
    (cond
      (keyword? t) t
      (string? t)  (keyword t)
      :else :USDC)))

(defn- ok [world] {:ok true :world world})
(defn- err [kw detail] {:ok false :error kw :detail detail})

(defn- held-balance [world token]
  (long (or (get-in world [:yield/held-balances (name token)])
            (get-in world [:yield/held-balances token])
            0)))

(defn- sync-held-from-positions
  "Align custody ledger with active position economic value (post-accrual)."
  [world]
  (let [positions (:yield/positions world {})]
    (reduce
      (fn [w [oid pos]]
        (if (= (:status pos) :active)
          (let [tok (if (keyword? (:token pos)) (name (:token pos)) (str (:token pos)))
                need (yield-inv/position-custody-need w pos)]
            (assoc-in w [:yield/held-balances tok] need))
          w))
      world
      positions)))

(defn- with-held-sync [world]
  (ok (sync-held-from-positions world)))

(defn- dispatch-yield-action
  [world event]
  (case (action-name event)
    "yield_deposit"
    (try
      (let [oid (owner-id event)
            mid (module-id world event)
            amt (long (or (param event :amount) 0))
            tok (token-kw event)
            world' (yield-ops/apply-yield-op world {:op/type :yield/deposit
                                                    :owner/id oid
                                                    :module/id mid
                                                    :amount amt
                                                    :token tok})]
        (with-held-sync world'))
      (catch Exception e
        (err :yield-deposit-failed {:message (.getMessage e)})))

    "yield_accrue"
    (try
      (let [mid (module-id world event)
            tok (token-kw event)
            dt  (long (or (param event :dt) 0))
            world' (yield-ops/accrue-module world mid {:token tok :dt dt})]
        (with-held-sync world'))
      (catch Exception e
        (err :yield-accrue-failed {:message (.getMessage e)})))

    "yield_withdraw"
    (try
      (let [oid (owner-id event)
            mid (module-id world event)
            tok (token-kw event)
            world' (yield-ops/apply-yield-op world {:op/type :yield/withdraw
                                                    :owner/id oid
                                                    :module/id mid
                                                    :token tok})]
        (with-held-sync world'))
      (catch Exception e
        (err :yield-withdraw-failed {:message (.getMessage e)})))

    "set-yield-risk"
    (let [mid (module-id world event)
          tok (token-kw event)
          world' (yield-risk/apply-market-shock world mid tok (:params event))]
      (with-held-sync world'))

    "time_advance"
    (ok world)

    (err :unknown-action {:action (:action event)})))

(defn- check-yield-invariants [world]
  (let [generic (yield-inv/check-all world)
        exposure-ok? (yield-inv/check-yield-exposure
                      world
                      (fn [_ pos] (= (:status pos) :active))
                      #(held-balance world %))
        held (merge generic {:yield/exposure exposure-ok?})]
    {:ok? (every? true? (vals held))
     :violations (into {}
                       (keep (fn [[k v]] (when (false? v) {k v}))
                             held))}))

(deftype YieldProviderProtocol []
  proto/SimulationAdapter

  (protocol-id [_] "yield-v1")

  (init-world [_ scenario]
    (let [t0 (get scenario :initial-block-time 1000)
          pp (merge {:yield-profile :aave-v3}
                    (get scenario :protocol-params {}))
          yc (get scenario :yield-config {})]
      (-> {:block-time t0
           :yield/held-balances {}}
          (yield-proto/init-world pp yc)
          (assoc-in [:params] pp))))

  (build-execution-context [_ agents protocol-params]
    {:agent-index (into {} (map (juxt :id identity) agents))
     :protocol-params protocol-params})

  (dispatch-action [_ context world event]
    (dispatch-yield-action world event))

  (check-invariants-single [_ world]
    (check-yield-invariants world))

  (check-invariants-transition [_ _before _after]
    {:ok? true :violations nil})

  (world-snapshot [_ world]
    {:block-time (:block-time world)
     :yield-positions (when-let [pos (:yield/positions world)]
                        (into {} (map (fn [[oid p]]
                                         [oid (select-keys p [:status :principal :shares
                                                              :unrealized-yield :realized-yield
                                                              :token :module/id :yield-loss :shortfall])])
                                      pos)))
     :yield-held (:yield/held-balances world)})

  (available-actions [_ _world _actor]
    [])

  (resolve-id-alias [_ event _aliases]
    {:ok true :event event})

  (created-id [_ _action _extra]
    nil)

  (open-entities [_ _world]
    [])

  (project-state [_ world query]
    (get world query)))

(def protocol
  "Singleton YieldProviderProtocol for replay and tests."
  (YieldProviderProtocol.))
