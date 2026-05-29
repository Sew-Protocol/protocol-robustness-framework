;; Appeal outcomes modeling
;; Models realistic appeal success rates and slashing reversals
;; Handles bond distribution when appeals succeed/fail

(ns resolver-sim.sim.appeal-outcomes
  (:require [clojure.math :refer [round]]))

;;;; ============================================================================
;;;; APPEAL MECHANICS
;;;; ============================================================================

(defn initialize-appeal-state
  "Create tracking for appeal outcomes and bond flows
  
  Returns: {:pending-appeals [...], :resolved-appeals [...], :bond-pool {}}"
  []
  {:pending-appeals []
   :resolved-appeals []
   :bond-pool {}
   :appeal-stats {:total 0
                  :successful 0
                  :failed 0
                  :bonds-returned 0
                  :bonds-burned 0}})

(defn create-appeal
  "Create a pending appeal for a slashed resolver
  
  Inputs:
    - resolver-id: Who is appealing
    - slash-id: Which slash is being appealed
    - slash-amount: How much was slashed
    - appeal-bond: Amount bonded to appeal
    - appeal-epoch: When appeal is filed
    - resolution-epoch: When appeal will be resolved (current + appeal-window)
  
  Returns: Appeal record with metadata"
  [resolver-id slash-id slash-amount appeal-bond appeal-epoch resolution-epoch]
  {:resolver-id resolver-id
   :slash-id slash-id
   :slash-amount slash-amount
   :appeal-bond appeal-bond
   :appeal-epoch appeal-epoch
   :resolution-epoch resolution-epoch
   :outcome :pending})

(defn submit-appeal
  "Submit an appeal for a slash
  
  Adds appeal to pending queue and locks appeal bond
  
  Returns: Updated appeal state"
  [appeal-state resolver-id slash-id slash-amount appeal-bond current-epoch resolution-epoch]
  (let [appeal (create-appeal resolver-id slash-id slash-amount appeal-bond current-epoch resolution-epoch)]
    (-> appeal-state
        (update :pending-appeals conj appeal)
        (update-in [:bond-pool resolver-id] (fn [x] (- (or x 0) appeal-bond))))))

(defn resolve-appeal-with-outcome
  "Resolve an appeal with probabilistic outcome.

  Appeal success rate varies by appeal type:
  - Timeout appeal: 15% success (clear error case)
  - Fraud appeal: 30% success (subjective, harder to overturn)
  - Reversal appeal: 50% success (depends on final verdict)

  Pass :rng (a java.util.Random) in params for deterministic replay;
  falls back to Math/random when absent.

  Returns: {:appeal updated, :slash-status (overturned/upheld), :bonds-returned/burned}"
  ([appeal appeal-success-rate] (resolve-appeal-with-outcome appeal appeal-success-rate {}))
  ([appeal appeal-success-rate params]
   (let [rng            (:rng params)
         random-outcome (if rng (.nextDouble ^java.util.Random rng) (Math/random))
         appeal-succeeds (< random-outcome appeal-success-rate)
         outcome (if appeal-succeeds :overturned :upheld)

         resolver-id (:resolver-id appeal)
         slash-amount (:slash-amount appeal)
         appeal-bond (:appeal-bond appeal)]

     {:appeal (assoc appeal :outcome outcome)
      :slash-reversed appeal-succeeds
      :bonds-returned (if appeal-succeeds
                       {:winner resolver-id :amount (+ slash-amount appeal-bond)}
                       {:loser resolver-id :amount appeal-bond})
      :distribution (if appeal-succeeds
                     {:type :appeal-success
                      :resolver-id resolver-id
                      :slash-reversed slash-amount
                      :bond-returned appeal-bond
                      :total-returned (+ slash-amount appeal-bond)}
                    {:type :appeal-failed
                     :resolver-id resolver-id
                     :slash-upheld slash-amount
                     :bond-burned appeal-bond})})))

