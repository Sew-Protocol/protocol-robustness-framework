(ns resolver-sim.io.trace-score
  "Scoring function for replay traces.

   Assigns a numeric :trace-score to any replay-with-protocol result. Higher
   scores indicate traces that are more valuable for regression coverage:

     score = attacker-profit
           + (* 10 invariant-violations)
           + (* 5  liveness-failure?)

   Definitions
   -----------
   attacker-profit
     Number of attack-successes recorded in :metrics.  This is a proxy for
     economic damage: each successfully executed attacker action (create,
     dispute, resolution) that succeeded represents a potential gain.
     If the scenario includes explicit agent-type annotations (attacker),
     `:metrics :attack-successes` is the count.  We scale it by 1 here;
     callers may substitute a richer profit figure from the world state.

   invariant-violations
     Count of invariant violations recorded in :metrics.  Weighted ×10
     because a violated invariant is a protocol-level failure.

   liveness-failure?
     1 if any escrow in the final state is still :pending or :disputed at
     the last trace step (escrow never resolved).  Weighted ×5.

   Adapter status
   --------------
   This namespace preserves a stable, generic call surface while delegating
   protocol-specific score semantics to protocol-scoped providers.
   Current default provider: SEW (`resolver-sim.io.sew.trace-score`).

   Categories
   ----------
   score-category classifies a scored result into one or more category
   keywords for selective persistence:
     :top-profitable  — attack-successes > 0
     :liveness-fail   — liveness-failure? = true
     :cascade         — disputes-triggered > 1
     :abnormal-slash  — invariant-violations > 0"
  (:require [resolver-sim.io.sew.trace-score :as sew-ts]))

;; ---------------------------------------------------------------------------
;; Public: score-result
;; ---------------------------------------------------------------------------

(defn score-result
  "Compute :trace-score for a replay-with-protocol result map.

   Returns the result map augmented with:
     :trace-score       — numeric score (higher = more interesting)
     :score-components  — {:attacker-profit :invariant-violations :liveness-failure}

   Formula:
     score = attacker-profit + (10 × invariant-violations) + (5 × liveness-failure?)

   Compatibility adapter: currently delegates to the SEW scorer.
   Works with replay results regardless of :outcome."
  [result]
  (sew-ts/score-result result))

;; ---------------------------------------------------------------------------
;; Public: score-category
;; ---------------------------------------------------------------------------

(defn score-category
  "Return a set of category keywords for a scored result (after score-result).

   Categories:
     :top-profitable  — attacker had at least one successful action
     :liveness-fail   — at least one escrow stuck in :pending or :disputed
     :cascade         — more than one dispute was triggered
     :abnormal-slash  — invariant violation occurred (likely slashing-related)"
  [scored-result]
  (let [comps     (:score-components scored-result {})
        metrics   (:metrics scored-result {})
        cats      (cond-> #{}
                    (> (:attacker-profit comps 0) 0)
                    (conj :top-profitable)

                    (> (:liveness-failure comps 0) 0)
                    (conj :liveness-fail)

                    (> (:disputes-triggered metrics 0) 1)
                    (conj :cascade)

                    (> (:invariant-violations metrics 0) 0)
                    (conj :abnormal-slash))]
    cats))

;; ---------------------------------------------------------------------------
;; Public: select-top-n
;; ---------------------------------------------------------------------------

(defn select-top-n
  "Given a collection of scored results (after score-result), return the
   top n by :trace-score descending.  Ties broken by invariant-violations
   descending, then attack-successes descending."
  [n scored-results]
  (->> scored-results
       (sort-by (fn [r]
                  [(- (:trace-score r 0))
                   (- (get-in r [:score-components :invariant-violations] 0))
                   (- (get-in r [:score-components :attacker-profit] 0))]))
       (take n)
       vec))

;; ---------------------------------------------------------------------------
;; Public: select-top-percentile
;; ---------------------------------------------------------------------------

(defn select-top-percentile
  "Return the top p% of scored results by trace-score.
   p is a fraction in (0, 1], e.g. 0.01 for top 1%.
   Returns at least 1 result when the collection is non-empty."
  [p scored-results]
  (let [n (max 1 (int (Math/ceil (* p (count scored-results)))))]
    (select-top-n n scored-results)))
