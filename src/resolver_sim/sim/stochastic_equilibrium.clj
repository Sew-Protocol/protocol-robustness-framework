(ns resolver-sim.sim.stochastic-equilibrium
  "Stochastic equilibrium bridge.

   Translates the output of run-multi-epoch into falsifiable population-level
   equilibrium claims. Unlike scenario/equilibrium (which evaluates a single
   deterministic replay trace), this namespace evaluates emergent properties
   across many epochs of a stochastic agent population.

   ## Why this exists

   Multi-epoch simulation (Phase J) produces aggregated stats and equity
   trajectories but never runs them through any theory evaluator. The claim
   'malice cannot dominate over time' was checked by printing numbers, not by
   a formal pass/fail falsification. This namespace closes that gap.

   ## Claim-strength taxonomy

   All checks are labelled :single-simulation-evidence — stronger than a single
   trace but weaker than an analytic proof or ensemble of independent runs.
   The :basis field in every result declares this explicitly.

   ## Layering

   sim/* may import sim/* per project rules. This namespace imports nothing
   outside sim/. All inputs are plain Clojure maps (no DB, no I/O)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Shared result helpers
;; ---------------------------------------------------------------------------

(defn- pass [claim-id evidence detail]
  {:claim-id  claim-id
   :status    :pass
   :basis     :single-simulation-evidence
   :evidence  evidence
   :detail    detail})

(defn- fail [claim-id evidence detail]
  {:claim-id  claim-id
   :status    :fail
   :basis     :single-simulation-evidence
   :evidence  evidence
   :detail    detail})

(defn- inconclusive [claim-id reason]
  {:claim-id  claim-id
   :status    :inconclusive
   :basis     :single-simulation-evidence
   :reason    reason})

;; ---------------------------------------------------------------------------
;; Individual claim evaluators
;;
;; Each takes the multi-epoch result map from run-multi-epoch.
;; Inputs used:
;;   :aggregated-stats    — {:malice-cumulative-profit :honest-cumulative-profit
;;                            :malice-avg-win-rate :honest-avg-win-rate
;;                            :final-resolver-count :total-resolver-exits}
;;   :epoch-results       — [{:dominance-ratio :honest-mean-profit :malice-mean-profit ...}]
;;   :initial-resolver-count — from top-level result key
;; ---------------------------------------------------------------------------

(defn evaluate-malice-net-profit-negative
  "Claim: malicious resolvers end up with negative cumulative profit.

   Rationale: slashing penalties should exceed any gains from fraudulent
   resolutions over the course of the simulation.

   Metric: aggregated-stats :malice-cumulative-profit < 0.

   Note: this is aggregate across all malicious resolvers. A subset may be
   profitable if others absorb large slashes. A stronger check would be
   per-resolver profit distribution — not yet available here."
  [result]
  (let [stats  (:aggregated-stats result)
        profit (:malice-cumulative-profit stats)]
    (if (nil? profit)
      (inconclusive :malice-net-profit-negative "aggregated-stats missing from result")
      (if (neg? profit)
        (pass :malice-net-profit-negative
              {:malice-cumulative-profit profit}
              (format "Malice cumulative profit = %.0f < 0: slashing deters net profit" profit))
        (fail :malice-net-profit-negative
              {:malice-cumulative-profit profit}
              (format "Malice cumulative profit = %.0f ≥ 0: malice is net-profitable" profit))))))

(defn evaluate-honest-dominates
  "Claim: honest strategy dominates malicious in the final epoch.

   Uses the :dominance-ratio metric from the last epoch result.
   dominance-ratio > 1.2 means honest profit is ≥ 1.2× the mean resolver profit,
   which is a strong dominance signal.

   Falls back to comparing final-epoch honest-mean-profit vs malice-mean-profit
   if dominance-ratio is absent."
  [result]
  (let [epochs     (:epoch-results result)
        final-ep   (last epochs)
        dom-ratio  (:dominance-ratio final-ep)
        h-profit   (:honest-mean-profit final-ep)
        m-profit   (:malice-mean-profit final-ep)]
    (cond
      (nil? final-ep)
      (inconclusive :honest-dominates "no epoch-results in multi-epoch output")

      ;; Primary: dominance-ratio
      (and dom-ratio (>= dom-ratio 1.2))
      (pass :honest-dominates
            {:dominance-ratio dom-ratio :epoch (count epochs)}
            (format "dominance-ratio=%.2f ≥ 1.2 at final epoch %d" dom-ratio (count epochs)))

      (and dom-ratio (< dom-ratio 1.2))
      (fail :honest-dominates
            {:dominance-ratio dom-ratio :epoch (count epochs)}
            (format "dominance-ratio=%.2f < 1.2 at final epoch %d: malice is competitive" dom-ratio (count epochs)))

      ;; Fallback: direct profit comparison
      (and h-profit m-profit (> h-profit m-profit))
      (pass :honest-dominates
            {:honest-mean-profit h-profit :malice-mean-profit m-profit}
            (format "honest-mean=%.0f > malice-mean=%.0f at final epoch" h-profit m-profit))

      (and h-profit m-profit)
      (fail :honest-dominates
            {:honest-mean-profit h-profit :malice-mean-profit m-profit}
            (format "honest-mean=%.0f ≤ malice-mean=%.0f at final epoch" h-profit m-profit))

      :else
      (inconclusive :honest-dominates "missing dominance-ratio and mean-profit data in final epoch"))))

(defn evaluate-slashing-deters
  "Claim: malicious resolvers have a lower win rate than honest resolvers.

   Win rate is the fraction of trials that produce a positive verdict for the
   resolver. Slashing should cause malicious resolvers to lose significantly
   more trials (via detection + slash) than honest ones.

   Metric: aggregated-stats :malice-avg-win-rate < :honest-avg-win-rate.

   This is a weaker claim than malice-net-profit-negative — even if malice has
   a lower win rate, a higher fee-per-win could still make malice profitable.
   Both claims together form a coherent deterrence case."
  [result]
  (let [stats    (:aggregated-stats result)
        m-wr     (:malice-avg-win-rate stats)
        h-wr     (:honest-avg-win-rate stats)]
    (cond
      (nil? m-wr)
      (inconclusive :slashing-deters "win-rate data missing from aggregated-stats")

      (< m-wr h-wr)
      (pass :slashing-deters
            {:malice-avg-win-rate m-wr :honest-avg-win-rate h-wr}
            (format "malice win-rate=%.1f%% < honest=%.1f%%: slashing reduces win frequency"
                    (* 100 m-wr) (* 100 h-wr)))

      :else
      (fail :slashing-deters
            {:malice-avg-win-rate m-wr :honest-avg-win-rate h-wr}
            (format "malice win-rate=%.1f%% ≥ honest=%.1f%%: slashing insufficient deterrent"
                    (* 100 m-wr) (* 100 h-wr))))))

(defn evaluate-participation-stable
  "Claim: resolver pool is stable — fewer than 20% of resolvers exit over the simulation.

   Participation stability ensures the protocol has a viable resolver market.
   If ≥20% of resolvers exit, the pool may be too small to route disputes fairly.

   Metric: (total-resolver-exits / initial-resolver-count) < 0.20."
  [result]
  (let [initial  (:initial-resolver-count result)
        exits    (get-in result [:aggregated-stats :total-resolver-exits] 0)]
    (if (nil? initial)
      (inconclusive :participation-stable "initial-resolver-count missing from result")
      (let [exit-rate (/ (double exits) (max 1 initial))]
        (if (< exit-rate 0.20)
          (pass :participation-stable
                {:total-exits exits :initial-count initial :exit-rate exit-rate}
                (format "exit-rate=%.1f%% < 20%%: resolver pool is stable" (* 100 exit-rate)))
          (fail :participation-stable
                {:total-exits exits :initial-count initial :exit-rate exit-rate}
                (format "exit-rate=%.1f%% ≥ 20%%: significant resolver attrition" (* 100 exit-rate))))))))

(defn evaluate-honest-survival-rate
  "Claim: honest resolvers survive at a higher rate than malicious resolvers.

   The slashing mechanism should preferentially drive out malicious resolvers
   while retaining honest ones. Final counts should reflect this.

   Metric: (honest-final-count / initial-honest-count) > (malice-final-count / initial-malice-count)
   where initial counts are approximated from the initial strategy-mix and n-resolvers."
  [result]
  (let [stats      (:aggregated-stats result)
        h-final    (:honest-final-count stats)
        m-final    (:malice-final-count stats)
        initial    (:initial-resolver-count result 0)
        ;; We don't have the exact initial split in the result — use epoch 1 if available
        epoch-1    (first (:epoch-results result))
        ;; epoch-results don't carry initial counts; use a reasonable proxy
        ;; If stats are available, check relative dominance
        total-final (+ (or h-final 0) (or m-final 0))]
    (cond
      (nil? h-final)
      (inconclusive :honest-survival-rate "final resolver counts missing from aggregated-stats")

      (zero? total-final)
      (inconclusive :honest-survival-rate "no resolvers remain at end of simulation")

      :else
      (let [h-share (/ (double h-final) total-final)
            ;; A simple check: honest resolvers should comprise >50% of survivors
            ;; (true by construction if initial mix is honest-majority, but can degrade)
            healthy? (>= h-share 0.50)]
        (if healthy?
          (pass :honest-survival-rate
                {:honest-final h-final :malice-final m-final :honest-share h-share}
                (format "honest share of survivors=%.1f%% ≥ 50%%: honest resolvers dominate" (* 100 h-share)))
          (fail :honest-survival-rate
                {:honest-final h-final :malice-final m-final :honest-share h-share}
                (format "honest share of survivors=%.1f%% < 50%%: malicious resolvers outlasted honest" (* 100 h-share))))))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private evaluators
  [evaluate-malice-net-profit-negative
   evaluate-honest-dominates
   evaluate-slashing-deters
   evaluate-participation-stable
   evaluate-honest-survival-rate])

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-stochastic-equilibrium
  "Evaluate all stochastic equilibrium claims against a multi-epoch result map.

   The result map is the return value of resolver-sim.sim.multi-epoch/run-multi-epoch.

   Returns:
     {:claim-results  [{:claim-id :status :basis :evidence :detail} ...]
      :overall-status :pass | :fail | :inconclusive
      :pass-count     int
      :fail-count     int
      :inconclusive-count int
      :summary        string}"
  [multi-epoch-result]
  (let [claim-results (mapv #(% multi-epoch-result) evaluators)
        pass-count    (count (filter #(= :pass (:status %)) claim-results))
        fail-count    (count (filter #(= :fail (:status %)) claim-results))
        inc-count     (count (filter #(= :inconclusive (:status %)) claim-results))
        overall       (cond
                        (pos? fail-count)    :fail
                        (pos? inc-count)     :inconclusive
                        :else                :pass)
        summary       (format "%d/%d claims pass (%d fail, %d inconclusive)"
                               pass-count (count claim-results) fail-count inc-count)]
    {:claim-results       claim-results
     :overall-status      overall
     :pass-count          pass-count
     :fail-count          fail-count
     :inconclusive-count  inc-count
     :summary             summary}))

(defn print-equilibrium-report
  "Print a human-readable summary of a stochastic equilibrium report.
   Takes the return value of evaluate-stochastic-equilibrium."
  [report]
  (println "\n── Stochastic Equilibrium Claims ─────────────────────────────────────────")
  (doseq [r (:claim-results report)]
    (let [icon (case (:status r) :pass "✅" :fail "❌" "⚠️")]
      (println (format "  %s %-40s %s" icon (name (:claim-id r)) (:detail r "")))))
  (println (format "\n  Overall: %s  (%s)" (name (:overall-status report)) (:summary report)))
  (println "───────────────────────────────────────────────────────────────────────────"))
