(ns resolver-sim.server.session
  "Stateful session store for the Phase 2 gRPC simulation server.

   Each session owns:
     :world      — canonical world state (pure Clojure map)
     :context    — immutable {:agent-index :snapshot} built at session creation
     :protocol   — the tiered Protocol instances in use for this session
     :lock       — ReentrantLock; ensures serialised per-session step execution
     :step-count — monotonically increasing counter

   Layering: server/* may import contract_model/*.  Must NOT import db/* or io/*."
  (:require [resolver-sim.protocols.protocol        :as proto]
            [resolver-sim.protocols.registry        :as preg]
            [resolver-sim.contract-model.replay     :as replay])
  (:import [java.util.concurrent.locks ReentrantLock]))

;; ---------------------------------------------------------------------------
;; Protocol registry
;; ---------------------------------------------------------------------------

(def ^:private protocol-registry
  "Map of protocol-id string → Protocol instance.
   Sourced from the central protocol registry."
  (into {} (map (fn [pid] [pid (preg/get-protocol pid)])
                (preg/known-protocol-ids))))

;; ---------------------------------------------------------------------------
;; Session store
;; ---------------------------------------------------------------------------

(defonce ^{:dynamic true :private true
            :doc "Atom: {session-id → {:world :context :lock :step-count}}"}
  sessions
  (atom {}))

(defmacro with-fresh-sessions
  "Execute body with a fresh empty session store.
   The outer store is restored when body exits.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [sessions fresh-atom#]
       ~@body)))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- with-lock
  [^ReentrantLock lock f]
  (.lock lock)
  (try (f) (finally (.unlock lock))))

(defn- keywordize [m]
  (cond
    (map? m)    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (keywordize v)]) m))
    (sequential? m) (mapv keywordize m)
    :else       m))

(defn- normalise-agents
  [agents]
  (mapv (fn [a]
          (let [m (keywordize a)]
            (cond-> m
              (string? (:id m))       (update :id str)
              (string? (:address m))  (update :address str)
              (or (:type m) (not (:role m)))
              (assoc :role (or (:role m) (:type m) "buyer"))
              (not (:strategy m)) (assoc :strategy "honest"))))
        agents))

(defn- normalise-params
  [params]
  (if (map? params) (keywordize params) {}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn session-exists?
  [session-id]
  (contains? @sessions session-id))

(defn create-session!
  ([session-id agents protocol-params initial-block-time]
   (create-session! session-id agents protocol-params initial-block-time preg/default-protocol-id))
  ([session-id agents protocol-params initial-block-time protocol-id]
   (let [pid        (or protocol-id preg/default-protocol-id)
         protocol   (get protocol-registry pid)]
     (if-not protocol
       {:ok false :error :unknown-protocol :detail {:protocol-id pid
                                                    :known (keys protocol-registry)}}
       (let [agent-list (normalise-agents agents)
             params     (normalise-params protocol-params)
             validation (replay/validate-agents agent-list)]
         (if-not (:ok validation)
           validation
           (let [context (proto/build-execution-context protocol agent-list params)
                 world0  (proto/init-world protocol {:initial-block-time initial-block-time})
                 session {:world      world0
                          :context    context
                          :protocol   protocol
                          :lock       (ReentrantLock.)
                          :step-count 0}
                 [old _] (swap-vals! sessions (fn [s]
                                                (if (contains? s session-id)
                                                  s
                                                  (assoc s session-id session))))]
             (if (contains? old session-id)
               {:ok false :error :session-already-exists :detail {:session-id session-id}}
               {:ok true :session-id session-id}))))))))

(defn step-session!
  [session-id event]
  (let [session (get @sessions session-id)]
    (if-not session
      {:ok false :error :session-not-found :detail {:session-id session-id}}
      (with-lock (:lock session)
        (fn []
          (let [current (get @sessions session-id)]
            (if-not current
              {:ok false :error :session-not-found :detail {:session-id session-id}}
              (let [world   (:world current)
                    context (:context session)
                    proto   (:protocol current)
                    evt     (keywordize event)
                    step    (replay/process-step proto context world evt)]
                (swap! sessions
                       (fn [s]
                         (if (contains? s session-id)
                           (-> s
                               (assoc-in  [session-id :world] (:world step))
                               (update-in [session-id :step-count] inc))
                           s)))
                {:ok true :step step}))))))))

(defn destroy-session!
  [session-id]
  (let [session (get @sessions session-id)]
    (if-not session
      {:ok false :error :session-not-found :detail {:session-id session-id}}
      (with-lock (:lock session)
        (fn []
          (if (session-exists? session-id)
            (do (swap! sessions dissoc session-id)
                {:ok true :session-id session-id})
            {:ok false :error :session-not-found :detail {:session-id session-id}}))))))

(defn active-sessions
  []
  (keys @sessions))

(defn get-session-state
  [session-id]
  (if-let [s (get @sessions session-id)]
    {:ok true :world (:world s)}
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-info
  [session-id]
  (when-let [s (get @sessions session-id)]
    (let [wv (if (satisfies? proto/AnalysisModule (:protocol s))
               (proto/io-projection (:protocol s) (:world s) :world-view)
               {:block-time (:block-time (:world s)) :entity-count 0})
          fv (when (satisfies? proto/AnalysisModule (:protocol s))
               (proto/io-projection (:protocol s) (:world s) :funds-ledger-view))]
      {:step-count   (:step-count s)
       :block-time   (:block-time wv)
       :escrow-count (:entity-count wv)
       :funds-conservation-holds? (get-in fv [:conservation :holds?])
       :funds-drift-total         (get-in fv [:conservation :drift-total])})))

(defn suggest-actions
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (:world s)
                                   :suggest-actions
                                   {:actor-id    actor-id
                                    :agent-index (get-in s [:context :agent-index] {})})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id :actor-id actor-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-signals
  [session-id]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (:world s) :session-signals {})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-payoff
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (:world s)
                                   :evaluate-payoff {:actor-id actor-id})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-attack-objective
  [session-id actor-id objective]
  (if-let [s (get @sessions session-id)]
    (if (satisfies? proto/EconomicModel (:protocol s))
      (let [result (proto/advisory (:protocol s) (:world s)
                                   :evaluate-attack-objective
                                   {:actor-id actor-id :objective objective})]
        (if (:not-supported result)
          {:ok false :error :not-supported :detail {:session-id session-id}}
          (assoc result :ok true :session-id session-id)))
      {:ok false :error :not-supported :detail {:session-id session-id}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))
