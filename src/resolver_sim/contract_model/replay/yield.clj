(ns resolver-sim.contract-model.replay.yield
  "Sequential replay for yield-v1 — no batch/alias/temporal/theory branches.

   Validates that each `yield_accrue` event's `:dt` matches the `:time` delta
   from the previous event. Provider scenarios should use `replay-yield-scenario`
   (or `simple-replay` on yield-v1, which delegates here)."
  (:require [clojure.stacktrace :as st]
            [resolver-sim.logging :as log]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.contract-model.replay.temporal :as temporal]
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

(defn- trace-entry
  [protocol world-before event result-kw error-kw detail violations world-after]
  {:seq           (:seq event)
   :time          (:time event)
   :time-before   {:block-ts (:block-time world-before)}
   :time-after    {:block-ts (:block-time world-after)}
   :agent         (:agent event)
   :action        (:action event)
   :params        (:params event)
   :transition/id (analysis/action->transition-id (:action event))
   :result        result-kw
   :error         error-kw
   :detail        detail
   :invariants-ok? (empty? violations)
   :violations    (when (seq violations) violations)
   :world         (proto/world-snapshot protocol world-after)})

(defn- process-yield-step
  [protocol context world event]
  (let [event-time (:time event)
        world-t    (:world (temporal/advance-world-time world event-time))
        result (try
                 (proto/dispatch-action protocol context world-t event)
                 (catch Exception e
                   (log/error! "yield-replay dispatch exception"
                               {:error (.getMessage e) :seq (:seq event) :action (:action event)})
                   {:ok false
                    :error :dispatch-exception
                    :detail {:message (.getMessage e)
                             :stack   (with-out-str (st/print-stack-trace e))}}))
        ok?        (:ok result)
        world-next (if (and ok? (:world result)) (:world result) world-t)
        inv-single (when ok? (proto/check-invariants-single protocol world-next))
        inv-trans  (when ok? (proto/check-invariants-transition protocol world-t world-next))
        violated?  (and ok? (not (and (:ok? inv-single) (:ok? inv-trans))))
        violations (when violated?
                     (merge (when-not (:ok? inv-single) (:violations inv-single))
                            (when-not (:ok? inv-trans) (:violations inv-trans))))
        result-kw  (cond violated? :invariant-violated ok? :ok :else :rejected)
        error-kw   (when-not ok? (:error result))
        final-world (if violated? world-t world-next)]
    {:ok?    (and ok? (not violated?))
     :world  final-world
     :trace-entry (trace-entry protocol world event result-kw error-kw
                               (:detail result) violations final-world)
     :halted? violated?}))

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
            step   (process-yield-step protocol context world event)
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

(defn replay-yield-scenario
  "Replay a yield-provider scenario with the thin sequential runner.

   `protocol` defaults to `yield-v1`. Result shape matches `replay-with-protocol`
   enough for `scenario.runner` and expectation finalization."
  ([scenario] (replay-yield-scenario yp/protocol scenario))
  ([protocol scenario]
   (risk/clear!)
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
             context     (-> (proto/build-execution-context protocol agents p-params)
                             (assoc :replay-flags yield-replay-flags))
             world0      (proto/init-world protocol scenario)
             events      (:events scenario)
             scenario-id (:scenario-id scenario)]
          (log/info! "yield-replay/start" {:id scenario-id})
          (let [raw (run-yield-loop protocol context scenario-id events world0)]
            (log/info! "yield-replay/end" {:id scenario-id :outcome (:outcome raw)})
            (let [result (analysis/finalize-scenario-result scenario raw yield-replay-flags)]
              (assoc result :risk-events (risk/events)))))))))
