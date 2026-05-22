(ns resolver-sim.protocols.sew.research-models.panel-decision
  "Panel voting, correlation, and herding dynamics.
   
   Phase P Lite: Replaces single resolver with n-person panel.
   
   Key insight: Panels create two new failure modes:
   1. Correlated errors: If resolvers share priors (rho > 0), wrong majority wins
   2. Herding: If slashing penalizes deviance, truth-tellers converge on wrong answer
   
   Together, these can invert the dominance ratio even if individual accuracy is fine."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.difficulty :as diff]))

;; === Panel Basics ===

(defn create-panel
  "Create panel of n resolvers with given strategies.
   
   Returns vector of resolver IDs with assigned strategies."
  [panel-size strategy-distribution]
  (vec (range panel-size)))

(defn panel-majority-threshold
  "How many votes needed to reach majority?
   
   For n=3: need 2 votes
   For n=5: need 3 votes
   General: ceil((n+1)/2)"
  [panel-size]
  (inc (quot panel-size 2)))

;; === Correlation & Herding ===

(defn should-vote-with-correlation?
  "Does resolver vote based on shared priors (correlation) or own analysis?
   
   rho = correlation parameter ∈ [0.0, 1.0]
   - rho=0.0: independent analysis (current model)
   - rho=0.5: 50% follow consensus, 50% own analysis
   - rho=0.9: 90% herding, 10% independent
   
   Returns true if resolver follows correlation signal this time."
  [rng rho]
  (< (rng/next-double rng) rho))

(defn herding-incentive-modifier
  "How much does herding increase perceived slashing risk?
   
   Base: if you vote alone against 2 others, you're slashed if wrong.
   With herding incentive: deviation from majority feels riskier even if you're right.
   
   Modifier = 20% additional perceived risk for deviating
   
   This makes following the crowd rational even when you have better information.
   This is the Schelling game trap: be right and slashed, or be wrong safely."
  [panel-size panel-votes own-vote]
  (let [votes-against-you (count (filter (fn [v] (not= v own-vote)) panel-votes))
        is-minority? (> votes-against-you (/ panel-size 2.0))]
    (if is-minority?
      1.20  ; +20% perceived slashing risk for deviance
      1.0)))

(defn resolve-with-herding
  "Adjust resolver's vote given herding pressure.
   
   Returns: actual vote after considering herding incentive
   
   Logic:
   1. If rho > threshold, follow majority (correlated voting)
   2. If would vote against majority, apply herding penalty
   3. If penalty exceeds some threshold, switch vote
   
   This encodes: in ambiguous cases, following crowd becomes safer."
  [rng own-vote majority-vote rho difficulty]
  (if (should-vote-with-correlation? rng rho)
    majority-vote  ; Follow social signal
    own-vote))     ; Stick with own analysis

;; === Panel Voting ===

(defn panel-vote
  "Run panel voting on a single dispute.
   
   Returns: {:majority-vote bool
             :votes [v1 v2 v3]
             :herding-occurred? bool
             :unanimous? bool
             :confidence-level int}  ; 0=close (2-1), 1=unanimous (3-0)
   
   Process:
   1. Each resolver votes based on accuracy (given difficulty/strategy)
   2. Apply correlation: rho fraction follow majority
   3. Apply herding: minority voters feel slashing risk
   4. Calculate final majority vote
   5. Determine confidence (2-1 vs 3-0)"
  [rng resolvers strategies difficulties rho]
  (let [panel-size (count resolvers)
        
        ;; Step 1: Get base votes from each resolver
        base-votes
        (mapv (fn [idx strategy difficulty]
                (let [accuracy (diff/accuracy-by-difficulty strategy difficulty)]
                  (< (rng/next-double rng) accuracy)))
              (range panel-size) strategies difficulties)
        
        ;; Step 2: Count what majority would be
        majority-vote (>= (count (filter true? base-votes)) (panel-majority-threshold panel-size))
        
        ;; Step 3: Apply correlation/herding
        final-votes
        (mapv (fn [idx base-vote]
                (if (should-vote-with-correlation? rng rho)
                  majority-vote    ; Herding: follow majority
                  base-vote))      ; Independence: stick with analysis
              (range panel-size) base-votes)
        
        ;; Step 4: Recompute final majority (after herding)
        final-majority (>= (count (filter true? final-votes))
                          (panel-majority-threshold panel-size))
        
        ;; Step 5: Confidence level
        true-votes (count (filter true? final-votes))
        confidence (cond
                     (= true-votes 0) 0  ; Unanimous false
                     (= true-votes panel-size) 0  ; Unanimous true
                     :else 1)  ; Split decision (2-1 or 1-2)]
        
        herding-occurred? (not= final-votes base-votes)]
    
    {:majority-vote final-majority
     :votes final-votes
     :base-votes base-votes
     :herding-occurred? herding-occurred?
     :unanimous? (or (= true-votes 0) (= true-votes panel-size))
     :confidence-level confidence
     :split-ratio (str true-votes "-" (- panel-size true-votes))}))

