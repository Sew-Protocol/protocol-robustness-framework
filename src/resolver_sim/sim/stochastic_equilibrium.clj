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

   Mechanism-proxy checks (see evaluate-mechanism-proxies) use the basis
   :multi-epoch-population-proxy, which is weaker than :single-trace-terminal-proxy
   (the basis used by scenario/equilibrium for single-trace replay) but provides
   convergent evidence across many epochs that mechanism properties hold at the
   population level.

   ## Claim-strength correspondence with scenario/equilibrium

   | scenario/equilibrium basis         | stochastic-equilibrium basis          |
   |------------------------------------|---------------------------------------|
   | :single-trace-terminal-proxy       | :single-simulation-evidence           |
   | :single-trace-metric-proxy         | :multi-epoch-population-proxy         |
   | :multi-trace-required              | (fulfilled by multi-epoch evidence)   |
   | :multi-epoch-required              | :single-simulation-evidence           |

   The mechanism-proxy evaluators mirror the mechanism-property vocabulary from
   scenario/equilibrium.clj so the two evaluators can be cross-referenced.

   ## Layering

   sim/* may import sim/* per project rules. This namespace imports nothing
    outside sim/. All inputs are plain Clojure maps (no DB, no I/O).")

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
        init-comp  (:initial-composition result)
        h-init     (:honest-count init-comp)
        m-init     (:malice-count init-comp)]
    (cond
      (nil? h-final)
      (inconclusive :honest-survival-rate "final resolver counts missing from aggregated-stats")

      (or (nil? h-init) (nil? m-init))
      (inconclusive :honest-survival-rate "initial-composition missing honest/malice counts")

      (or (zero? h-init) (zero? m-init))
      (inconclusive :honest-survival-rate "initial-composition has zero honest or malice cohort")

      :else
      (let [h-survival (/ (double h-final) (double h-init))
            m-survival (/ (double m-final) (double m-init))
            margin (- h-survival m-survival)
            healthy? (> margin 0.0)]
        (if healthy?
          (pass :honest-survival-rate
                {:honest-final h-final :malice-final m-final
                 :honest-initial h-init :malice-initial m-init
                 :honest-survival-rate h-survival :malice-survival-rate m-survival
                 :survival-margin margin}
                (format "honest survival=%.1f%% > malice survival=%.1f%%"
                        (* 100 h-survival) (* 100 m-survival)))
          (fail :honest-survival-rate
                {:honest-final h-final :malice-final m-final
                 :honest-initial h-init :malice-initial m-init
                 :honest-survival-rate h-survival :malice-survival-rate m-survival
                 :survival-margin margin}
                (format "honest survival=%.1f%% ≤ malice survival=%.1f%%"
                        (* 100 h-survival) (* 100 m-survival))))))))

(defn evaluate-strategy-adaptation-compatibility
  "Claim: adaptation targets are compatible with scenario strategy space.

   If any epoch reports :resolver.strategy/blocked with
   :target-outside-strategy-space, load-adaptation evidence is marked
   inconclusive at scenario level."
  [result]
  (let [epoch-results (:epoch-results result)
        policy (or (some-> epoch-results first (get-in [:defection :adaptation/resolved-config :blocked-target-policy]))
                   :inconclusive)
        blocked (->> epoch-results
                     (mapcat (fn [ep] (get-in ep [:defection :diagnostics] [])))
                     (filter #(= :target-outside-strategy-space (:reason %)))
                     vec)]
    (if (seq blocked)
      (case policy
        :fail
        (fail :strategy-adaptation-compatibility
              {:blocked-events (count blocked)
               :blocked-target-policy policy}
              (format "load-optimal target outside strategy space in %d event(s); policy=%s"
                      (count blocked) (name policy)))
        :warn
        (pass :strategy-adaptation-compatibility
              {:blocked-events (count blocked)
               :blocked-target-policy policy}
              (format "load-optimal target outside strategy space in %d event(s); policy=%s"
                      (count blocked) (name policy)))
        {:claim-id :strategy-adaptation-compatibility
         :status   :inconclusive
         :basis    :single-simulation-evidence
         :reason   (format "load-optimal target outside strategy space in %d event(s); policy=%s"
                           (count blocked) (name policy))
         :evidence {:blocked-events (count blocked)
                    :blocked-target-policy policy}})
      (pass :strategy-adaptation-compatibility
            {:blocked-events 0
             :blocked-target-policy policy}
            "no strategy-space mismatch detected for adaptation targets"))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private evaluators
  [evaluate-strategy-adaptation-compatibility
   evaluate-malice-net-profit-negative
   evaluate-honest-dominates
   evaluate-slashing-deters
   evaluate-participation-stable
   evaluate-honest-survival-rate])

;; ---------------------------------------------------------------------------
;; Mechanism-property proxy evaluators
;;
;; These mirror the mechanism-property vocabulary from scenario/equilibrium.clj
;; but operate on multi-epoch aggregate statistics rather than single-trace
;; terminal projections. The :basis is :multi-epoch-population-proxy throughout.
;;
;; Properties evaluated:
;;   :budget-balance          — net value flow sums near zero (no leakage)
;;   :incentive-compatibility — honest strategy yields better outcomes than malicious
;;   :individual-rationality  — honest resolvers earn positive cumulative profit
;;   :collusion-resistance    — malicious share does not grow relative to initial
;; ---------------------------------------------------------------------------

(defn- mech-pass [property evidence detail]
  {:property property
   :status   :pass
   :basis    :multi-epoch-population-proxy
   :evidence evidence
   :detail   detail})

(defn- mech-fail [property evidence detail]
  {:property property
   :status   :fail
   :basis    :multi-epoch-population-proxy
   :evidence evidence
   :detail   detail})

(defn- mech-inconclusive [property reason]
  {:property property
   :status   :inconclusive
   :basis    :multi-epoch-population-proxy
   :reason   reason})

(defn- evaluate-mech-budget-balance
  "Proxy for :budget-balance.

   In a closed system all value that leaves honest resolvers as losses should
   appear as protocol fees or slash revenue. We check the directional signal:
   honest-cumulative-profit > 0 AND (honest + malice net sum is not strongly
   positive, implying no runaway money creation).

   This is a directional check, not a full token-level reconciliation (that
   requires per-trace projections from scenario/projection)."
  [result]
  (let [stats  (:aggregated-stats result)
        h-prof (:honest-cumulative-profit stats)
        m-prof (:malice-cumulative-profit stats)]
    (if (or (nil? h-prof) (nil? m-prof))
      (mech-inconclusive :budget-balance "cumulative profit data missing from aggregated-stats")
      (let [net-sum    (+ h-prof m-prof)
            ;; A strongly positive net sum (> 5% of honest profit) suggests value leakage
            leaking?   (and (pos? h-prof) (> net-sum (* 0.05 (Math/abs h-prof))))
            no-data?   (and (zero? h-prof) (zero? m-prof))]
        (cond
          no-data?
          (mech-inconclusive :budget-balance "all profits are zero; no value flowed")

          leaking?
          (mech-fail :budget-balance
                     {:net-sum net-sum :honest h-prof :malice m-prof}
                     (format "net value sum=%.0f suggests outflow imbalance (honest=%.0f malice=%.0f)"
                             net-sum h-prof m-prof))

          :else
          (mech-pass :budget-balance
                     {:net-sum net-sum :honest h-prof :malice m-prof}
                     (format "net value sum=%.0f within expected range (honest=%.0f malice=%.0f)"
                             net-sum h-prof m-prof)))))))

(defn- evaluate-mech-incentive-compatibility
  "Proxy for :incentive-compatibility.

   Incentive compatibility requires that honest play is at least as good as
   deviation. Population proxy: honest resolvers earn higher cumulative profit
   AND higher win rate than malicious resolvers over the full simulation."
  [result]
  (let [stats  (:aggregated-stats result)
        h-prof (:honest-cumulative-profit stats)
        m-prof (:malice-cumulative-profit stats)
        h-wr   (:honest-avg-win-rate stats)
        m-wr   (:malice-avg-win-rate stats)]
    (if (some nil? [h-prof m-prof h-wr m-wr])
      (mech-inconclusive :incentive-compatibility "profit or win-rate data missing")
      (let [profit-ok? (>= h-prof m-prof)
            winrate-ok? (>= h-wr m-wr)]
        (cond
          (and profit-ok? winrate-ok?)
          (mech-pass :incentive-compatibility
                     {:honest-profit h-prof :malice-profit m-prof :honest-wr h-wr :malice-wr m-wr}
                     (format "honest profit=%.0f≥malice=%.0f and win-rate=%.1f%%≥%.1f%%"
                             h-prof m-prof (* 100 h-wr) (* 100 m-wr)))

          profit-ok?
          (mech-fail :incentive-compatibility
                     {:honest-profit h-prof :malice-profit m-prof :honest-wr h-wr :malice-wr m-wr}
                     (format "honest win-rate=%.1f%% < malice=%.1f%% — deviation rewarded in win rate"
                             (* 100 h-wr) (* 100 m-wr)))

          :else
          (mech-fail :incentive-compatibility
                     {:honest-profit h-prof :malice-profit m-prof :honest-wr h-wr :malice-wr m-wr}
                     (format "honest profit=%.0f < malice=%.0f — deviation is profitable"
                             h-prof m-prof)))))))

(defn- evaluate-mech-individual-rationality
  "Proxy for :individual-rationality.

   Individual rationality requires that no required honest participant ends up
   with a negative payoff. Population proxy: honest-cumulative-profit > 0."
  [result]
  (let [stats  (:aggregated-stats result)
        h-prof (:honest-cumulative-profit stats)]
    (if (nil? h-prof)
      (mech-inconclusive :individual-rationality "honest-cumulative-profit missing")
      (if (pos? h-prof)
        (mech-pass :individual-rationality
                   {:honest-cumulative-profit h-prof}
                   (format "honest cumulative profit=%.0f > 0: participation individually rational" h-prof))
        (mech-fail :individual-rationality
                   {:honest-cumulative-profit h-prof}
                   (format "honest cumulative profit=%.0f ≤ 0: honest participation not individually rational" h-prof))))))

(defn- evaluate-mech-collusion-resistance
  "Proxy for :collusion-resistance.

   Collusion resistance requires that a coalition of malicious resolvers cannot
   profitably deviate. Population proxy: malicious resolver share does not grow
   — i.e., malice-final-count / initial-malice-approx ≤ 1.0 (no net growth).

   Uses aggregated stats and assumes ~35% initial malice share (the default
   strategy mix: 25% malicious + 10% collusive)."
  [result]
  (let [stats        (:aggregated-stats result)
        m-final      (:malice-final-count stats)
        init-comp    (:initial-composition result)
        m-initial    (:malice-count init-comp)]
    (if (some nil? [m-final m-initial])
      (mech-inconclusive :collusion-resistance "malice final count or initial-composition.malice-count missing")
      (let [growth-ratio (/ (double m-final) (max 1 (double m-initial)))
            grew?        (> growth-ratio 1.10)]  ; >10% growth = coalition expanded
        (if grew?
          (mech-fail :collusion-resistance
                     {:malice-final m-final :initial-malice-count m-initial :growth-ratio growth-ratio}
                     (format "malice pool grew ×%.2f from explicit initial cohort: collusion may be attracting new actors"
                             growth-ratio))
          (mech-pass :collusion-resistance
                     {:malice-final m-final :initial-malice-count m-initial :growth-ratio growth-ratio}
                     (format "malice pool ×%.2f of initial (≤1.1): no coalition growth detected"
                             growth-ratio)))))))

(def ^:private mechanism-proxy-evaluators
  [evaluate-mech-budget-balance
   evaluate-mech-incentive-compatibility
   evaluate-mech-individual-rationality
   evaluate-mech-collusion-resistance])

(defn evaluate-mechanism-proxies
  "Evaluate mechanism-property proxies against a multi-epoch result map.

   These are population-level analogues of the mechanism properties checked by
   scenario/equilibrium.clj on single traces. The :basis is
   :multi-epoch-population-proxy throughout — weaker than :single-trace-terminal-proxy
   but provides convergent multi-epoch evidence for the same claims.

   Properties checked: :budget-balance, :incentive-compatibility,
                       :individual-rationality, :collusion-resistance.

   Returns:
     {:mechanism-proxy-results  {property-kw → result-map}
      :mechanism-proxy-status   :pass | :fail | :inconclusive}"
  [multi-epoch-result]
  (let [results  (into {} (map (fn [f]
                                 (let [r (f multi-epoch-result)]
                                   [(:property r) r]))
                               mechanism-proxy-evaluators))
        statuses (map :status (vals results))
        overall  (cond
                   (some #(= :fail %) statuses)         :fail
                   (every? #(= :pass %) statuses)       :pass
                   :else                                 :inconclusive)]
    {:mechanism-proxy-results results
     :mechanism-proxy-status  overall}))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private evaluators
  [evaluate-strategy-adaptation-compatibility
   evaluate-malice-net-profit-negative
   evaluate-honest-dominates
   evaluate-slashing-deters
   evaluate-participation-stable
   evaluate-honest-survival-rate])

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-stochastic-equilibrium
  "Evaluate all stochastic equilibrium claims and mechanism-property proxies
   against a multi-epoch result map.

   The result map is the return value of resolver-sim.sim.multi-epoch/run-multi-epoch.

   Returns:
     {:claim-results           [{:claim-id :status :basis :evidence :detail} ...]
      :mechanism-proxy-results {property-kw → result-map}
      :mechanism-proxy-status  :pass | :fail | :inconclusive
      :overall-status          :pass | :fail | :inconclusive
      :pass-count              int
      :fail-count              int
      :inconclusive-count      int
      :summary                 string}"
  [multi-epoch-result]
  (let [claim-results    (mapv #(% multi-epoch-result) evaluators)
        mech-proxies     (evaluate-mechanism-proxies multi-epoch-result)
        pass-count       (count (filter #(= :pass (:status %)) claim-results))
        fail-count       (count (filter #(= :fail (:status %)) claim-results))
        inc-count        (count (filter #(= :inconclusive (:status %)) claim-results))
        overall          (cond
                           (pos? fail-count)    :fail
                           (pos? inc-count)     :inconclusive
                           :else                :pass)
        summary          (format "%d/%d claims pass (%d fail, %d inconclusive)"
                                 pass-count (count claim-results) fail-count inc-count)]
    (merge
     {:claim-results       claim-results
      :overall-status      overall
      :pass-count          pass-count
      :fail-count          fail-count
      :inconclusive-count  inc-count
      :summary             summary}
     mech-proxies)))

(defn print-equilibrium-report
  "Print a human-readable summary of a stochastic equilibrium report.
   Takes the return value of evaluate-stochastic-equilibrium."
  [report]
  (println "\n── Stochastic Equilibrium Claims ─────────────────────────────────────────")
  (doseq [r (:claim-results report)]
    (let [icon (case (:status r) :pass "✅" :fail "❌" "⚠️")]
      (println (format "  %s %-40s %s" icon (name (:claim-id r)) (:detail r "")))))
  (println (format "\n  Overall: %s  (%s)" (name (:overall-status report)) (:summary report)))
  (when-let [proxies (:mechanism-proxy-results report)]
    (println "\n── Mechanism-Property Proxies (multi-epoch population) ────────────────────")
    (doseq [[_prop r] (sort-by key proxies)]
      (let [icon (case (:status r) :pass "✅" :fail "❌" "⚠️")]
        (println (format "  %s %-40s %s" icon (name (:property r)) (:detail r (:reason r ""))))))
    (println (format "\n  Mechanism proxies: %s" (name (:mechanism-proxy-status report)))))
  (println "───────────────────────────────────────────────────────────────────────────"))
