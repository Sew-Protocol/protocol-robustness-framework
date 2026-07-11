(ns resolver-sim.benchmark.claims
  "Claim evaluators for benchmark packs at Levels 1 and 2.

   Level 1 (mechanical): checks that required artifacts, hashes, evidence
   roots, or result fields exist and are internally consistent.  These are
   structural assertions about the evidence bundle — no domain reasoning.

   Level 2 (invariant-backed): checks that specific named post-hoc invariants
   passed for a scenario result.  This covers Sew protocol claims where a
   semantic property is proxied by invariant results from check-all.

   See benchmarks/DESIGN_CLAIM_VERIFICATION.md for maturity level definitions."
  (:require [resolver-sim.io.resource-path :as rp]
            [resolver-sim.yield.partial-fill :as partial-fill]))

;; ── Claim ref normalization ───────────────────────────────────────────────────
;; Benchmark packs may declare claims as flat keywords or as maps with
;; :claim/id plus optional :claim/role, :claim/rationale, :claim/failure-meaning.
;; normalize-claim-refs converts mixed vectors to uniform map vectors.

(defn normalize-claim-refs
  "Normalize a vector of claim refs: keywords become {:claim/id <keyword>},
   maps are returned as-is. Throws on unexpected types."
  [claims]
  (when (seq claims)
    (mapv (fn [c]
            (cond
              (keyword? c) {:claim/id c}
              (map? c) c
              :else (throw (ex-info "Invalid claim ref" {:claim-ref c}))))
          claims)))

(defn claim-ref->id
  "Extract the claim keyword from a normalized or raw claim ref."
  [claim-ref]
  (or (:claim/id claim-ref)
      (when (keyword? claim-ref) claim-ref)))

