(ns resolver-sim.scenario.runner
  "Pure deterministic scenario run and pass/fail judgement.

   Returns data only — no printing, files, or exit codes."
  (:require [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.scenario.summary :as summary]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.theory :as theory]))

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

   `opts` may include :require-theory? — when true, a scenario with :theory
   must have an evaluated theory check in :checks (see build-entry-result)."
  [{:keys [outcome expected-fail? checks replay-result scenario]} opts]
  (let [outcome-ok? (if expected-fail?
                      (= :fail outcome)
                      (= :pass outcome))
        expected-outcomes (or (:expected-outcomes checks)
                              (:expected-outcomes replay-result))
        expectations     (:expectations checks)
        theory-check     (:theory checks)
        checks-ok?       (and (or (nil? expected-outcomes) (:ok? expected-outcomes true))
                              (or (nil? expectations) (:ok? expectations true))
                              (or (nil? theory-check) (:ok? theory-check true)))
        theory-required? (and (:require-theory? opts)
                              (:theory scenario)
                              (nil? theory-check))]
    (and outcome-ok? checks-ok? (not theory-required?))))

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

(defn build-entry-result
  "Build one collection entry from a replay result.

   Required: :name :replay-result
   Optional: :scenario (for theory/require checks), :source, type metadata keys.

   Second argument `opts` forwards theory evaluation flags.

   Does not re-evaluate expectations when replay already attached them."
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
                         scenario (assoc :scenario scenario))]
     (assoc entry :pass? (scenario-pass? entry opts)))))

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
