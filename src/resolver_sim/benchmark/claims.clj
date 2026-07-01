(ns resolver-sim.benchmark.claims
  "Claim evaluators for benchmark packs at Levels 1 and 2.

   Level 1 (mechanical): checks that required artifacts, hashes, evidence
   roots, or result fields exist and are internally consistent.  These are
   structural assertions about the evidence bundle — no domain reasoning.

   Level 2 (invariant-backed): checks that specific named post-hoc invariants
   passed for a scenario result.  This covers Sew protocol claims where a
   semantic property is proxied by invariant results from check-all.

   See benchmarks/DESIGN_CLAIM_VERIFICATION.md for maturity level definitions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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
   Invariant IDs not found in results are treated as passing (matching
   post-hoc inference behavior for transition invariants not re-checked)."
  [ctx invariant-ids]
  (let [inv-results (get-in ctx [:scenario/result :invariant-results])
        failures   (keep (fn [id]
                           (let [entry (some #(when (= (:id %) id) %) inv-results)]
                             (when (and entry (not= :pass (:result entry)))
                               {:type :invariant-failed
                                :invariant-id id
                                :message (str "invariant " id " failed for claim")})))
                         invariant-ids)]
    {:holds?    (empty? failures)
     :violations (vec failures)}))

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

   :claim/partial-fill-fairness
   {:scope :scenario
    :check (fn [ctx]
             (check-invariants ctx [:shortfall-fidelity]))}

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
  {:scoring/robustness-dimensions-v0 "benchmarks/scoring/robustness-dimensions-v0.edn"
   :scoring/binary-claims-v1 "benchmarks/scoring/binary-claims-v1.edn"
   :scoring/severity-weighted-robustness-v1 "benchmarks/scoring/severity-weighted-robustness-v1.edn"
   :scoring/severity-weighted-v1 "benchmarks/scoring/severity-weighted-robustness-v1.edn"
   :scoring/shortfall-allocation-v0 "benchmarks/scoring/shortfall-allocation-v0.edn"})

(defn- load-scoring
  [scoring-id]
  (when-let [path (get scoring-rule-paths scoring-id)]
    (let [f (io/file path)]
      (when (.exists f)
        (edn/read-string (slurp f))))))

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
       (let [{:keys [holds? violations]} (check context)]
         (merge {:claim/id claim-id
                 :claim/outcome (if holds? :pass :fail)
                 :claim/severity severity
                 :claim/evidence (mapv :type violations)}
                (when (= scope :scenario)
                  {:claim/scope :scenario
                   :scenario/id (:scenario/id scenario-fields)
                   :scenario/file (:file scenario-fields)
                   :simulator/scenario-path (:simulator/scenario-path scenario-fields)})))
       (merge {:claim/id claim-id
               :claim/outcome :inconclusive
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
