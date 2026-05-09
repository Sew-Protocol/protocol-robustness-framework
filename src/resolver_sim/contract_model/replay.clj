(ns resolver-sim.contract-model.replay
  "Open-world scenario replay engine. (Protocol Simulation Kernel)
  
    Provides the deterministic harness for executing scenarios. This engine 
    is designed as a protocol-agnostic template and is currently instantiated 
    for the SEW Protocol. Implementation details (actions, invariants, 
    snapshots) are protocol-specific.

    DisputeProtocol implementation.

    ## Replay Invariants
    After every successful transition:
      1. protocol/check-invariants-single
      2. protocol/check-invariants-transition"
   (:require [clojure.data.json              :as json]
             [clojure.stacktrace             :as st]
             [clojure.string                :as str]
             [resolver-sim.protocols.protocol :as engine]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private supported-versions #{"1.0" "1.1"})

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
;;   resolver-sim.sim.multi-epoch/known-metrics   (stochastic, multi-epoch)
;;
;; Do NOT add population/batch metrics here. Blending the two worlds would
;; cause validate-scenario to accept :theory/falsifies-if conditions that
;; the deterministic replay can never satisfy, producing silent :inconclusive
;; results for the wrong reason.
(def known-metrics
  "Canonical set of metric keys emitted by the deterministic replay engine.

   Any metric referenced in :expectations/:metrics or :theory/:falsifies-if
   must be in this set. validate-scenario rejects scenarios that reference
   unknown metrics so that :inconclusive results are never caused by typos
   or references to unimplemented metrics.

   All metrics below are LIVE — incremented by accum-metrics on each event.

     :total-escrows                  accepted create_escrow calls
     :total-volume                   sum of :amount for accepted create_escrow
     :disputes-triggered             accepted raise_dispute calls
     :resolutions-executed           accepted execute_resolution calls
     :pending-settlements-executed   accepted execute_pending_settlement calls
     :attack-attempts                adversarial events (per-event flag or agent type=attacker)
     :attack-successes               adversarial events that were accepted
     :rejected-attacks               adversarial events that were rejected
     :reverts                        all rejected events (blunt aggregate)
     :invariant-violations           aggregate count of invariant failures
     :double-settlements             accepted settlement after a prior resolution
     :invalid-state-transitions      rejected events with a state-logic error code
     :funds-lost                     decrease in total-held from accepted adversarial events

   NON-NUMERIC internal key (not in this set, not checkable via evaluate-metric-op):
     :invariant-results  — map {inv-kw :fail}; used only by evaluate-invariants."
  #{:total-escrows
    :total-volume
    :disputes-triggered
    :resolutions-executed
    :pending-settlements-executed
    :attack-attempts
    :attack-successes
    :rejected-attacks
    :reverts
    :invariant-violations
    :double-settlements
    :invalid-state-transitions
    :funds-lost})

(defn- metric-key
  "Coerce a metric name (string or keyword) to a keyword, stripping any
   spurious leading colon (e.g. ':reverts' → :reverts)."
  [x]
  (let [s  (if (keyword? x) (name x) (str x))
        s' (if (.startsWith s ":") (subs s 1) s)]
    (keyword s')))

;; ---------------------------------------------------------------------------
;; Input validation (Generic scenario structure)
;; ---------------------------------------------------------------------------

(defn validate-scenario
  "Validate a scenario map for structural correctness before replay."
  [scenario]
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

      (and (= version "1.1") (not (:id scenario)))
      {:ok false :error :missing-id :detail "v1.1 scenarios must have a unique :id"}

      (and (= version "1.1") (not (:title scenario)))
      {:ok false :error :missing-title :detail "v1.1 scenarios must have a human-readable :title"}

      (and (= version "1.1") (not (:purpose scenario)))
      {:ok false :error :missing-purpose :detail "v1.1 scenarios must declare a :purpose"}

      ;; :theory-falsification scenarios must include a :theory block
      (and (= version "1.1")
           (= :theory-falsification (:purpose scenario))
           (not (:theory scenario)))
      {:ok false :error :theory-required
       :detail "purpose :theory-falsification requires a :theory block"}

      ;; :adversarial-robustness scenarios must include :theory or meaningful :expectations
      (and (= version "1.1")
           (= :adversarial-robustness (:purpose scenario))
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
      ;; be in known-metrics. This prevents silent :inconclusive results caused
      ;; by typos or references to unimplemented metrics.
      (let [exp-metrics  (map #(metric-key (:name   %)) (get-in scenario [:expectations :metrics] []))
            theory-metrics (map #(metric-key (:metric %)) (get-in scenario [:theory :falsifies-if] []))
            all-refs      (concat exp-metrics theory-metrics)
            unknown       (vec (remove known-metrics all-refs))]
        (seq unknown))
      {:ok false :error :unknown-metric-references
       :detail {:unknown (vec (remove known-metrics
                                (concat (map #(metric-key (:name   %)) (get-in scenario [:expectations :metrics] []))
                                        (map #(metric-key (:metric %)) (get-in scenario [:theory :falsifies-if] [])))))
                :known known-metrics}}

      :else {:ok true})))

;; ---------------------------------------------------------------------------
;; Metrics — accumulation
;; ---------------------------------------------------------------------------

(defn- zero-metrics []
  {:total-escrows                0
   :total-volume                 0
   :disputes-triggered           0
   :resolutions-executed         0
   :pending-settlements-executed 0
   :attack-attempts              0
   :attack-successes             0
   :rejected-attacks             0
   :reverts                      0
   :invariant-violations         0
   :double-settlements           0
   :invalid-state-transitions    0
   :funds-lost                   0
   ;; Per-invariant failure map: {inv-kw :fail} for any invariant that
   ;; violated at least once during the run. Invariants NOT in this map
   ;; either passed throughout or were never exercised.  Used exclusively
   ;; by evaluate-invariants — not evaluated by evaluate-metric-op.
   :invariant-results            {}})

(defn- held-total
  "Sum all total-held values across tokens in a world or world-snapshot map.
   Accepts either a full world map (keyed :total-held) or a world-snapshot map."
  [world]
  (let [held (:total-held world {})]
    (if (map? held) (apply + (vals held)) 0)))

(defn- accum-metrics [metrics event trace-entry agent-index world-before]
  (let [result-kw (:result trace-entry)
        error-kw  (:error trace-entry)
        accepted? (= result-kw :ok)
        agent     (get agent-index (:agent event))
        ;; Per-event :adversarial? flag takes precedence over agent.type so
        ;; that mixed-role actors (e.g. an honest buyer performing adversarial
        ;; calls) can be classified at the event level without marking the whole
        ;; agent type as "attacker" (which would inflate attack-successes for
        ;; legitimate accepted actions by the same agent).
        attack?   (or (:adversarial? event)
                      (= "malicious" (:strategy agent))
                      (= "attacker" (:role agent))
                      (= "attacker" (:type agent)))
        tags      (:event-tags trace-entry)
        ;; double-settlements: a second accepted lifecycle-ending action on what
        ;; is (for single-escrow scenarios) already a resolved/settled workflow.
        ;; Uses aggregate resolutions-executed as a proxy — imprecise for
        ;; multi-escrow traces but correct for the current corpus.
        double-settle? (and accepted?
                            (or (contains? tags :dispute-resolved)
                                (contains? tags :settlement-executed))
                            (pos? (:resolutions-executed metrics)))
        ;; funds-lost: total-held decrease caused by an adversarial accepted
        ;; action. Computed as max(0, held-before - held-after) so that
        ;; refunds-into-held (impossible in normal operation) don't produce
        ;; negative values. Works for multi-token traces since all tokens are
        ;; summed. Does NOT fire for honest actions (attack? = false).
        held-before (held-total world-before)
        held-after  (held-total (:world trace-entry))
        funds-lost-delta (if (and attack? accepted?)
                           (max 0 (- held-before held-after))
                           0)]
    (cond-> metrics
      (contains? tags :entity-created)
      (-> (update :total-escrows inc)
          (update :total-volume + (get-in event [:params :amount] 0)))

      (contains? tags :dispute-raised)
      (update :disputes-triggered inc)

      (contains? tags :dispute-resolved)
      (update :resolutions-executed inc)

      (contains? tags :settlement-executed)
      (update :pending-settlements-executed inc)

      (and attack? accepted?)
      (update :attack-successes inc)

      attack?
      (update :attack-attempts inc)

      (and attack? (not accepted?))
      (update :rejected-attacks inc)

      (not accepted?)
      (update :reverts inc)

      ;; Increment aggregate violation counter and record which specific
      ;; invariants failed.  :violations is a map {inv-kw result-map}
      ;; from check-all / check-transition; only entries where :holds? is
      ;; false should be marked :fail.
      (:violations trace-entry)
      (-> (update :invariant-violations inc)
          (update :invariant-results
                  (fn [acc]
                    (reduce (fn [m [kw r]]
                              (if (:holds? r) m (assoc m kw :fail)))
                            acc
                            (:violations trace-entry)))))

      double-settle?
      (update :double-settlements inc)

      (contains? tags :invalid-state-transition)
      (update :invalid-state-transitions inc)

      (pos? funds-lost-delta)
      (update :funds-lost + funds-lost-delta))))

;; ---------------------------------------------------------------------------
;; Step Processing (Kernel)
;; ---------------------------------------------------------------------------

(defn process-step
  "Apply one scenario event using a specific DisputeProtocol implementation."
  [protocol context world event]
  (let [event-time (:time event)
        now        (:block-time world)]
    (if (< event-time now)
      (let [[proj proj-hash] (engine/compute-projection protocol world)
            tags             (engine/classify-event protocol event :rejected :time-regression)]
        {:ok?    true
         :world  world
         :trace-entry {:seq             (:seq event)
                       :time            event-time
                       :agent           (:agent event)
                       :action          (:action event)
                       :result          :rejected
                       :error           :time-regression
                       :extra           nil
                       :event-tags      tags
                       :invariants-ok?  true
                       :violations      nil
                       :world           (engine/world-snapshot protocol world)
                       :projection      proj
                       :projection-hash proj-hash}
         :halted? false})

      (let [world-t    (if (> event-time now) (assoc world :block-time event-time) world)
            result     (try
                         (engine/dispatch-action protocol context world-t event)
                         (catch Exception e
                           (println "[CRITICAL] Dispatch Exception:" (.getMessage e))
                           (.printStackTrace e)
                           {:ok false :error :dispatch-exception
                            :detail {:message (.getMessage e)
                                     :stack   (with-out-str (st/print-stack-trace e))}}))
            ok?        (:ok result)
            world-next (if (and ok? (:world result)) (:world result) world-t)

            inv-single (when ok? (engine/check-invariants-single protocol world-next))
            inv-trans  (when ok? (engine/check-invariants-transition protocol world-t world-next))
            violated?  (and ok? (not (and (:ok? inv-single) (:ok? inv-trans))))
            all-violations (when violated?
                             (merge (when-not (:ok? inv-single) (:violations inv-single))
                                    (when-not (:ok? inv-trans)  (:violations inv-trans))))]

        (let [result-kw    (cond violated? :invariant-violated ok? :ok :else :rejected)
              error-kw     (when-not ok? (:error result))
              event-tags   (engine/classify-event protocol event result-kw error-kw)
              final-world  (if violated? world-t world-next)
              [proj ph]    (engine/compute-projection protocol final-world)]
          {:ok?    (and ok? (not violated?))
           :world  final-world
           :trace-entry
           {:seq             (:seq event)
            :time            event-time
            :agent           (:agent event)
            :action          (:action event)
            :result          result-kw
            :error           error-kw
            :extra           (:extra result)
            :detail          (:detail result)
            :event-tags      event-tags            :invariants-ok?  (if ok? (and (:ok? inv-single) (:ok? inv-trans)) true)
            :violations      all-violations
            :trace-metadata  (engine/classify-transition protocol (:action event) result-kw)
            :world           (engine/world-snapshot protocol final-world)
            :projection      proj
            :projection-hash ph}
           :halted? violated?})))))

;; ---------------------------------------------------------------------------
;; Public API (Generic)
;; ---------------------------------------------------------------------------

(require '[clojure.tools.logging :as log])

(defn replay-with-protocol
  "Replay a scenario map using a specific DisputeProtocol implementation."
  [protocol scenario]
  (let [validation (validate-scenario scenario)]
    (if-not (:ok validation)
      {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed 0 :trace [] :metrics (zero-metrics) :halt-reason (:error validation)}
      (let [agents   (:agents scenario)
            p-params (get scenario :protocol-params {})
            context  (engine/build-execution-context protocol agents p-params)
            agent-index (:agent-index context)
            world0  (engine/init-world protocol scenario)
            events  (sort-by :seq (:events scenario))
            scenario-id (:scenario-id scenario)]
        (log/info :scenario/start {:id scenario-id})
        (loop [world world0 events events trace [] metrics (zero-metrics) id-alias-map {}]
          (if (empty? events)
            (let [open (when-not (:allow-open-disputes? scenario)
                         (seq (engine/open-disputes protocol world)))]
              (if open
                {:outcome :fail :scenario-id scenario-id :events-processed (count trace) :halt-reason :open-disputes-at-end :detail {:open-disputes (vec open)} :trace trace :metrics metrics :agents agents}
                (do
                  (log/info :scenario/end {:id scenario-id :outcome :pass})
                  {:outcome :pass :scenario-id scenario-id :events-processed (count trace) :trace trace :metrics metrics :agents agents})))
            (let [raw-event  (first events)
                  alias-res  (engine/resolve-id-alias protocol raw-event id-alias-map)]
              (if-not (:ok alias-res)
                {:outcome :invalid :scenario-id scenario-id :events-processed (count trace) :halted-at-seq (:seq raw-event) :halt-reason :unresolved-alias :detail (dissoc alias-res :ok) :trace trace :metrics metrics}
                (let [event    (:event alias-res)
                      step     (process-step protocol context world event)
                      entry    (:trace-entry step)
                      new-trace   (conj trace entry)
                      new-metrics (accum-metrics metrics event entry agent-index world)
                      created     (when (and (= :ok (:result entry)) (:save-id-as raw-event))
                                    (engine/created-id protocol (:action event) (:extra entry)))
                      new-alias-map (if created
                                      (assoc id-alias-map (:save-id-as raw-event) created)
                                      id-alias-map)]
                  
                  ;; Telemetry hook
                  (tap> {:scenario-id scenario-id :seq (:seq event) :world world :entry entry})
                  (log/debug :scenario/step {:id scenario-id :seq (:seq event) :action (:action event)})

                  (if (:halted? step)
                    (do
                      (log/error :scenario/halt {:id scenario-id :seq (:seq event) :reason :invariant-violation})
                      {:outcome :fail :scenario-id scenario-id :events-processed (count new-trace) :halted-at-seq (:seq event) :halt-reason :invariant-violation :trace new-trace :metrics new-metrics})
                    (recur (:world step) (rest events) new-trace new-metrics new-alias-map)))))))))))

(defn replay-scenario
  "Standard SEW entry point: replays using SEWProtocol.
   Callers that need a different protocol should use replay-with-protocol directly."
  [scenario]
  (replay-with-protocol @(requiring-resolve 'resolver-sim.protocols.sew/protocol) scenario))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (json/write-str result :key-fn kw->json-key :value-fn kw-val->str))
