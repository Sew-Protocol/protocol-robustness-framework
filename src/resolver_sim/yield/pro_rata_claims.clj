(ns resolver-sim.yield.pro-rata-claims
  "Claim evaluators for pro-rata allocation correctness properties.
   Each evaluator returns {:holds? bool :violations [...]}."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [clojure.set :as set]))

;; ── Helpers ────────────────────────────────────────────────────────────────────

(defn- result->allocations
  "Normalize a result to a seq of allocation maps with :allocated :unmet keys."
  [result]
  (or (:allocations result) []))

(defn- allocations-set
  "Multi-set of allocations ignoring order (for permutation comparison)."
  [allocations]
  (into #{} (map #(dissoc % :idx :order)) allocations))

(defn- direct-projection-shadow
  [input]
  (let [direct-result (sew-economics/calculate-sew-slash-allocation input)
        projection-artifact (sew-economics/build-sew-slash-projection-artifact input)
        projection-result (sew-economics/calculate-sew-slash-allocation-from-projection projection-artifact)
        projection-artifact-again (sew-economics/build-sew-slash-projection-artifact input)]
    {:input input
     :direct-result direct-result
     :projection-artifact projection-artifact
     :projection-artifact-again projection-artifact-again
     :projection-result projection-result}))

(defn- shadow-equivalence-violations
  [{:keys [direct-result projection-result projection-artifact projection-artifact-again]}]
  (cond-> []
    (not= (:projection-hash projection-artifact)
          (:projection-hash projection-artifact-again))
    (conj {:type :non-deterministic-projection-hash
           :first (:projection-hash projection-artifact)
           :second (:projection-hash projection-artifact-again)})

    (not= direct-result projection-result)
    (conj {:type :allocation-mismatch
           :direct (:allocations direct-result)
           :projection (:allocations projection-result)})))

(defn- non-negative-violations
  [result]
  (vec (mapcat (fn [[i alloc]]
                 (keep (fn [k]
                         (when (and (some? (k alloc))
                                    (neg? (long (k alloc))))
                           {:idx i :key k :value (k alloc)}))
                       [:paid :unmet :owed :basis-amount :share :allocated :weight :cap]))
               (map-indexed vector (result->allocations result)))))

(defn- allocation-complete-violations
  [input result]
  (let [liable-parties (set (map :id (:liable-parties input [])))
        allocated-ids (set (map :id (result->allocations result)))
        missing (set/difference liable-parties allocated-ids)
        extra (set/difference allocated-ids liable-parties)]
    (cond-> []
      (seq missing) (conj {:type :missing-participants :ids (vec missing)})
      (seq extra) (conj {:type :extra-participants :ids (vec extra)}))))

(defn- conservation-violations
  [result]
  (let [violations (cond-> []
                     (and (seq (:allocations result))
                          (every? (fn [a]
                                    (let [owed (long (or (:owed a) 0))
                                          paid (long (or (:paid a) 0))
                                          unmet (long (or (:unmet a) 0))]
                                      (not= owed (+ paid unmet))))
                                  (:allocations result)))
                     (conj {:type :per-allocation-owed-mismatch}))

        total-requested (long (or (:total-requested result)
                                  (:slash-obligation result)
                                  0))
        total-allocated (long (or (:total-allocated result)
                                  (:recovered-total result)
                                  0))
        total-unmet (long (or (:total-unmet result)
                              (:unmet-total result)
                              0))
        remainder (long (or (:remainder result) 0))
        sum-checked (+ total-allocated total-unmet remainder)]
    (cond-> violations
      (not= total-requested sum-checked)
      (conj {:type :aggregate-conservation
             :total-requested total-requested
             :total-allocated total-allocated
             :total-unmet total-unmet
             :remainder remainder
             :difference (- total-requested sum-checked)}))))

(defn- rounding-bounded-violations
  [input result]
  (let [total-basis (reduce + 0 (map #(max 0 (long (or ((:basis input :slashable-stake) %) 0)))
                                     (:liable-parties input [])))
        amount (long (or (:slash-amount input)
                         (:slash-obligation input)
                         (:total-requested result)
                         0))]
    (if (zero? total-basis)
      []
      (vec (keep (fn [alloc]
                   (let [basis (long (or (:basis-amount alloc)
                                         (:weight alloc)
                                         0))
                         share (/ basis total-basis)
                         ideal (* share amount)
                         paid (long (or (:paid alloc)
                                        (:allocated alloc)
                                        0))
                         diff (- paid ideal)]
                     (when (< 1 (Math/abs (double diff)))
                       {:type :rounding-exceeded
                        :id (:id alloc)
                        :basis basis
                        :share share
                        :ideal ideal
                        :paid paid
                        :deviation diff})))
                 (result->allocations result))))))

(defn- ordering-independent-violations
  [input shadow]
  (let [shuffled (update input :liable-parties (fn [parties]
                                                 (vec (shuffle (vec (or parties []))))))
        shuffled-shadow (direct-projection-shadow shuffled)
        original-direct (:direct-result shadow)
        original-projection (:projection-result shadow)
        shuffled-direct (:direct-result shuffled-shadow)
        shuffled-projection (:projection-result shuffled-shadow)
        direct-original-set (allocations-set (:allocations original-direct))
        direct-shuffled-set (allocations-set (:allocations shuffled-direct))
        projection-original-set (allocations-set (:allocations original-projection))
        projection-shuffled-set (allocations-set (:allocations shuffled-projection))]
    (cond-> []
      (not= direct-original-set direct-shuffled-set)
      (conj {:type :ordering-dependent
             :path :direct
             :original-allocations (:allocations original-direct)
             :shuffled-allocations (:allocations shuffled-direct)
             :original-set (vec direct-original-set)
             :shuffled-set (vec direct-shuffled-set)})

      (not= projection-original-set projection-shuffled-set)
      (conj {:type :ordering-dependent
             :path :projection
             :original-allocations (:allocations original-projection)
             :shuffled-allocations (:allocations shuffled-projection)
             :original-set (vec projection-original-set)
             :shuffled-set (vec projection-shuffled-set)})

      (not= original-direct original-projection)
      (conj {:type :shadow-allocation-mismatch
             :direct (:allocations original-direct)
             :projection (:allocations original-projection)}))))

;; ── Claim Evaluators ──────────────────────────────────────────────────────────

(defn check-projection-deterministic
  "Same input through direct and projection paths produces equivalent allocations.
   Compares calculate-sew-slash-allocation (direct) vs
   build-sew-slash-projection-artifact + calculate-sew-slash-allocation-from-projection."
  [input]
  (try
    (let [shadow (direct-projection-shadow input)
          violations (shadow-equivalence-violations shadow)]
      (if (empty? violations)
        {:holds? true}
        {:holds? false :violations violations}))
    (catch Exception e
      {:holds? false
       :violations [{:type :exception
                     :message (.getMessage e)
                     :class (.getName (class e))}]})))

(defn check-projection-canonical-safe
  "Projection artifact contains only canonical hash-safe values."
  [input]
  (try
    (let [shadow (direct-projection-shadow input)
          artifact (:projection-artifact shadow)
          canonical-violations (try
                                 (hc/validate-canonical-value! artifact)
                                 []
                                 (catch Exception e
                                   [{:type :non-canonical-value
                                     :message (.getMessage e)
                                     :class (.getName (class e))}]))
          violations (into (shadow-equivalence-violations shadow)
                           canonical-violations)]
      (if (empty? violations)
        {:holds? true}
        {:holds? false :violations violations}))
    (catch Exception e
      {:holds? false
       :violations [{:type :non-canonical-value
                     :message (.getMessage e)
                     :class (.getName (class e))}]})))

(defn check-allocation-complete
  "Every eligible participant has a corresponding allocation row."
  [input]
  (let [shadow (direct-projection-shadow input)
        result (:direct-result shadow)
        violations (into (shadow-equivalence-violations shadow)
                         (allocation-complete-violations input result))]
    (if (empty? violations)
      {:holds? true}
      {:holds? false :violations violations})))

(defn check-non-negative
  "Allocation, unmet, weight, and cap values are never negative."
  [input]
  (let [shadow (direct-projection-shadow input)
        result (:direct-result shadow)
        violations (into (shadow-equivalence-violations shadow)
                         (non-negative-violations result))]
    (if (empty? violations)
      {:holds? true}
      {:holds? false :violations violations})))

(defn check-conservation
  "Requested amount equals allocated plus unmet plus remainder.
   For per-allocation: owed = paid + unmet.
   For aggregate: total-requested = total-allocated + total-unmet + remainder."
  [input]
  (let [shadow (direct-projection-shadow input)
        result (:direct-result shadow)
        violations (into (shadow-equivalence-violations shadow)
                         (conservation-violations result))]
    (if (empty? violations)
      {:holds? true}
      {:holds? false :violations violations})))

(defn check-rounding-bounded
  "No allocation deviates from its ideal share by more than 1 unit
   (quota rule: floor <= paid <= ceil)."
  [input]
  (let [shadow (direct-projection-shadow input)
        result (:direct-result shadow)
        violations (into (shadow-equivalence-violations shadow)
                         (rounding-bounded-violations input result))]
    (if (empty? violations)
      {:holds? true}
      {:holds? false :violations violations})))

(defn check-ordering-independent
  "Allocation result is invariant under permutation of input items
   (multi-set equality of allocations)."
  [input]
  (try
    (let [shadow (direct-projection-shadow input)
          violations (into (shadow-equivalence-violations shadow)
                           (ordering-independent-violations input shadow))]
      (if (empty? violations)
        {:holds? true}
        {:holds? false :violations violations}))
    (catch Exception e
      {:holds? false
       :violations [{:type :exception
                     :message (.getMessage e)
                     :class (.getName (class e))}]})))

;; ── Registry ───────────────────────────────────────────────────────────────────

(def ^:private check-fns
  {:projection-deterministic
   {:evaluator check-projection-deterministic
    :inputs [:sew-slash-input]}
   :projection-canonical-safe
   {:evaluator check-projection-canonical-safe
    :inputs [:sew-slash-input]}
   :allocation-complete
   {:evaluator check-allocation-complete
    :inputs [:sew-slash-input]}
   :non-negative
   {:evaluator check-non-negative
    :inputs [:sew-slash-input]}
   :conservation
   {:evaluator check-conservation
    :inputs [:sew-slash-input]}
   :rounding-bounded
   {:evaluator check-rounding-bounded
    :inputs [:sew-slash-input]}
   :ordering-independent
   {:evaluator check-ordering-independent
    :inputs [:sew-slash-input]}})

(defn registered-claim-ids
  []
  (vec (keys check-fns)))

(defn evaluate-claim
  "Run a single claim evaluator.
   Input map should contain :sew-slash-input.
   Returns {:holds? bool :violations [...]}."
  [claim-id {:keys [sew-slash-input]}]
  (if-let [entry (get check-fns claim-id)]
    ((:evaluator entry) sew-slash-input)
    (throw (ex-info "Unknown pro-rata claim" {:claim-id claim-id
                                              :known (registered-claim-ids)}))))

(defn evaluate-all
  "Run all registered claim evaluators.
   Returns {claim-id {:holds? bool :violations [...]}}."
  [ctx]
  (into {}
        (for [id (registered-claim-ids)]
          [id (evaluate-claim id ctx)])))
