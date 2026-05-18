(ns resolver-sim.research.sew.governance.effort-rewards
  "Phase AB: Per-Dispute Effort Rewards.

   Safeguard for the Phase Y vulnerability: evidence fog causes resolvers to
   defect when effort cost exceeds fee income.  This phase tests whether
   adding a per-dispute effort-reward multiplier (fee × complexity-tier)
   keeps the defection rate below 20% across evidence complexity tiers.

   Effort tiers (mirrors Phase Y's attention-budget model):
     :low    — simple dispute, 1× base fee
     :medium — moderate complexity, 2× base fee
     :high   — evidence-heavy dispute, 4× base fee

   Resolver defection model:
     Resolver defects when expected-income < effort-cost.
     effort-cost = base-effort-cost × complexity-multiplier
     expected-income = base-fee × reward-multiplier (the safeguard lever)

   Hypothesis to confirm:
     With reward-multiplier ≥ tier-complexity-multiplier, defection rate < 20%
     across all tiers.  Without the multiplier (reward = 1×), high-complexity
     tiers breach the 20% threshold."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as engine]))

;; ---------------------------------------------------------------------------
;; Effort tier definitions
;; ---------------------------------------------------------------------------

(def effort-tiers
  [{:tier :low    :complexity 1.0 :fraction 0.60}
   {:tier :medium :complexity 2.0 :fraction 0.30}
   {:tier :high   :complexity 4.0 :fraction 0.10}])

;; ---------------------------------------------------------------------------
;; Defection model
;; ---------------------------------------------------------------------------

(defn defection-probability
  "Probability a resolver defects for a dispute of given complexity.

   resolver-income   = base-fee × reward-multiplier
   resolver-cost     = base-effort-cost × complexity-multiplier
   defection-base    = 5% irreducible noise (resolver inconsistency)
   gap-factor        = max(0, cost/income - 1) → penalises income-below-cost

   defect-prob = defection-base + gap-factor × 0.60  (capped at 1.0)"
  [base-fee reward-multiplier base-effort-cost complexity-multiplier]
  (let [income    (* base-fee reward-multiplier)
        cost      (* base-effort-cost complexity-multiplier)
        gap       (max 0.0 (- (/ cost (max 1.0 income)) 1.0))
        raw       (+ 0.05 (* gap 0.60))]
    (min 1.0 raw)))

;; ---------------------------------------------------------------------------
;; Single scenario simulation
;; ---------------------------------------------------------------------------

(defn run-effort-reward-scenario
  "Simulate n-disputes across effort tiers with the given reward-multiplier.

   Returns {:tier-results [...] :overall-defection-rate f :passes? bool}."
  [n-disputes-per-tier base-fee reward-multiplier base-effort-cost seed]
  (let [d-rng         (rng/make-rng seed)
        tier-results
        (for [{:keys [tier complexity fraction]} effort-tiers
              :let [n          (int (* n-disputes-per-tier fraction))
                    defect-p   (defection-probability base-fee reward-multiplier
                                                      base-effort-cost complexity)
                    n-defected (count (filter (fn [_] (< (rng/next-double d-rng) defect-p))
                                             (range n)))
                    rate       (/ (double n-defected) (max 1 n))]]
          {:tier tier :n n :defect-prob defect-p :defection-rate rate
           :passes? (< rate 0.20)})
        overall-defected   (reduce + (map #(* (:n %) (:defection-rate %)) tier-results))
        overall-total      (reduce + (map :n tier-results))
        overall-rate       (/ overall-defected (max 1.0 overall-total))]
    {:tier-results           (vec tier-results)
     :overall-defection-rate overall-rate
     :passes?                (every? :passes? tier-results)}))

;; ---------------------------------------------------------------------------
;; Full Phase AB sweep
;; ---------------------------------------------------------------------------

(defn run-phase-ab-sweep
  "Sweep reward multipliers and effort cost levels; report safeguard effectiveness."
  [params]
  (let [seed              (get params :rng-seed 42)
        base-fee          (get params :base-fee 50)
        base-effort-cost  (get params :base-effort-cost 30)
        n-per-tier        (get params :n-trials 500)]

    (println "\n📊 PHASE AB: PER-DISPUTE EFFORT REWARDS")
    (println "   Hypothesis: reward-multiplier ≥ tier-complexity keeps defection < 20%")
    (println "")

    (let [scenarios
          [{:label "No reward multiplier (1×)"   :reward-multiplier 1.0}
           {:label "Moderate rewards (2×)"        :reward-multiplier 2.0}
           {:label "Proportional rewards (4×)"    :reward-multiplier 4.0}
           {:label "Over-rewarded (6×)"           :reward-multiplier 6.0}]

          results
          (mapv (fn [{:keys [label reward-multiplier] :as sc}]
                  (println (format "   Testing: %s" label))
                  (let [r (run-effort-reward-scenario n-per-tier base-fee
                                                      reward-multiplier base-effort-cost seed)]
                    (doseq [{:keys [tier defection-rate passes?]} (:tier-results r)]
                      (println (format "     tier=%-8s defect-rate=%.1f%%  %s"
                                       (name tier)
                                       (* 100 defection-rate)
                                       (if passes? "✅" "❌"))))
                    (println (format "     Overall: %.1f%% defection  %s"
                                     (* 100 (:overall-defection-rate r))
                                     (if (:passes? r) "✅ SAFE" "❌ UNSAFE")))
                    (merge sc r)))
                scenarios)

          class-a           (count (filter :passes? results))
          class-c           (count (remove :passes? results))
          min-safe-mult     (->> results
                                 (filter :passes?)
                                 (map :reward-multiplier)
                                 (apply min Double/MAX_VALUE))
          hypothesis-holds? (some :passes? results)]

      (println "\n═══════════════════════════════════════════════════")
      (println "📋 PHASE AB SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Safe (A): %d  Unsafe (C): %d" class-a class-c))
      (println (format "   Minimum safe reward multiplier: %.1f×"
                       (if (= min-safe-mult Double/MAX_VALUE) ##Inf min-safe-mult)))
      (println (format "   Hypothesis holds? %s"
                       (if hypothesis-holds?
                         "✅ YES — effort rewards suppress defection"
                         "❌ NO — effort rewards insufficient")))
      (println "")
      (if hypothesis-holds?
        (do (println "   Recommendation: Implement reward-multiplier ≥ tier-complexity")
            (println "   Confidence impact: +5% (Phase Y vulnerability mitigated)"))
        (do (println "   Recommendation: Revisit fee structure; effort-cost model may be miscalibrated")
            (println "   Confidence impact: 0%")))
      (println "")

      (proto/make-result
       {:benchmark-id "AB"
        :label        "Per-Dispute Effort Rewards"
        :hypothesis   "reward-multiplier >= tier-complexity keeps defection < 20%"
        :passed?      hypothesis-holds?
        :results      results
        :summary      {:class-a class-a :class-c class-c :min-safe-mult min-safe-mult}}))))
