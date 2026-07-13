(ns resolver-sim.contract-model.replay.yield
  "Sequential replay for yield-v1 — no batch/alias/temporal/theory branches.

   Validates that each `yield_accrue` event's `:dt` matches the `:time` delta
   from the previous event. Provider scenarios should use `replay-yield-scenario`
   (or `simple-replay` on yield-v1, which delegates here).

   === Architecture ===
   - `replay-yield-events` — pure computation, no side effects, no risk monitor.
     Used by the simple-replay profile adapter.
   - `replay-yield-scenario` — legacy wrapper adding risk monitor, logging.
     Retained for existing callers that depend on risk/events side effects."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.validation :as validation]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.yield.risk-monitor :as risk]))

(def ^:private yield-replay-flags replay-flags/minimal-replay-flags)

(defn- action-name [event]
  (let [a (:action event)]
    (if (keyword? a) (name a) (str a))))

(defn- yield-accrue-event? [event]
  (= "yield_accrue" (action-name event)))

(defn- validate-dt-time-alignment
  "For yield_accrue events, require params :dt to equal (:time - prev-time)."
  [events]
  (let [sorted (sort-by :seq events)
        violations
        (loop [prev nil events sorted acc []]
          (if (empty? events)
            acc
            (let [e    (first events)
                  acc' (if (and prev (yield-accrue-event? e))
                         (let [dt         (get-in e [:params :dt])
                               time-delta (- (:time e) (:time prev))]
                           (if (and (number? dt) (not= (long dt) (long time-delta)))
                             (conj acc {:seq (:seq e)
                                        :dt dt
                                        :time-delta time-delta
                                        :prev-seq (:seq prev)
                                        :prev-time (:time prev)
                                        :time (:time e)})
                             acc))
                         acc)]
              (recur e (rest events) acc'))))]
    (if (seq violations)
      {:ok false
       :error :dt-time-mismatch
       :detail {:hint "yield_accrue :dt must match the event :time delta from the prior event"
                :violations violations}}
      {:ok true})))

(defn validate-yield-scenario
  "Structural checks for provider-only scenarios (relaxed schema, strict time/dt)."
  [scenario]
  (let [effective (into metrics/base-metrics (or (metrics/expectation-metric-keys scenario) #{}))
        base      (validation/validate-scenario scenario effective {:strict-validation? false})
        align     (validate-dt-time-alignment (:events scenario []))]
    (cond
      (not (:ok base)) base
      (not (:ok align)) align
      :else {:ok true})))

(defn- run-yield-loop
  [protocol context scenario-id events world0]
  (loop [world world0
         events (sort-by :seq events)
         trace  []
         metrics (metrics/zero-metrics protocol)]
    (if (empty? events)
      {:outcome :pass
       :scenario-id scenario-id
       :events-processed (count trace)
       :trace trace
       :metrics metrics
       :execution {:mode :yield-sequential}
       :protocol protocol
       :world world}
      (let [event (first events)
            step   (execution/process-step protocol context world event)
            {:keys [ok? world trace-entry halted?]} step
            metrics' (metrics/accum-metrics protocol metrics event trace-entry
                                            (:agent-index context) world)
            trace'   (conj trace trace-entry)]
        (if halted?
          {:outcome :fail
           :scenario-id scenario-id
           :events-processed (count trace')
           :halted-at-seq (:seq event)
           :halt-reason :invariant-violation
           :trace trace'
           :metrics metrics'
           :execution {:mode :yield-sequential}
           :protocol protocol
           :world world}
          (recur world (rest events) trace' metrics'))))))

(defn replay-yield-events
  "Pure computation: replay a yield-provider scenario with the thin sequential runner.

   This is the side-effect-free core — no risk monitor, no logging, no evidence I/O.
   Used by the simple-replay profile adapter.

   Accepts optional replay-opts map for compatibility; currently supports :run-id.
   Unsupported opts are silently ignored (caller should validate via adapter)."
  ([protocol scenario] (replay-yield-events protocol scenario nil))
  ([protocol scenario replay-opts]
   (let [validation (validate-yield-scenario scenario)]
     (if-not (:ok validation)
       {:outcome :invalid
        :scenario-id (:scenario-id scenario)
        :events-processed 0
        :trace []
        :metrics (metrics/zero-metrics protocol)
        :halt-reason (:error validation)
        :detail (:detail validation)
        :protocol protocol}
       (let [agents      (:agents scenario)
             p-params    (get scenario :protocol-params {})
             scenario-id (:scenario-id scenario)
             run-id (or (:run-id replay-opts) (:run-id scenario) (str scenario-id "-run"))
             context     (-> (proto/build-execution-context protocol agents p-params)
                             (assoc :replay-flags yield-replay-flags
                                    :run-id run-id))
             world0      (-> (proto/init-world protocol scenario)
                             (assoc-in [:params :scenario-id] scenario-id))
             events      (:events scenario)]
         (let [raw (run-yield-loop protocol context scenario-id events world0)]
           (analysis/finalize-scenario-result scenario raw yield-replay-flags)))))))

(defn replay-yield-scenario
  "Legacy wrapper: replay a yield-provider scenario with risk-monitor instrumentation.

   Adds risk-monitor side effects (`risk/clear!`, `risk/events`) around
   `replay-yield-events`.  `protocol` defaults to `yield-v1`.

   Result shape matches `replay-with-protocol` enough for `scenario.runner`
   and expectation finalization.

   NOTE: This wrapper exists for backward compatibility. New callers should
   prefer `replay-yield-events` (pure) or the simple-replay profile adapter."
  ([scenario] (replay-yield-scenario yp/protocol scenario))
  ([protocol scenario]
   (risk/clear!)
   (log/info! "yield-replay/start" {:id (:scenario-id scenario)})
   (let [result (replay-yield-events protocol scenario)]
     (log/info! "yield-replay/end" {:id (:scenario-id scenario) :outcome (:outcome result)})
     (assoc result :risk-events (risk/events)))))
