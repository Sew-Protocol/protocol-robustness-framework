(ns resolver-sim.contract-model.replay.temporal
  "Temporal instrumentation and time management for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [resolver-sim.time.model :as time-model]))

(defn advance-world-time
  "Advance :block-ts and scenario-step counter atomically.
   Returns {:world w' :delta-ms n :advanced? bool}.

   Same-timestamp events (event-time == block-time) are a no-op:
   world is unchanged and scenario-step is NOT incremented.
   This models same-block semantics — multiple actions at the same
   timestamp share a single time context without stepping the counter.

   Throws when :block-ts is missing (uninitialized world)."
  [world event-time]
  (let [now-ts       (:block-ts (time-model/now world))
        event-ts     (if (number? event-time) (long event-time)
                         (.getEpochSecond ^java.time.Instant event-time))
        delta-seconds (- event-ts now-ts)]
    (if (pos? delta-seconds)
      (let [step (inc (:scenario-step (time-model/now world)))]
        {:world     (time-model/with-time world {:block-ts event-ts :scenario-step step})
         :delta-ms  (* delta-seconds 1000)
         :advanced? true})
      {:world     world
       :delta-ms  0
       :advanced? false})))

(def ^:private temporal-rules
  [{:id :missing-event-time
    :check (fn [{:keys [event-time]}]
             (if (number? event-time)
               {:ok? true}
               {:ok? false :error :invalid-event-time}))}
   {:id :non-regressive-time
    :check (fn [{:keys [event-time now]}]
             (if (< event-time now)
               {:ok? false :error :time-regression}
               {:ok? true}))}])

(defn effective-temporal-rules
  "Base temporal rules + optional protocol/context-provided rules.
   Extra rules must be maps with keys {:id kw :check (fn [ctx] -> {:ok? bool ...})}."
  [context]
  (let [extra (:temporal-rules context)
        extra' (if (sequential? extra) extra [])]
    (into temporal-rules extra')))

(defn evaluate-temporal-rules
  [rules ctx]
  (reduce (fn [_ {:keys [id check]}]
            (let [r (check ctx)]
              (if (:ok? r)
                nil
                (reduced (assoc r :rule-id id)))))
          nil
          rules))

(defn maybe-record-temporal!
  "Invoke optional :recorder from :temporal-evidence when collection is enabled."
  [temporal-cfg temporal-enabled? scenario-id outcome world metrics trace]
  (when (and temporal-enabled? (:recorder temporal-cfg))
    ((:recorder temporal-cfg)
     (:datasource temporal-cfg)
     temporal-cfg
     scenario-id
     outcome
     world
     metrics
     trace)))