;; ── Evaluator registry ────────────────────────────────────────────────────────
;; Each entry: {<claim-kw> {:scope <:scenario|:benchmark>
;;                          :check (fn [ctx]) -> {:holds? bool
;;                                                :violations [<map> ...]}}

(defn- sha-256-hex?
  [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{64}" s))))

;; ── Helpers for Level 2 invariant-backed checks ────────────────────────────────

(defn- check-invariants
  "Check that all named invariants passed in the scenario's invariant results.
   Returns {:holds? bool :violations [map]}.
   Missing invariants are :not-exercised, never an implicit pass: an active
   benchmark must show that its required semantic check actually ran."
  [ctx invariant-ids]
  (let [inv-results (get-in ctx [:scenario/result :invariant-results])
        missing    (remove (fn [id]
                             (some #(= (:id %) id) inv-results))
                           invariant-ids)
        failures   (keep (fn [id]
                           (let [entry (some #(when (= (:id %) id) %) inv-results)]
                             (when (and entry (not= :pass (:result entry)))
                               {:type :invariant-failed
                                :invariant-id id
                                :message (str "invariant " id " failed for claim")})))
                         invariant-ids)]
    (cond
      (seq missing)
      {:outcome :not-exercised
       :violations (mapv (fn [id]
                           {:type :invariant-not-exercised
                            :invariant-id id
                            :message (str "invariant " id " was not produced for claim")})
                         missing)}

      :else
      {:holds? (empty? failures)
       :violations (vec failures)})))

(defn- partial-fill-decisions
  [results]
  (mapcat :partial-fill-decisions results))

(defn- check-partial-fill-closed-forms
  "Evaluate selected closed-form checks across every emitted partial-fill decision.
   A workload with no decision artifact has not exercised this property."
  [results check-ids]
  (let [decisions (vec (partial-fill-decisions results))]
    (if (empty? decisions)
      {:outcome :not-exercised
       :violations [{:type :missing-partial-fill-decision
                     :message "workload produced no partial-fill decision artifact"}]}
      (let [failures (->> decisions
                          (mapcat (fn [decision]
                                    (->> (partial-fill/partial-fill-closed-form-checks decision)
                                         (filter #(and (contains? check-ids (:check/id %))
                                                       (= :fail (:status %))))
                                         (map (fn [check]
                                                {:type :closed-form-failure
                                                 :decision-id (:decision/id decision)
                                                 :check-id (:check/id check)
                                                 :details (:details check)})))))
                          vec)]
        {:holds? (empty? failures)
         :violations failures}))))

(defn- scenario-group-key
  [result]
  (or (:scenario/id result)
      (:simulator/scenario-path result)
      (:file result)))

(defn- duplicate-scenario-groups
  [results]
  (->> results
       (group-by scenario-group-key)
       (remove (fn [[scenario-key grouped-results]]
                 (or (nil? scenario-key)
                     (< (count grouped-results) 2))))
       (into {})))

(defn- scenario-groups
  [results]
  (->> results
       (group-by scenario-group-key)
       (remove (comp nil? key))
       (into {})))

(defn- result-fingerprint
  [result]
  (select-keys result [:outcome :halt-reason :invariant-results]))

(defn- nondeterminism-fingerprint
  [result]
  (select-keys result [:outcome :halt-reason :invariant-results :scenario/evidence-root]))

(defn- consistent-fingerprints?
  [results fingerprint-fn]
  (<= (count (distinct (map fingerprint-fn results))) 1))

(defn- missing-scenario-identity-violations
  [results]
  (->> results
       (keep (fn [result]
               (when-not (scenario-group-key result)
                 {:type :missing-scenario-identity
                  :message "scenario result is missing :scenario/id, :simulator/scenario-path, and :file"})))
       vec))

(defn- positive-int?
  [x]
  (and (int? x) (pos? x)))

(defn- run-pairing-violations
  [grouped-results]
  (let [scenario-id (some :scenario/id grouped-results)
        scenario-path (some :simulator/scenario-path grouped-results)
        file (some :file grouped-results)
        run-counts (keep :benchmark/run-count grouped-results)
        run-indices (keep :benchmark/run-index grouped-results)
        distinct-run-counts (distinct run-counts)
        declared-run-count (first distinct-run-counts)]
    (cond
      (not-every? #(contains? % :benchmark/run-count) grouped-results)
      [{:type :missing-run-count
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result is missing :benchmark/run-count"}]

      (not-every? #(contains? % :benchmark/run-index) grouped-results)
      [{:type :missing-run-index
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result is missing :benchmark/run-index"}]

      (not= 1 (count distinct-run-counts))
      [{:type :inconsistent-run-count
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario results disagree on :benchmark/run-count"}]

      (not (positive-int? declared-run-count))
      [{:type :invalid-run-count
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result has invalid :benchmark/run-count"}]

      (not-every? positive-int? run-indices)
      [{:type :invalid-run-index
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario result has invalid :benchmark/run-index"}]

      (not= declared-run-count (count grouped-results))
      [{:type :insufficient-replay-runs
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message (str "scenario expected " declared-run-count " replay runs, got " (count grouped-results))}]

      (not= declared-run-count (count (distinct run-indices)))
      [{:type :duplicate-run-index
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario replay results contain duplicate :benchmark/run-index values"}]

      (not= (set run-indices) (set (range 1 (inc declared-run-count))))
      [{:type :incomplete-run-pairing
        :scenario-id scenario-id
        :scenario-path scenario-path
        :file file
        :message "scenario replay results do not cover the full declared run index range"}]

      :else
      [])))

(defn- benchmark-consistency-check
  [results {:keys [required-fields fingerprint-fn violation-type mismatch-message require-run-pairing?]}]
  (let [missing-identity (missing-scenario-identity-violations results)
        grouped-results (scenario-groups results)
        missing-fields (->> results
                            (mapcat (fn [result]
                                      (keep (fn [field]
                                              (when (nil? (get result field))
                                                {:type :missing-required-field
                                                 :field field
                                                 :scenario-id (:scenario/id result)
                                                 :scenario-path (:simulator/scenario-path result)
                                                 :file (:file result)
                                                 :message (str "scenario result missing required field " field)}))
                                            required-fields)))
                            vec)
        run-pairing (if require-run-pairing?
                      (->> grouped-results
                           vals
                           (mapcat run-pairing-violations)
                           vec)
                      [])
        mismatches (->> (duplicate-scenario-groups results)
                        (keep (fn [[scenario-key grouped-results]]
                                (when-not (consistent-fingerprints? grouped-results fingerprint-fn)
                                  {:type violation-type
                                   :scenario-id (some :scenario/id grouped-results)
                                   :scenario-path (some :simulator/scenario-path grouped-results)
                                   :file (some :file grouped-results)
                                   :message (mismatch-message scenario-key)})))
                        vec)
        violations (vec (concat missing-identity missing-fields run-pairing mismatches))]
    {:holds? (empty? violations)
     :violations violations}))

(def evaluator-registry
  {:evidence-root-present
   {:scope :scenario
    :check
    (fn [ctx]
      (let [root (get-in ctx [:scenario/result :scenario/evidence-root])]
        {:holds? (boolean root)
         :violations (when-not root
                       [{:type :missing-evidence-root
                         :message "scenario/evidence-root is nil or missing"}])}))}

   :replay-result-present
   {:scope :scenario
    :check
    (fn [ctx]
      (let [outcome (get-in ctx [:scenario/result :outcome])]
        {:holds? (boolean outcome)
         :violations (when-not outcome
                       [{:type :missing-outcome
                         :message "scenario outcome is nil or missing"}])}))}

   :scenario-hash-present
   {:scope :scenario
    :check
    (fn [ctx]
      (let [root (get-in ctx [:scenario/result :scenario/evidence-root])]
        {:holds? (sha-256-hex? root)
         :violations (cond
                       (nil? root) [{:type :missing-evidence-root
                                     :message "scenario/evidence-root is nil"}]
                       (not (string? root)) [{:type :invalid-evidence-root-type
                                              :message (str "expected string, got " (type root))}]
                       (not (re-matches #"[0-9a-f]{64}" root)) [{:type :invalid-evidence-root-format
                                                                 :message (str "expected 64-char hex, got " (count root) " chars")}]
                       :else [])}))}

   :no-invariant-errors
   {:scope :scenario
    :check
    (fn [ctx]
      (let [inv-results (get-in ctx [:scenario/result :invariant-results])
            failures (filter #(= :fail (:result %)) inv-results)]
        {:holds? (empty? failures)
         :violations (mapv (fn [f]
                             {:type :invariant-failure
                              :invariant-id (:id f)
                              :message (str "invariant " (:id f) " failed")})
                           failures)}))}

   :all-scenarios-pass
   {:scope :benchmark
    :check
    (fn [ctx]
      (let [results (:benchmark/results ctx)
            failures (remove #(= :pass (:outcome %)) results)]
        {:holds? (empty? failures)
         :violations (mapv (fn [r]
                             {:type :scenario-not-passed
                              :scenario-id (:scenario/id r)
                              :outcome (:outcome r)
                              :message (str "scenario " (:scenario/id r) " outcome is " (:outcome r))})
                           failures)}))}

   :claim/replay-identical-results
   {:scope :benchmark
    :check (fn [ctx]
             (benchmark-consistency-check
              (:benchmark/results ctx)
              {:required-fields [:outcome]
               :require-run-pairing? true
               :fingerprint-fn result-fingerprint
               :violation-type :replay-results-mismatch
               :mismatch-message (fn [scenario-key]
                                   (str "scenario " scenario-key " produced non-identical replay results"))}))}

   :claim/hash-consistency-across-runs
   {:scope :benchmark
    :check (fn [ctx]
             (benchmark-consistency-check
              (:benchmark/results ctx)
              {:required-fields [:scenario/evidence-root]
               :require-run-pairing? true
               :fingerprint-fn :scenario/evidence-root
               :violation-type :evidence-root-mismatch
               :mismatch-message (fn [scenario-key]
                                   (str "scenario " scenario-key " produced non-identical evidence roots"))}))}

   :claim/no-nondeterminism
   {:scope :benchmark
    :check (fn [ctx]
             (benchmark-consistency-check
              (:benchmark/results ctx)
              {:required-fields [:outcome :scenario/evidence-root]
               :require-run-pairing? true
               :fingerprint-fn nondeterminism-fingerprint
               :violation-type :nondeterministic-replay
               :mismatch-message (fn [scenario-key]
                                   (str "scenario " scenario-key " exhibited nondeterministic replay artifacts"))}))}

   ;; ── Level 2: Sew protocol claims (invariant-backed) ─────────────────────────
   ;; Each maps a Sew semantic claim to one or more post-hoc invariants
   ;; that serve as proxies for the claimed property.

   ;; escrow-dispute-v1 pack
   :claim/no-unauthorized-release
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:conservation-of-funds :released-monotonic]))}

   :claim/funds-conserved
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:conservation-of-funds]))}

   :claim/dispute-liveness
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:dispute-resolution-path :dispute-level-bounded]))}

   :claim/slashing-conservation
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :conservation-of-funds]))}

   :claim/governance-non-interference
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:escrow-state-transition-valid :cancellation-mutex]))}

   ;; dispute-liveness-v1 pack (additional claims)
   :claim/bounded-resolution-time
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:time-non-decreasing :temporal-consistency]))}

   ;; yield-shortfall-v1 pack
   :claim/yield-preserved-during-shortfall
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:yield-exposure :shortfall-fidelity]))}

   :claim/partial-fill-decision-integrity
   {:scope :benchmark
    :check (fn [ctx]
             (check-partial-fill-closed-forms
              (:benchmark/results ctx)
              #{:partial-fill/conservation
                :partial-fill/capacity-bound
                :partial-fill/per-claim-bound
                :partial-fill/per-claim-conservation
                :partial-fill/rounding-residual-bounded
                :partial-fill/claim-key-consistency
                :partial-fill/non-negative-amounts
                :partial-fill/settlement-mode-consistency
                :partial-fill/settlement-mode-valid
                :partial-fill/mode-valid
                :partial-fill/deferred-haircut-overlap
                :partial-fill/evidence-self-consistency
                :partial-fill/unrealized-bucket-valid
                :partial-fill/decision-artifact-format
                :partial-fill/pro-rata-cross-product
                :partial-fill/principal-first-priority
                :partial-fill/waterfall-priority}))}

   :claim/cap-adherence
   {:scope :benchmark
    :check (fn [ctx]
             (check-partial-fill-closed-forms
              (:benchmark/results ctx)
              #{:partial-fill/capacity-bound
                :partial-fill/per-claim-bound}))}

   :claim/no-leakage-beyond-shortfall
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:shortfall-fidelity :conservation-of-funds]))}

   ;; resolver-slashing-v1 pack (additional claims)
   :claim/waterfall-coverage-correct
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:senior-coverage-not-exceeded]))}

   :claim/no-over-slashing
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :bond-slash-bounded]))}

   :claim/appeal-bond-adequacy
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:appeal-bond-conserved :challenge-bond-proportional]))}

   ;; escrow-dispute-v1 pack (solvency)
   :claim/solvency-status
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:solvency :conservation-of-funds]))}

   ;; reversal-slashing-v1 pack
   :claim/reversal-reviewer-due-process
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :resolver/balances-conserved]))}

   :claim/reversal-slash-conservation
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :conservation-of-funds]))}

   :claim/vindication-stability
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :resolver/balances-conserved]))}

   :claim/challenge-bounty-correctness
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :resolver/balances-conserved]))}

   :claim/governance-force-reversal-authorized
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:slash-distribution-consistent :resolver/balances-conserved]))}})

