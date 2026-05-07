(ns resolver-sim.stochastic.economics
  "Payoff and economic functions.
   
   All functions are pure: no side effects, deterministic given inputs."
  (:require [resolver-sim.stochastic.rng :as rng])
  (:import [java.math BigDecimal]))

(defn calculate-fee
  "Calculate resolver fee based on escrow and fee rate (bps).
   Uses integer division (quot) to match contract_model/types compute-fee.

   Example:
   (calculate-fee 1000 150) ; 1000 wei escrow, 1.5% = 15 wei"
  [escrow-wei fee-bps]
  (quot (* escrow-wei fee-bps) 10000))

(defn calculate-bond
  "Calculate appeal bond based on escrow and bond rate (bps).
   Uses integer division (quot) to match contract_model/types compute-fee.

   Example:
   (calculate-bond 1000 700) ; 1000 wei escrow, 7% = 70 wei"
  [escrow-wei bond-bps]
  (quot (* escrow-wei bond-bps) 10000))

(defn calculate-slashing-loss
  "Calculate slashing loss if resolver is caught and slashed.
   
   Loss = bond * slash_multiplier"
  [bond-wei slash-multiplier]
  (* bond-wei slash-multiplier))

(defn honest-expected-value
  "Expected value for honest resolver.
   
   EV = fee * (1 - appeal_rate)
        + fee * appeal_rate * (1 - appeal_won_rate)
   
   Simplified: assume honest loses appeals rarely."
  [fee-wei appeal-prob-if-correct]
  (let [appeal-loss-prob (- 1 appeal-prob-if-correct)
        ev (* fee-wei appeal-loss-prob)]
    (max 0 ev)))

(defn malicious-expected-value
  "Expected value for malicious resolver.

   EV = fee + fraud-upside * fraud-success-rate - slashing-loss * detection-probability

   fraud-upside is the escrow-diversion gain: escrow × (1 − fee-rate).
   When fraud-success-rate=0.0 (the default), this reduces to the original
   EV = fee - slashing-loss * detection-probability, keeping backward compatibility.

   IMPORTANT: This is protocol income only. It does not model the full economic
   gain to a colluding *party* who receives the misdirected escrow."
  ([fee-wei slashing-loss detection-prob]
   (malicious-expected-value fee-wei slashing-loss detection-prob 0 0.0))
  ([fee-wei slashing-loss detection-prob fraud-upside fraud-success-rate]
   (let [expected-fraud-gain (* fraud-upside fraud-success-rate)
         net-profit (+ fee-wei expected-fraud-gain (- (* slashing-loss detection-prob)))]
     net-profit)))

(defn lazy-expected-value
  "Expected value for lazy resolver.
   
   EV = fee * (correct_probability * (1 - appeal_prob_correct)
               + wrong_probability * (1 - appeal_prob_wrong))"
  [fee-wei correct-prob appeal-prob-correct appeal-prob-wrong]
  (let [correct-ev (* correct-prob (- 1 appeal-prob-correct))
        wrong-ev (* (- 1 correct-prob) (- 1 appeal-prob-wrong))
        total-survival-prob (+ correct-ev wrong-ev)
        ev (* fee-wei total-survival-prob)]
    (max 0 ev)))

(defn collusive-expected-value
  "Expected value for collusive resolver.

   EV = fee * colluder-gain-rate - fee * effective-detection

   colluder-gain-rate drives extra profit from coordinated wrong verdicts.
   Default 1.2 reproduces the original model's coordination bonus at coalition=1.
   Calibrated value from ring-attack trace: use ~1.15 (Phase AI escalation-trap).

   To use the original hard-coded formula: omit colluder-gain-rate (or pass nil).
   To calibrate from trace data: pass the measured gain multiplier directly."
  ([fee-wei coalition-size detection-prob-increased]
   (collusive-expected-value fee-wei coalition-size detection-prob-increased nil))
  ([fee-wei coalition-size detection-prob-increased colluder-gain-rate]
   (let [; colluder-gain-rate nil → fall back to the original log-based formula
         gain-rate (or colluder-gain-rate
                       (/ 1.2 (Math/log (+ 2 coalition-size))))
         effective-detection (min 0.5 detection-prob-increased)
         ev (- (* fee-wei gain-rate)
               (* fee-wei effective-detection))]
     (max 0 ev))))

(defn strategy-dominance-score
  "How much better is honest than malicious?

   score = ev_honest / ev_malicious

   score > 2.0 means honest is 2× better."
  [ev-honest ev-malicious]
  (if (zero? ev-malicious)
    (if (zero? ev-honest) 1.0 Double/POSITIVE_INFINITY)
    (/ ev-honest ev-malicious)))

(defn worst-case-fraud-success-rate
  "Worst-case fraud-success-rate: every undetected malicious resolver diverts funds.
   fraud-success-rate = 1 - detection-prob.

   This is the correct default for economic security analysis. It means:
   'if not caught, the malicious resolver captures the escrow.'
   Setting fraud-success-rate=0.0 (the original default) only models protocol income."
  [detection-prob]
  (max 0.0 (- 1.0 detection-prob)))

