(ns resolver-sim.research.sew.adversarial.ema-convergence
  "Phase AG: EMA Quality Signal Convergence (BM-05).

   Tests whether the exponential moving average (EMA) quality score used
   for resolver workload routing converges to an accurate signal within the
   documented 30-dispute window, and whether the cold-start initial score
   choice affects assignment access for new resolvers.

   Hypothesis A (convergence):
     With alpha=0.10 (emaAlphaBps=1000), the EMA score of a resolver with
     known accuracy p-correct converges to within 5% MAE of its steady-state
     value after ≤ 30 disputes for all p-correct in [0.60, 0.99].

   Hypothesis B (cold-start):
     A resolver initialised at score=0.5 (neutral) vs score=1.0 (production).
     Production (init=1.0) provides immediate access and the 'benefit of the doubt',
     whereas 0.5 is the minimum threshold.

   Parameters swept:
     :alpha-bps      — [500 1000 2000 3000]  (0.05, 0.10, 0.20, 0.30)
     :p-correct      — [0.60 0.70 0.80 0.85 0.90 0.99]
     :initial-score  — [0.5 1.0]             (neutral vs production)
     :n-disputes     — 50 (enough to observe convergence)
     :n-sims         — 2000 (Monte Carlo runs per parameter set)

   Protocol constants (from DRMStorageBase.sol):
     emaAlphaBps            = 1000   (10%)
     minEmaScoreThreshold   = 500000 / 1e6 = 0.50  (normalised)"
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine :as engine]))

;; ---------------------------------------------------------------------------
;; Protocol constants
;; ---------------------------------------------------------------------------

(def DEFAULT_ALPHA_BPS      1000)     ; emaAlphaBps = 1000 → α = 0.10 (DRMStorageBase.sol)
(def MIN_SCORE_THRESHOLD    0.50)     ; minEmaScoreThreshold = 500000 / 1e6
(def CONVERGENCE_EPSILON    0.05)     ; 5% MAE target
(def CONVERGENCE_N_TARGET   30)       ; disputes before convergence expected

;; ---------------------------------------------------------------------------
;; EMA update rule (mirrors DRM state machine)
;; ---------------------------------------------------------------------------

(defn alpha [alpha-bps] (/ alpha-bps 10000.0))

(defn ema-update
  "Apply one EMA update given a binary outcome signal (1=correct, 0=reversed)."
  [score a signal]
  (+ (* (- 1.0 a) score) (* a signal)))

(defn ema-steady-state
  "EMA steady-state = p-correct (binary signal expectation)."
  [p-correct]
  p-correct)

;; ---------------------------------------------------------------------------
;; Single resolver simulation
;; ---------------------------------------------------------------------------

(defn simulate-resolver
  "Simulate n-disputes for one resolver with known p-correct.
   Returns the EMA score at each dispute step as a vector."
  [p-correct alpha-bps initial-score n-disputes d-rng]
  (let [a (alpha alpha-bps)]
    (loop [step 0 score initial-score history []]
      (if (= step n-disputes)
        history
        (let [signal    (if (< (rng/next-double d-rng) p-correct) 1.0 0.0)
              new-score (ema-update score a signal)]
          (recur (inc step) new-score (conj history new-score)))))))

;; ---------------------------------------------------------------------------
;; BM-05a: Convergence trial
;; ---------------------------------------------------------------------------