(def ^:private scoring-rule-paths
  {:scoring/robustness-dimensions-v0 "resource:benchmarks/scoring/robustness-dimensions-v0.edn"
   :scoring/binary-claims-v1 "resource:benchmarks/scoring/binary-claims-v1.edn"
   :scoring/severity-weighted-robustness-v1 "resource:benchmarks/scoring/severity-weighted-robustness-v1.edn"
   :scoring/severity-weighted-v1 "resource:benchmarks/scoring/severity-weighted-robustness-v1.edn"
   :scoring/shortfall-allocation-v0 "resource:benchmarks/scoring/shortfall-allocation-v0.edn"})

(defn- load-scoring
  [scoring-id]
  (when-let [path (get scoring-rule-paths scoring-id)]
    (try (rp/edn-read path)
         (catch Exception _ nil))))

(defn- severity-claims
  [scoring]
  (->> (:severity/claims scoring)
       (apply merge {})
       (map (fn [[claim-id severity-entry]]
              [claim-id (:severity severity-entry)]))
       (into {})))

(defn- manifest-severity-index
  [manifest]
  (let [scoring-severities (severity-claims (load-scoring (:benchmark/scoring-rule manifest)))
        declared-severities (into {}
                                  (keep (fn [claim-ref]
                                          (let [normalized (if (keyword? claim-ref)
                                                             {:claim/id claim-ref}
                                                             claim-ref)]
                                            (when-let [severity (:claim/severity normalized)]
                                              [(:claim/id normalized) severity]))))
                                  (:benchmark/claims manifest))]
    (merge scoring-severities declared-severities)))

