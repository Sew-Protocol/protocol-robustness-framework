(ns resolver-sim.benchmark.claims
  "Level 1 mechanical claim evaluators for benchmark packs.

   Each evaluator checks that required artifacts, hashes, evidence roots,
   or result fields exist and are internally consistent. No domain-specific
   reasoning — these are structural assertions about the evidence bundle.

   See benchmarks/DESIGN_CLAIM_VERIFICATION.md for maturity level definitions."
  (:require [clojure.string :as str]))

;; ── Evaluator registry ────────────────────────────────────────────────────────
;; Each entry: {<claim-kw> {:scope <:scenario|:benchmark>
;;                          :check (fn [ctx]) -> {:holds? bool
;;                                                :violations [<map> ...]}}

(defn- sha-256-hex?
  [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{64}" s))))

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
                           failures)}))}})

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
  [claim-id context]
  (if-let [{:keys [scope check]} (evaluator-resolver claim-id)]
    (let [{:keys [holds? violations]} (check context)]
      {:claim/id claim-id
       :claim/outcome (if holds? :pass :fail)
       :claim/severity :low
       :claim/evidence (mapv :type violations)})
    {:claim/id claim-id
     :claim/outcome :inconclusive
     :claim/severity :low
     :claim/evidence []
     :claim/error (str "No evaluator registered for " claim-id)}))

(defn evaluate-manifest-claims
  "Evaluate all claims declared in a benchmark manifest against scenario results.
   Dispatches per-claim by scope: :scenario claims are evaluated once per result,
   :benchmark claims are evaluated once against the full result set.
   Returns a flat vector of claim result maps."
  [manifest results]
  (let [claim-ids (:benchmark/claims manifest)]
    (when (seq claim-ids)
      (mapcat (fn [claim-id]
                (if-let [{:keys [scope]} (evaluator-resolver claim-id)]
                  (case scope
                    :scenario
                    (mapv (fn [result]
                            (evaluate-claim claim-id
                              {:scenario/result result
                               :scenario/world (:world result)
                               :scenario/metrics (:metrics result)}))
                          results)

                    :benchmark
                    [(evaluate-claim claim-id
                       {:benchmark/results results
                        :benchmark/manifest manifest})]

                    [(evaluate-claim claim-id {:error (str "Unknown scope: " scope)})])
                  [(evaluate-claim claim-id {:error (str "Unknown claim: " claim-id)})]))
              claim-ids))))