;; === Panel Error Analysis ===

(defn panel-error-distribution
  "How many resolvers got it wrong?
   
   Returns: 0 (all correct), 1 (one wrong), 2+ (majority wrong)
   
   This is the key metric: even if individual accuracy is high,
   if rho > 0 they can systematically agree on wrong answer."
  [votes truth]
  (count (filter (fn [v] (not= v truth)) votes)))

(defn panel-wrong-due-to-correlation?
  "Did the panel get it wrong because of herding (not because individuals are bad)?
   
   Returns true if:
   - Base votes had majority correct
   - But final votes have majority wrong (herding flipped it)"
  [base-votes final-votes truth]
  (let [base-majority (>= (count (filter true? base-votes)) 2)
        final-majority (>= (count (filter true? final-votes)) 2)
        truth-vote truth]
    (and (= base-majority truth-vote)    ; Base had correct majority
         (not= final-majority truth-vote))))  ; But herding flipped it

(defn herding-captures-truth-teller
  "Did herding cause an honest minority to switch to wrong majority?
   
   This is the worst case: someone with good analysis gets overruled
   by Schelling game pressure and cascades into wrong answer."
  [base-votes final-votes truth]
  (and
    ; There's at least one person who had it right in base votes
    (some (fn [v] (= v truth)) base-votes)
    ; But in final votes, they switched
     (not (some (fn [v] (= v truth)) final-votes))))

;; === Multi-Panel Aggregation ===

(defn aggregate-multiple-disputes
  "Aggregate results across multiple disputes for panel statistics.
   
   Returns: {:avg-herding-rate float
             :truth-flips int
             :unanimous-fraction float}"
  [dispute-results]
  (let [total (count dispute-results)
        herding-count (count (filter :herding-occurred? dispute-results))
        truth-flips (count (filter :truth-captured? dispute-results))
        unanimous (count (filter :unanimous? dispute-results))]
    {:avg-herding-rate (if (zero? total) 0.0 (/ herding-count (double total)))
     :truth-flips truth-flips
     :unanimous-fraction (if (zero? total) 0.0 (/ unanimous (double total)))}))

;; === Correlation Sensitivity ===

(defn correlation-phase-transition
  "Identify threshold where system breaks (rho value where dominance inverts).
   
   Tests: rho ∈ [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8]
   
   Expected: somewhere between 0.3-0.6, system transitions from robust to fragile.
   
   Returns: rho value where dominance drops below 1.0"
  [rho-values dominance-ratios]
  (let [pairs (map vector rho-values dominance-ratios)]
    ; Find first crossing from > 1.0 to < 1.0
    (some (fn [[[r1 d1] [r2 d2]]]
            (when (and (> d1 1.0) (< d2 1.0))
              (+ r1 (/ (- r2 r1) 2.0))))  ; Linear interpolation
          (partition 2 1 pairs))))

;; === Validation ===

(defn validate-panel-size
  "Ensure panel size is odd (for clear majority)."
  [panel-size]
  (odd? panel-size))

(defn validate-correlation
  "Ensure rho ∈ [0.0, 1.0]"
  [rho]
  (and (>= rho 0.0) (<= rho 1.0)))

(defn validate-panel-votes
  "Ensure votes are boolean and match panel size."
  [votes panel-size]
  (and (= (count votes) panel-size)
       (every? boolean? votes)))