(defn- claim-severity
  [severity-index claim-ref]
  (or (:claim/severity claim-ref)
      (get severity-index (:claim/id claim-ref))
      :low))

(defn evaluator-resolver
  "Look up a claim evaluator by claim keyword.
   Returns {:scope <kw> :check <fn>} or nil."
  [claim-id]
  (get evaluator-registry claim-id))

;; ── Evaluation ────────────────────────────────────────────────────────────────

(defn evaluate-claim
  "Evaluate a single claim against the given context.
   context depends on scope — for :scenario claims it includes
   :scenario/result, for :benchmark claims it includes :benchmark/results.
   Returns {:claim/id <kw> :claim/outcome <kw> :claim/evidence [<coll>] :claim/severity <kw>}."
  ([claim-id context]
   (evaluate-claim claim-id context :low))
  ([claim-id context severity]
   (let [scenario-result (:scenario/result context)
         scenario-fields (select-keys scenario-result
                                      [:scenario/id :simulator/scenario-path :file])]
     (if-let [{:keys [scope check]} (evaluator-resolver claim-id)]
       (let [{:keys [holds? violations outcome]} (check context)]
         (merge {:claim/id claim-id
                 :claim/outcome (or outcome (if holds? :pass :fail))
                 :claim/severity severity
                 :claim/evidence (mapv :type violations)}
                (when (= scope :scenario)
                  {:claim/scope :scenario
                   :scenario/id (:scenario/id scenario-fields)
                   :scenario/file (:file scenario-fields)
                   :simulator/scenario-path (:simulator/scenario-path scenario-fields)})))
       (merge {:claim/id claim-id
               :claim/outcome :not-implemented
               :claim/severity severity
               :claim/evidence []
               :claim/error (str "No evaluator registered for " claim-id)}
              (when scenario-result
                {:claim/scope :scenario
                 :scenario/id (:scenario/id scenario-fields)
                 :scenario/file (:file scenario-fields)
                 :simulator/scenario-path (:simulator/scenario-path scenario-fields)}))))))

