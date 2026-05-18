(ns resolver-sim.io.sew.trace-score
  "SEW-specific scoring for replay traces.")

(defn- classify-issue
  "Protocol-agnostic issue classifier used by scoring.
   Returns one of: :none, :liveness-failure, :invariant-failure, :attack-success, :mixed."
  [result]
  (let [metrics (:metrics result {})
        comps   (:score-components result {})
        liveness? (pos? (:liveness-failure comps 0))
        inv?      (pos? (:invariant-violations metrics 0))
        attack?   (pos? (:attack-successes metrics 0))]
    (cond
      (and liveness? inv?) :mixed
      inv?                 :invariant-failure
      liveness?            :liveness-failure
      attack?              :attack-success
      :else                :none)))

(defn- liveness-failure?
  "Returns true if the final world state contains any escrow in a non-terminal
   state (:pending or :disputed).  Reads the last trace entry's projection."
  [trace]
  (let [last-entry (last trace)
        proj       (:projection last-entry)]
    (when proj
      (let [transfers (vals (:escrow-transfers proj {}))]
        (boolean (some #(#{:pending :disputed} (:escrow-state %)) transfers))))))

(defn score-result
  "Compute :trace-score for a replay-with-protocol result map.
   Formula:
     score = attacker-profit + (10 × invariant-violations) + (5 × liveness-failure?)"
  [result]
  (let [metrics             (:metrics result {})
        attack-successes    (:attack-successes metrics 0)
        invariant-violations (:invariant-violations metrics 0)
        trace               (:trace result [])
        liveness?           (liveness-failure? trace)
        liveness-penalty    (if liveness? 1 0)
        score               (+ attack-successes
                               (* 10 invariant-violations)
                               (* 5  liveness-penalty))]
    (assoc result
           :trace-score      score
           :issue/type       (classify-issue (assoc result :score-components {:liveness-failure liveness-penalty}))
           :score-components {:attacker-profit     attack-successes
                              :invariant-violations invariant-violations
                              :liveness-failure     liveness-penalty})))
