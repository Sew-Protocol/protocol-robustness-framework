(ns resolver-sim.contract-model.replay
  "Open-world scenario replay proto. (Protocol Simulation Kernel)

   Provides the deterministic harness for executing scenarios. This engine
   is designed as a protocol-agnostic template. Implementation details
   (actions, invariants, snapshots) are protocol-specific and provided by
   implementations of the DisputeProtocol interface.

   Replay invariants (after every successful transition):
     1. protocol/check-invariants-single
     2. protocol/check-invariants-transition"
   (:require [clojure.stacktrace             :as st]
             [clojure.string                :as str]
             [resolver-sim.logging          :as log]
             [resolver-sim.definitions.registry :as defs]
             [resolver-sim.scenario.schema-profile :as schema-profile]
             [resolver-sim.contract-model.replay.metrics :as metrics]
              [resolver-sim.contract-model.replay.validation :as validation]
             [resolver-sim.contract-model.replay.analysis :as analysis]
             [resolver-sim.contract-model.replay.temporal :as temporal]
             [resolver-sim.contract-model.replay.validation :as validation]
             [resolver-sim.contract-model.replay.yield :as yield-replay]
             [resolver-sim.contract-model.replay.flags :as replay-flags]
             [resolver-sim.contract-model.replay.checkpoints :as replay-checkpoints]
             [resolver-sim.protocols.protocol :as proto]
             [resolver-sim.protocols.registry :as preg]
              [resolver-sim.time.model        :as time-model]
              [resolver-sim.util.attribution :as attr]
              [resolver-sim.yield.risk-monitor :as risk]))

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
  "Bridge to proto/build-execution-context using SewProtocol."
  [agents params]
  (proto/build-execution-context (preg/get-protocol "sew-v1") agents params))

(defn sew-dispatch-action
  "Bridge to proto/dispatch-action using SewProtocol."
  [context world event]
  (proto/dispatch-action (preg/get-protocol "sew-v1") context world event))

(defn sew-check-invariants-single
  "Bridge to proto/check-invariants-single using SewProtocol."
  [world]
  (proto/check-invariants-single (preg/get-protocol "sew-v1") world))

