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
             [resolver-sim.protocols.protocol :as proto]
             [resolver-sim.db.temporal       :as temporal]))
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
    :invariant-violations})

(defn- metric-key
  "Coerce a metric name (string or keyword) to a keyword, stripping any
   spurious leading colon (e.g. ':reverts' → :reverts)."
  [x]
  (let [s  (if (keyword? x) (name x) (str x))
        s' (if (.startsWith s ":") (subs s 1) s)]
    (keyword s')))

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

(defn- expected-error-key
  [{:keys [seq action error]}]
  [seq action error])

(defn- rejected-entry-key
  [{:keys [seq action error]}]
  [seq action error])

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

      ;; :falsifies-if may be absent or empty ONLY when mechanism-properties or
      ;; equilibrium-concept are declared (mechanism-only theory blocks are valid).
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

      ;; All metric names in expectations.metrics and theory.falsifies-if must
      ;; be in effective-metrics. This prevents silent :inconclusive results
      ;; caused by typos or references to unimplemented metrics.
      (let [exp-metrics    (map #(metric-key (:name   %)) (get-in scenario [:expectations :metrics] []))
            theory-metrics (map #(metric-key (:metric %)) (get-in scenario [:theory :falsifies-if] []))
            all-refs       (concat exp-metrics theory-metrics)
            unknown        (vec (remove effective-metrics all-refs))]
        (seq unknown))
      {:ok false :error :unknown-metric-references
       :detail {:unknown (vec (remove effective-metrics
                                (concat (map #(metric-key (:name   %)) (get-in scenario [:expectations :metrics] []))
                                        (map #(metric-key (:metric %)) (get-in scenario [:theory :falsifies-if] [])))))
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
  "Canonical simulation-time transition helper.
   Returns {:world w' :delta-ms n :advanced? bool}."
  [world event-time]
  (let [now (:block-time world)
        delta (- event-time now)]
    (if (pos? delta)
      {:world (assoc world :block-time event-time)
       :delta-ms delta
       :advanced? true}
      {:world world
       :delta-ms 0
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
  (let [event-time (:time event)
        now        (:block-time world)
        rules      (effective-temporal-rules context)
        temporal-failure (evaluate-temporal-rules rules
                                                  {:event-time event-time
                                                   :now now
                                                   :world world
                                                   :advanced-world (:world (advance-world-time world event-time))
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
                       :agent           (:agent event)
                       :action          (:action event)
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
            :agent           (:agent event)
            :action          (:action event)
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

;; ---------------------------------------------------------------------------
;; Public API (Generic)
;; ---------------------------------------------------------------------------

(defn replay-with-protocol
  "Replay a scenario map using tiered protocol implementations."
  [protocol scenario]
  (let [vocab              (if (satisfies? proto/EconomicModel protocol)
                             (proto/metric-vocabulary protocol)
                             #{})
        effective-metrics  (into base-metrics vocab)
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
        (loop [world world0 events events trace [] metrics (zero-metrics protocol) id-alias-map {}]
          (if (empty? events)
            (let [open (when-not (or (:allow-open-entities? scenario)
                                     (:allow-open-disputes? scenario))
                         (seq (proto/open-entities protocol world)))]
              (if open
                {:outcome :fail :scenario-id scenario-id :events-processed (count trace) :halt-reason :open-entities-at-end :detail {:open-entities (vec open)} :trace trace :metrics metrics :agents agents :protocol protocol}
                (do
                  (when temporal-enabled?
                    (temporal/record-temporal-run!
                     (:datasource temporal-cfg)
                     {:run {:run-id      (or (:run-id temporal-cfg) (str scenario-id "-run"))
                            :batch-id    (or (:batch-id temporal-cfg) :temporal-batch)
                            :protocol    protocol
                            :suite-id    (or (:suite-id temporal-cfg) :temporal-suite)
                            :scenario-id scenario-id
                            :seed        (:seed scenario)
                            :git-sha     (or (:git-sha temporal-cfg) "unknown")
                            :outcome     :pass
                            :metrics     metrics
                            :block-time  (:block-time world)}
                      :steps (map-indexed (fn [i e]
                                            {:step-index i
                                             :action (:action e)
                                             :result (:result e)
                                             :time-before {}
                                             :time-advance {}
                                             :time-after {:time/block-ts (:time e)}
                                             :projection-hash (:projection-hash e)
                                             :block-time (:time e)})
                                          trace)
                      :invariants (mapcat (fn [i e]
                                            (for [[k r] (:violations e)
                                                  :when (map? r)]
                                              {:step-index i
                                               :invariant k
                                               :holds? (:holds? r)
                                               :severity :time
                                               :violations (:violations r)
                                               :block-time (:time e)}))
                                          (range)
                                          trace)
                      :coverage (:coverage temporal-cfg)}))
                  (let [expected-error-analysis (analyze-expected-errors scenario trace)
                        expected-errors-mismatch? (and strict-expected-errors?
                                                       (not (:ok? expected-error-analysis)))
                        outcome (if expected-errors-mismatch? :fail :pass)
                        halt-reason (when expected-errors-mismatch? :expected-error-mismatch)]
                    (log/info! "scenario/end" {:id scenario-id :outcome outcome})
                    {:outcome outcome
                     :scenario-id scenario-id
                     :events-processed (count trace)
                     :halt-reason halt-reason
                     :trace trace
                     :metrics metrics
                     :agents agents
                     :expected-error-analysis expected-error-analysis
                     :protocol protocol}))))
            (let [raw-event  (first events)
                  alias-res  (proto/resolve-id-alias protocol raw-event id-alias-map)]
              (if-not (:ok alias-res)
                {:outcome :invalid :scenario-id scenario-id :events-processed (count trace) :halted-at-seq (:seq raw-event) :halt-reason :unresolved-alias :detail (dissoc alias-res :ok) :trace trace :metrics metrics :protocol protocol}
                (let [event    (:event alias-res)
                      step     (process-step protocol context world event)
                      entry0   (:trace-entry step)
                      expected-failure? (and (= :rejected (:result entry0))
                                             (contains? expected-errors-set
                                                        [(:seq entry0) (:action entry0) (:error entry0)]))
                      reject-phase (when (= :rejected (:result entry0))
                                     (if (:temporal-rule-id entry0) :temporal-rule :dispatch))
                      entry    (cond-> entry0
                                 (= :rejected (:result entry0))
                                 (assoc :reject-class (:error entry0)
                                        :reject-phase reject-phase
                                        :expected-failure? expected-failure?))
                      new-trace   (conj trace entry)
                      new-metrics (accum-metrics protocol metrics event entry agent-index world)
                      created     (when (and (= :ok (:result entry)) (:save-id-as raw-event))
                                    (proto/created-id protocol (:action event) (:extra entry)))
                      new-alias-map (if created
                                      (assoc id-alias-map (:save-id-as raw-event) created)
                                      id-alias-map)]
                  
                  (tap> {:scenario-id scenario-id :seq (:seq event) :world world :entry entry})
                  (log/debug! "scenario/step" {:id scenario-id :seq (:seq event) :action (:action event)})

                  (if (:halted? step)
                    (do
                      (when temporal-enabled?
                        (temporal/record-temporal-run!
                         (:datasource temporal-cfg)
                         {:run {:run-id      (or (:run-id temporal-cfg) (str scenario-id "-run"))
                                :batch-id    (or (:batch-id temporal-cfg) :temporal-batch)
                                :protocol    protocol
                                :suite-id    (or (:suite-id temporal-cfg) :temporal-suite)
                                :scenario-id scenario-id
                                :seed        (:seed scenario)
                                :git-sha     (or (:git-sha temporal-cfg) "unknown")
                                :outcome     :fail
                                :metrics     new-metrics
                                :block-time  (:block-time (:world step))}
                          :steps (map-indexed (fn [i e]
                                                {:step-index i
                                                 :action (:action e)
                                                 :result (:result e)
                                                 :time-before {}
                                                 :time-advance {}
                                                 :time-after {:time/block-ts (:time e)}
                                                 :projection-hash (:projection-hash e)
                                                 :block-time (:time e)})
                                              new-trace)
                          :invariants (mapcat (fn [i e]
                                                (for [[k r] (:violations e)
                                                      :when (map? r)]
                                                  {:step-index i
                                                   :invariant k
                                                   :holds? (:holds? r)
                                                   :severity :time
                                                   :violations (:violations r)
                                                   :block-time (:time e)}))
                                              (range)
                                              new-trace)
                          :coverage (:coverage temporal-cfg)}))
                      (log/error! "scenario/halt" {:id scenario-id :seq (:seq event) :reason :invariant-violation})
                      {:outcome :fail :scenario-id scenario-id :events-processed (count new-trace) :halted-at-seq (:seq event) :halt-reason :invariant-violation :trace new-trace :metrics new-metrics :protocol protocol})
                    (recur (:world step) (rest events) new-trace new-metrics new-alias-map)))))))))))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (let [serializable (dissoc result :protocol)]
    (json/write-str serializable :key-fn kw->json-key :value-fn kw-val->str)))
