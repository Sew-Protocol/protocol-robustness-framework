(ns resolver-sim.stochastic.evidence-costs
  "Evidence verification costs and load-dependent strategy selection.
   
   Phase P Lite: Adds attention budget constraint to break infinite-capacity assumption.
   
   Key insight: Under heavy load, honest resolvers can't fully verify everything.
   This makes shortcuts rational → lazy strategy dominates → system breaks.
   
   Also models: evidence forgery cost < verification cost for hard cases.
   This creates an asymmetry where attackers can forge faster than honest can verify."
  (:require [resolver-sim.stochastic.difficulty :as diff]
            [resolver-sim.stochastic.rng :as rng]))

;; === Effort Budget Constraints ===

(defn epoch-effort-budget
  "Total time units available per resolver per epoch.
   
   Default: 100 time units
   This is roughly 1 hour of investigation per epoch (week-scale).
   
   Under uniform load (50 disputes):
   - Can fully verify ~3 medium cases, OR
   - ~1 hard case + several easy ones, OR
   - Shortcut everything and triage heuristically"
  ([] 100)
  ([custom-budget] custom-budget))

(defn effort-available-per-dispute
  "Average effort available per dispute given load.
   
   effort-per-dispute = effort-budget / num-disputes
   
   At light load (10 disputes): 10 units/dispute → can fully verify all
   At heavy load (100 disputes): 1 unit/dispute → impossible to verify any
   
   This creates load-dependent strategy switching."
  [effort-budget num-disputes]
  (if (zero? num-disputes)
    effort-budget
    (/ effort-budget (double num-disputes))))

(defn effort-required-to-verify
  "Effort required for full verification by strategy/difficulty.
   
   Maps to difficulty module's effort-cost-to-fully-verify for honest,
   lazy, malicious strategies.
   
   Key: 'Full verification' for hard cases costs 80 time units.
   Under heavy load, this is unachievable; shortcuts become rational."
  [strategy difficulty]
  (diff/effort-cost-to-fully-verify strategy difficulty))

(defn effort-actually-spent
  "How much effort does resolver actually spend on a dispute?
   
   Strategy-dependent:
   - HONEST: wants to verify properly, limited by budget
   - LAZY: takes minimum viable effort (heuristic)
   - MALICIOUS: already knows what they want to do, minimal effort
   
   Under normal load, honest can meet targets.
   Under heavy load, honest falls back to heuristics."
  [rng strategy difficulty effort-available effort-required]
  (case strategy
    :honest
    (min effort-available effort-required)  ; Do best effort within budget
    
    :lazy
    (max 3 (/ effort-available 2.0))  ; Lazy spends ~half effort on anything
    
    :malicious
    (max 1 (/ effort-available 4.0))  ; Malice barely tries; they know the answer
    
    :collusive
    (min effort-available (double (/ effort-required 1.3)))))  ; Collusive tries harder

;; === Accuracy with Effort ===

(defn accuracy-given-load
  "Compute realistic accuracy given load and effort available.
   
   1. Get base accuracy for strategy/difficulty
   2. Adjust for effort available vs required
   3. Return degraded accuracy under load
   
   Effect: Under heavy load, honest accuracy drops to lazy levels."
  [rng strategy difficulty effort-available]
  (let [base-accuracy (diff/accuracy-by-difficulty strategy difficulty)
        effort-required (effort-required-to-verify strategy difficulty)
        effort-spent (effort-actually-spent rng strategy difficulty
                                            effort-available effort-required)
        adjusted-accuracy (diff/accuracy-with-effort base-accuracy effort-spent
                                                    effort-required)]
    adjusted-accuracy))

(defn should-shortcut?
  "Does resolver rationally decide to shortcut verification?
   
   Returns true if:
   - Load is heavy (effort-available < effort-required)
   - AND shortcutting is faster than full verification
   - AND resolver's strategy allows it (honest avoids when possible)
   
   This encodes: under load, heuristics become economically dominant."
  [effort-available effort-required strategy]
  (let [needs-shortcut? (< effort-available effort-required)
        shortcut-allowed? (not (= strategy :honest))]
    (and needs-shortcut? shortcut-allowed?)))

;; === Load Analysis ===

