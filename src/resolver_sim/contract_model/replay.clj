(ns resolver-sim.contract-model.replay
  "Open-world scenario replay proto. (Protocol Simulation Kernel)

   Provides the deterministic harness for executing scenarios. This engine
   is designed as a protocol-agnostic template. Implementation details
   (actions, invariants, snapshots) are protocol-specific and provided by
   implementations of the DisputeProtocol interface.

   Replay invariants (after every successful transition):
     1. protocol/check-invariants-single
     2. protocol/check-invariants-transition"
   (:require [clojure.data.json              :as json]
             [clojure.set                    :as set]
             [clojure.stacktrace             :as st]
             [clojure.string                :as str]
              [resolver-sim.logging          :as log]
             [resolver-sim.definitions.registry :as defs]
             [resolver-sim.scenario.schema-profile :as schema-profile]
             [resolver-sim.scenario.expectations :as expectations]
             [resolver-sim.scenario.theory :as theory]
             [resolver-sim.scenario.yield-metrics :as yield-metrics]
             [resolver-sim.protocols.protocol :as proto]
             [resolver-sim.time.model        :as time-model]))
;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private supported-versions (:supported-versions schema-profile/default-profile))

;; ---------------------------------------------------------------------------
;; JSON serialisation helpers (Generic)
;; ---------------------------------------------------------------------------

(defn- kw->json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn- kw-val->str [_k v]
  (if (keyword? v) (name v) v))

;; ---------------------------------------------------------------------------
;; Agent Validation (Generic)
;; ---------------------------------------------------------------------------

(defn validate-agents
  "Validate a list of agent maps {:id :address :role :strategy ...} for structural correctness.
   Returns {:ok true} or {:ok false :error kw :detail {...}}.

   Checks: non-empty, unique :id values, unique :address values."
  [agents]
  (let [id-counts   (frequencies (map :id agents))
        addr-counts (frequencies (map :address agents))
        dup-ids     (keys (filter (fn [[_ n]] (> n 1)) id-counts))
        dup-addrs   (keys (filter (fn [[_ n]] (> n 1)) addr-counts))]
    (cond
      (empty? agents)   {:ok false :error :no-agents}
      (seq dup-ids)     {:ok false :error :duplicate-agent-ids
                         :detail {:duplicates (vec dup-ids)}}
      (seq dup-addrs)   {:ok false :error :duplicate-agent-addresses
                         :detail {:duplicates (vec dup-addrs)}}
      :else             {:ok true})))

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

(def population-metrics
  "Metrics that require stochastic / multi-epoch population simulation.
   Must not appear in single-trace `:theory :falsifies-if` unless
   `:theory :metric-scope` is `:population` (multi-epoch runner).
   Keep in sync with `resolver-sim.sim.multi-epoch/known-metrics`."
  #{:coalition/net-profit
    :malice-mean-profit
    :dominance-ratio
    :mean-profit
    :reputation-concentration})

(def base-metrics
  "Universal metrics incremented by the replay engine for every protocol.

   These counters are tracked regardless of which DisputeProtocol is active.
   Protocol implementations declare their own additional metrics via
   DisputeProtocol/metric-vocabulary; the full effective set is the union of
   base-metrics and metric-vocabulary.

     :attack-attempts      adversarial events (per-event :adversarial? flag or agent type)
     :attack-successes     adversarial events that were accepted
     :rejected-attacks     adversarial events that were rejected
     :reverts              all rejected events (blunt aggregate)
     :invariant-violations aggregate count of invariant failures

   NOTE: :funds-lost is NOT a base metric — it is protocol-specific (financial
   protocols declare it via metric-vocabulary). Non-financial protocols (e.g.
   governance, identity) would never populate it."
  #{:attack-attempts
    :attack-successes
    :rejected-attacks
    :reverts
    :invariant-violations
    :batch-buckets
    :batch-events
    :batch-conflicts})

(defn- metric-key
  "Coerce a metric name to a keyword; preserve namespaced keys like :coalition/net-profit."
  [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (if (.startsWith ^String x ":") (subs x 1) x)]
      (if (.contains s "/")
        (let [[ns n] (str/split s #"/" 2)]
          (keyword ns n))
        (keyword s)))
    :else (keyword (str x))))

