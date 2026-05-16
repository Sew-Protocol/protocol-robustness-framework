(ns resolver-sim.server.session
  "Stateful session store for the Phase 2 gRPC simulation server.

   Each session owns:
     :world      — canonical world state (pure Clojure map)
     :context    — immutable {:agent-index :snapshot} built at session creation
     :protocol   — the DisputeProtocol instance in use for this session
     :lock       — ReentrantLock; ensures serialised per-session step execution
     :step-count — monotonically increasing counter

   Invariants:
     - Session IDs are caller-supplied strings (typically UUIDs).
     - Duplicate session IDs are rejected.
     - world and context are immutable references; only the :world slot is swapped
       under the session lock after each successful step.
     - Clojure is the sole authority: no state lives outside this store.

   Layering: server/* may import contract_model/*.  Must NOT import db/* or io/*."
  (:require [resolver-sim.protocols.protocol        :as engine]
            [resolver-sim.protocols.sew             :as sew]
            [resolver-sim.contract-model.replay     :as replay])
  (:import [java.util.concurrent.locks ReentrantLock]))

;; ---------------------------------------------------------------------------
;; Protocol registry
;; ---------------------------------------------------------------------------

(def ^:private protocol-registry
  "Map of protocol-id string → DisputeProtocol instance.
   Add new protocols here to make them available to the gRPC server."
  {"sew-v1" sew/protocol})

;; ---------------------------------------------------------------------------
;; Session store
;; ---------------------------------------------------------------------------

;; Intentional mutable singleton — the server is stateful by design.
;; defonce ensures a live REPL reload does not drop active sessions.
;; Tests that create sessions must clean up via destroy-session! or
;; reset the atom directly: (reset! #'resolver-sim.server.session/sessions {})
(defonce ^{:private true :doc "Atom: {session-id → {:world :context :lock :step-count}}"}
  sessions
  (atom {}))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- with-lock
  "Acquire lock, run f (thunk), release lock.  Always releases even on throw."
  [^ReentrantLock lock f]
  (.lock lock)
  (try (f) (finally (.unlock lock))))

(defn- keywordize [m]
  "Recursively convert string keys in a nested map to keywords."
  (cond
    (map? m)    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (keywordize v)]) m))
    (sequential? m) (mapv keywordize m)
    :else       m))

(defn- normalise-agents
  "Accept agents as seq of maps (string or keyword keys) and return keyword-keyed maps.
   Converts :id :address :role :strategy to ensure downstream agent-index works correctly.
   :role = structural role (resolver/governance/keeper), 
   :strategy = behavioral strategy (honest/rational/malicious)."
  [agents]
  (mapv (fn [a]
        (let [m (keywordize a)]
          (cond-> m
            (string? (:id m))       (update :id str)
            (string? (:address m))  (update :address str)
            (string? (:role m))     (update :role str)
            (string? (:strategy m)) (update :strategy str))))
        agents))

(defn- normalise-params
  "Accept protocol-params as a map (string or keyword keys) and return keyword-keyed map."
  [params]
  (if (map? params) (keywordize params) {}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn session-exists?
  "True when session-id is active."
  [session-id]
  (contains? @sessions session-id))

(defn create-session!
  "Create a new session with the given agents and protocol-params.

   session-id         — caller-supplied string (typically a UUID)
   agents             — seq of agent maps {:id :address :role :strategy ...} (string keys OK)
   protocol-params    — map of protocol params (string keys OK)
   initial-block-time — initial block timestamp for world0
   protocol-id        — optional string identifying which protocol to use (default: \"sew-v1\")

   Returns {:ok true :session-id sid} or {:ok false :error kw :detail map}.

   Atomicity: uses swap-vals! so that two concurrent create calls for the same
   session-id can never both succeed — one will see the key already present in
   the old value and return :session-already-exists."
  ([session-id agents protocol-params initial-block-time]
   (create-session! session-id agents protocol-params initial-block-time "sew-v1"))
  ([session-id agents protocol-params initial-block-time protocol-id]
   (let [protocol   (get protocol-registry protocol-id)]
     (if-not protocol
       {:ok false :error :unknown-protocol :detail {:protocol-id protocol-id
                                                     :known (keys protocol-registry)}}
       (let [agent-list (normalise-agents agents)
             params     (normalise-params protocol-params)
             validation (replay/validate-agents agent-list)]
         (if-not (:ok validation)
           validation
           (let [context (engine/build-execution-context protocol agent-list params)
                 world0  (engine/init-world protocol {:initial-block-time initial-block-time})
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
  "Execute one event against the session's canonical world state.

   event — map {:seq :time :agent :action :params ...} (keyword keys; string keys OK)

   Returns:
     {:ok true  :step {process-step result}}  — on success (including reverts)
     {:ok false :error kw}                    — when session not found

   The world state is updated atomically under the session lock.
   The lock serialises concurrent calls to the same session.
   Different sessions proceed in parallel (each has its own lock).

   Race safety: after acquiring the lock we re-read the session from the atom.
   If destroy-session! ran and removed the session while we were waiting for the
   lock, we detect the nil and return :session-not-found rather than operating
   on a stale reference.  The final swap! is also guarded so that a destroyed
   session is never resurrected with partial state."
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
                    _ (println "[DEBUG] dispatching step. event=" evt)
                    step    (replay/process-step proto context world evt)
                    _ (println "[DEBUG] step processed.")]
                (swap! sessions
                       (fn [s]
                         (if (contains? s session-id)
                           (-> s
                               (assoc-in  [session-id :world] (:world step))
                               (update-in [session-id :step-count] inc))
                           s)))
                {:ok true :step step}))))))))

(defn destroy-session!
  "Remove a session from the store.
   Returns {:ok true} or {:ok false :error :session-not-found}.

   Race safety: acquires the session lock before removing so that any in-progress
   step completes first.  After acquiring the lock, the session is re-checked
   (another concurrent destroy may have already removed it).  Once the remove
   succeeds under the lock, any subsequent step-session! call that was blocked
   on the lock will re-read a nil session and return :session-not-found cleanly."
  [session-id]
  (let [session (get @sessions session-id)]
    (if-not session
      {:ok false :error :session-not-found :detail {:session-id session-id}}
      (with-lock (:lock session)
        (fn []
          ;; Re-check inside the lock: a concurrent destroy may have won the race.
          (if (session-exists? session-id)
            (do (swap! sessions dissoc session-id)
                {:ok true :session-id session-id})
            {:ok false :error :session-not-found :detail {:session-id session-id}}))))))

(defn active-sessions
  "Return a seq of active session IDs. Useful for monitoring."
  []
  (keys @sessions))

(defn get-session-state
  "Return the full internal world map of a session."
  [session-id]
  (if-let [s (get @sessions session-id)]
    {:ok true :world (:world s)}
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-info
  "Return a lean info map for a session: {:step-count :block-time :entity-count}.
   Returns nil if session not found."
  [session-id]
  (when-let [s (get @sessions session-id)]
    (let [wv (engine/io-projection (:protocol s) (:world s) :world-view)]
      {:step-count   (:step-count s)
       :block-time   (:block-time wv)
       ;; :escrow-count retained for backward compatibility with existing callers
       ;; and tests; source is now the protocol's io-projection :world-view.
       :escrow-count (:entity-count wv)})))

(defn suggest-actions
  "Return lightweight action suggestions for an actor without executing anything.
   Delegates to DisputeProtocol/advisory :suggest-actions."
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (let [result (engine/advisory (:protocol s) (:world s)
                                  :suggest-actions
                                  {:actor-id    actor-id
                                   :agent-index (get-in s [:context :agent-index] {})})]
      (if (:not-supported result)
        {:ok false :error :not-supported :detail {:session-id session-id}}
        (assoc result :ok true :session-id session-id :actor-id actor-id)))
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-signals
  "Return read-only risk/economic signals for adversarial strategy search.
   Delegates to DisputeProtocol/advisory :session-signals."
  [session-id]
  (if-let [s (get @sessions session-id)]
    (let [result (engine/advisory (:protocol s) (:world s) :session-signals {})]
      (if (:not-supported result)
        {:ok false :error :not-supported :detail {:session-id session-id}}
        (assoc result :ok true :session-id session-id)))
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-payoff
  "Return a simple realised payoff projection for actor-id from canonical world.
   Delegates to DisputeProtocol/advisory :evaluate-payoff."
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (let [result (engine/advisory (:protocol s) (:world s)
                                  :evaluate-payoff {:actor-id actor-id})]
      (if (:not-supported result)
        {:ok false :error :not-supported :detail {:session-id session-id}}
        (assoc result :ok true :session-id session-id)))
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-attack-objective
  "Evaluate objective-oriented score from canonical world for adversarial search.
   Delegates to DisputeProtocol/advisory :evaluate-attack-objective."
  [session-id actor-id objective]
  (if-let [s (get @sessions session-id)]
    (let [result (engine/advisory (:protocol s) (:world s)
                                  :evaluate-attack-objective
                                  {:actor-id actor-id :objective objective})]
      (if (:not-supported result)
        {:ok false :error :not-supported :detail {:session-id session-id}}
        (assoc result :ok true :session-id session-id)))
    {:ok false :error :session-not-found :detail {:session-id session-id}}))
