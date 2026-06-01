(ns resolver-sim.scenario.theory
  "Theory evaluator for schema-profile-driven scenarios.

   Determines whether a theoretical claim is falsified by replay metrics.
   This namespace is pure — no I/O, no DB, no side effects.

   ## Falsification semantics

   `falsifies-if` declares observable conditions that, if satisfied by the replay
   trace, contradict the scenario's theory claim. A satisfied condition means the
   theory is *falsified*, not validated.

   - Any falsification condition true → `:falsified`
   - All false and evidence complete → `:not-falsified`
   - Required metric evidence missing → `:inconclusive` (policy-dependent; strict never → `:falsified`)
   - Empty `falsifies-if` → `:not-falsified` when only mechanism/equilibrium apply

   Legacy vectors of flat metric predicates use OR semantics. Structured predicates
   preserve explicit logical meaning.

   Equilibrium validators are trace-consistency checks, not full game-theoretic proofs.

   ## Options

   See `resolver-sim.scenario.theory-eval/theory-eval-profiles` and
   `evaluate-theory` optional third argument.

   ## Full Schema Documentation

   See: docs/CDRS-v1.1-THEORY-SCHEMA.md"
  (:require [clojure.string :as str]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.scenario.equilibrium :as equilibrium]
            [resolver-sim.scenario.theory-eval :as theory-eval]
            [resolver-sim.scenario.theory-result :as theory-result]))

;; ---------------------------------------------------------------------------
;; Shared value helpers (also used by scenario.expectations)
;; ---------------------------------------------------------------------------

(defn normalize-val
  "Normalize a value to a string for non-numeric equality comparisons."
  [v]
  (cond
    (keyword? v) (name v)
    :else (str v)))