(defn load-level
  "Classify load as light/medium/heavy/extreme.
   
   light: <= 10 disputes (honest can fully verify all)
   medium: 11-50 disputes (honest can verify most)
   heavy: 51-150 disputes (honest must shortcut some)
   extreme: >150 disputes (honest can't verify any)
   
   Each level has different equilibrium strategies."
  [num-disputes effort-budget]
  (let [avg-effort (/ effort-budget (double num-disputes))]
    (cond
      (<= num-disputes 10)  :light
      (<= num-disputes 50)  :medium
      (<= num-disputes 150) :heavy
      :else                 :extreme)))

(defn load-multiplier-for-lazy-advantage
  "How much does load shift incentives toward lazy?
   
   Light: lazy pays same as honest (both can verify)
   Medium: lazy has slight advantage (honest stretched)
   Heavy: lazy has 2x advantage (honest can't verify, lazy doesn't care)
   Extreme: lazy has 5x advantage (honest completely overwhelmed)
   
   This multiplier applies to profit calculations."
  [num-disputes effort-budget]
  (case (load-level num-disputes effort-budget)
    :light   1.0
    :medium  1.5
    :heavy   2.0
    :extreme 5.0))

;; === Evidence Forgery Costs ===

(defn forge-evidence-cost
  "Cost for attacker to create plausible evidence by difficulty.
   
   Easy: 2 units (trivial to fake consensus)
   Medium: 5 units (need selective screenshots/translation tricks)
   Hard: 8 units (need deep fake or narrative construction)
   
   Compare to honest verification:
   - Easy: 5 units honest vs 2 units fake → 2.5x cost disadvantage
   - Medium: 30 units honest vs 5 units fake → 6x cost disadvantage
   - Hard: 80 units honest vs 8 units fake → 10x cost disadvantage
   
   This creates the evidence asymmetry that breaks systems."
  [difficulty]
  (case difficulty
    :easy   2
    :medium 5
    :hard   8))

(defn verify-evidence-cost
  "Cost for honest to verify potentially forged evidence.
   
   Same as effort-required-to-verify (they need full investigation)."
  [difficulty]
  (diff/effort-cost-to-fully-verify :honest difficulty))

(defn evidence-asymmetry-ratio
  "How much cheaper is forgery vs verification?
   
   High ratio = attacker advantage
   easy: 5/2 = 2.5x
   medium: 30/5 = 6x
   hard: 80/8 = 10x
   
   This ratio shows why hard cases are attack targets."
  [difficulty]
  (/ (verify-evidence-cost difficulty)
     (forge-evidence-cost difficulty)))

;; === Strategy Selection Under Load ===

(defn- canon-round [f] (long (Math/floor f)))

(defn optimal-strategy-under-load
  "Which strategy maximizes profit given load?
   
   This is the core broken assumption: under load, LAZY becomes optimal
   because verification cost exceeds potential upside.
   
   Returns: :honest | :lazy | :malicious (rarely) based on profit maximization"
  [rng num-disputes effort-budget detection-prob slashing-multiplier fee-profit]
  (let [effort-available (effort-available-per-dispute effort-budget num-disputes)
        load-mult (load-multiplier-for-lazy-advantage num-disputes effort-budget)
        honest-accuracy 0.80
        
        ;; Honest: pays slashing (low) but reliable profit. 
        ;; Load impact is on accuracy, not a direct scalar penalty on fee-profit.
        honest-profit (canon-round (* fee-profit honest-accuracy))

        ;; Lazy: lower accuracy but avoids slashing risk and effort costs.
        ;; Lazy gains advantage because it saves effort costs compared to honest.
        lazy-accuracy 0.40
        lazy-profit (canon-round (* fee-profit lazy-accuracy load-mult))
        
        ;; Malicious: lowest accuracy but high upside if not detected
        malice-accuracy 0.20
        malice-detection-risk (* detection-prob slashing-multiplier)
        malice-profit (canon-round (* fee-profit malice-accuracy (- 1.0 malice-detection-risk)))]
    
    (cond
      (> honest-profit lazy-profit) :honest
      (> lazy-profit malice-profit) :lazy
      :else :malicious)))

;; === Validation ===

(defn validate-effort-budget
  "Ensure effort budget is positive and reasonable."
  [budget]
  (and (> budget 0) (< budget 1000)))

(defn validate-load
  "Ensure load (num-disputes) is positive."
  [num-disputes]
  (> num-disputes 0))

;; === Metrics ===

(defn effort-utilization
  "Fraction of available budget that's used.
   
   Used / Available.
   - < 1.0: budget is sufficient, slack exists
   - > 1.0: budget is insufficient, overloaded
   
   When > 1.0, shortcuts become rational."
  [total-effort-needed effort-budget]
  (if (zero? effort-budget)
    0.0
    (/ total-effort-needed (double effort-budget))))
