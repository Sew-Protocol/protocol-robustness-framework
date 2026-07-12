(ns resolver-sim.contract-model.replay
  "Open-world scenario replay proto. (Protocol Simulation Kernel)

   Provides the deterministic harness for executing scenarios. This engine
   is designed as a protocol-agnostic template. Implementation details
   (actions, invariants, snapshots) are protocol-specific and provided by
   implementations of the DisputeProtocol interface.

   Replay invariants (after every successful transition):
     1. protocol/check-invariants-single
     2. protocol/check-invariants-transition"
  (:require [clojure.data.json                 :as json]
            [clojure.java.io                   :as io]
            [resolver-sim.evidence.config      :as evcfg]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.validation :as validation]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.contract-model.replay.temporal :as temporal]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.contract-model.replay.checkpoints :as replay-checkpoints]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.yield.risk-monitor :as risk]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.logging :as log]))

;; ---------------------------------------------------------------------------
;; JSON serialisation helpers (Generic)
;; ---------------------------------------------------------------------------

(defn- kw->json-key [k] (if (keyword? k) (name k) (str k)))
(defn- kw-val->str [_k v] (if (keyword? v) (name v) v))

;; ---------------------------------------------------------------------------
;; Agent Validation (Generic)
;; ---------------------------------------------------------------------------

(defn validate-agents [agents] (validation/validate-agents agents))

;; ---------------------------------------------------------------------------
;; Bridge functions (Legacy Sew support)
;; ---------------------------------------------------------------------------

(defn build-context
  "Build an execution context for a protocol.

   Protocols must implement DisputeProtocol (build-execution-context).

   One-arg and two-arg arities default to SEW for backward compatibility.
   Prefer the three-arg arity: (build-context protocol agents params)."
  ([agents params]
   (build-context (preg/get-protocol "sew-v1") agents params))
  ([protocol agents params]
   (proto/build-execution-context protocol agents params)))

(defn sew-dispatch-action
  "Deprecated: call proto/dispatch-action with a protocol instance directly.
   Bridge to proto/dispatch-action using SewProtocol."
  [context world event]
  (proto/dispatch-action (preg/get-protocol "sew-v1") context world event))

(defn sew-check-invariants-single
  "Deprecated: call proto/check-invariants-single with a protocol instance directly.
   Bridge to proto/check-invariants-single using SewProtocol."
  [world]
  (proto/check-invariants-single (preg/get-protocol "sew-v1") world))

(defn sew-check-invariants-transition
  "Deprecated: call proto/check-invariants-transition with a protocol instance directly.
   Bridge to proto/check-invariants-transition using SewProtocol."
  [world-before world-after]
  (proto/check-invariants-transition (preg/get-protocol "sew-v1") world-before world-after))

;; ---------------------------------------------------------------------------
;; Analysis & Result Interpretation
;; ---------------------------------------------------------------------------

(defn- normalize-error-value [error] (analysis/normalize-error-value error))
(defn- expected-error-key [x] (analysis/expected-error-key x))
(defn- rejected-entry-key [x] (analysis/rejected-entry-key x))
(defn- analyze-expected-errors [scenario trace] (analysis/analyze-expected-errors scenario trace))

(defn finalize-scenario-result
  ([scenario result] (analysis/finalize-scenario-result scenario result {}))
  ([scenario result flags] (analysis/finalize-scenario-result scenario result flags)))

;; ---------------------------------------------------------------------------
;; Temporal Instrumentation
;; ---------------------------------------------------------------------------

(defn- advance-world-time [world event-time] (temporal/advance-world-time world event-time))
(defn- effective-temporal-rules [context] (temporal/effective-temporal-rules context))
(defn- evaluate-temporal-rules [rules ctx] (temporal/evaluate-temporal-rules rules ctx))
(defn- maybe-record-temporal! [cfg enabled? id outcome world metrics trace] (temporal/maybe-record-temporal! cfg enabled? id outcome world metrics trace))

;; ---------------------------------------------------------------------------
;; Metrics — registry (must precede validate-scenario which references it)
;; ---------------------------------------------------------------------------

(def population-metrics metrics/population-metrics)
(def base-metrics metrics/base-metrics)
(defn- metric-key [x] (metrics/metric-key x))
(defn- falsifies-if-metric-refs [falsifies-if] (metrics/falsifies-if-metric-refs falsifies-if))
(defn- theory-metric-scope [scenario] (metrics/theory-metric-scope scenario))
(defn- action->transition-id [action] (analysis/action->transition-id action))

;; ---------------------------------------------------------------------------
;; Input validation (Generic scenario structure)
;; ---------------------------------------------------------------------------

(defn validate-scenario
  ([scenario] (validation/validate-scenario scenario metrics/base-metrics {}))
  ([scenario effective-metrics] (validation/validate-scenario scenario effective-metrics {}))
  ([scenario effective-metrics opts]
   (validation/validate-scenario scenario effective-metrics opts)))

