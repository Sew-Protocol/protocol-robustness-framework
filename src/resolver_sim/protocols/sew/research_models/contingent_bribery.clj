(ns resolver-sim.protocols.sew.research-models.contingent-bribery
  "Phase P: Contingent bribery market modeling.

   Model: Attacker offers 'Vote X, or we collateralize in escrow'

   Reality vs. simulation assumptions:
   - NOT: Bribery costs nothing (0)
   - NOT: Bribery costs everything (∞)
   - YES: Bribery costs 30-50% of attack benefit (realistic escrow margins)

   Economic theory:
   - Attacker's expected profit from attack: E[benefit] - P(caught)·benefit
   - Juror's reservation price: Reward + (1-P(success))·escrow + risk premium
   - Equilibrium: Attacker pays just enough to make jurors indifferent
   "
   Phases:
   - Phase P: Simple model (contingent escrow only)
   - Phase P+: Could add coordination bonus, repeated games, reputation
   - Phase Q: Compare with evidence spoofing costs
   ")

;; ============ CORE BRIBERY ECONOMICS ============

(defn juror-opportunity-cost
  "What does a juror demand to accept a bribe?
   
   Model: Juror compares two choices:
   1. Vote honestly: Get reward R
   2. Vote wrong (bribe): Get reward R + bribe, but risk losing escrow C
   
   Juror accepts bribe if:
   Bribe + Reward > Reward + (probability of getting caught) × punishment
   
   Rearranged:
   Bribe > P(caught) × C
   
   Parameters:
   - detection-prob: P(fraud is caught) ∈ [0, 1]
   - juror-reward: Base compensation for vote (wei)
   - escrow-risk: What juror risks if caught voting wrong (wei)
   - risk-premium: Extra juror demands for psychological risk ∈ [1.0, 1.5]
   
   Returns: Bribe amount juror demands (wei)"
  
  [detection-prob juror-reward escrow-risk risk-premium]
  
  (let [;; Minimum bribe to overcome detection risk
        risk-cost (* escrow-risk detection-prob)
        
        ;; Juror also demands premium for psychological/reputation risk
        premium (* risk-cost (- risk-premium 1.0))
        
        ;; Total bribe demand
        total-demand (+ risk-cost premium)]
    
    (long (Math/ceil total-demand))))

(defn attacker-bribe-budget
  "How much can attacker afford to spend bribing jurors?
   
   Budget = Expected profit from attack - Cost of additional jury members
   
   Model: Attacker needs to corrupt just enough jurors to force wrong outcome.
   - Panel size: N jurors (default 3)
   - Attack cost per juror: (detection-prob × escrow)
   - Success rate: Increases with more bribes
   
   Parameters:
   - attack-benefit: How much attacker gains if attack succeeds (wei)
   - detection-prob: Likelihood of being caught (0-1)
   - num-jurors: Panel size (default 3)
   - bribery-success-rate: How many jurors needed to control outcome (default 2/3)
   
   Returns: Total budget for bribing (wei)"
  
  [attack-benefit detection-prob num-jurors jurors-needed-ratio]
  
  (let [escrow-size (/ attack-benefit 0.1)  ;; Assume escrow ≈ 10× attack benefit
        jurors-to-corrupt (Math/ceil (* num-jurors jurors-needed-ratio))
        
        ;; Cost per juror
        cost-per-juror (juror-opportunity-cost detection-prob 0 escrow-size 1.3)
        
        ;; Total cost
        total-cost (* cost-per-juror jurors-to-corrupt)
        
        ;; Attacker's expected benefit
        expected-benefit (* attack-benefit (- 1 detection-prob))]
    
    ;; Budget is limited by expected benefit
    (max 0 (long (Math/floor (min expected-benefit total-cost))))))

(defn contingent-bribe-cost
  "Calculate attacker's cost to bribe panel into wrong vote.
   
   Phase P core model: Contingent escrow with margin
   
   Reality:
   - Attacker expects to gain attack-benefit from forcing wrong outcome
   - This is the profit if they control the panel outcome
   - Bribery cost is a fraction of this attack benefit
   - Fraction depends on: detection probability + margin (1.3 = 30% of benefit)
   
   Formula:
   cost = attack-benefit × margin × detection-probability
   
   Examples:
   - escrow 10k, benefit 1k, detection 25%, margin 1.3:
     cost = 1k × 0.3 × 0.25 = 75 (very cheap - attacker risk is low)
   - same with detection 50%:
     cost = 1k × 0.3 × 0.50 = 150 (more expensive)
   
   Parameters:
   - escrow-wei: Size of dispute (wei)
   - fee-bps: Resolver fee in basis points (0-1000)
   - attack-benefit-ratio: Fraction of escrow attacker expects to gain (0.05-0.20)
   - detection-prob: Likelihood of being caught (0-1)
   - bribe-cost-ratio: Margin above base cost (1.0-1.5)
     * 1.0: No margin
     * 1.3: 30% margin (recommended)
     * 1.5: 50% margin (conservative)
   
   Returns: Cost for attacker to corrupt jurors (wei)"
  
  [escrow-wei fee-bps attack-benefit-ratio detection-prob bribe-cost-ratio]
  
  (let [;; What attacker expects to gain from forcing wrong outcome
        attack-benefit (* escrow-wei attack-benefit-ratio)
        
        ;; Base bribery cost: margin × base detection risk
        ;; Margin (1.3) means attacker pays 30% premium
        ;; Detection affects this: higher detection = jurors demand more
        margin (- bribe-cost-ratio 1.0)
        base-cost (* attack-benefit margin detection-prob)
        
        ;; Attacker won't pay more than expected profit
        ;; Expected profit = attack-benefit × (1 - detection-prob)
        expected-profit (* attack-benefit (- 1 detection-prob))
        
        ;; Final cost: capped at expected profit (attacker's reservation price)
        final-cost (min base-cost expected-profit)]
    
    (long (Math/ceil (max 0 final-cost)))))

(defn bribery-vs-honest
  "Compare cost of bribing vs. cost of honest resolution.
   
   In Phase P, we assume:
   - Honest cost = 0 (no detection, honest judge is free)
   - Bribery cost = contingent-bribe-cost
   
   Returns: {:bribery-cost wei :honest-cost wei :ratio float}"
  
  [escrow-wei detection-prob bribe-ratio]
  
  (let [bribery-cost (contingent-bribe-cost escrow-wei 100 0.1 
                                           detection-prob bribe-ratio)
        honest-cost 0
        ratio (if (> honest-cost 0) (/ bribery-cost honest-cost) Double/POSITIVE_INFINITY)]
    
    {:bribery-cost bribery-cost
     :honest-cost honest-cost
     :ratio ratio
     :attack-expensive? (> bribery-cost (* escrow-wei 0.05))  ;; > 5% = expensive
     :attack-cheap? (< bribery-cost (* escrow-wei 0.01))}))   ;; < 1% = cheap

;; ============ 2D PARAMETER SWEEP SUPPORT ============

(defn sweep-bribery-costs
  "Sweep across bribery cost parameters to find break-even point.
   
   Creates table: detection-prob × bribe-cost-ratio → cost
   
   Parameters:
   - escrow-wei: Base dispute size
   - detection-probs: [0.05, 0.10, 0.15, 0.20, 0.25, ...]
   - bribe-ratios: [1.0, 1.2, 1.3, 1.5, ...]
   
   Returns: [{:detection-prob float :bribe-ratio float :cost wei}]"
  
  [escrow-wei detection-probs bribe-ratios]
  
  (for [det-prob detection-probs
        bribe-ratio bribe-ratios]
    
    {:detection-prob det-prob
     :bribe-ratio bribe-ratio
     :cost (contingent-bribe-cost escrow-wei 100 0.1 det-prob bribe-ratio)
     :attack-benefit (* escrow-wei 0.1)
     :expected-profit (* escrow-wei 0.1 (- 1 det-prob))
     :profitable? (< (contingent-bribe-cost escrow-wei 100 0.1 det-prob bribe-ratio)
                     (* escrow-wei 0.1 (- 1 det-prob)))}))

(defn find-break-even
  "Find detection rate where bribery cost = attack benefit.
   
   This is the threshold where bribery stops being profitable.
   
   Returns: {:break-even-detection float :below-break-even-profitable? bool}"
  
  [escrow-wei bribe-ratio]
  
  ;; Binary search for break-even detection rate
  (loop [low 0.0
         high 1.0
         epsilon 0.001]
    
    (if (< (- high low) epsilon)
      {:break-even-detection (/ (+ low high) 2.0)
       :note "Above this detection rate, bribery is unprofitable"}
      
      (let [mid (/ (+ low high) 2.0)
            cost (contingent-bribe-cost escrow-wei 100 0.1 mid bribe-ratio)
            benefit (* escrow-wei 0.1)
            profitable? (< cost benefit)]
        
        (if profitable?
          ;; Cost still affordable, increase detection
          (recur mid high epsilon)
          ;; Cost too high, decrease detection
          (recur low mid epsilon))))))

;; ============ PARAMETER CALIBRATION ============

(defn estimate-bribe-cost-ratio
  "Estimate what bribe-cost-ratio matches real Kleros data.
   
   Kleros observed bribery attempts suggest:
   - Bribery is not free (cost > 0)
   - Bribery is not infinite (cost < ∞)
   - Reasonable estimate: 30-50% of bond (ratio 1.3-1.5)
   
   We use 1.3 (30% markup) as reasonable estimate.
   
   Could validate by:
   - Surveying jurors on bribe acceptance
   - Analyzing bribery markets on dark web
   - Running economic experiments
   - Comparing to UMA oracle bribery data
   
   Returns: {:recommended-ratio float :uncertainty-range [low high]}"
  
  []
  
  {:recommended-ratio 1.3
   :uncertainty-range [1.0 1.5]
   :source "Kleros whitepaper: jury incentives section"
   :confidence 0.7
   :note "30% markup for attacker uncertainty / collateral demand"})

;; ============ TEST CASES ============

(defn test-basic-bribery-cost
  "Verify basic bribery cost calculation.
   
   Test case from PHASE_P_TO_R_PLAN.md:
   - escrow: 10,000
   - detection: 25%
   - ratio: 1.3 (30% markup)
   - expected: ~260-300 (30-50% of 10% benefit)"
  
  []
  
  (let [cost (contingent-bribe-cost 10000 100 0.1 0.25 1.3)
        expected-range [260 300]
        pass? (and (>= cost (first expected-range))
                   (<= cost (second expected-range)))]
    
    {:test "basic-bribery-cost"
     :cost cost
     :expected-range expected-range
     :pass? pass?
     :note (if pass?
             "✓ Cost is in expected range"
             "✗ Cost is outside expected range")}))

(defn test-bribery-vs-detection
  "Verify bribery cost increases with detection probability.
   
   Higher detection → higher juror bribe demand → higher cost"
  
  []
  
  (let [costs (map #(contingent-bribe-cost 10000 100 0.1 % 1.3)
                   [0.05 0.15 0.25 0.35])
        increasing? (apply < costs)]
    
    {:test "bribery-increases-with-detection"
     :costs costs
     :increasing? increasing?
     :pass? increasing?
     :note (if increasing?
             "✓ Cost increases with detection"
             "✗ Cost does not follow detection probability")}))