(defn evaluate-manifest-claims
  "Evaluate all claims declared in a benchmark manifest against scenario results.
   Dispatches per-claim by scope: :scenario claims are evaluated once per result,
   :benchmark claims are evaluated once against the full result set.
   Returns a flat vector of claim result maps."
  [manifest results]
  (let [claim-refs (normalize-claim-refs (:benchmark/claims manifest))
        severity-index (manifest-severity-index manifest)]
    (when (seq claim-refs)
      (vec
       (mapcat (fn [claim-ref]
                 (let [claim-id (:claim/id claim-ref)
                       severity (claim-severity severity-index claim-ref)]
                   (if-let [{:keys [scope]} (evaluator-resolver claim-id)]
                     (case scope
                       :scenario
                       (mapv (fn [result]
                               (evaluate-claim claim-id
                                               {:scenario/result result
                                                :scenario/world (:world result)
                                                :scenario/metrics (:metrics result)}
                                               severity))
                             results)

                       :benchmark
                       [(evaluate-claim claim-id
                                        {:benchmark/results results
                                         :benchmark/manifest manifest}
                                        severity)]

                       [(evaluate-claim claim-id {:error (str "Unknown scope: " scope)} severity)])
                     [(evaluate-claim claim-id {:error (str "Unknown claim: " claim-id)} severity)])))
               claim-refs)))))