(defn to-kw
  "Coerce a value to a keyword, stripping any spurious leading colon."
  [x]
  (let [s  (if (keyword? x) (name x) (str x))
        s' (if (.startsWith s ":") (subs s 1) s)]
    (keyword s')))

(defn metric-key
  "Keyword for metrics map lookup; preserves namespaced keys like :coalition/net-profit."
  [m]
  (cond
    (keyword? m) m
    (string? m)  (if (.contains ^String m "/")
                   (let [[ns n] (str/split m "/" 2)]
                     (keyword ns n))
                   (keyword m))
    :else (to-kw m)))

(defn- try-number
  "Coerce value to a number (Long parse). Returns nil if not possible."
  [v]
  (cond
    (number? v) v
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    :else nil))

;; ---------------------------------------------------------------------------
;; Metric operator evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-metric-op
  "Evaluate a metric operation with robust numeric comparison.
   Uses == for numeric types (handles Long/Integer/Double equivalence).
   Falls back to normalized string equality for non-numeric values."
  [op actual target]
  (let [op-kw      (to-kw op)
        num-actual (try-number actual)
        num-target (try-number target)]
    (case op-kw
      :=    (if (and num-actual num-target)
              (== num-actual num-target)
              (= (normalize-val actual) (normalize-val target)))
      :>    (if (and num-actual num-target) (> num-actual num-target) false)
      :<    (if (and num-actual num-target) (< num-actual num-target) false)
      :>=   (if (and num-actual num-target) (>= num-actual num-target) false)
      :<=   (if (and num-actual num-target) (<= num-actual num-target) false)
      :not= (not (if (and num-actual num-target)
                   (== num-actual num-target)
                   (= (normalize-val actual) (normalize-val target))))
      false)))

;; ---------------------------------------------------------------------------
;; Predicate evaluation
;; ---------------------------------------------------------------------------

(defn- extract-metrics-at-entry [entry]
  (:metrics entry))

(defn- empty-logical-result [operator]
  {:holds? false
   :reason :empty-logical-operator
   :operator operator
   :children []})

(defn- debug-log! [opts msg data]
  (when (and (:debug-theory? opts) (:theory-diagnostics-atom opts))
    (swap! (:theory-diagnostics-atom opts) conj (assoc data :message msg))))

(defn- eval-predicate
  "Recursively evaluate a predicate map against the trace, metrics, and world state."
  [protocol world trace metrics predicate opts]
  (cond
    (:and predicate)
    (let [children (:and predicate)]
      (if (empty? children)
        (empty-logical-result :and)
        (let [results (mapv #(eval-predicate protocol world trace metrics % opts) children)]
          (debug-log! opts "evaluated :and" {:operator :and :child-count (count results)})
          {:holds? (every? :holds? results) :operator :and :children results})))

    (:or predicate)
    (let [children (:or predicate)]
      (if (empty? children)
        (empty-logical-result :or)
        (let [results (mapv #(eval-predicate protocol world trace metrics % opts) children)]
          (debug-log! opts "evaluated :or" {:operator :or :child-count (count results)})
          {:holds? (some :holds? results) :operator :or :children results})))

    (:not predicate)
    (let [result (eval-predicate protocol world trace metrics (:not predicate) opts)]
      {:holds? (not (:holds? result)) :operator :not :children [result]})

    (:implies predicate)
    (let [if-result (eval-predicate protocol world trace metrics (:if predicate) opts)
          then-result (eval-predicate protocol world trace metrics (:then predicate) opts)]
      {:holds? (or (not (:holds? if-result)) (:holds? then-result))
       :operator :implies
       :children [if-result then-result]})

    (:always predicate)
    (let [results (mapv #(eval-predicate protocol world trace
                                         (extract-metrics-at-entry %)
                                         (:always predicate) opts)
                        trace)]
      {:holds? (every? :holds? results) :operator :always :children results})

    (:eventually predicate)
    (let [results (mapv #(eval-predicate protocol world trace
                                         (extract-metrics-at-entry %)
                                         (:eventually predicate) opts)
                        trace)]
      {:holds? (some :holds? results) :operator :eventually :children results})

    (:after predicate)
    (let [{:keys [event predicate]} (:after predicate)
          idx (first (keep-indexed #(when (= (:action %2) event) %1) trace))
          sub-trace (if idx (drop (inc idx) trace) [])]
      (if (empty? sub-trace)
        {:holds? false :reason :event-not-found :event event}
        (let [results (mapv #(eval-predicate protocol world trace
                                             (extract-metrics-at-entry %)
                                             predicate opts)
                            sub-trace)]
          {:holds? (every? :holds? results) :operator :after :children results})))

    (:before predicate)
    (let [{:keys [event predicate]} (:before predicate)
          idx (first (keep-indexed #(when (= (:action %2) event) %1) trace))
          sub-trace (if idx (take idx trace) [])]
      (if (empty? sub-trace)
        {:holds? false :reason :event-not-found :event event}
        (let [results (mapv #(eval-predicate protocol world trace
                                             (extract-metrics-at-entry %)
                                             predicate opts)
                            sub-trace)]
          {:holds? (every? :holds? results) :operator :before :children results})))

    (:state predicate)
    (let [proj-val (proto/project-state protocol world (:query predicate))
          holds?   (evaluate-metric-op (:op predicate) proj-val (:value predicate))]
      {:holds? holds? :kind :state
       :state (:query predicate) :op (:op predicate) :value (:value predicate) :actual proj-val
       :truth-status (if holds? :true :false)})

    :else
    (let [metric-kw (metric-key (:metric predicate))
          actual    (get metrics metric-kw)
          missing?  (nil? actual)
          holds?    (evaluate-metric-op (:op predicate) actual (:value predicate))]
      {:holds? holds? :kind :metric-leaf
       :metric metric-kw
       :op (:op predicate) :value (:value predicate)
       :actual actual :missing-metric? missing?
       :truth-status (if missing? :unknown (if holds? :true :false))})))

(defn- leaf-conditions [conds]
  (filter map? conds))

(defn- all-metrics-missing? [metrics conds]
  (let [leaves (vec (leaf-conditions conds))]
    (and (seq leaves)
         (every? #(nil? (get metrics (metric-key (:metric %)))) leaves))))

(defn- collect-missing-metrics
  "Walk an eval tree and return metric keywords referenced by leaves with nil actual."
  [tree]
  (cond
    (nil? tree) []
    (:missing-metric? tree) [(:metric tree)]
    (:children tree) (vec (distinct (mapcat collect-missing-metrics (:children tree))))
    :else []))

(defn- collect-evaluated-leaves
  [tree]
  (cond
    (nil? tree) []
    (= :metric-leaf (:kind tree))
    [(select-keys tree [:metric :op :value :actual :holds? :missing-metric? :truth-status])]

    (= :state (:kind tree))
    [(select-keys tree [:state :op :value :actual :holds? :kind])]

    (:children tree) (vec (mapcat collect-evaluated-leaves (:children tree)))
    :else []))

(defn normalize-telemetry-evidence
  "Flat, reviewer-oriented evidence rows derived from an eval tree."
  [eval-tree]
  (mapv
   (fn [leaf]
     (if (= :state (:kind leaf))
       {:kind  :state
        :state (:state leaf)
        :actual (:actual leaf)
        :op    (:op leaf)
        :value (:value leaf)
        :threshold (:value leaf)
        :holds? (:holds? leaf)
        :missing? false
        :truth-status (if (:holds? leaf) :true :false)
        :source [:terminal-world :projection (:state leaf)]}
       (let [missing? (boolean (:missing-metric? leaf))]
         {:kind  :metric
          :metric (:metric leaf)
          :actual (:actual leaf)
          :op    (:op leaf)
          :value (:value leaf)
          :threshold (:value leaf)
          :holds? (:holds? leaf)
          :missing? missing?
          :truth-status (or (:truth-status leaf)
                            (if missing? :unknown (if (:holds? leaf) :true :false)))
          :source [:metrics (:metric leaf)]})))
   (collect-evaluated-leaves eval-tree)))

(defn- empty-logical-in-tree? [tree]
  (or (= :empty-logical-operator (:reason tree))
      (some empty-logical-in-tree? (:children tree []))))

(defn- apply-empty-logical-policy [policy tree]
  (when (empty-logical-in-tree? tree)
    (case policy
      :fail {:status :falsified :reason :empty-logical-operator}
      :inconclusive {:status :inconclusive :reason :empty-logical-operator}
      nil)))

(defn- apply-missing-metric-policy
  [{:keys [missing-metric-policy]} metrics conds missing-metrics falsified?]
  (cond
    falsified? nil

    (and (all-metrics-missing? metrics conds)
         (not= :ignore-missing-leaves missing-metric-policy))
    {:status :inconclusive :reason :metrics-missing-in-trace}

    (and (seq missing-metrics)
         (= :any-missing-fail missing-metric-policy))
    {:status :inconclusive :reason :strict-missing-metrics}

    (and (seq missing-metrics)
         (= :any-missing-inconclusive missing-metric-policy))
    {:status :inconclusive :reason :partial-metrics-missing}

    :else nil))

(defn- build-diagnostics
  [eval-res missing-metrics]
  (let [missing (vec (distinct missing-metrics))]
    {:missing-metrics missing
     :evaluated-predicates (collect-evaluated-leaves eval-res)
     :warnings (when (seq missing)
                 (mapv (fn [m] {:kind :missing-metric :metric m}) missing))}))

(defn- include-telemetry-evidence? [opts]
  (boolean (or (:include-telemetry-evidence? opts)
               (:notebook-export? opts)
               (:debug-theory? opts))))

(defn- finalize-metric-result
  "Apply derived diagnostics; optional flat :telemetry-evidence when requested."
  [metric-result opts theory]
  (let [opts'       (theory-eval/resolve-theory-eval-opts opts)
        with-metric (assoc metric-result :metric-status (:status metric-result))
        res         (theory-result/attach-three-way-model with-metric opts' :theory theory)
        eval-tree   (first (:evidence res))]
    (cond-> (dissoc res :telemetry-evidence)
      (and (include-telemetry-evidence? opts') eval-tree)
      (assoc :telemetry-evidence (normalize-telemetry-evidence eval-tree)))))

(defn evaluate-theory
  "Evaluate theory falsification and optional mechanism/equilibrium proxies.

   Canonical metric-track contract (`theory-result/canonical-keys`):
     :status — fixture/audit gate (:falsified | :not-falsified | :inconclusive | :not-evaluated)
     :reason — why that status was assigned
     :falsified? — whether a falsification condition fired
     :evidence — machine-readable eval tree
     :diagnostics — missing metrics, evaluated leaves, warnings, derived interpretation

   Derived fields live under :diagnostics. Legacy top-level copies only when
   :include-legacy-derived-top-levels? is true. Flat :telemetry-evidence only
   when :include-telemetry-evidence? (or :notebook-export? / :debug-theory?) is set.

   Optional third argument `opts` (see `theory-eval/resolve-theory-eval-opts`):
   :theory-eval-profile — :regression (default) | :optimistic | :strict | :public-evidence
   (legacy aliases :exploratory / :authoring → :optimistic)
   :missing-metric-policy — override profile
   :include-legacy-derived-top-levels? — default false (deprecated)
   :include-telemetry-evidence? — default false
   :debug-theory? — append to :theory-diagnostics-atom; also enables :telemetry-evidence"
  ([result theory]
   (evaluate-theory result theory {}))
  ([result theory opts]
   (if (nil? theory)
     (finalize-metric-result
      {:status              :not-evaluated
       :reason              :theory-missing
       :falsified?          false
       :evidence            []
       :diagnostics         {}
       :mechanism-results   {}
       :mechanism-status    :not-checked
       :equilibrium-results {}
       :equilibrium-status  :not-checked}
       opts theory)
     (let [opts'     (theory-eval/resolve-theory-eval-opts opts)
           protocol  (:protocol result)
           world     (:terminal-world result)
           trace     (:trace result [])
           metrics   (:metrics result)
           conds     (:falsifies-if theory [])
           eq-result (when (or (seq (:mechanism-properties theory))
                             (seq (:equilibrium-concept theory)))
                       (try
                         (equilibrium/evaluate-equilibrium theory result)
                         (catch Exception e
                           (case (:validator-error-policy opts' :inconclusive)
                             :throw (throw e)
                             :fail {:mechanism-results {}
                                    :mechanism-status :fail
                                    :equilibrium-results {}
                                    :equilibrium-status :fail
                                    :validator-error {:class (str (type e))
                                                      :message (.getMessage e)}}
                             {:mechanism-results {}
                              :mechanism-status :inconclusive
                              :equilibrium-results {}
                              :equilibrium-status :inconclusive
                              :validator-error {:class (str (type e))
                                                :message (.getMessage e)}}))))
           merge-eq  (fn [base]
                       (merge base
                              (if eq-result
                                (select-keys eq-result [:mechanism-results :mechanism-status
                                                        :equilibrium-results :equilibrium-status
                                                        :evidence-schema-version :equilibrium-claim-tier
                                                        :equilibrium-trust-mode :provenance
                                                        :validator-error])
                                {:mechanism-results   {}
                                 :mechanism-status    :not-checked
                                 :equilibrium-results {}
                                 :equilibrium-status  :not-checked})))]
       (cond
         (empty? conds)
         (finalize-metric-result
          (merge-eq {:status     :not-falsified
                     :reason     :no-metric-falsification-claim
                     :falsified? false
                     :evidence   []
                     :diagnostics {}})
          opts' theory)

         :else
         (let [predicate (if (and (vector? conds) (map? (first conds)))
                            {:or conds}
                            conds)
               eval-res  (eval-predicate protocol world trace metrics predicate opts')
               missing   (collect-missing-metrics eval-res)
               falsified? (boolean (:holds? eval-res))
               diagnostics (build-diagnostics eval-res missing)
               empty-logical (apply-empty-logical-policy (:empty-logical-policy opts') eval-res)
               missing-policy (or empty-logical
                                  (apply-missing-metric-policy opts' metrics conds missing falsified?))
               base (if missing-policy
                      (merge missing-policy
                             {:falsified? (= :falsified (:status missing-policy))
                              :evidence [eval-res]
                              :diagnostics diagnostics})
                      (if falsified?
                        {:status :falsified :reason :falsification-triggered
                         :falsified? true :evidence [eval-res]
                         :diagnostics diagnostics}
                        {:status :not-falsified :reason :predicate-not-satisfied
                         :falsified? false :evidence [eval-res]
                         :diagnostics diagnostics}))]
           (finalize-metric-result (merge-eq base) opts' theory)))))))