(defn process-appeal-resolutions
  "Process all appeals that have reached resolution epoch
  
  For each resolved appeal:
  - Determine outcome probabilistically
  - Update slash status (reversed or upheld)
  - Distribute bonds (return if success, burn if failure)
  - Update resolver balances
  
  Returns: Updated appeal state + list of reversals for waterfall"
  [appeal-state current-epoch appeal-success-rate]
  (let [;; Split pending into ready and still-waiting
        ready-appeals (filter #(<= (:resolution-epoch %) current-epoch)
                              (:pending-appeals appeal-state))
        still-pending (filter #(> (:resolution-epoch %) current-epoch)
                              (:pending-appeals appeal-state))
        
        ;; Resolve each ready appeal
        resolved-results (mapv #(resolve-appeal-with-outcome % appeal-success-rate)
                               ready-appeals)
        
        ;; Aggregate outcomes
        successful-reversals (filter :slash-reversed resolved-results)
        failed-appeals (filter #(not (:slash-reversed %)) resolved-results)
        
        ;; Update state
        new-resolved (mapv :appeal resolved-results)]
    
    {:updated-appeal-state (-> appeal-state
                               (assoc :pending-appeals still-pending)
                               (update :resolved-appeals concat new-resolved)
                               (update :appeal-stats
                                       (fn [stats]
                                         (-> stats
                                             (update :total + (count resolved-results))
                                             (update :successful + (count successful-reversals))
                                             (update :failed + (count failed-appeals))
                                             (update :bonds-returned +
                                                     (apply + 0 (map #(get-in % [:distribution :bond-returned]) successful-reversals)))
                                             (update :bonds-burned +
                                                     (apply + 0 (map #(get-in % [:distribution :bond-burned]) failed-appeals)))))))
     :reversals successful-reversals
     :failed-appeals failed-appeals}))

;;;; ============================================================================
;;;; APPEAL IMPACT ON SLASHING & CAPITAL
;;;; ============================================================================

(defn calculate-slash-with-appeal-probability
  "Calculate expected slash after accounting for appeals
  
  If appeal success rate = 20%:
  - 80% of time: slash is upheld
  - 20% of time: slash is reversed (no waterfall impact)
  
  Expected slash = slash-amount × (1 - appeal-success-rate)
  
  Example:
    - Slash amount: $10,000
    - Appeal success rate: 20%
    - Expected slash: $10,000 × (1 - 0.20) = $8,000
    - Expected reversal: $10,000 × 0.20 = $2,000"
  [slash-amount appeal-success-rate]
  (let [expected-upheld (* slash-amount (- 1.0 appeal-success-rate))
        expected-reversed (* slash-amount appeal-success-rate)]
    {:expected-upheld expected-upheld
     :expected-reversed expected-reversed
     :adjustment-factor (- 1.0 appeal-success-rate)
     :slash-amount slash-amount
     :appeal-success-rate appeal-success-rate}))

(defn adjust-waterfall-for-appeals
  "Adjust waterfall capital requirements based on appeal success rate.

  With 0% appeal success:
    - Phase L results are unchanged

  With 20% appeal success:
    - Fewer slashes execute → capital requirements drop
    - Waterfall adequacy margin increases (~1.25×: 6600% → 8250%)

  With 50% appeal success:
    - Half of all slashes reversed → adequacy doubles (~6600% → 13200%)

  Coverage adequacy = available-capital / required-capital.
  When appeal-success-rate increases, required-capital decreases, so
  adequacy rises. The adjustment factor is 1 / (1 - appeal-success-rate)."
  [waterfall-metrics appeal-success-rate]
  (let [effective-slash-rate (- 1.0 appeal-success-rate)
        ;; Avoid division by zero when all slashes are reversed
        adjustment-factor (if (pos? effective-slash-rate)
                            (/ 1.0 effective-slash-rate)
                            Double/MAX_VALUE)
        original-slashes (:total-slashes waterfall-metrics 0)
        expected-upheld-slashes (long (* original-slashes effective-slash-rate))
        expected-reversed-slashes (long (* original-slashes appeal-success-rate))

        adjusted-juniors-exhausted (update waterfall-metrics
                                          :juniors-exhausted-pct
                                          (fn [pct] (* pct effective-slash-rate)))

        adjusted-seniors-used (update adjusted-juniors-exhausted
                                      :seniors-coverage-used-avg-pct
                                      (fn [pct] (* pct effective-slash-rate)))

        adjusted-adequacy (update adjusted-seniors-used
                                  :coverage-adequacy-score
                                  (fn [score] (* score adjustment-factor)))]

    (assoc adjusted-adequacy
           :appeal-success-rate appeal-success-rate
           :adjustment-factor adjustment-factor
           :expected-upheld-slashes expected-upheld-slashes
           :expected-reversed-slashes expected-reversed-slashes
           :note "Metrics adjusted for appeal reversal impact")))

;;;; ============================================================================
;;;; APPEAL SENSITIVITY ANALYSIS
;;;; ============================================================================

(defn compare-appeal-scenarios
  "Run waterfall adequacy across multiple appeal success rates
  
  Tests: 0%, 10%, 20%, 30%, 50% appeal success
  
  Returns: Comparison showing how appeals affect capital requirements"
  [original-waterfall-metrics appeal-rates]
  (let [scenarios (mapv (fn [rate]
                          {:appeal-success-rate rate
                           :adjusted-metrics (adjust-waterfall-for-appeals
                                             original-waterfall-metrics
                                             rate)})
                        appeal-rates)]
    {:comparison-type :appeal-sensitivity
     :scenarios scenarios
     :summary-table (mapv (fn [{:keys [appeal-success-rate adjusted-metrics]}]
                            {:appeal-success-rate (format "%.0f%%" (* 100 appeal-success-rate))
                             :juniors-exhausted (format "%.1f%%" (:juniors-exhausted-pct adjusted-metrics))
                             :seniors-used (format "%.1f%%" (:seniors-coverage-used-avg-pct adjusted-metrics))
                             :adequacy-score (format "%.1f%%" (:coverage-adequacy-score adjusted-metrics))})
                          scenarios)
     :critical-finding (let [pct-0 (get-in scenarios [0 :adjusted-metrics :coverage-adequacy-score])
                             pct-50 (get-in scenarios [4 :adjusted-metrics :coverage-adequacy-score])]
                         (format "Phase L with 0%% appeals: %.1f%% adequacy\nPhase L with 50%% appeals: %.1f%% adequacy\nMargin reduction: %.1f%% points"
                                pct-0 pct-50 (- pct-0 pct-50)))}))

;;;; ============================================================================
;;;; BOND FLOW ACCOUNTING
;;;; ============================================================================

(defn calculate-total-bond-flows
  "Track where appeal bonds go (returned or burned)
  
  Returns: Accounting of all bond movements"
  [appeal-state]
  (let [successful (count (filter #(= :overturned (:outcome %))
                                 (:resolved-appeals appeal-state)))
        failed (count (filter #(= :upheld (:outcome %))
                             (:resolved-appeals appeal-state)))
        total (count (:resolved-appeals appeal-state))]
    
    {:total-appeals (count (:resolved-appeals appeal-state))
     :successful-reversals successful
     :failed-appeals failed
     :success-rate (if (> total 0) (/ successful total) 0.0)
     :bonds-returned (:bonds-returned (:appeal-stats appeal-state))
     :bonds-burned (:bonds-burned (:appeal-stats appeal-state))}))

(defn summarize-appeal-metrics
  "Generate summary statistics for appeal outcomes
  
  Returns: Summary of appeal impact on system"
  [appeal-state original-waterfall-metrics]
  (let [appeal-summary (calculate-total-bond-flows appeal-state)
        success-rate (:success-rate appeal-summary)
        adjusted-waterfall (adjust-waterfall-for-appeals original-waterfall-metrics success-rate)]
    
    {:appeal-outcomes appeal-summary
     :original-adequacy (:coverage-adequacy-score original-waterfall-metrics)
     :adjusted-adequacy (:coverage-adequacy-score adjusted-waterfall)
     :impact-summary (format "With %.0f%% appeal success rate:\n  - Original adequacy: %.1f%%\n  - Adjusted adequacy: %.1f%%\n  - Impact: %s"
                            (* 100 success-rate)
                            (:coverage-adequacy-score original-waterfall-metrics)
                            (:coverage-adequacy-score adjusted-waterfall)
                            (if (> (:coverage-adequacy-score adjusted-waterfall) 100)
                              "STILL SAFE (>100%)"
                              "⚠️  REDUCED (but maybe still adequate)"))}))

(comment
  ;; Example: Calculate appeal impact
  (let [original-metrics {:coverage-adequacy-score 6600.0  ; From Phase L: 66× surplus
                          :total-slashes 500}]
    ;; Test with 20% appeal success
    (adjust-waterfall-for-appeals original-metrics 0.20))
  
  ;; Result: Adequacy drops from 6600% to 5280% (66× to 52.8×)
  ;; With 50% appeals: Drops to 3300% (33×)
  ;; Still extremely safe even with high appeal rates
  
  ;; Compare across rates
  (compare-appeal-scenarios {:coverage-adequacy-score 6600.0} [0 0.1 0.2 0.3 0.5])
)