(defn sew-check-invariants-transition
  "Bridge to proto/check-invariants-transition using SewProtocol."
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

;; SCOPE: deterministic trace metrics only.
;;
;; This registry covers metrics that the replay engine can compute from a
;; single scenario execution (event log + world snapshots).  It intentionally
;; excludes stochastic / population-level metrics such as:
;;
;;   :coalition/net-profit   — needs N-epoch batch run, not a single trace
;;   :malice-mean-profit     — population average from sim/multi_epoch.clj
;;   :dominance-ratio        — strategy-share statistic across resolver pool
;;
;; Those belong in a separate future registry, e.g.:
;;
;;   resolver-sim.sim.multi-epoch/known-metrics   (stochastic, multi-epoch; Sew-specific)
;;
;; Do NOT add population/batch metrics here. Blending the two worlds would
;; cause validate-scenario to accept :theory/falsifies-if conditions that
;; the deterministic replay can never satisfy, producing silent :inconclusive
;; results for the wrong reason.

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



;; ---------------------------------------------------------------------------
;; Step Processing (Kernel)
;; ---------------------------------------------------------------------------

(defn process-step
  "Apply one scenario event using tiered Protocol implementations.
   Wraps dispatch in with-attribution so downstream yield accrual, invariant
   checks, and logging automatically carry event-level context."
  [protocol context world event]
  (let [flags        (or (:replay-flags context) replay-flags/default-replay-flags)
        temporal-on? (let [v (:temporal-enabled? flags)] (if (nil? v) true (boolean v)))
        check-inv?   (:check-invariants? flags true)
        event-time   (:time event)
        now          (:block-time world)
        time-before  {:block-ts now}
        rules        (effective-temporal-rules context)
        temporal-failure (when temporal-on?
                           (evaluate-temporal-rules rules
                                                    {:event-time event-time
                                                     :now now
                                                     :world world
                                                     :event event
                                                     :context context
                                                     :protocol protocol}))]
    (if temporal-failure
      (let [[proj ph] (if (satisfies? proto/AnalysisModule protocol)
                        (proto/compute-projection protocol world)
                        [nil nil])
            tags      (if (satisfies? proto/EconomicModel protocol)
                        (proto/classify-event protocol event :rejected (:error temporal-failure))
                        #{})]
         {:ok?    true
          :world  world
          :trace-entry {:seq             (:seq event)
                        :time            event-time
                        :time-before     time-before
                        :time-after      {:block-ts event-time}
                        :agent           (:agent event)
                        :action          (:action event)
                        :params          (:params event)
                        :transition/id   (action->transition-id (:action event))
                        :result          :rejected
                        :error           (:error temporal-failure)
                        :temporal-rule-id (:rule-id temporal-failure)
                        :extra           nil
                        :event-tags      tags
                        :invariant-phase :temporal-rule
                        :invariants-ok?  true
                        :violations      nil
                        :world           (proto/world-snapshot protocol world)
                        :projection      proj
                        :projection-hash ph}
          :halted? false})

      (let [{world-t :world} (advance-world-time world event-time)
            time-after       {:block-ts event-time}
            result     (attr/with-attribution
                        {:replay/scenario-id (get-in world [:params :scenario-id])
                         :replay/seq         (:seq event)
                         :replay/action      (:action event)
                         :replay/agent       (:agent event)
                         :replay/event-time  event-time}
                        (try
                         (proto/dispatch-action protocol context world-t event)
                         (catch Exception e
                             (attr/log-with-attr :error "dispatch exception"
                                        {:error (.getMessage e)
                                         :scenario-step (:seq event)
                                         :action (:action event)})
                           (.printStackTrace e)
                            {:ok false :error :dispatch-exception
                             :detail {:message (.getMessage e)
                                      :stack   (with-out-str (st/print-stack-trace e))}})))
            ok?        (:ok result)
            world-next (if (and ok? (:world result)) (:world result) world-t)

            inv-single (when (and ok? check-inv?)
                         (proto/check-invariants-single protocol world-next))
            inv-trans  (when (and ok? check-inv?)
                         (proto/check-invariants-transition protocol world-t world-next))
            violated?  (and ok? check-inv?
                            (not (and (:ok? inv-single) (:ok? inv-trans))))
            all-violations (when violated?
                             (merge (when-not (:ok? inv-single) (:violations inv-single))
                                    (when-not (:ok? inv-trans)  (:violations inv-trans))))]

        (let [result-kw    (cond violated? :invariant-violated ok? :ok :else :rejected)
              error-kw     (when-not ok? (:error result))
              event-tags   (if (satisfies? proto/EconomicModel protocol)
                             (proto/classify-event protocol event result-kw error-kw)
                             #{})
              final-world  (if violated? world-t world-next)
              [proj ph]    (if (satisfies? proto/AnalysisModule protocol)
                             (proto/compute-projection protocol final-world)
                             [nil nil])
              metadata     (if (satisfies? proto/AnalysisModule protocol)
                             (proto/classify-transition protocol (:action event) result-kw)
                             nil)]
          {:ok?    (and ok? (not violated?))
           :world  final-world
           :trace-entry
           {:seq             (:seq event)
            :time            event-time
            :time-before     time-before
            :time-after      time-after
            :agent           (:agent event)
            :action          (:action event)
            :params          (:params event)
            :transition/id   (action->transition-id (:action event))
            :result          result-kw
            :error           error-kw
            :extra           (:extra result)
            :detail          (:detail result)
            :event-tags      event-tags
             :invariant-phase :post-event
             :invariants-ok?  (if (and ok? check-inv?)
                                 (and (:ok? inv-single) (:ok? inv-trans))
                                 true)
             :violations      all-violations
             :trace-metadata  metadata
            :world           (proto/world-snapshot protocol final-world)
            :projection      proj
             :projection-hash ph
             :guard-context   (:guard-context result)}
            :halted? violated?})))))

(defn- execution-mode
  [scenario]
  (keyword (or (:execution-mode scenario) :sequential)))

(def ^:private batch-commit-policy :deterministic-first-wins)

(defn- event-conflict-domains*
  [protocol world event agent-index]
  (let [domains (when (satisfies? proto/BatchConflictModel protocol)
                  (proto/event-conflict-domains protocol world event agent-index))]
    (if (seq domains)
      (set domains)
      #{[:global :unknown]})))

(defn- group-same-time-bucket
  [events]
  (let [t (:time (first events))]
    (split-with #(= t (:time %)) events)))

(defn- conflict-rejection-entry
  [protocol world-before event preflight-status conflict-domain conflict-with-seq flags]
  (let [[proj ph] (if (and (satisfies? proto/AnalysisModule protocol)
                           (or (= (:projection-mode flags) :full)
                               (= (:op/type event) :scenario/end)))
                    (proto/compute-projection protocol world-before)
                    [nil nil])
        tags      (if (satisfies? proto/EconomicModel protocol)
                    (proto/classify-event protocol event :rejected :batch-conflict)
                    #{})]
    {:seq               (:seq event)
     :time              (:time event)
     :time-before       {:block-ts (:block-time world-before)}
     :time-after        {:block-ts (:time event)}
     :agent             (:agent event)
     :action            (:action event)
     :params            (:params event)
     :transition/id     (action->transition-id (:action event))
     :result            :rejected
     :error             :batch-conflict
     :reject-phase      :batch-commit
     :reject-class      :batch-conflict
     :commit-policy     batch-commit-policy
     :preflight-status  preflight-status
     :commit-status     :rejected
     :conflict-domain   conflict-domain
     :conflict-with-seq conflict-with-seq
     :event-tags        tags
     :invariants-ok?    true
     :violations        nil
     :world             (proto/world-snapshot protocol world-before)
     :projection        proj
     :projection-hash   ph}))

;; ---------------------------------------------------------------------------
;; Public API (Generic)
;; ---------------------------------------------------------------------------

(defn- expectation-metric-keys [scenario] (metrics/expectation-metric-keys scenario))

(defn trace-entry->replay-event
  "Strip trace metadata; return the minimal replay event shape.
   Used by counterfactual fork replay so continuation steps do not carry
   stale :world / :result fields from the main-line trace."
  [entry]
  (select-keys entry [:seq :time :agent :action :params]))

(defn- run-simulation-loop
  "Execute the core simulation loop from a given world state and event sequence."
  [protocol context scenario-id events world trace metrics options]
  (let [{:keys [expected-errors-set strict-expected-errors?
                allow-open-entities? allow-open-disputes?
                agents temporal-cfg temporal-enabled? agent-index
                scenario replay-flags]} options
        check-inv? (:check-invariants? (or replay-flags replay-flags/default-replay-flags) true)
        supports-alias? (satisfies? proto/SimulationAdapter protocol)]
    (loop [world world
           events events
           trace trace
           metrics metrics
           states {(:seq (first events) 0) (proto/world-snapshot protocol world)}
           world-checkpoints {}
            id-alias-map {}]
      (if (empty? events)
        (let [open (when-not (or allow-open-entities? allow-open-disputes?)
                     (seq (proto/open-entities protocol world)))]
          (if open
            {:outcome :fail :scenario-id scenario-id :events-processed (count trace) :halt-reason :open-entities-at-end :detail {:open-entities (vec open)} :trace trace :metrics metrics :agents agents :protocol protocol :last-valid-world world}
            (do
              (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :pass world metrics trace)
              (let [expected-error-analysis (analyze-expected-errors scenario trace)
                    expected-errors-mismatch? (and strict-expected-errors?
                                                  (not (:ok? expected-error-analysis)))
                    outcome (if expected-errors-mismatch? :fail :pass)
                    halt-reason (when expected-errors-mismatch? :expected-error-mismatch)]
                 (attr/log-with-attr :info "scenario/end" {:id scenario-id :outcome outcome})
                {:context/version "1.0"
                 :context/source {:scenario-id scenario-id :run-id (str scenario-id "-run")}
                 :execution {:mode (execution-mode scenario)
                             :batch-policy (when (= :deterministic-batch (execution-mode scenario))
                                             batch-commit-policy)}
                 :outcome outcome
                 :events-processed (count trace)
                 :halt-reason halt-reason
                 :expected-error-analysis expected-error-analysis
                 :trace trace
                 :metrics metrics
                 :states states
                 :agents agents
                 :protocol protocol
                 :world world
                 :world-checkpoints world-checkpoints}))))
        (if (= :deterministic-batch (execution-mode scenario))
           (let [[bucket rest-events] (group-same-time-bucket events)
                 base-world world
                 batch-time (:time (first bucket))
                 metrics' (-> metrics
                              (update :batch-buckets inc)
                              (update :batch-events + (count bucket)))
                ;; Preflight runs every event against base-world (world BEFORE the bucket).
                ;; Commit runs against the incremental world (world after previous events
                ;; in the bucket were applied).  Preflight :eligible means "this event can
                ;; execute in isolation at the pre-bucket state", not "it will commit".
                ;; A preflight-eligible event may still be rejected during commit because
                ;; an earlier event in the same bucket changed the world state (guard
                ;; condition, depleted balance, etc.) or because its conflict domain
                ;; intersects with a previously committed event.
                ;; Preflight uses raw bucket events (unresolved aliases).
                ;; Events referencing same-bucket aliases may show :ineligible
                ;; here but succeed at commit after the alias is resolved.
                preflight (into {}
                                (map (fn [ev]
                                       (let [s (process-step protocol context base-world ev)]
                                         [(:seq ev)
                                          (if (= :ok (get-in s [:trace-entry :result])) :eligible :ineligible)])))
                                bucket)
                batch-result (reduce
                              (fn [acc raw-event]
                                (if (:halted? acc)
                                  acc
                                  ;; Resolve aliases against the cumulative alias map.
                                  ;; Aliases created by earlier events in the same bucket
                                  ;; (via :save-id-as) are visible here — this is the key
                                  ;; difference from pre-reduce alias resolution.
                                  (let [event (if (and supports-alias? (seq (:id-alias-map acc)))
                                                (let [res (proto/resolve-id-alias protocol raw-event (:id-alias-map acc))]
                                                  (if (:ok res) (:event res) raw-event))
                                                raw-event)
                                        working-world (:world acc)
                                        domains (event-conflict-domains* protocol working-world event agent-index)
                                        conflict-domain (some #(when (contains? (:claimed-domains acc) %) %) domains)
                                        winner-seq (get (:claimed-domains acc) conflict-domain)
                                        pre-status (get preflight (:seq event) :ineligible)]
                                        (if conflict-domain
                                        (let [entry (-> (conflict-rejection-entry protocol working-world event pre-status conflict-domain winner-seq replay-flags)
                                                      (assoc :expected-failure?
                                                             (contains? expected-errors-set
                                                                        [(:seq event) (:action event) :batch-conflict])))]
                                        (-> acc

                                            (update :trace conj entry)
                                            (assoc :metrics (metrics/accum-metrics protocol (:metrics acc) event entry agent-index working-world))
                                            (update :states assoc (:seq event) (proto/world-snapshot protocol working-world))
                                            (update :world-checkpoints assoc (:seq event) working-world)))
                                      (let [step (process-step protocol context working-world event)
                                            entry0 (:trace-entry step)
                                            expected-failure? (and (= :rejected (:result entry0))
                                                                   (contains? expected-errors-set
                                                                              [(:seq entry0) (:action entry0) (:error entry0)]))
                                            reject-phase (when (= :rejected (:result entry0))
                                                           (if (:temporal-rule-id entry0) :temporal-rule :batch-commit))
                                            entry (cond-> (assoc entry0
                                                                 :preflight-status pre-status
                                                                 :commit-policy batch-commit-policy
                                                                 :commit-status (if (= :ok (:result entry0)) :accepted :rejected))
                                                    (= :ok (:result entry0)) (assoc :invariant-phase :post-event)
                                                    (= :rejected (:result entry0))
                                                    (assoc :reject-class (:error entry0)
                                                           :reject-phase reject-phase
                                                           :expected-failure? expected-failure?))
                                            new-world (:world step)
                                            alias-key (:save-id-as event)
                                            new-id (when (and alias-key supports-alias? (= :ok (:result entry)))
                                                     (proto/created-id protocol (:action event) (:extra entry)))
                                            claimed' (if (= :ok (:result entry))
                                                       (reduce (fn [m d] (assoc m d (:seq event))) (:claimed-domains acc) domains)
                                                       (:claimed-domains acc))]
                                        (tap> {:scenario-id scenario-id :seq (:seq event) :world working-world :entry entry})
                                        (-> acc
                                            (assoc :world new-world
                                                   :claimed-domains claimed'
                                                   :halted? (:halted? step)
                                                   :id-alias-map (if (and alias-key new-id)
                                                                   (assoc (:id-alias-map acc) alias-key new-id)
                                                                   (:id-alias-map acc)))
                                            (update :trace conj entry)
                                            (assoc :metrics (metrics/accum-metrics protocol (:metrics acc) event entry agent-index working-world))
                                            (update :states assoc (:seq event) (proto/world-snapshot protocol new-world))
                                            (update :world-checkpoints assoc (:seq event) working-world)))))))
                              {:world base-world
                               :trace trace
                               :metrics metrics'
                               :states states
                               :world-checkpoints world-checkpoints
                               :claimed-domains {}
                               :halted? false
                               :id-alias-map id-alias-map}
                              bucket)]
            (if (:halted? batch-result)
              (do
                (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world batch-result) (:metrics batch-result) (:trace batch-result))
                {:outcome :fail :scenario-id scenario-id :events-processed (count (:trace batch-result)) :halt-reason :invariant-violation :trace (:trace batch-result) :metrics (:metrics batch-result) :execution {:mode :deterministic-batch :batch-policy batch-commit-policy} :protocol protocol :world-checkpoints (:world-checkpoints batch-result) :last-valid-world (:world batch-result)})
              (let [post-single (when check-inv?
                                  (proto/check-invariants-single protocol (:world batch-result)))
                    post-trans  (when check-inv?
                                  (proto/check-invariants-transition protocol base-world (:world batch-result)))
                    post-ok?    (if check-inv?
                                  (and (:ok? post-single) (:ok? post-trans))
                                  true)
                    post-entry  {:seq             (str "batch-" batch-time)
                                 :time            batch-time
                                 :result          (if post-ok? :ok :invariant-violated)
                                 :invariant-phase :post-batch
                                 :invariants-ok?  post-ok?
                                 :violations      (when-not post-ok?
                                                    (merge (when-not (:ok? post-single) (:violations post-single))
                                                           (when-not (:ok? post-trans) (:violations post-trans))))
                                 :world           (proto/world-snapshot protocol (:world batch-result))}
                    trace' (conj (:trace batch-result) post-entry)
                    metrics'' (if post-ok?
                                (:metrics batch-result)
                                (update (:metrics batch-result) :invariant-violations inc))]
                (if post-ok?
                  (recur (:world batch-result)
                         rest-events
                         trace'
                         metrics''
                         (:states batch-result)
                         (:world-checkpoints batch-result)
                         (:id-alias-map batch-result))
                  (do
                    (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world batch-result) metrics'' trace')
                    {:outcome :fail
                     :scenario-id scenario-id
                     :events-processed (count trace')
                     :halt-reason :invariant-violation
                     :trace trace'
                     :metrics metrics''
                     :execution {:mode :deterministic-batch :batch-policy batch-commit-policy}
                     :protocol protocol
                     :world-checkpoints (:world-checkpoints batch-result)
                     :last-valid-world (:world batch-result)})))))
          (let [raw-event (first events)
                event (if (and supports-alias? (seq id-alias-map))
                        (let [res (proto/resolve-id-alias protocol raw-event id-alias-map)]
                          (if (:ok res) (:event res) raw-event))
                        raw-event)
                checkpoints' (assoc world-checkpoints (:seq event) world)
                step (process-step protocol context world event)
                entry0 (:trace-entry step)
                alias-key (:save-id-as raw-event)
                new-id (when (and alias-key supports-alias? (= :ok (:result entry0)))
                         (proto/created-id protocol (:action raw-event) (:extra entry0)))
                new-alias-map (if (and alias-key new-id)
                                (assoc id-alias-map alias-key new-id)
                                id-alias-map)
                expected-failure? (and (= :rejected (:result entry0))
                                       (contains? expected-errors-set
                                                  [(:seq entry0) (:action entry0) (:error entry0)]))
                reject-phase (when (= :rejected (:result entry0))
                               (if (:temporal-rule-id entry0) :temporal-rule :dispatch))
                 entry (cond-> entry0
                         (= :rejected (:result entry0))
                         (assoc :reject-class (:error entry0)
                                :reject-phase reject-phase
                                :expected-failure? expected-failure?
                                :event-tags (conj (:event-tags entry0)
                                                  (if expected-failure?
                                                    :expected-revert
                                                    :unexpected-revert))))
                new-trace (conj trace entry)
                new-metrics (metrics/accum-metrics protocol metrics event entry agent-index world)
                new-world (:world step)
                new-states (assoc states (:seq event) (proto/world-snapshot protocol new-world))]
            (attr/log-with-attr :debug "scenario/step" {:id scenario-id :seq (:seq event) :action (:action event)})
            (if (:halted? step)
              (do
                (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world step) new-metrics new-trace)
                (attr/log-with-attr :error "scenario/halt" {:id scenario-id :seq (:seq event) :reason :invariant-violation})
                {:outcome :fail
                 :scenario-id scenario-id
                 :events-processed (count new-trace)
                 :halted-at-seq (:seq event)
                 :halt-reason :invariant-violation
                 :trace new-trace
                 :metrics new-metrics
                 :execution {:mode :sequential}
                 :protocol protocol
                 :last-valid-world world
                 :world-checkpoints checkpoints'})
              (recur new-world (rest events) new-trace new-metrics new-states checkpoints' new-alias-map))))))))

(defn replay-with-protocol
  "Replay a scenario map using tiered protocol implementations.

   Optional third argument `replay-opts` may include `:flags` (see `replay.flags`).
   Scenario `:options {:minimal true}` or `:options {:flags {...}}` merge the same way."
  ([protocol scenario] (replay-with-protocol protocol scenario {}))
   ([protocol scenario replay-opts]
    (risk/clear!)
    (let [flags              (replay-flags/resolve-replay-flags scenario replay-opts)
         vocab              (if (satisfies? proto/EconomicModel protocol)
                              (proto/metric-vocabulary protocol)
                              #{})
         effective-metrics  (into (into metrics/base-metrics vocab)
                                  (or (metrics/expectation-metric-keys scenario) #{}))
         validation         (validate-scenario scenario effective-metrics
                                               {:strict-validation? (:strict-validation? flags)})
         temporal-cfg       (:temporal-evidence scenario)
         temporal-enabled?    (:temporal-enabled? flags)]
     (if-not (:ok validation)
       {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed 0 :trace [] :metrics (metrics/zero-metrics protocol) :halt-reason (:error validation) :protocol protocol}
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
             raw-result (run-simulation-loop protocol context scenario-id events world0 [] (metrics/zero-metrics protocol)
                                             {:expected-errors-set expected-errors-set
                                              :strict-expected-errors? strict-expected-errors?
                                              :allow-open-entities? (:allow-open-entities? scenario)
                                              :allow-open-disputes? (:allow-open-disputes? scenario)
                                              :agents agents
                                              :temporal-cfg temporal-cfg
                                              :temporal-enabled? temporal-enabled?
                                              :agent-index agent-index
                                              :scenario scenario
                                              :replay-flags flags})
             trimmed-result (replay-checkpoints/apply-checkpoint-policy-to-result
                             (:world-checkpoint-policy flags)
                             raw-result)]
         (attr/log-with-attr :info "scenario/start" {:id scenario-id})
         (let [result (if (:evaluate-expectations? flags true)
                        (finalize-scenario-result scenario trimmed-result flags)
                        trimmed-result)]
           (assoc result :risk-events (risk/events))))))))

(defn replay-yield-scenario
  "Thin sequential replay for `yield-v1` (see `replay.yield`)."
  ([scenario] (yield-replay/replay-yield-scenario scenario))
  ([protocol scenario] (yield-replay/replay-yield-scenario protocol scenario)))

(defn simple-replay
  "Replay with library-style defaults: no temporal enforcement, no theory DSL, relaxed validation.
   
   For `yield-v1`, delegates to `replay-yield-scenario` (thin runner). Other protocols
   use `replay-with-protocol` with `minimal-replay-flags`. Caller `replay-opts` apply
   only on the generic path.
   
   Auto-defaults :schema-version to \"1.0\" when missing so hand-built
   notebook scenarios work without an explicit version key."
  [protocol scenario & [replay-opts]]
  (let [scenario (if (:schema-version scenario)
                   scenario
                   (assoc scenario :schema-version "1.0"))]
    (if (= "yield-v1" (proto/protocol-id protocol))
      (yield-replay/replay-yield-scenario protocol scenario)
      (replay-with-protocol protocol scenario
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
        temporal-enabled? (boolean (:enabled? temporal-cfg))]
    (run-simulation-loop protocol context scenario-id events world trace metrics'
                         (merge {:expected-errors-set expected-errors-set
                                 :strict-expected-errors? strict-expected-errors?
                                 :allow-open-entities? true
                                 :allow-open-disputes? true
                                 :agents agents
                                 :temporal-cfg temporal-cfg
                                 :temporal-enabled? temporal-enabled?
                                 :agent-index agent-index}
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