(def default-escalation-assumptions
  "Named parameter bands for escalation model sensitivity analysis.

   :base        — realistic mid-range estimates
   :optimistic  — strong deterrence (escalation reliably corrects fraud)
   :pessimistic — weak deterrence (escalation often fails to correct fraud)
   :two-layer   — no Kleros backstop (has-kleros? false); represents 2-layer only protocol

   Use :pessimistic for conservative (worst-case) economic security analysis.
   Use :two-layer to compare outcomes without Kleros fallback."
  {:base        {:p-appeal-wrong 0.40  :p-l1-reversal 0.85  :has-kleros? true  :p-l2-escalation 0.70  :p-l2-reversal 0.95}
   :optimistic  {:p-appeal-wrong 0.60  :p-l1-reversal 0.95  :has-kleros? true  :p-l2-escalation 0.90  :p-l2-reversal 0.99}
   :pessimistic {:p-appeal-wrong 0.20  :p-l1-reversal 0.60  :has-kleros? true  :p-l2-escalation 0.40  :p-l2-reversal 0.80}
   :two-layer   {:p-appeal-wrong 0.40  :p-l1-reversal 0.85  :has-kleros? false :p-l2-escalation 0.0   :p-l2-reversal 0.0}})

(defn fraud-survival-probability
  "Probability that a malicious L0 verdict survives all active escalation tiers.

   Three-layer path (has-kleros? true):
     P = P(no appeal)
       + P(appeal) × P(L1 fails to reverse)
         × [P(no L2 escalation) + P(L2 escalation) × P(L2 fails to reverse)]

   Two-layer path (has-kleros? false, no Kleros backstop):
     P = P(no appeal) + P(appeal) × P(L1 fails to reverse)

   Parameters (map):
     :p-appeal-wrong    P(aggrieved party appeals) — default 0.40
     :p-l1-reversal     P(L1 overturns corrupt verdict | appealed) — default 0.85
     :has-kleros?       whether L2/Kleros backstop is active — default true
     :p-l2-escalation   P(party escalates to L2 | L1 upholds corrupt) — default 0.70
     :p-l2-reversal     P(L2 overturns | escalated) — default 0.95

   Inputs are clamped to [0,1]."
  [{:keys [p-appeal-wrong p-l1-reversal has-kleros? p-l2-escalation p-l2-reversal]
    :or   {p-appeal-wrong  0.40
           p-l1-reversal   0.85
           has-kleros?     true
           p-l2-escalation 0.70
           p-l2-reversal   0.95}}]
  (let [clamp         (fn [x] (-> x double (max 0.0) (min 1.0)))
        p-appeal      (clamp p-appeal-wrong)
        p-l1-fail     (- 1.0 (clamp p-l1-reversal))
        p-l2-escalate (clamp p-l2-escalation)
        p-l2-fail     (- 1.0 (clamp p-l2-reversal))
        after-appeal  (* p-appeal p-l1-fail)]
    (if has-kleros?
      (+ (- 1.0 p-appeal)
         (* after-appeal (+ (- 1.0 p-l2-escalate) (* p-l2-escalate p-l2-fail))))
      (+ (- 1.0 p-appeal) after-appeal))))

(defn sequential-fraud-success-prob
  "Analytical probability that a corrupt outcome survives all escalation tiers.

   Use this to understand parameter sensitivity without running MC trials.
   The simulation uses stochastic draws (in resolve-dispute); this function
   computes the closed-form expectation for comparison and documentation.

   Three-layer path (with Kleros):
     P = P(no appeal)
       + P(appeal) × P(L1 upholds)
         × [P(no L2 escalation) + P(L2 escalation) × P(L2 upholds)]

   Two-layer path (no Kleros, has-kleros?=false):
     P = P(no appeal) + P(appeal) × P(L1 upholds)

   Parameters (map):
     :appeal-prob-wrong    P(aggrieved party appeals at all) — default 0.40
     :p-l1-reversal        P(senior resolver overturns corrupt verdict | appealed) — default 0.85
     :has-kleros?          whether L2/Kleros backstop exists — default true
     :p-l2-escalation      P(party escalates to L2 | L1 upheld corrupt) — default 0.70
     :p-l2-reversal        P(Kleros overturns | escalated to L2) — default 0.95

   Returns the probability [0,1] that fraud reaches final settlement as-is."
  [{:keys [appeal-prob-wrong p-l1-reversal has-kleros? p-l2-escalation p-l2-reversal]
    :or   {appeal-prob-wrong 0.40
           p-l1-reversal     0.85
           has-kleros?       true
           p-l2-escalation   0.70
           p-l2-reversal     0.95}}]
  (let [p-no-appeal  (- 1.0 appeal-prob-wrong)
        p-l1-upholds (- 1.0 p-l1-reversal)
        p-after-l1   (* appeal-prob-wrong p-l1-upholds)]
    (if has-kleros?
      (let [p-no-l2     (- 1.0 p-l2-escalation)
            p-l2-upholds (- 1.0 p-l2-reversal)]
        (+ p-no-appeal (* p-after-l1 (+ p-no-l2 (* p-l2-escalation p-l2-upholds)))))
      (+ p-no-appeal p-after-l1))))

(defn breakeven-detection
  "Minimum detection probability for honest EV to exceed full malicious EV.

   Derivation (worst-case model: fraud-success-rate = 1 - detection-prob):
     malice-EV = fee + (1-d) × (escrow - fee) - d × bond-loss
     honest-EV ≈ fee

   Setting honest-EV = malice-EV and solving for d:
     d = (escrow - fee) / (bond-loss + escrow - fee)

   If current detection-prob < breakeven-detection, bond deterrence alone is insufficient.
   The protocol's economic security relies on the state machine constraining
   fraud-success-rate (via invariants: funds-conservation, no-double-release)."
  [escrow-wei fee-wei bond-loss]
  (let [escrow-net (- escrow-wei fee-wei)]
    (double (/ escrow-net (+ bond-loss escrow-net)))))
