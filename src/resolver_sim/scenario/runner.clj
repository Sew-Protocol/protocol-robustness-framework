(ns resolver-sim.scenario.runner
  "Pure deterministic scenario run and pass/fail judgement.

   Returns data only — no printing, files, or exit codes.

   `:pass?` on each entry is the single source of truth for pass/fail.
   Reports read `:pass?` and use `:checks` only to explain failures.

   Use `with-attribute` to attach clarifying semantic annotations to a
   result entry — e.g. resolution outcome, dispute level, module identity.
   Attributes are stored under `:attributes` and surfaced in verbose reports."
  (:require [clojure.string :as str]
            [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.scenario.summary :as summary]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.theory :as theory]))

;; ---------------------------------------------------------------------------
;; Attribute helpers — enrich result entries with clarifying annotations
;; ---------------------------------------------------------------------------

(defn with-attribute
  "Attach clarifying semantic attributes to a result entry.

   `entry` is a result map (from `build-entry-result` / `run-scenario`).
   Remaining args are alternating keyword-value pairs.

   Attributes are stored under `:attributes` and surfaced in verbose/audit reports.
   Unlike `with-attribution` (dynamic binding for execution context), this is a
   pure-data enrichment for post-hoc clarity — e.g. annotating a pass/fail result
   with `:resolution/type :release` or `:dispute/level 0`.

   Returns the entry with an appended or merged `:attributes` map."
  [entry & kvs]
  (assert (even? (count kvs)) "with-attribute requires an even number of key-value pairs")
  (let [attr (apply hash-map kvs)]
    (update entry :attributes #(merge % attr))))

;; ---------------------------------------------------------------------------
;; Pass semantics
;; ---------------------------------------------------------------------------

(defn- theory-check-ok?
  [theory-result scenario {:keys [strict-theory?] :as opts}]
  (if (nil? theory-result)
    true
    (let [purpose               (:purpose scenario)
          require-conclusive?   (boolean (or strict-theory? (:require-theory? opts)))
          eval-opts             {:require-conclusive? require-conclusive?}
          mech-status           (get theory-result :mechanism-status :not-checked)
          eq-status             (get theory-result :equilibrium-status :not-checked)
          mech-results          (vals (get theory-result :mechanism-results {}))
          eq-results            (vals (get theory-result :equilibrium-results {}))]
      (and (ose/theory-result-ok? theory-result purpose eval-opts)
           (ose/domain-results-ok? purpose mech-status mech-results eval-opts)
           (ose/domain-results-ok? purpose eq-status eq-results eval-opts)))))

(defn scenario-pass?
  "Unified pass predicate for a single scenario entry.

   `entry` must include :outcome, :expected-fail?, :checks, and optionally
   :replay-result for expected-outcomes from replay finalize.

   Optional top-level keys (fixture suites):
     :expected-halt-reason — must match :halt-reason when present

   Optional :checks keys:
     :thresholds — {:ok? bool ...}
     :golden     — {:ok? bool ...} (golden verify mode)

   `opts` may include :require-theory? — when true, a scenario with :theory
   must have an evaluated theory check in :checks (see build-entry-result)."
  [{:keys [outcome expected-fail? expected-halt-reason halt-reason checks replay-result scenario]} opts]
  (let [outcome-ok? (if expected-fail?
                      (= :fail outcome)
                      (= :pass outcome))
        halt-ok?    (or (nil? expected-halt-reason)
                        (= expected-halt-reason halt-reason))
        expected-outcomes (or (:expected-outcomes checks)
                              (:expected-outcomes replay-result))
        expectations     (:expectations checks)
        theory-check     (:theory checks)
        thresholds       (:thresholds checks)
        golden           (:golden checks)
        checks-ok?       (and (or (nil? expected-outcomes) (:ok? expected-outcomes true))
                              (or (nil? expectations) (:ok? expectations true))
                              (or (nil? theory-check) (:ok? theory-check true))
                              (or (nil? thresholds) (:ok? thresholds true))
                              (or (nil? golden) (:ok? golden true)))
        theory-required? (and (:require-theory? opts)
                              (:theory scenario)
                              (nil? theory-check))]
    (and outcome-ok? halt-ok? checks-ok? (not theory-required?))))

;; ---------------------------------------------------------------------------
;; Entry construction
;; ---------------------------------------------------------------------------

(defn- expectation-checks
  "Reuse expectation diagnostics from replay when present; do not re-evaluate."
  [replay-result scenario]
  (let [from-replay (:expectations replay-result)]
    (cond
      from-replay
      from-replay

      (:expectations scenario)
      (expectations/evaluate-expectations replay-result (:expectations scenario))

      :else nil)))

(defn- theory-check
  [replay-result scenario opts]
  (when (and (:evaluate-theory? opts) (:theory scenario))
    (let [profile (or (get-in scenario [:theory :theory-eval-profile])
                      (when (true? (get-in scenario [:theory :require-conclusive?])) :strict)
                      :regression)
          theory-opts (merge {:theory-eval-profile profile} opts)]
      (theory/evaluate-theory replay-result (:theory scenario) theory-opts))))

(defn- derive-resolution-attributes
  "Extract resolution attributes from a replay result's world state.
   Scans all escrow-transfers for :resolution metadata and produces
   a flat attribute map. Includes dispute level progression, module,
   and scenario purpose when available."
  [replay-result scenario]
  (let [world    (:world replay-result)
        escrows  (get world :escrow-transfers {})
        ;; Derive global attributes from world state
        mod-attr (when-let [m (get-in world [:params :resolution-module])]
                   {:resolution/module (keyword (str/replace m "0x" ""))})
        lvl-attr (let [levels (get world :dispute-levels {})]
                   (when (seq levels)
                     (let [max-lvl (apply max (vals levels))
                           path   (vec (range 0 (inc max-lvl)))]
                       (merge {:dispute/max-level max-lvl
                               :escalation/path path}
                              (if (= 1 (count levels))
                                {}
                                (into {} (map (fn [[wf lvl]] [(keyword (str "wf-" wf "-level")) lvl]) levels)))))))
        purpose-attr (when-let [p (:purpose scenario)]
                       {:scenario/purpose (keyword p)})]
    (merge mod-attr lvl-attr purpose-attr
           ;; Resolution-specific attributes per escrow
           (let [resolutions (keep (fn [[wf-id et]]
                                     (when-let [r (:resolution et)]
                                       [wf-id r]))
                                   escrows)]
             (when (seq resolutions)
               (if (= 1 (count resolutions))
                 (let [[_ r] (first resolutions)]
                   {:resolution/type (if (:is-release r) :release :refund)
                    :resolved-by (:resolved-by r)})
                  (apply merge (map (fn [[wf-id r]]
                                      {(keyword (str "wf-" wf-id "-resolution"))
                                       (if (:is-release r) :release :refund)
                                       (keyword (str "wf-" wf-id "-resolved-by"))
                                       (:resolved-by r)})
                                     (filter (fn [[_ r]] (map? r)) resolutions)))))))))

(defn build-entry-result
  "Build one collection entry from a replay result.

   Required: :name :replay-result
   Optional: :scenario (for theory/require checks), :source, type metadata keys.

   Second argument `opts` forwards theory evaluation flags.

   Does not re-evaluate expectations when replay already attached them.
   Automatically enriches the entry with resolution attributes derived
   from the replay result's world state."
  ([input] (build-entry-result input {}))
  ([{:keys [name replay-result scenario source expected-fail?]
     :or {expected-fail? (boolean (:expected-fail? scenario false))}}
    opts]
   (let [expectations  (expectation-checks replay-result scenario)
         expected-out  (:expected-outcomes replay-result)
         theory-res    (theory-check replay-result scenario opts)
         checks        (cond-> {}
                         expected-out (assoc :expected-outcomes expected-out)
                         expectations (assoc :expectations expectations)
                         theory-res   (assoc :theory {:ok? (theory-check-ok? theory-res scenario opts)
                                                      :result theory-res}))
         violations    (if expected-fail?
                         0
                         (get-in replay-result [:metrics :invariant-violations] 0))
          entry         (cond-> {:name           name
                                 :scenario-id    (:scenario-id replay-result)
                                 :source         (or source :replay)
                                 :outcome        (:outcome replay-result)
                                 :halt-reason    (:halt-reason replay-result)
                                 :expected-fail? expected-fail?
                                 :steps          (:events-processed replay-result 0)
                                 :reverts        (get-in replay-result [:metrics :reverts] 0)
                                 :violations     violations
                                 :checks         checks
                                 :replay-result  replay-result
                                 :details        [replay-result]}
                          scenario (assoc :scenario scenario))
          pass-entry    (assoc entry :pass? (scenario-pass? entry opts))
          attr          (derive-resolution-attributes replay-result scenario)]
      (if attr
        (apply with-attribute pass-entry (mapcat identity (seq attr)))
        pass-entry))))

(defn runner-opts-for-scenario
  "Default theory evaluation opts for a scenario map (fixture or JSON).

   `:evaluate-theory?` defaults from scenario `:options` / `:flags`, then
   `(boolean (:theory scenario))`, unless overridden in `suite-opts`."
  [scenario & [suite-opts]]
  (let [suite-opts (or suite-opts {})
        from-scenario (get-in scenario [:options :flags :evaluate-theory?])
        minimal?      (or (:minimal (get-in scenario [:options]))
                          (= :minimal (get-in scenario [:options :profile])))
        default-eval? (if (some? from-scenario)
                        (boolean from-scenario)
                        (and (not minimal?) (boolean (:theory scenario))))
        evaluate?  (if (contains? suite-opts :evaluate-theory?)
                     (:evaluate-theory? suite-opts)
                     default-eval?)]
    (merge {:require-theory? false
            :strict-theory? false}
           suite-opts
           {:evaluate-theory? evaluate?})))

(defn finalize-fixture-entry
  "Attach fixture-only metadata and checks to a `build-entry-result` entry.

   Adds legacy keys (:trace-id, :expectations, :theory, :golden-report, …)
   for existing fixture consumers while keeping unified :checks / :pass?."
  [base-entry fixture-meta opts]
  (let [{:keys [expected-outcome expected-halt-reason threshold-validation
                golden-comparison golden-report metrics trace-id
                scenario-author purpose theory-source]} fixture-meta
        expected-fail? (= :fail (or expected-outcome :pass))
        expectations   (get-in base-entry [:checks :expectations])
        theory-res     (get-in base-entry [:checks :theory :result])
        fixture-outcome (when expected-outcome
                          {:ok? (= expected-outcome (:outcome base-entry))
                           :expected expected-outcome
                           :actual (:outcome base-entry)})
        halt-check     (when expected-halt-reason
                          {:ok? (= expected-halt-reason (:halt-reason base-entry))
                           :expected expected-halt-reason
                           :actual (:halt-reason base-entry)})
        entry          (-> base-entry
                           (assoc :expected-outcome expected-outcome
                                  :expected-halt-reason expected-halt-reason
                                  :expected-fail? expected-fail?
                                  :trace-id trace-id
                                  :scenario-id trace-id
                                  :scenario-author scenario-author
                                  :purpose purpose
                                  :theory-source theory-source
                                  :threshold-validation threshold-validation
                                  :golden-comparison golden-comparison
                                  :golden-report golden-report
                                  :metrics metrics
                                  :expectations expectations
                                  :theory theory-res
                                  :name (or (:name base-entry) (str trace-id)))
                           (update :checks assoc
                                   :fixture-outcome fixture-outcome
                                   :halt halt-check
                                   :thresholds threshold-validation
                                   :golden golden-comparison))]
    (assoc entry :pass? (scenario-pass? entry opts))))

(defn- build-paired-entry
  [name scenarios replay-fn opts type-meta]
  (let [sub-results (mapv (fn [s]
                            (let [res (replay-fn s)]
                              (build-entry-result
                               {:name           (str name " / " (:scenario-id s))
                                :replay-result  res
                                :scenario       s
                                :source         :registry
                                :expected-fail? (boolean (:expected-fail? s false))}
                               opts)))
                          scenarios)
        all-ok      (every? :pass? sub-results)
        any-xfail   (some :expected-fail? sub-results)]
    (merge {:name           name
            :pass?          all-ok
            :expected-fail? any-xfail
            :steps          (reduce + (map :steps sub-results))
            :reverts        (reduce + (map :reverts sub-results))
            :violations     (reduce + (map :violations sub-results))
            :details        (mapv :replay-result sub-results)}
           type-meta)))

(defn run-scenario
  "Replay one scenario map. Returns an entry result map (see `build-entry-result`).

   `opts` must include :replay-fn (fn [scenario] → replay result).

   When :normalize? is true, applies `fixtures/normalize-scenario` before replay."
  [scenario {:keys [replay-fn normalize? name source] :as opts}]
  (let [scenario* (if normalize? (normalize/normalize-scenario scenario) scenario)
        result    (replay-fn scenario*)
        display   (or name (:scenario-id scenario*) (str (:scenario-id scenario*)))]
    (build-entry-result {:name           display
                         :replay-result  result
                         :scenario       scenario*
                         :source         (or source :inline)
                         :expected-fail? (boolean (:expected-fail? scenario* false))}
                        opts)))

(defn run-collection
  "Run a collection of scenarios. Returns a summary map from `summary/build-summary`.

   `collection` is a map:
     :entries — vector of
       {:name string :scenario map} |
       [display-name scenario-or-pair]  (registry style)
     :replay-fn — (fn [scenario] → replay result), required
     :type-meta-fn — optional (fn [scenario-id] → metadata map for entry)

   `opts` forwarded to build-entry-result / theory (e.g. :evaluate-theory?)."
  [{:keys [entries replay-fn type-meta-fn]} opts]
  (let [t0      (System/currentTimeMillis)
        results (mapv (fn [entry]
                        (cond
                          (and (map? entry) (:scenario entry))
                          (run-scenario (:scenario entry)
                                        (assoc opts :replay-fn replay-fn
                                               :name (:name entry)
                                               :source (:source entry :inline)))

                          (vector? entry) (let [[name data] entry
                                                type-meta (when type-meta-fn
                                                            (type-meta-fn (:scenario-id (if (map? data) data (first data)))))]
                                            (if (map? data)
                                              (let [res (replay-fn data)]
                                                (merge (build-entry-result
                                                        {:name           name
                                                         :replay-result  res
                                                         :scenario       data
                                                         :source         :registry
                                                         :expected-fail? (boolean (:expected-fail? data false))}
                                                        opts)
                                                       type-meta))
                                              (build-paired-entry name data replay-fn opts type-meta)))

                          :else (throw (ex-info "Invalid collection entry" {:entry entry}))))
                      entries)
        elapsed (- (System/currentTimeMillis) t0)]
    (summary/build-summary results {:elapsed-ms elapsed
                                    :suite-id   (:suite-id opts)})))
