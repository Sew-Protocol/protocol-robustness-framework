(ns resolver-sim.stochastic.detection
  "Pure detection and penalty helpers for the Monte Carlo dispute model.

   Live-aligned reversal slashing (deterministic on appeal outcome, stake basis)
   and probabilistic fraud/timeout detection. Used by stochastic/dispute and
   re-exported from oracle/detection for sim-layer adapters."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.economics.payoffs :as payoffs]))

(defn appeal-reversal-outcome
  "Sample whether an appealed wrong verdict is reversed at L1 and/or L2.

   Uses :p-l1-reversal, :p-l2-escalation, :p-l2-reversal (or escalation-assumptions band).
   Returns {:l1-reversed? :l2-escalated? :l2-reversed? :decision-reversed?}."
  [rng params {:keys [verdict-correct? appealed?]}]
  (let [band-assumptions (get (:escalation-assumptions params)
                              (:escalation-assumption-band params :base))
        p-l1 (or (:p-l1-reversal params) (:p-l1-reversal band-assumptions 0.75))
        p-l2-esc (or (:p-l2-escalation params) (:p-l2-escalation band-assumptions 0.55))
        p-l2-rev (or (:p-l2-reversal params) (:p-l2-reversal band-assumptions 0.88))
        has-kleros? (if (some? (:has-kleros? params))
                      (:has-kleros? params)
                      (:has-kleros? band-assumptions true))
        wrong-and-appealed? (and (not verdict-correct?) appealed?)
        l1-reversed? (and wrong-and-appealed? (< (rng/next-double rng) p-l1))
        l2-escalated? (and wrong-and-appealed?
                           (not l1-reversed?)
                           has-kleros?
                           (< (rng/next-double rng) p-l2-esc))
        l2-reversed? (and l2-escalated? (< (rng/next-double rng) p-l2-rev))
        decision-reversed? (or l1-reversed? l2-reversed?)]
    {:l1-reversed? l1-reversed?
     :l2-escalated? l2-escalated?
     :l2-reversed? l2-reversed?
     :decision-reversed? decision-reversed?}))

(defn reversal-slashed-live?
  "Track 1 (live replay): deterministic reversal slash when appeal overturns a wrong verdict.

   Ignores :reversal-detection-probability (legacy). Slash when :reversal-slash-bps > 0."
  [params {:keys [verdict-correct? appealed? decision-reversed?]}]
  (and (not verdict-correct?)
       appealed?
       decision-reversed?
       (pos? (:reversal-slash-bps params 0))))

(defn reversal-pending-live?
  "Track 2 (live replay): new evidence → pending slash (not counted in immediate loss)."
  [params rng {:keys [reversal-slashed?]}]
  (and reversal-slashed?
       (pos? (:new-evidence-probability params 0))
       (< (rng/roll-double rng) (:new-evidence-probability params))))

(defn detect-probabilistic-violations
  "Fraud, timeout, and generic L1 detection rolls.

   Pass :rng in params. Returns {:fraud-detected? :timeout-detected? :l1-slashed?}."
  [params strategy verdict-correct? detection-prob]
  (let [rng (:rng params)
        roll #(rng/roll-double rng)
        fraud-det (:fraud-detection-probability params 0.0)
        timeout-det (:timeout-detection-probability params 0.0)
        base-detection-prob
        (case strategy
          :honest 0.01
          :lazy 0.02
          :malicious detection-prob
          :collusive 0.05)]
    {:fraud-detected?
     (if (and (not verdict-correct?)
              (> fraud-det 0)
              (= strategy :malicious))
       (< (roll) fraud-det)
       false)

     :timeout-detected?
     (if (and (> timeout-det 0)
              (or (= strategy :lazy) (= strategy :malicious)))
       (< (roll) timeout-det)
       false)

     :l1-slashed?
     (and (not verdict-correct?) (< (roll) base-detection-prob))}))

(defn select-slash-reason
  "Priority order for slashing reason (first match wins)."
  [{:keys [fraud-detected? reversal-slashed? l2-slashed? timeout-detected? l1-slashed?]}]
  (cond
    fraud-detected? :fraud
    reversal-slashed? :reversal
    l2-slashed? :fraud
    timeout-detected? :timeout
    l1-slashed? :timeout
    :else nil))

(defn slash-amount-for-reason
  "Slash amount in wei. Reversal uses stake basis (live); other reasons use bond basis."
  [reason params {:keys [bond-total resolver-stake slash-mult timeout-detected?]}]
  (case reason
    :reversal (payoffs/calculate-reversal-slash resolver-stake (:reversal-slash-bps params 0))
    :fraud    (long (* bond-total (/ (:fraud-slash-bps params 0) 10000.0)))
    :timeout  (if timeout-detected?
                (long (* bond-total (/ (:timeout-slash-bps params 200) 10000.0)))
                (long (* bond-total slash-mult)))
    nil 0))

(defn detect-fraud-rolls
  "Legacy standalone probabilistic rolls (fraud/reversal/timeout).

   MC dispute resolution should use appeal-reversal-outcome + reversal-slashed-live?
   for reversal; this helper remains for oracle adapter tests."
  [params]
  (let [fraud-det    (:fraud-detection-probability params 0.0)
        reversal-det (:reversal-detection-probability params 0.0)
        timeout-det  (:timeout-detection-probability params 0.0)
        rng          (:rng params)
        roll         #(rng/roll-double rng)]
    {:fraud-detected?    (< (roll) fraud-det)
     :reversal-detected? (< (roll) reversal-det)
     :timeout-detected?  (< (roll) timeout-det)}))

(defn apply-slashing
  [bond slash-bps]
  (if (zero? slash-bps)
    0
    (long (Math/ceil (* bond (/ slash-bps 10000.0))))))

(defn apply-freeze
  [params]
  (if (:freeze-on-detection? params true)
    {:frozen? true
     :freeze-duration-days (:freeze-duration-days params 3)}
    {:frozen? false
     :freeze-duration-days 0}))

(defn calculate-escape-window
  [params]
  (let [unstaking-delay (:unstaking-delay-days params 14)
        freeze-duration (:freeze-duration-days params 3)
        appeal-window (:appeal-window-days params 7)]
    (max 0 (- unstaking-delay freeze-duration appeal-window))))

(defn detection-result->penalty
  [detection params]
  (cond
    (:fraud-detected? detection)
    {:penalty-applied? true
     :slash-reason :fraud
     :slash-bps (:fraud-slash-bps params 5000)
     :frozen? (:freeze-on-detection? params true)}

    (:reversal-detected? detection)
    {:penalty-applied? true
     :slash-reason :reversal
     :slash-bps (:reversal-slash-bps params 2500)
     :frozen? false}

    (:timeout-detected? detection)
    {:penalty-applied? true
     :slash-reason :timeout
     :slash-bps (:timeout-slash-bps params 200)
     :frozen? false}

    :else
    {:penalty-applied? false
     :slash-reason nil
     :slash-bps 0
     :frozen? false}))