(defn- falsifies-if-metric-refs
  "All metric keywords referenced by a `:falsifies-if` vector or predicate tree."
  [falsifies-if]
  (cond
    (nil? falsifies-if) []
    (sequential? falsifies-if)
    (vec (distinct (keep #(when-let [m (:metric %)] (metric-key m)) falsifies-if)))

    (map? falsifies-if)
    (vec (distinct
          (concat
           (when-let [m (:metric falsifies-if)] [(metric-key m)])
           (mapcat falsifies-if-metric-refs (:and falsifies-if))
           (mapcat falsifies-if-metric-refs (:or falsifies-if))
           (mapcat falsifies-if-metric-refs (list (:not falsifies-if)))
           (mapcat falsifies-if-metric-refs (:always falsifies-if))
           (mapcat falsifies-if-metric-refs (:eventually falsifies-if))
           (when-let [p (:after falsifies-if)] (falsifies-if-metric-refs (:predicate p)))
           (when-let [p (:before falsifies-if)] (falsifies-if-metric-refs (:predicate p)))
           (when-let [p (:implies falsifies-if)]
             (concat (falsifies-if-metric-refs (:if p))
                     (falsifies-if-metric-refs (:then p)))))))

    :else []))

(defn- theory-metric-scope [scenario]
  (metric-key (or (get-in scenario [:theory :metric-scope]) :trace)))

(defn- action->transition-id
  "Map an event action string/keyword to canonical transition semantic id.
   Keeps backward compatibility by tolerating hyphen/underscore forms."
  [action]
  (let [s (-> (if (keyword? action) (name action) (str action))
              str/lower-case
              (str/replace "-" "_"))
        k (keyword s)]
    (if (defs/transition-def k)
      (keyword "scenario.transition" s)
      (keyword "scenario.transition" "unknown"))))

(defn- normalize-error-value
  [error]
  (cond
    (keyword? error) error
    (string? error)  (let [s (if (.startsWith ^String error ":")
                               (subs error 1)
                               error)]
                       (keyword s))
    :else error))

(defn- expected-error-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn- rejected-entry-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn- analyze-expected-errors
  "Compare rejected trace entries against scenario :expected-errors.

   Returns {:ok? bool :matched [...] :missing [...] :unexpected [...]}.
   Matching is exact on [:seq :action :error]."
  [scenario trace]
  (let [expected        (vec (:expected-errors scenario []))
        expected-set    (set (map expected-error-key expected))
        rejected        (->> trace
                             (filter #(= :rejected (:result %)))
                             (map #(select-keys % [:seq :action :error]))
                             vec)
        rejected-set    (set (map rejected-entry-key rejected))
        matched-set     (set/intersection expected-set rejected-set)
        missing-set     (set/difference expected-set rejected-set)
        unexpected-set  (set/difference rejected-set expected-set)]
    {:ok?        (and (empty? missing-set) (empty? unexpected-set))
     :matched    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? matched-set (expected-error-key %)) expected)))
     :missing    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? missing-set (expected-error-key %)) expected)))
     :unexpected (vec (sort-by (juxt :seq :action)
                               (filter #(contains? unexpected-set (rejected-entry-key %)) rejected)))}))

;; ---------------------------------------------------------------------------
;; Input validation (Generic scenario structure)
;; ---------------------------------------------------------------------------

(defn validate-scenario
  "Validate a scenario map for structural correctness before replay.
   Accepts an optional effective-metrics set used to validate metric references
   in :expectations and :theory. Defaults to base-metrics (universal counters)."
  ([scenario] (validate-scenario scenario base-metrics))
  ([scenario effective-metrics]
   (let [version     (str (:schema-version scenario))
         agents      (:agents scenario)
         events      (sort-by :seq (:events scenario))
         known-ids   (set (map :id agents))
         init-time   (get scenario :initial-block-time 1000)
         agent-check (validate-agents agents)]
    (cond
      (not (contains? supported-versions version))
      {:ok false :error :unsupported-schema-version
       :detail {:expected supported-versions :got version}}

      (and (contains? (set (schema-profile/required-fields version)) :id)
           (not (:id scenario)))
      {:ok false :error :missing-id :detail "v1.1 scenarios must have a unique :id"}

      (and (contains? (set (schema-profile/required-fields version)) :title)
           (not (:title scenario)))
      {:ok false :error :missing-title :detail "v1.1 scenarios must have a human-readable :title"}

      (and (contains? (set (schema-profile/required-fields version)) :purpose)
           (not (:purpose scenario)))
      {:ok false :error :missing-purpose :detail "v1.1 scenarios must declare a :purpose"}

      (and (contains? (set (schema-profile/required-fields version)) :scenario-author)
           (not (string? (:scenario-author scenario))))
      {:ok false :error :missing-scenario-author
       :detail "v1.1 scenarios must include :scenario-author as a non-empty string"}

      (and (contains? (set (schema-profile/required-fields version)) :scenario-author)
           (str/blank? (:scenario-author scenario)))
      {:ok false :error :blank-scenario-author
       :detail ":scenario-author must not be blank"}

      ;; Purpose-based requirements are enforced for enriched schemas only
      ;; (v1.1+). Legacy v1.0 scenario packs are intentionally tolerated.
      (and (contains? (set (schema-profile/required-fields version)) :purpose)
           (schema-profile/requires-theory? (:purpose scenario))
           (not (:theory scenario)))
      {:ok false :error :theory-required
       :detail "purpose :theory-falsification requires a :theory block"}

      ;; :adversarial-robustness scenarios must include :theory or meaningful :expectations
      (and (contains? (set (schema-profile/required-fields version)) :purpose)
           (schema-profile/requires-theory-or-expectations? (:purpose scenario))
           (not (:theory scenario))
           (empty? (get-in scenario [:expectations :metrics]))
           (empty? (get-in scenario [:expectations :terminal]))
           (empty? (get-in scenario [:expectations :invariants])))
      {:ok false :error :adversarial-requires-analysis
       :detail "purpose :adversarial-robustness requires :theory or non-trivial :expectations"}

      ;; Validate :theory structure when present
      (and (:theory scenario) (not (get-in scenario [:theory :claim-id])))
      {:ok false :error :theory-missing-claim-id
       :detail ":theory must include a :claim-id"}

      (and (:theory scenario) (nil? (get-in scenario [:theory :assumptions])))
      {:ok false :error :theory-missing-assumptions
       :detail ":theory must include an :assumptions vector (may be empty)"}

      ;; :purpose :theory-falsification requires a direct metric disconfirmer (negative test).
      (and (:theory scenario)
           (contains? (set (schema-profile/required-fields version)) :purpose)
           (schema-profile/requires-metric-falsifies-if? (:purpose scenario))
           (not (seq (get-in scenario [:theory :falsifies-if])))
           (not (true? (get-in scenario [:theory :mechanism-only-negative-test?]))))
      {:ok false :error :theory-falsification-requires-falsifies-if
       :detail ":purpose :theory-falsification requires a non-empty :falsifies-if (metric disconfirmer), or :mechanism-only-negative-test? true with mechanism/equilibrium proxies"}

      ;; :falsifies-if may be empty for regression/adversarial when mechanism-properties or
      ;; equilibrium-concept are declared (mechanism-only theory blocks).
      (and (:theory scenario)
           (not (seq (get-in scenario [:theory :falsifies-if])))
           (empty? (get-in scenario [:theory :mechanism-properties]))
           (empty? (get-in scenario [:theory :equilibrium-concept])))
      {:ok false :error :theory-missing-falsifies-if
       :detail ":theory must include a non-empty :falsifies-if vector, or declare :mechanism-properties / :equilibrium-concept"}

      (not (:ok agent-check))
      agent-check

      (empty? events)
      {:ok false :error :no-events}

      (not= (mapv :seq events) (vec (range (count events))))
      {:ok false :error :non-contiguous-event-seq :detail {:got (mapv :seq events)}}

      (some (fn [[a b]] (> (:time a) (:time b))) (partition 2 1 events))
      {:ok false :error :non-monotonic-event-time
       :detail {:violations (vec (filter (fn [[a b]] (> (:time a) (:time b))) (partition 2 1 events)))}}

      (some #(< (:time %) init-time) events)
      {:ok false :error :event-time-before-initial
       :detail {:initial-block-time init-time
                :violations (mapv :time (filter #(< (:time %) init-time) events))}}

      (some #(not (contains? known-ids (:agent %))) events)
      {:ok false :error :unknown-agent-in-event
       :detail {:bad-refs (vec (filter #(not (contains? known-ids (:agent %))) events))}}

      ;; Population metrics in single-trace theory → hard error (silent inconclusive otherwise).
      (and (:theory scenario)
           (not= :population (theory-metric-scope scenario))
           (seq (let [refs (falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))
                      bad  (vec (filter population-metrics refs))]
                  bad)))
      {:ok false :error :population-metric-in-trace-theory
       :detail {:metrics (vec (filter population-metrics
                               (falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))))
                :metric-scope (theory-metric-scope scenario)
                :hint "Single-trace replay cannot compute population metrics. Use trace metrics (e.g. :coalition-net-profit), or set :theory {:metric-scope :population} for multi-epoch scenarios."}}

      ;; Theory :falsifies-if metrics must be in the active trace metric registry
      ;; (population-scoped metrics are validated by the multi-epoch runner, not here).
      (let [scope          (theory-metric-scope scenario)
            theory-metrics (falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))
            trace-metrics  (if (= scope :population)
                             (vec (remove population-metrics theory-metrics))
                             theory-metrics)
            unknown-theory (vec (remove effective-metrics trace-metrics))]
        (seq unknown-theory))
      {:ok false :error :unknown-theory-metric
       :detail {:unknown (vec (remove effective-metrics
                               (falsifies-if-metric-refs (get-in scenario [:theory :falsifies-if]))))
                :known   effective-metrics
                :hint    "Declare the metric in the protocol metric-vocabulary or fix the name."}}

      ;; Expectations metrics — separate error code for clearer diagnostics.
      (let [exp-metrics (map #(metric-key (:name %)) (get-in scenario [:expectations :metrics] []))
            unknown-exp (vec (remove effective-metrics exp-metrics))]
        (seq unknown-exp))
      {:ok false :error :unknown-expectation-metric
       :detail {:unknown (vec (remove effective-metrics
                               (map #(metric-key (:name %)) (get-in scenario [:expectations :metrics] []))))
                :known effective-metrics}}

      :else {:ok true}))))

;; ---------------------------------------------------------------------------
;; Metrics — accumulation
;; ---------------------------------------------------------------------------

(defn- zero-metrics
  "Initialise the metrics accumulator for one replay run.

   Produces a map containing all base-metrics keys, zeroed, plus any
   protocol-specific keys declared via EconomicModel/metric-vocabulary."
  [protocol]
  (let [base {:attack-attempts      0
              :attack-successes     0
              :rejected-attacks     0
              :reverts              0
              :invariant-violations 0
              :batch-buckets        0
              :batch-events         0
              :batch-conflicts      0
              :invariant-results    {}}
        vocab (if (satisfies? proto/EconomicModel protocol)
                (proto/metric-vocabulary protocol)
                #{})]
    (into base (map #(vector % 0) vocab))))

(defn- accum-metrics [protocol metrics event trace-entry agent-index world-before]
  (let [result-kw (:result trace-entry)
        accepted? (= result-kw :ok)
        agent     (get agent-index (:agent event))
        attack?   (if (satisfies? proto/EconomicModel protocol)
                    (proto/adversarial-event? protocol event agent)
                    false)
        tags      (:event-tags trace-entry)
        world-after (:world trace-entry)
        base (cond-> metrics
               (and attack? accepted?)
               (update :attack-successes inc)

               attack?
               (update :attack-attempts inc)

               (and attack? (not accepted?))
               (update :rejected-attacks inc)

               (not accepted?)
               (update :reverts inc)

               (= :batch-conflict (:error trace-entry))
               (update :batch-conflicts inc)

               (:violations trace-entry)
               (-> (update :invariant-violations inc)
                   (update :invariant-results
                           (fn [acc]
                             (reduce (fn [m [kw r]]
                                       (if (:holds? r) m (assoc m kw :fail)))
                                     acc
                                     (:violations trace-entry))))))]
    (if (satisfies? proto/EconomicModel protocol)
      (proto/accum-protocol-metrics protocol base tags event accepted? attack? world-before world-after)
      base)))

(defn- advance-world-time
  "Advance :block-time and scenario-step counter atomically.
   Returns {:world w' :delta-ms n :advanced? bool}.

   Same-timestamp events (event-time == block-time) are a no-op:
   world is unchanged and scenario-step is NOT incremented.
   This models same-block semantics — multiple actions at the same
   timestamp share a single time context without stepping the counter."
  [world event-time]
  (let [now (:block-time world)
        delta (- event-time now)]
    (if (pos? delta)
      (let [step (inc (get-in world [:time :scenario-step] 0))]
        {:world     (time-model/with-time world {:block-ts event-time :scenario-step step})
         :delta-ms  delta
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

(defn- effective-temporal-rules
  "Base temporal rules + optional protocol/context-provided rules.
   Extra rules must be maps with keys {:id kw :check (fn [ctx] -> {:ok? bool ...})}."
  [context]
  (let [extra (:temporal-rules context)
        extra' (if (sequential? extra) extra [])]
    (into temporal-rules extra')))

(defn- evaluate-temporal-rules
  [rules ctx]
  (reduce (fn [_ {:keys [id check]}]
            (let [r (check ctx)]
              (if (:ok? r)
                nil
                (reduced (assoc r :rule-id id)))))
          nil
          rules))

;; ---------------------------------------------------------------------------
;; Step Processing (Kernel)
;; ---------------------------------------------------------------------------

(defn process-step
  "Apply one scenario event using tiered Protocol implementations."
  [protocol context world event]
  (let [event-time   (:time event)
        now          (:block-time world)
        time-before  {:block-ts now}
        rules        (effective-temporal-rules context)
        temporal-failure (evaluate-temporal-rules rules
                                                  {:event-time event-time
                                                   :now now
                                                   :world world
                                                   :event event
                                                   :context context
                                                   :protocol protocol})]
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
                        :invariants-ok?  true
                        :violations      nil
                        :world           (proto/world-snapshot protocol world)
                        :projection      proj
                        :projection-hash ph}
          :halted? false})

      (let [{world-t :world} (advance-world-time world event-time)
            time-after       {:block-ts event-time}
            result     (try
                         (proto/dispatch-action protocol context world-t event)
                         (catch Exception e
                            (log/error! "dispatch exception"
                                        {:error (.getMessage e)
                                         :scenario-step (:seq event)
                                         :action (:action event)})
                           (.printStackTrace e)
                           {:ok false :error :dispatch-exception
                            :detail {:message (.getMessage e)
                                     :stack   (with-out-str (st/print-stack-trace e))}}))
            ok?        (:ok result)
            world-next (if (and ok? (:world result)) (:world result) world-t)

            inv-single (when ok? (proto/check-invariants-single protocol world-next))
            inv-trans  (when ok? (proto/check-invariants-transition protocol world-t world-next))
            violated?  (and ok? (not (and (:ok? inv-single) (:ok? inv-trans))))
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
            :invariants-ok?  (if ok? (and (:ok? inv-single) (:ok? inv-trans)) true)
            :violations      all-violations
            :trace-metadata  metadata
            :world           (proto/world-snapshot protocol final-world)
            :projection      proj
            :projection-hash ph}
           :halted? violated?})))))

(defn- execution-mode
  [scenario]
  (keyword (or (:execution-mode scenario) :sequential)))

(def ^:private batch-commit-policy :deterministic-first-wins)

(defn- event-conflict-domains*
  [protocol world event]
  (let [domains (when (satisfies? proto/BatchConflictModel protocol)
                  (proto/event-conflict-domains protocol world event))]
    (if (seq domains)
      (set domains)
      #{[:global :unknown]})))

(defn- group-same-time-bucket
  [events]
  (let [t (:time (first events))]
    (split-with #(= t (:time %)) events)))

(defn- conflict-rejection-entry
  [protocol world-before event preflight-status conflict-domain conflict-with-seq]
  (let [[proj ph] (if (satisfies? proto/AnalysisModule protocol)
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

(defn- expectation-metric-keys [scenario]
  (when-let [metrics (get-in scenario [:expectations :metrics])]
    (into #{} (map #(theory/metric-key (:name %)) metrics))))

(defn finalize-scenario-result
  "Apply yield metrics, expected-outcomes, and :expectations checks."
  [scenario result]
  (let [result'      (yield-metrics/merge-yield-metrics result)
        outcomes     (expectations/analyze-expected-outcomes scenario (:trace result'))
        expect       (when (:expectations scenario)
                       (expectations/evaluate-expectations result' (:expectations scenario)))
        outcomes-ok? (:ok? outcomes true)
        expect-ok?   (or (nil? expect) (:ok? expect))
        checks-ok?   (and outcomes-ok? expect-ok?)]
    (cond-> (assoc result'
              :expected-outcomes outcomes
              :expectations expect)
      (and (= (:outcome result') :pass) (not checks-ok?))
      (assoc :outcome :fail
             :halt-reason (if (not outcomes-ok?)
                            :expected-outcome-mismatch
                            :expectation-mismatch)
             :expectation-violations
             (vec (concat (:violations outcomes [])
                          (:violations expect [])))))))

(defn- maybe-record-temporal!
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

(defn- run-simulation-loop
  "Execute the core simulation loop from a given world state and event sequence."
  [protocol context scenario-id events world trace metrics options]
  (let [{:keys [expected-errors-set strict-expected-errors?
                allow-open-entities? allow-open-disputes?
                agents temporal-cfg temporal-enabled? agent-index
                scenario]} options
        supports-alias? (satisfies? proto/SimulationAdapter protocol)]
    (loop [world world
           events events
           trace trace
           metrics metrics
           states {(:seq (first events) 0) (proto/world-snapshot protocol world)}
           id-alias-map {}]
      (if (empty? events)
        (let [open (when-not (or allow-open-entities? allow-open-disputes?)
                     (seq (proto/open-entities protocol world)))]
          (if open
            {:outcome :fail :scenario-id scenario-id :events-processed (count trace) :halt-reason :open-entities-at-end :detail {:open-entities (vec open)} :trace trace :metrics metrics :agents agents :protocol protocol}
            (do
              (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :pass world metrics trace)
              (let [expected-error-analysis (analyze-expected-errors scenario trace)
                    expected-errors-mismatch? (and strict-expected-errors?
                                                  (not (:ok? expected-error-analysis)))
                    outcome (if expected-errors-mismatch? :fail :pass)
                    halt-reason (when expected-errors-mismatch? :expected-error-mismatch)]
                (log/info! "scenario/end" {:id scenario-id :outcome outcome})
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
                 :events events
                 :agents agents
                 :protocol protocol}))))
        (if (= :deterministic-batch (execution-mode scenario))
          (let [[bucket rest-events] (group-same-time-bucket events)
                base-world world
                resolved-bucket (mapv (fn [raw]
                                        (if (and supports-alias? (seq id-alias-map))
                                          (let [res (proto/resolve-id-alias protocol raw id-alias-map)]
                                            (if (:ok res) (:event res) raw))
                                          raw))
                                      bucket)
                batch-time (:time (first resolved-bucket))
                metrics' (-> metrics
                             (update :batch-buckets inc)
                             (update :batch-events + (count resolved-bucket)))
                preflight (into {}
                                (map (fn [ev]
                                       (let [s (process-step protocol context base-world ev)]
                                         [(:seq ev)
                                          (if (= :ok (get-in s [:trace-entry :result])) :eligible :ineligible)])))
                                resolved-bucket)
                batch-result (reduce
                              (fn [acc event]
                                (if (:halted? acc)
                                  acc
                                  (let [working-world (:world acc)
                                        domains (event-conflict-domains* protocol working-world event)
                                        conflict-domain (some #(when (contains? (:claimed-domains acc) %) %) domains)
                                        winner-seq (get (:claimed-domains acc) conflict-domain)
                                        pre-status (get preflight (:seq event) :ineligible)]
                                    (if conflict-domain
                                      (let [entry (-> (conflict-rejection-entry protocol working-world event pre-status conflict-domain winner-seq)
                                                      (assoc :expected-failure?
                                                             (contains? expected-errors-set
                                                                        [(:seq event) (:action event) :batch-conflict])))]
                                        (-> acc
                                            (update :trace conj entry)
                                            (assoc :metrics (accum-metrics protocol (:metrics acc) event entry agent-index working-world))
                                            (update :states assoc (:seq event) (proto/world-snapshot protocol working-world))))
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
                                            (assoc :metrics (accum-metrics protocol (:metrics acc) event entry agent-index working-world))
                                            (update :states assoc (:seq event) (proto/world-snapshot protocol new-world))))))))
                              {:world base-world
                               :trace trace
                               :metrics metrics'
                               :states states
                               :claimed-domains {}
                               :halted? false
                               :id-alias-map id-alias-map}
                              resolved-bucket)]
            (if (:halted? batch-result)
              (do
                (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world batch-result) (:metrics batch-result) (:trace batch-result))
                {:outcome :fail :scenario-id scenario-id :events-processed (count (:trace batch-result)) :halt-reason :invariant-violation :trace (:trace batch-result) :metrics (:metrics batch-result) :execution {:mode :deterministic-batch :batch-policy batch-commit-policy} :protocol protocol})
              (let [post-single (proto/check-invariants-single protocol (:world batch-result))
                    post-trans  (proto/check-invariants-transition protocol base-world (:world batch-result))
                    post-ok?    (and (:ok? post-single) (:ok? post-trans))
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
                     :protocol protocol})))))
          (let [raw-event (first events)
                event (if (and supports-alias? (seq id-alias-map))
                        (let [res (proto/resolve-id-alias protocol raw-event id-alias-map)]
                          (if (:ok res) (:event res) raw-event))
                        raw-event)
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
                               :expected-failure? expected-failure?))
                new-trace (conj trace entry)
                new-metrics (accum-metrics protocol metrics event entry agent-index world)
                new-world (:world step)
                new-states (assoc states (:seq event) (proto/world-snapshot protocol new-world))]
            (tap> {:scenario-id scenario-id :seq (:seq event) :world world :entry entry})
            (log/debug! "scenario/step" {:id scenario-id :seq (:seq event) :action (:action event)})
            (if (:halted? step)
              (do
                (maybe-record-temporal! temporal-cfg temporal-enabled? scenario-id :fail (:world step) new-metrics new-trace)
                (log/error! "scenario/halt" {:id scenario-id :seq (:seq event) :reason :invariant-violation})
                {:outcome :fail :scenario-id scenario-id :events-processed (count new-trace) :halted-at-seq (:seq event) :halt-reason :invariant-violation :trace new-trace :metrics new-metrics :execution {:mode :sequential} :protocol protocol})
              (recur new-world (rest events) new-trace new-metrics new-states new-alias-map))))))))

(defn replay-with-protocol
  "Replay a scenario map using tiered protocol implementations."
  [protocol scenario]
  (let [vocab              (if (satisfies? proto/EconomicModel protocol)
                             (proto/metric-vocabulary protocol)
                             #{})
        effective-metrics  (into (into base-metrics vocab)
                                 (or (expectation-metric-keys scenario) #{}))
        validation (validate-scenario scenario effective-metrics)
        temporal-cfg (:temporal-evidence scenario)
        temporal-enabled? (boolean (:enabled? temporal-cfg))]
    (if-not (:ok validation)
      {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed 0 :trace [] :metrics (zero-metrics protocol) :halt-reason (:error validation) :protocol protocol}
      (let [agents   (:agents scenario)
            p-params (get scenario :protocol-params {})
            context  (proto/build-execution-context protocol agents p-params)
            agent-index (:agent-index context)
            world0  (proto/init-world protocol scenario)
            events  (sort-by :seq (:events scenario))
            scenario-id (:scenario-id scenario)
            expected-errors-set (set (map expected-error-key (:expected-errors scenario [])))
            strict-expected-errors? (boolean (:strict-expected-errors? scenario false))]
        (log/info! "scenario/start" {:id scenario-id})
        (finalize-scenario-result
         scenario
         (run-simulation-loop protocol context scenario-id events world0 [] (zero-metrics protocol)
                              {:expected-errors-set expected-errors-set
                               :strict-expected-errors? strict-expected-errors?
                               :allow-open-entities? (:allow-open-entities? scenario)
                               :allow-open-disputes? (:allow-open-disputes? scenario)
                               :agents agents
                               :temporal-cfg temporal-cfg
                               :temporal-enabled? temporal-enabled?
                               :agent-index agent-index
                               :scenario scenario}))))))
(defn resume-from-snapshot
  "Resume a simulation from a world snapshot and a sequence of events.
   Useful for exploring counterfactual subgames."
  [protocol agents p-params scenario-id world events trace metrics options]
  (let [context  (proto/build-execution-context protocol agents p-params)
        agent-index (:agent-index context)
        expected-errors-set (set (map expected-error-key (:expected-errors (:scenario options) [])))
        strict-expected-errors? (boolean (:strict-expected-errors? (:scenario options) false))
        temporal-cfg (:temporal-evidence (:scenario options))
        temporal-enabled? (boolean (:enabled? temporal-cfg))]
    (run-simulation-loop protocol context scenario-id events world trace metrics
                         (merge {:expected-errors-set expected-errors-set
                                 :strict-expected-errors? strict-expected-errors?
                                 :allow-open-entities? true
                                 :allow-open-disputes? true
                                 :agents agents
                                 :temporal-cfg temporal-cfg
                                 :temporal-enabled? temporal-enabled?
                                 :agent-index agent-index}
                                options))))

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

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (let [serializable (dissoc result :protocol)]
    (json/write-str serializable :key-fn kw->json-key :value-fn kw-val->str)))