(defn run-convergence-trial
  "Run n-sims simulations and compute MAE of EMA at each dispute step.
   Accepts a param map as produced by build-convergence-grid."
  [{:keys [p-correct alpha-bps initial-score n-disputes n-sims seed]
    :or   {n-disputes 50 n-sims 2000}}]
  (let [d-rng        (rng/make-rng seed)
        steady       (ema-steady-state p-correct)
        trajectories (doall (repeatedly n-sims
                              #(simulate-resolver p-correct alpha-bps
                                                  initial-score n-disputes d-rng)))
        mae-at-step  (mapv (fn [step]
                             (let [errs (map #(Math/abs (- (nth % step) steady)) trajectories)]
                               (/ (reduce + errs) n-sims)))
                           (range n-disputes))
        mae-at-30    (nth mae-at-step (min (dec n-disputes) (dec CONVERGENCE_N_TARGET)))]
    {:p-correct     p-correct
     :alpha-bps     alpha-bps
     :initial-score initial-score
     :steady-state  steady
     :mae-at-30     mae-at-30
     :mae-at-50     (last mae-at-step)
     :converged-at  (first (keep-indexed #(when (< %2 CONVERGENCE_EPSILON) %1) mae-at-step))
     :pass?         (< mae-at-30 CONVERGENCE_EPSILON)}))

;; ---------------------------------------------------------------------------
;; BM-05b: Cold-start trial
;; ---------------------------------------------------------------------------

(defn cold-start-gap
  "Disputes until EMA score first reaches MIN_SCORE_THRESHOLD. nil = never."
  [p-correct alpha-bps initial-score n-disputes d-rng]
  (first (keep-indexed #(when (>= %2 MIN_SCORE_THRESHOLD) %1)
                       (simulate-resolver p-correct alpha-bps initial-score n-disputes d-rng))))

(defn run-cold-start-trial
  "Compare init=0.5 vs init=1.0 for one p-correct value.
   Accepts a param map as produced by build-cold-start-grid."
  [{:keys [p-correct n-sims seed] :or {n-sims 2000}}]
  (let [d-rng      (rng/make-rng seed)
        n-disputes 100
        gaps-half  (doall (repeatedly n-sims #(cold-start-gap p-correct DEFAULT_ALPHA_BPS 0.5 n-disputes d-rng)))
        gaps-prod  (doall (repeatedly n-sims #(cold-start-gap p-correct DEFAULT_ALPHA_BPS 1.0 n-disputes d-rng)))
        median-gap (fn [coll]
                     (let [f (remove nil? coll)]
                       (if (empty? f)
                         n-disputes
                         (nth (sort f) (int (/ (count f) 2))))))]
    {:p-correct       p-correct
     :median-gap-half (median-gap gaps-half)
     :median-gap-prod (median-gap gaps-prod)
     :pct-locked-half (double (/ (count (filter nil? gaps-half)) n-sims))
     :pct-locked-prod (double (/ (count (filter nil? gaps-prod)) n-sims))
     :gap-reduction   (- (median-gap gaps-half) (median-gap gaps-prod))}))

;; ---------------------------------------------------------------------------
;; Parameter grids
;; ---------------------------------------------------------------------------

(defn- build-convergence-grid
  [{:keys [n-sims n-disputes base-seed] :or {n-sims 2000 n-disputes 50 base-seed 42}}]
  (for [a-bps [500 1000 2000 3000]
        p     [0.60 0.70 0.80 0.85 0.90 0.99]
        init  [0.5 1.0]
        :let  [seed (+ base-seed (* a-bps 7) (int (* p 100)) (int (* init 10)))]]
    {:alpha-bps a-bps :p-correct p
     :initial-score init :n-disputes n-disputes :n-sims n-sims :seed seed}))

(defn- build-cold-start-grid
  [{:keys [n-sims base-seed] :or {n-sims 2000 base-seed 42}}]
  (map (fn [p]
         {:p-correct p :n-sims n-sims
          :seed (+ base-seed (int (* p 100)))})
       [0.60 0.70 0.80 0.85 0.90]))

;; ---------------------------------------------------------------------------
;; Summary
;; ---------------------------------------------------------------------------

(defn- summarize-convergence [results]
  (let [protocol-default (filter #(= DEFAULT_ALPHA_BPS (:alpha-bps %)) results)
        pass-default     (count (filter :pass? protocol-default))
        total-default    (count protocol-default)
        worst            (when (seq protocol-default)
                           (apply max-key :mae-at-30 protocol-default))]
    {:total-scenarios  (count results)
     :protocol-default {:total             total-default
                        :passing           pass-default
                        :hypothesis-holds? (= pass-default total-default)}
     :worst-case       (when worst
                         {:p-correct     (:p-correct worst)
                          :alpha-bps     (:alpha-bps worst)
                          :initial-score (:initial-score worst)
                          :mae-at-30     (:mae-at-30 worst)})}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn run-phase-ag
  "BM-05: EMA quality signal convergence sweep."
  ([] (run-phase-ag {}))
  ([params]
   (proto/print-phase-header
    {:benchmark-id "BM-05"
     :label        "EMA Quality Signal Convergence"
     :hypothesis   (str "MAE < 5% after 30 disputes at α=0.10; "
                        "init=1.0 avoids cold-start lockout")
     :details      [(format "Protocol default: alpha-bps=%d, threshold=%.2f"
                            DEFAULT_ALPHA_BPS MIN_SCORE_THRESHOLD)]})

   (println "   Running convergence sweep...")
   (let [conv-grid    (build-convergence-grid params)
         conv-results (proto/run-parameter-sweep conv-grid run-convergence-trial)
         conv-summary (summarize-convergence conv-results)]

     (println (format "   Protocol-default results (alpha=%d, init ∈ {0.5, 1.0}):"
                      DEFAULT_ALPHA_BPS))
     (println "   p-correct  init   MAE@30   converged-at  result")
     (println "   ──────────────────────────────────────────────────")
     (doseq [r (filter #(= DEFAULT_ALPHA_BPS (:alpha-bps %)) conv-results)]
       (println (format "   %-10.2f %-6.1f %-8.4f %-13s %s"
                        (:p-correct r)
                        (:initial-score r)
                        (:mae-at-30 r)
                        (if (:converged-at r)
                          (str (:converged-at r) " disputes")
                          "never@50")
                        (if (:pass? r) "✅" "❌"))))

     (println "")
     (println "   Running cold-start comparison...")
     (let [cs-grid    (build-cold-start-grid params)
           cs-results (proto/run-parameter-sweep cs-grid run-cold-start-trial)]

       (println "   Assignment gap (disputes until score ≥ 0.50 threshold):")
       (println "   p-correct  gap@init=0.5  gap@init=1.0  reduction  pct-locked@0.5")
       (println "   ──────────────────────────────────────────────────────────────────")
       (doseq [r cs-results]
         (println (format "   %-10.2f %-13d %-13d %-10d %.0f%%"
                          (:p-correct r)
                          (:median-gap-half r)
                          (:median-gap-prod r)
                          (:gap-reduction r)
                          (* 100 (:pct-locked-half r)))))

       (let [pd             (:protocol-default conv-summary)
             cs-any-locked? (some #(> (:pct-locked-half %) 0.10) cs-results)
             cs-prod-clear? (every? #(< (:pct-locked-prod %) 0.01) cs-results)
             passed?        (and (:hypothesis-holds? pd) cs-prod-clear?)]

         (proto/print-phase-footer
          {:benchmark-id  "BM-05"
           :passed?       passed?
           :summary-lines [(format "Convergence (Hypothesis A):")
                           (format "  Protocol-default configs passing: %d / %d"
                                   (:passing pd) (:total pd))
                           (format "  Hypothesis A holds? %s"
                                   (if (:hypothesis-holds? pd)
                                     "✅ YES — EMA converges within 30 disputes"
                                     "❌ NO  — convergence gap; consider higher alpha"))
                           (when (:worst-case conv-summary)
                             (format "  Worst: p=%.2f init=%.1f MAE@30=%.4f"
                                     (get-in conv-summary [:worst-case :p-correct])
                                     (get-in conv-summary [:worst-case :initial-score])
                                     (get-in conv-summary [:worst-case :mae-at-30])))
                           ""
                           (format "Initialization Strategy (Hypothesis B):")
                           (format "  init=0.5 lockout > 10%%: %s"
                                   (if cs-any-locked? "✅ CONFIRMED" "— not observed"))
                           (format "  init=1.0 lockout < 1%%:  %s"
                                   (if cs-prod-clear? "✅ CONFIRMED" "❌ still locked"))
                           (format "  Recommendation: Maintain production default at 1.0")]})

         (proto/make-result
          {:benchmark-id "BM-05"
           :label        "EMA Quality Signal Convergence"
           :hypothesis   (str "MAE < 5% after 30 disputes; init=1.0 avoids cold-start lockout")
           :passed?      passed?
           :results      {:convergence conv-results :cold-start cs-results}
           :summary      {:convergence conv-summary
                          :cold-start  {:any-locked? cs-any-locked?
                                        :prod-clear? cs-prod-clear?}}}))))))
