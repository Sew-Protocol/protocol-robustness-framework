(ns resolver-sim.sim.batch
  "Batch runner: aggregate N trials into summary statistics."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.resolver-ring :as ring]
            [resolver-sim.sim.batch-integration :as integration]))

(defn mean [vals]
  (if (empty? vals) 0 (double (/ (reduce + vals) (count vals)))))

(defn variance [vals mean-val]
  (if (<= (count vals) 1)
    0.0
    (double (/ (reduce + (map #(Math/pow (- % mean-val) 2) vals))
               (dec (count vals))))))

(defn std-dev [vals mean-val]
  (Math/sqrt (variance vals mean-val)))

(defn quantile
  "Linear-interpolation quantile. q in [0,1]; sorted-vals must be pre-sorted."
  [sorted-vals q]
  (if (empty? sorted-vals)
    0
    (let [idx (* q (dec (count sorted-vals)))]
      (if (integer? idx)
        (nth sorted-vals (int idx))
        (let [lo (int (Math/floor idx))
              hi (int (Math/ceil idx))
              frac (- idx lo)]
          (+ (* (- 1 frac) (nth sorted-vals lo))
             (* frac (nth sorted-vals hi))))))))

(defn run-batch
  "Run N trials with given parameters and return aggregated stats.
   Phase E1: Add Kleros backstop (L2 detection) metrics.
   Phase G: Add slashing delay support and control baseline support.
   Phase DR2: Add resolver-bond-bps for reputation+slashing model."
  [rng n-trials params]
  (let [base-strategy (or (:force-strategy params) (:strategy params :honest))
        ;; Phase P: Adjust strategy if bribery cost makes attack infeasible
        strategy (integration/adjust-strategy-for-bribery base-strategy params)
        results
        (repeatedly n-trials
          #(dispute/resolve-dispute
            rng (:escrow-size params 10000)
            (:resolver-fee-bps params)
            (:appeal-bond-bps params)
            (:slash-multiplier params)
            strategy
            (:appeal-probability-if-correct params)
            (:appeal-probability-if-wrong params)
            (:slashing-detection-probability params)
            :l2-detection-prob (:l2-detection-prob params 0)
            :slashing-detection-delay-weeks (:slashing-detection-delay-weeks params 0)
            :allow-slashing? (:allow-slashing? params true)
            :resolver-bond-bps (:resolver-bond-bps params 0)
            :fraud-detection-probability (:fraud-detection-probability params 0.0)
            :fraud-slash-bps (:fraud-slash-bps params 0)
            :reversal-detection-probability (:reversal-detection-probability params 0.0)
            :reversal-slash-bps (:reversal-slash-bps params 0)
            :timeout-slash-bps (:timeout-slash-bps params 200)
            :unstaking-delay-days (:unstaking-delay-days params 14)
            :freeze-on-detection? (:freeze-on-detection? params true)
            :freeze-duration-days (:freeze-duration-days params 3)
            :appeal-window-days (:appeal-window-days params 7)
             :fraud-model (:fraud-model params :single-stage-ev)
             :escalation-assumptions (:escalation-assumptions params)
             :escalation-assumption-band (:escalation-assumption-band params :base)
             :p-appeal-wrong (:p-appeal-wrong params)
             :p-l1-reversal (:p-l1-reversal params)
             :p-l2-escalation (:p-l2-escalation params)
             :p-l2-reversal (:p-l2-reversal params)
             :has-kleros? (:has-kleros? params)
            :fraud-success-rate (:fraud-success-rate params 0.0)))
        
        profits-honest (map :profit-honest results)
        profits-malice (map :profit-malice results)
        mean-honest (mean profits-honest)
        mean-malice (mean profits-malice)
        sorted-honest (sort profits-honest)
        sorted-malice (sort profits-malice)
        
        ; Phase E1: L2 detection metrics
        l2-detected-count (count (filter :l2-detected? results))
        
        ; Phase G: Slashing delay metrics
        pending-slashed (count (filter :slashing-pending? results))
        pending-delay-weeks (if (empty? results) 0 
                             (double (mean (map :slashing-delay-weeks (filter :slashing-pending? results)))))
        
        ; Phase H: Freeze and escape metrics
        frozen-count (count (filter :frozen? results))
        escaped-count (count (filter :escaped? results))
        
        ; Phase D: Slashing reason breakdown
        total-slashed (count (filter :slashed? results))
        timeout-slashed (count (filter #(= (:slashing-reason %) :timeout) results))
        reversal-slashed (count (filter #(= (:slashing-reason %) :reversal) results))
        fraud-slashed (count (filter #(= (:slashing-reason %) :fraud) results))
        
        ; Phase B: Escalation metrics
        appeal-count (count (filter :appeal-triggered? results))
        escalation-count (count (filter :escalated? results))]
    
    {:n-trials n-trials
     :strategy (or (:force-strategy params) (:strategy params :honest))
     
     ; Honest profit statistics
     :honest-mean (double mean-honest)
     :honest-std (double (std-dev profits-honest mean-honest))
     :honest-min (apply min profits-honest)
     :honest-max (apply max profits-honest)
     :honest-p25 (quantile sorted-honest 0.25)
     :honest-p50 (quantile sorted-honest 0.50)
     :honest-p75 (quantile sorted-honest 0.75)
     
     ; Malice profit statistics
     :malice-mean (double mean-malice)
     :malice-std (double (std-dev profits-malice mean-malice))
     :malice-min (apply min profits-malice)
     :malice-max (apply max profits-malice)
     :malice-p25 (quantile sorted-malice 0.25)
     :malice-p50 (quantile sorted-malice 0.50)
     :malice-p75 (quantile sorted-malice 0.75)
     
     ; Comparative statistics
     :honest-wins (count (filter #(> % 0) (map - profits-honest profits-malice)))
     :dominance-ratio (if (zero? mean-malice) Double/POSITIVE_INFINITY
                        (double (/ mean-honest mean-malice)))
     
      ; Phase B: Escalation statistics
      :appeal-rate (double (/ appeal-count n-trials))
      :escalation-rate (double (/ escalation-count n-trials))
      
      ; Phase E1: Kleros backstop (L2 detection)
      :l2-detection-rate (double (/ l2-detected-count n-trials))
      
      ; Phase D: Graduated slashing breakdown
      :slash-rate (double (/ total-slashed n-trials))
      :timeout-slash-rate (double (/ timeout-slashed n-trials))
      :reversal-slash-rate (double (/ reversal-slashed n-trials))
      :fraud-slash-rate (double (/ fraud-slashed n-trials))
      
      ; Phase H: Freeze and escape metrics
      :frozen-rate (double (/ frozen-count n-trials))
      :escaped-rate (double (/ escaped-count n-trials))
      
      ; Phase P: Bribery metrics
      :adjusted-strategy strategy
      :bribery-enabled (boolean (and (:bribe-cost-ratio params) 
                                     (:fraud-slash-bps params)
                                     (> (:fraud-slash-bps params) 0)))
      :bribery-cost (when (:bribe-cost-ratio params)
                      (integration/calculate-bribery-cost params))}))

(defn run-batch-with-attribution
  "Run N trials and return both aggregate stats and per-trial results.

   Returns {:aggregate <same map as run-batch>
            :trials    [{per-trial result map} ...]}

   The :trials vector has exactly n-trials entries. Each entry is the raw
   map returned by dispute/resolve-dispute and contains at minimum:
     :profit-honest     numeric
     :profit-malice     numeric
     :slashed?          bool
     :slashing-reason   keyword or nil
     :appeal-triggered? bool
     :escalated?        bool
     :dispute-correct?  bool

   The trial router (sim.trial-router) uses :trials to attribute outcomes to
   individual resolvers. Conservation invariants on :trials:
     (sum :profit-honest) == (* n-trials (:honest-mean aggregate))   ± fp-epsilon
     (sum :profit-malice) == (* n-trials (:malice-mean aggregate))   ± fp-epsilon
     (count (filter :slashed? trials)) == (* n-trials (:slash-rate aggregate))  exactly"
  [rng n-trials params]
  (let [base-strategy (or (:force-strategy params) (:strategy params :honest))
        strategy      (integration/adjust-strategy-for-bribery base-strategy params)
        trials
        (vec (repeatedly n-trials
               #(dispute/resolve-dispute
                  rng (:escrow-size params 10000)
                  (:resolver-fee-bps params)
                  (:appeal-bond-bps params)
                  (:slash-multiplier params)
                  strategy
                  (:appeal-probability-if-correct params)
                  (:appeal-probability-if-wrong params)
                  (:slashing-detection-probability params)
                  :l2-detection-prob (:l2-detection-prob params 0)
                  :slashing-detection-delay-weeks (:slashing-detection-delay-weeks params 0)
                  :allow-slashing? (:allow-slashing? params true)
                  :resolver-bond-bps (:resolver-bond-bps params 0)
                  :fraud-detection-probability (:fraud-detection-probability params 0.0)
                  :fraud-slash-bps (:fraud-slash-bps params 0)
                  :reversal-detection-probability (:reversal-detection-probability params 0.0)
                  :reversal-slash-bps (:reversal-slash-bps params 0)
                  :timeout-slash-bps (:timeout-slash-bps params 200)
                  :unstaking-delay-days (:unstaking-delay-days params 14)
                  :freeze-on-detection? (:freeze-on-detection? params true)
                  :freeze-duration-days (:freeze-duration-days params 3)
                  :appeal-window-days (:appeal-window-days params 7)
                   :fraud-model (:fraud-model params :single-stage-ev)
                   :escalation-assumptions (:escalation-assumptions params)
                   :escalation-assumption-band (:escalation-assumption-band params :base)
                   :p-appeal-wrong (:p-appeal-wrong params)
                   :p-l1-reversal (:p-l1-reversal params)
                   :p-l2-escalation (:p-l2-escalation params)
                   :p-l2-reversal (:p-l2-reversal params)
                   :has-kleros? (:has-kleros? params)
                  :fraud-success-rate (:fraud-success-rate params 0.0))))

        profits-honest (map :profit-honest trials)
        profits-malice (map :profit-malice trials)
        mean-honest    (mean profits-honest)
        mean-malice    (mean profits-malice)
        sorted-honest  (sort profits-honest)
        sorted-malice  (sort profits-malice)

        total-slashed    (count (filter :slashed? trials))
        timeout-slashed  (count (filter #(= (:slashing-reason %) :timeout)  trials))
        reversal-slashed (count (filter #(= (:slashing-reason %) :reversal) trials))
        fraud-slashed    (count (filter #(= (:slashing-reason %) :fraud)    trials))
        appeal-count     (count (filter :appeal-triggered? trials))
        escalation-count (count (filter :escalated? trials))
        l2-detected-count (count (filter :l2-detected? trials))
        frozen-count     (count (filter :frozen? trials))
        escaped-count    (count (filter :escaped? trials))]

    {:aggregate
     {:n-trials n-trials
      :strategy strategy
      :honest-mean  (double mean-honest)
      :honest-std   (double (std-dev profits-honest mean-honest))
      :honest-min   (apply min profits-honest)
      :honest-max   (apply max profits-honest)
      :honest-p25   (quantile sorted-honest 0.25)
      :honest-p50   (quantile sorted-honest 0.50)
      :honest-p75   (quantile sorted-honest 0.75)
      :malice-mean  (double mean-malice)
      :malice-std   (double (std-dev profits-malice mean-malice))
      :malice-min   (apply min profits-malice)
      :malice-max   (apply max profits-malice)
      :malice-p25   (quantile sorted-malice 0.25)
      :malice-p50   (quantile sorted-malice 0.50)
      :malice-p75   (quantile sorted-malice 0.75)
      :honest-wins  (count (filter #(> % 0) (map - profits-honest profits-malice)))
      :dominance-ratio (if (zero? mean-malice)
                         Double/POSITIVE_INFINITY
                         (double (/ mean-honest mean-malice)))
      :appeal-rate      (double (/ appeal-count n-trials))
      :escalation-rate  (double (/ escalation-count n-trials))
      :l2-detection-rate (double (/ l2-detected-count n-trials))
      :slash-rate       (double (/ total-slashed n-trials))
      :timeout-slash-rate   (double (/ timeout-slashed n-trials))
      :reversal-slash-rate  (double (/ reversal-slashed n-trials))
      :fraud-slash-rate     (double (/ fraud-slashed n-trials))
      :fraud-slashed-count  fraud-slashed
      :reversal-slashed-count reversal-slashed
      :timeout-slashed-count  timeout-slashed
      :frozen-rate  (double (/ frozen-count n-trials))
      :escaped-rate (double (/ escaped-count n-trials))
      :adjusted-strategy strategy
      :bribery-enabled (boolean (and (:bribe-cost-ratio params)
                                     (:fraud-slash-bps params)
                                     (> (:fraud-slash-bps params) 0)))
      :bribery-cost (when (:bribe-cost-ratio params)
                      (integration/calculate-bribery-cost params))}
     :trials trials}))

(defn run-ring-batch
  "Run N trials of a resolver ring simulation and return aggregated stats.
   
   Phase F1: Multi-resolver collusion with waterfall slashing."
  [rng n-trials params ring-spec]
  (let [;; Initialize the ring
        initial-ring (ring/create-ring ring-spec)
        
        ;; Run repeated disputes for the ring
        ring-results
        (reduce
          (fn [ring-state _trial]
            (let [dispute-result
                  (ring/simulate-ring-dispute
                   rng ring-state
                   (:escrow-size params 10000)
                   (:resolver-fee-bps params)
                   (:appeal-bond-bps params)
                   (:slash-multiplier params)
                   (:appeal-probability-if-correct params)
                   (:appeal-probability-if-wrong params)
                   (:slashing-detection-probability params)
                   :l2-detection-prob (:l2-detection-prob params 0))]
              (:ring dispute-result)))
          initial-ring
          (range n-trials))
        
        ;; Extract profitability analysis
        profitability (ring/ring-profitability ring-results)
        
        ;; Individual resolver states
        member-states (:member-states profitability)
        senior-state (first (filter #(= (:tier %) :senior) member-states))
        junior-states (filter #(= (:tier %) :junior) member-states)]
    
    {:n-trials n-trials
     :ring-type (str (count junior-states) "-junior-ring")
     
     ;; Aggregate ring profitability
     :ring-total-profit (double (:total-profit profitability))
     :ring-avg-profit-per-dispute (double (:average-profit-per-dispute profitability))
     :ring-catch-rate (double (:catch-rate profitability))
     :ring-viable? (:viable? profitability)
     :ring-senior-exhausted? (:senior-exhausted? profitability)
     
     ;; Individual member status (scalars only for CSV compatibility)
     :senior-bond-remaining (double (:bond-remaining senior-state))
     :senior-slashed-amount (double (:slashed-amount senior-state))
     :juniors-count (count junior-states)
     :juniors-avg-bond-remaining (double 
       (if (empty? junior-states) 0 
         (/ (reduce + (map :bond-remaining junior-states)) (count junior-states))))
     :juniors-total-slashed (double
       (reduce + (map :slashed-amount junior-states)))
     
     ;; Comparative threshold
     :ring-profitable? (:ring-profitable? profitability)
     :ring-solvent? (:ring-solvent? profitability)}))
