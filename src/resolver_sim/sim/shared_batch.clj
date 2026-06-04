(ns resolver-sim.sim.shared-batch
  "Shared-world multi-agent batch runner.

   Unlike batch/run-batch-with-attribution which runs two independent sets of
   n-trials (one per strategy), this namespace generates PAIRED dispute outcomes:
   each dispute is run TWICE — once with honest strategy and once with malicious
   strategy — using independent RNG streams split from the same parent.

   This models a shared dispute pool where honest and malicious resolvers compete
   over the same underlying disputes rather than independent draws. The updated
   run-single-epoch with :use-shared-world? true splits the paired trial pool
   between honest and strategic resolver groups so total disputes = n-trials
   (not 2×n-trials as in the independent-batch model).

   Conservation invariant (checked in route-epoch downstream):
     (sum :profit-honest honest-slice) + (sum :profit-malice strategic-slice)
       == total profit attributed across all resolvers"
  (:require [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng     :as rng]))

;; ---------------------------------------------------------------------------
;; Statistics helpers (subset of batch.clj; kept local to avoid circular deps)
;; ---------------------------------------------------------------------------

(defn- mean [xs]
  (if (empty? xs) 0.0 (double (/ (reduce + xs) (count xs)))))

(defn- std-dev [xs m]
  (if (<= (count xs) 1)
    0.0
    (Math/sqrt (double (/ (reduce + (map #(Math/pow (- % m) 2) xs))
                          (count xs))))))

(defn- quantile [sorted-xs q]
  (if (empty? sorted-xs)
    0
    (let [n   (count sorted-xs)
          idx (* q (dec n))
          lo  (int (Math/floor idx))
          hi  (min (dec n) (int (Math/ceil idx)))
          f   (- idx lo)]
      (+ (* (- 1.0 f) (nth sorted-xs lo))
         (* f (nth sorted-xs hi))))))

;; ---------------------------------------------------------------------------
;; Paired trial generation
;; ---------------------------------------------------------------------------

(defn- common-kwargs
  "Extract the shared keyword args passed to resolve-dispute from params map."
  [params]
  [:l2-detection-prob              (:l2-detection-prob params 0)
   :slashing-detection-delay-weeks (:slashing-detection-delay-weeks params 0)
   :allow-slashing?                (:allow-slashing? params true)
   :resolver-bond-bps              (:resolver-bond-bps params 0)
   :fraud-detection-probability    (:fraud-detection-probability params 0.0)
   :fraud-slash-bps                (:fraud-slash-bps params 0)
   :reversal-detection-probability (:reversal-detection-probability params 1.0)
   :reversal-slash-bps             (:reversal-slash-bps params 0)
   :resolver-stake-wei             (:resolver-stake-wei params)
   :new-evidence-probability       (:new-evidence-probability params 0.0)
   :timeout-detection-probability  (:timeout-detection-probability params 0.0)
   :timeout-slash-bps              (:timeout-slash-bps params 200)
   :unstaking-delay-days           (:unstaking-delay-days params 14)
   :freeze-on-detection?           (:freeze-on-detection? params true)
   :freeze-duration-days           (:freeze-duration-days params 3)
   :appeal-window-days             (:appeal-window-days params 7)
   :fraud-model                    (:fraud-model params :single-stage-ev)
   :escalation-assumptions         (:escalation-assumptions params)
   :escalation-assumption-band     (:escalation-assumption-band params :base)
   :p-appeal-wrong                 (:p-appeal-wrong params)
   :p-l1-reversal                  (:p-l1-reversal params)
   :p-l2-escalation                (:p-l2-escalation params)
   :p-l2-reversal                  (:p-l2-reversal params)
   :has-kleros?                    (:has-kleros? params)
   :fraud-success-rate             (:fraud-success-rate params 0.0)
   :oracle-fixture                 (:oracle-fixture params)
   :oracle-mode                    (:oracle-mode params)
   :oracle-roll-sequence           (:oracle-roll-sequence params)
   :oracle-roll-on-exhaustion      (:oracle-roll-on-exhaustion params)
   :fixed-or                        (:fixed-or params)
   :oracle-roll-trace-enabled?     (:oracle-roll-trace-enabled? params false)])

(defn run-paired-trial
  "Run one dispute with BOTH honest and malicious strategies on independent
   RNG streams split from the parent rng.

   Returns a merged trial map suitable for route-epoch:
     {:profit-honest     — honest resolver's profit on this dispute
      :profit-malice     — malicious resolver's profit on this dispute
      :slashed?          — whether malicious resolver was slashed (malicious run)
      :slashing-reason   — slashing cause (malicious run)
      :dispute-correct?  — honest resolver always correct (true)
      :appeal-triggered? — whether an appeal occurred (malicious run)
      :escalated?        — whether dispute escalated to L2 (malicious run)
      ...}"
  [rng params]
  (let [[rng-h rng-m] (rng/split-rng rng)
        escrow        (:escrow-size params 10000)
        fee-bps       (:resolver-fee-bps params)
        bond-bps      (:appeal-bond-bps params)
        slash-mult    (:slash-multiplier params)
        apc           (:appeal-probability-if-correct params)
        apw           (:appeal-probability-if-wrong params)
        det-prob      (:slashing-detection-probability params)
        kwargs        (common-kwargs params)

        ;; Honest run: models the honest resolver's outcome for this dispute.
        ;; Returns profit-honest (and a counterfactual profit-malice, unused here).
        h (apply dispute/resolve-dispute
                 rng-h escrow fee-bps bond-bps slash-mult :honest
                 apc apw det-prob kwargs)

        ;; Malicious run: models the malicious resolver's outcome for this dispute.
        ;; Returns the actual profit-malice (with real detection/slashing applied).
        m (apply dispute/resolve-dispute
                 rng-m escrow fee-bps bond-bps slash-mult :malicious
                 apc apw det-prob kwargs)]

    {:profit-honest      (:profit-honest h)
     :profit-malice      (:profit-malice m)
     :slashed?           (:slashed? m)
     :slashing-reason    (:slashing-reason m)
     :dispute-correct?   (:dispute-correct? h true)
     :appeal-triggered?  (:appeal-triggered? m)
     :escalated?         (:escalated? m)
     :l2-detected?       (:l2-detected? m)
     :frozen?            (:frozen? m)
     :escaped?           (:escaped? m)
     :slashing-pending?  (:slashing-pending? m)
     :slashing-delay-weeks (:slashing-delay-weeks m)}))

;; ---------------------------------------------------------------------------
;; Shared batch runner
;; ---------------------------------------------------------------------------

(defn build-aggregate
  "Compute aggregate statistics from a vector of paired trial results.

   Returns a map with the same keys as batch/run-batch aggregate so that
   run-single-epoch can consume it without modification."
  [paired-trials n-trials]
  (let [profits-honest (map :profit-honest paired-trials)
        profits-malice (map :profit-malice paired-trials)
        mean-h         (mean profits-honest)
        mean-m         (mean profits-malice)
        sorted-h       (sort profits-honest)
        sorted-m       (sort profits-malice)

        total-slashed      (count (filter :slashed? paired-trials))
        appeal-count       (count (filter :appeal-triggered? paired-trials))
        escalation-count   (count (filter :escalated? paired-trials))
        fraud-slashed      (count (filter #(= (:slashing-reason %) :fraud) paired-trials))
        reversal-slashed   (count (filter #(= (:slashing-reason %) :reversal) paired-trials))
        timeout-slashed    (count (filter #(= (:slashing-reason %) :timeout) paired-trials))]

    {:n-trials          n-trials
     :mode              :shared-world
     :oracle-effective-mode (:mode (:oracle-effective params)
                                  (detection/normalize-oracle-fixture params))
     :honest-mean       (double mean-h)
     :honest-std        (double (std-dev profits-honest mean-h))
     :honest-min        (if (seq sorted-h) (first sorted-h) 0)
     :honest-max        (if (seq sorted-h) (last sorted-h) 0)
     :honest-p25        (if (seq sorted-h) (quantile sorted-h 0.25) 0)
     :honest-p50        (if (seq sorted-h) (quantile sorted-h 0.50) 0)
     :honest-p75        (if (seq sorted-h) (quantile sorted-h 0.75) 0)
     :malice-mean       (double mean-m)
     :malice-std        (double (std-dev profits-malice mean-m))
     :malice-min        (if (seq sorted-m) (first sorted-m) 0)
     :malice-max        (if (seq sorted-m) (last sorted-m) 0)
     :malice-p25        (if (seq sorted-m) (quantile sorted-m 0.25) 0)
     :malice-p50        (if (seq sorted-m) (quantile sorted-m 0.50) 0)
     :malice-p75        (if (seq sorted-m) (quantile sorted-m 0.75) 0)
     :honest-wins       (count (filter #(> (:profit-honest %) (:profit-malice %))
                                       paired-trials))
     :dominance-ratio   (if (and (double? (double mean-m)) (pos? mean-m))
                          (double (/ mean-h mean-m))
                          Double/POSITIVE_INFINITY)
     :appeal-rate       (double (/ appeal-count n-trials))
     :escalation-rate   (double (/ escalation-count n-trials))
     :slash-rate        (double (/ total-slashed n-trials))
     :fraud-slash-rate  (double (/ fraud-slashed n-trials))
     :reversal-slash-rate (double (/ reversal-slashed n-trials))
     :timeout-slash-rate  (double (/ timeout-slashed n-trials))
     ;; These keys are used by run-single-epoch from aggregate-malice
     :fraud-slashed-count    fraud-slashed
     :reversal-slashed-count reversal-slashed
     :timeout-slashed-count  timeout-slashed}))

(defn run-shared-batch
  "Run n-trials disputes generating PAIRED honest and malicious outcomes.

   Unlike batch/run-batch-with-attribution which runs two independent batches
   of n-trials each, this generates one pool of n-trials disputes where each
   dispute produces both an honest outcome and a malicious outcome via
   independent RNG streams.

   The caller (run-single-epoch with :use-shared-world? true) splits the
   paired-trials vector between honest and strategic resolver groups:
     - honest resolvers handle honest-slice  → profit-honest is used
     - strategic resolvers handle strategic-slice → profit-malice is used
   Total disputes handled == n-trials (not 2n).

   Returns:
     {:paired-trials    — vector of n-trials paired outcome maps
      :aggregate        — aggregate stats (same shape as batch/run-batch)
      :aggregate-malice — same aggregate (both strategies combined in one pool)}"
  [rng n-trials params]
  (let [paired-trials (vec (repeatedly n-trials #(run-paired-trial rng params)))
        aggregate     (build-aggregate paired-trials n-trials)]
    {:paired-trials    paired-trials
     :aggregate        aggregate
     :aggregate-malice aggregate}))