;; ---------------------------------------------------------------------------
;; Metrics — accumulation
;; ---------------------------------------------------------------------------

(defn- zero-metrics [protocol] (metrics/zero-metrics protocol))
(defn- accum-metrics [protocol metrics event trace-entry agent-index world-before]
  (metrics/accum-metrics protocol metrics event trace-entry agent-index world-before))

(def process-step
  "Forwarding reference — moved to replay.execution."
  execution/process-step)

;; ---------------------------------------------------------------------------
;; Public API (Generic)
;; ---------------------------------------------------------------------------

(defn- expectation-metric-keys [scenario] (metrics/expectation-metric-keys scenario))

(defn trace-entry->replay-event
  "Strip trace metadata; return the minimal replay event shape.
   Delegates to execution/trace-entry->replay-event."
  [entry]
  (execution/trace-entry->replay-event entry))

(defn replay-events
  "Replay a scenario and return trace + metrics without evidence chain or I/O.

   Accepts optional opts map — passed through to `resolve-replay-flags`:
   - :flags   — replay flag overrides (see `replay.flags`)
   - :minimal — use minimal replay flags
   - :run-id  — identifier for the replay run

   Returns the full simulation result including :trace, :metrics, :outcome,
   and (when `:evaluate-expectations?` is true) expectation and theory analysis.

   This is a pure computation — no evidence chain, no I/O, no risk monitoring.
   Callers (e.g. `replay-with-protocol`) add those layers externally."
  [protocol scenario & [opts]]
  (let [flags              (replay-flags/resolve-replay-flags scenario opts)
        vocab              (if (satisfies? proto/EconomicModel protocol)
                             (proto/metric-vocabulary protocol)
                             #{})
        effective-metrics  (into (into metrics/base-metrics vocab)
                                 (or (metrics/expectation-metric-keys scenario) #{}))
        validation         (validate-scenario scenario effective-metrics
                                              {:strict-validation? (:strict-validation? flags)})
        temporal-cfg       (:temporal-evidence scenario)
        temporal-enabled?  (:temporal-enabled? flags)]
    (if-not (:ok validation)
      {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed 0 :trace [] :metrics (metrics/zero-metrics protocol (:metrics-profile flags)) :halt-reason (:error validation) :protocol protocol}
      (let [agents   (:agents scenario)
            p-params (get scenario :protocol-params {})
            context  (-> (proto/build-execution-context protocol agents p-params)
                         (assoc :replay-flags flags))
            agent-index (:agent-index context)
            world0  (proto/init-world protocol scenario)
            events  (sort-by :seq (:events scenario))
            scenario-id (:scenario-id scenario)
            expected-errors-set (set (map expected-error-key (:expected-errors scenario [])))
            strict-expected-errors? (boolean (:strict-expected-errors? scenario false))
            run-id  (or (:run-id opts) (:run-id scenario) (str scenario-id "-run"))
            options {:expected-errors-set expected-errors-set
                     :strict-expected-errors? strict-expected-errors?
                     :allow-open-entities? (:allow-open-entities? scenario)
                     :allow-open-disputes? (:allow-open-disputes? scenario)
                     :agents agents
                     :temporal-cfg temporal-cfg
                     :temporal-enabled? temporal-enabled?
                     :agent-index agent-index
                     :scenario scenario
                     :run-id run-id
                     :replay-flags flags}
            raw-result (execution/run-simulation-loop protocol context scenario-id events world0 [] (metrics/zero-metrics protocol (:metrics-profile flags)) options)
            trimmed-result (replay-checkpoints/apply-checkpoint-policy-to-result
                            (:world-checkpoint-policy flags)
                            raw-result)]
        (if (:evaluate-expectations? flags true)
          (finalize-scenario-result scenario trimmed-result flags)
          trimmed-result)))))

(defn replay-with-protocol
  "Replay a scenario map using tiered protocol implementations.

   Optional third argument `replay-opts` may include `:flags` (see `replay.flags`).
   Scenario `:options {:minimal true}` or `:options {:flags {...}}` merge the same way.

   Layers evidence chain, I/O, and risk monitoring on top of `replay-events`."
  ([protocol scenario] (replay-with-protocol protocol scenario {}))
  ([protocol scenario replay-opts]
   (chain/with-fresh-registry
     (chain/with-fresh-chain-cursor
       (risk/with-fresh-risk-context
          (let [result (replay-events protocol scenario replay-opts)]
            (if (= :invalid (:outcome result))
              result
              (let [run-id (get-in result [:context/source :run-id])
                    scenario-id (or (:scenario-id result)
                                    (get-in result [:context/source :scenario-id]))]
                (attr/with-attribution
                  {:ctx/scenario-id scenario-id
                   :ctx/run-id run-id}
                  (attr/log-with-attr :info "scenario/start" {:id scenario-id}))
                (when-let [theory (:diagnostics result)]
                  (try
                    (let [f (io/file (evcfg/artifact-path :theory-eval))]
                      (.mkdirs (.getParentFile f))
                      (spit f (json/write-str theory {:indent true})))
                    (catch Exception e
                      (log/warn! :theory-diagnostics-write-failed
                                 {:path (evcfg/artifact-path :theory-eval)
                                  :error (.getMessage e)}))))
                (when-not (:skip-finalize replay-opts)
                  (let [signing-key (or (:signing-key replay-opts)
                                        chain/*signing-key*
                                        (System/getenv "PRF_SIGNING_KEY"))
                        signing-pw (or (:signing-password replay-opts)
                                       chain/*signing-password*
                                       (System/getenv "PRF_SIGNING_PASSWORD"))
                        tsa-url (or (:tsa-url replay-opts)
                                    ts/*tsa-url*
                                    (System/getenv "PRF_TSA_URL"))
                        allow-dirty? (:allow-dirty? replay-opts)]
                    (chain/finalize-and-attest!
                     :run-id run-id
                     :private-key-path signing-key
                     :password signing-pw
                     :tsa-url tsa-url
                     :allow-dirty? allow-dirty?)))
                (chain/register-scenario-snapshot!)
                (assoc result :risk-events (risk/events))))))))))

(defn replay-yield-scenario
  "Thin sequential replay for `yield-v1` (see `replay.yield`)."
  ([scenario] (yield-replay/replay-yield-scenario scenario))
  ([protocol scenario] (yield-replay/replay-yield-scenario protocol scenario)))

(defn simple-replay
  "Replay with library-style defaults: no temporal enforcement, no theory DSL, relaxed validation.

   For `yield-v1`, delegates to `replay-yield-scenario` (thin runner). Other protocols
   use `replay-events` with `minimal-replay-flags`. Caller `replay-opts` apply
   on the generic path.

   Auto-defaults :schema-version to \"1.0\" when missing so hand-built
   notebook scenarios work without an explicit version key."
  [protocol scenario & [replay-opts]]
  (let [scenario (if (:schema-version scenario)
                   scenario
                   (assoc scenario :schema-version "1.0"))]
    (if (= "yield-v1" (proto/protocol-id protocol))
      (yield-replay/replay-yield-scenario protocol scenario)
      (replay-events protocol scenario
                     (merge {:minimal true
                             :flags replay-flags/minimal-replay-flags}
                            replay-opts)))))
(defn resume-from-snapshot
  "Resume a simulation from a world snapshot and a sequence of events.
   Useful for exploring counterfactual subgames."
  [protocol agents p-params scenario-id world events trace metrics options]
  (let [context  (proto/build-execution-context protocol agents p-params)
        agent-index (:agent-index context)
        metrics' (if (seq metrics) metrics (metrics/zero-metrics protocol))
        expected-errors-set (set (map expected-error-key (:expected-errors (:scenario options) [])))
        strict-expected-errors? (boolean (:strict-expected-errors? (:scenario options) false))
        temporal-cfg (:temporal-evidence (:scenario options))
        temporal-enabled? (boolean (:enabled? temporal-cfg))
        run-id (or (:run-id options) (str scenario-id "-resume"))]
    (execution/run-simulation-loop protocol context scenario-id events world trace metrics'
                                   (merge {:expected-errors-set expected-errors-set
                                           :strict-expected-errors? strict-expected-errors?
                                           :allow-open-entities? true
                                           :allow-open-disputes? true
                                           :agents agents
                                           :temporal-cfg temporal-cfg
                                           :temporal-enabled? temporal-enabled?
                                           :agent-index agent-index
                                           :run-id run-id}
                                          options))))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  ((requiring-resolve 'resolver-sim.io.serialization/serialize-artifact) (dissoc result :protocol)))

;; ---------------------------------------------------------------------------
;; Verification & Determinism
;; ---------------------------------------------------------------------------

(defn replay-idempotent-same-trace?
  "Run the same scenario twice and check deterministic equivalence of key outputs.
   Returns:
     {:idempotent? bool
      :first result
      :second result}

   Equivalence checks:
   - :outcome
   - :halt-reason
   - :events-processed
   - trace result/error sequence
   - final world snapshot in trace tail"
  [protocol scenario]
  (let [r1 (replay-with-protocol protocol scenario)
        r2 (replay-with-protocol protocol scenario)
        trace-shape (fn [r] (mapv (juxt :seq :result :error) (:trace r)))
        last-world  (fn [r] (:world (last (:trace r))))
        eq? (and (= (:outcome r1) (:outcome r2))
                 (= (:halt-reason r1) (:halt-reason r2))
                 (= (:events-processed r1) (:events-processed r2))
                 (= (trace-shape r1) (trace-shape r2))
                 (= (last-world r1) (last-world r2)))]
    {:idempotent? eq?
     :first r1
     :second r2}))
