(ns resolver-sim.sim.defection
  "Agent strategy defection model.

   Resolvers observe epoch payoffs and may switch strategy when the other
   strategy is earning more. This models the rational defection dynamics
   absent from the original multi-epoch simulation, which used fixed strategies.

   Key design choices:

     Payoff comparison — each resolver compares its own epoch profit against
     the mean epoch profit of the OTHER strategy group for that epoch.

     Defection probability — `p = defection-rate × (payoff-diff / max(1, |own-profit|))`
     Clamped to [0, 0.8] to prevent lock-in from a single outlier epoch.

     Asymmetric inhibitors:
       Honest → malicious: inhibited by slashing risk.
         p *= (1 - slash-risk-inhibition × slashing-detection-probability)
       Malicious → honest: uninhibited (exiting malice is rational; sunk-cost only).

     Resolvers with zero trials are excluded (no signal to act on).

   Params:
     :defection-rate                — base switching multiplier (default 0; must set > 0 to enable)
     :slash-risk-inhibition         — 0–1 factor reducing honest→malicious defection (default 0.7)
     :slashing-detection-probability — used for inhibition calculation (default 0.1)

   Enable via :defection-rate 0.05 (or any positive value) in params.
   When :defection-rate is 0 or absent, returns resolver-histories unchanged."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Strategy grouping
;; ---------------------------------------------------------------------------

(defn- epoch-key [epoch] (keyword (str "epoch-" epoch)))

(defn- epoch-profit
  "Return this resolver's profit in the specified epoch, or nil if no entry."
  [resolver epoch]
  (get-in resolver [:epoch-history (epoch-key epoch) :profit]))

(defn- group-mean-profit
  "Compute mean epoch profit for all resolvers with the given strategy and ≥1 trial."
  [resolver-histories epoch strategy]
  (let [ek     (epoch-key epoch)
        profits (keep (fn [[_ r]]
                        (when (and (= strategy (:strategy r))
                                   (pos? (get-in r [:epoch-history ek :trials] 0)))
                          (get-in r [:epoch-history ek :profit] 0.0)))
                      resolver-histories)]
    (when (seq profits)
      (double (/ (apply + profits) (count profits))))))

;; ---------------------------------------------------------------------------
;; Per-resolver defection roll
;; ---------------------------------------------------------------------------

(defn- defect?
  "Return true if this resolver defects this epoch.

   own-profit        — this resolver's epoch profit
   other-mean        — mean epoch profit of the other strategy group
   current-strategy  — :honest or (anything else = malicious)
   params            — simulation params
   rng               — seeded SplittableRandom (mutated in place)"
  [own-profit other-mean current-strategy params rng]
  (let [defection-rate (:defection-rate params 0.0)]
    (when (pos? defection-rate)
      (let [payoff-diff (- other-mean own-profit)]
        (when (pos? payoff-diff)
          (let [raw-p (double (* defection-rate (/ payoff-diff (max 1.0 (Math/abs (double own-profit))))))
                ;; Honest→malicious: inhibited by slashing risk
                p     (if (= :honest current-strategy)
                        (let [slash-det  (:slashing-detection-probability params 0.1)
                              inhibition (:slash-risk-inhibition params 0.7)]
                          (* raw-p (- 1.0 (* inhibition slash-det))))
                        raw-p)
                p     (min 0.8 (max 0.0 p))]
            (< (rng/next-double rng) p)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn apply-strategy-defection
  "Apply per-resolver strategy defection after epoch `epoch` completes.

   Computes the mean profit for each strategy group, then for each resolver
   that had ≥1 trial, checks whether they defect to the other strategy.

   Resolvers with no trials this epoch are skipped (no payoff signal).

   Args:
     rng               — seeded SplittableRandom (mutated in place)
     resolver-histories — map of {id → resolver-record} (AFTER update-resolver-history)
     epoch             — epoch number just completed
     params            — simulation params

   Returns: {:updated-histories map :defection-events [{:id :from :to :epoch}]}"
  [rng resolver-histories epoch params]
  (let [defection-rate (:defection-rate params 0.0)]
    (if (zero? defection-rate)
      {:updated-histories resolver-histories
       :defection-events  []}

      (let [;; Compute group means once
            honest-mean  (group-mean-profit resolver-histories epoch :honest)
            malice-mean  (group-mean-profit resolver-histories epoch :malicious)

            ;; Process each resolver
            result
            (reduce-kv
             (fn [acc id resolver]
               (let [strategy    (:strategy resolver)
                     own-profit  (epoch-profit resolver epoch)
                     other-mean  (if (= :honest strategy) malice-mean honest-mean)]
                 (if (and own-profit
                          other-mean
                          (pos? (get-in resolver [:epoch-history (epoch-key epoch) :trials] 0)))
                   ;; Has a payoff signal — roll for defection
                   (let [[sub-rng _] (rng/split-rng rng)
                         defects?    (defect? own-profit other-mean strategy params sub-rng)]
                     (if defects?
                       (let [new-strategy (if (= :honest strategy) :malicious :honest)]
                         (-> acc
                             (assoc-in [:updated-histories id]
                                       (assoc resolver :strategy new-strategy))
                             (update :defection-events conj
                                     {:id    id
                                      :from  strategy
                                      :to    new-strategy
                                      :epoch epoch})))
                       (assoc-in acc [:updated-histories id] resolver)))
                   ;; No signal — leave unchanged
                   (assoc-in acc [:updated-histories id] resolver))))
             {:updated-histories {} :defection-events []}
             resolver-histories)]

        {:updated-histories (:updated-histories result)
         :defection-events  (:defection-events result [])}))))

(defn defection-summary
  "Produce a compact summary of defection events for epoch-summary injection."
  [defection-events]
  (when (seq defection-events)
    {:total-defections     (count defection-events)
     :honest-to-malicious  (count (filter #(= :honest (:from %)) defection-events))
     :malicious-to-honest  (count (filter #(= :malicious (:from %)) defection-events))
     :defector-ids         (mapv :id defection-events)}))
