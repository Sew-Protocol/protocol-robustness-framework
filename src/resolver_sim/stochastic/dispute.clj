(ns resolver-sim.stochastic.dispute
  "Dispute lifecycle and resolution mechanics."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.economics.payoffs :as payoffs]))

;; Dispute resolution for a single trial
;; Phase D: Track slashing reasons (timeout/reversal/fraud) without RNG changes
(defn resolve-dispute
  "Resolve one dispute with given parameters and strategy.

   Phase D adds slashing reason tracking (timeout/reversal/fraud).
   Reasons are deterministically derived from existing state.
   Phase G adds slashing delays and control baseline support.
   Phase H adds realistic bond mechanics: immediate freeze, unstaking delays, appeal windows.
   MC-1 adds fraud-success-rate: escrow-diversion upside for malicious resolvers.
   MC-4 adds model-appeal-costs?: appeal-bond recovery for honest resolvers when appeal fails.

   Detection mechanisms (mutually exclusive priority order):
     1. fraud-detected?    — intentional wrong verdict caught (fraud-slash-bps penalty)
     2. reversal-detected? — L1 verdict overturned by L2 (reversal-slash-bps penalty)
     3. l2-slashed?        — Kleros backstop catches wrong verdict (fraud-slash-bps penalty)
     4. timeout-detected?  — resolver missed deadline (timeout-slash-bps penalty)
     5. l1-slashed?        — generic L1 detection (slash-mult penalty, reason :timeout)

   Returns:
   {:dispute-correct? bool        ; Whether resolver judged correctly
    :appeal-triggered? bool       ; Whether initial appeal happened
    :escalated? bool              ; Whether case went beyond L0 (level > 0)
    :escalation-level int         ; Final level: 0=none, 1=L1 appeal, 2=L2 (Kleros)
    :slashed? bool                ; Whether resolver caught and slashed
    :slashing-pending? bool       ; Phase G: slashing is scheduled but delayed
    :frozen? bool                 ; Phase H: account frozen at detection
    :escaped? bool                ; Phase H: did resolver unstake before penalties?
    :slashing-delay-weeks int     ; Phase G: weeks until slashing takes effect (0 = immediate)
    :slashing-reason keyword      ; Reason for slashing (:timeout/:reversal/:fraud or nil)
    :profit-honest integer        ; Profit if honest (MC-4: includes appeal-bond recovery when enabled)
    :profit-malice integer        ; Profit if malicious (MC-1: includes escrow-diversion upside when fraud-success-rate > 0)
    :fraud-upside integer         ; MC-1: escrow-diversion gain (0 when fraud-success-rate=0 or slashed)
    :slash-distributed map        ; {:insurance :protocol :burned} — nil when not slashed
    :strategy keyword}            ; Strategy used"
  [rng escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob
   & {:keys [senior-resolver-skill resolver-bond-bps l2-detection-prob
             slashing-detection-delay-weeks allow-slashing?
             unstaking-delay-days freeze-on-detection? freeze-duration-days appeal-window-days
             detection-type timeout-detection-probability reversal-detection-probability
             fraud-detection-probability fraud-slash-bps reversal-slash-bps timeout-slash-bps
              fraud-success-rate fraud-model escalation-assumptions escalation-assumption-band
              p-appeal-wrong p-l1-reversal has-kleros? p-l2-escalation p-l2-reversal
              model-appeal-costs? appeal-bond-recovery-rate]
      :or {senior-resolver-skill 0.95
           resolver-bond-bps 1000
           l2-detection-prob 0
           slashing-detection-delay-weeks 0
           allow-slashing? true
           unstaking-delay-days 14
           freeze-on-detection? true
           freeze-duration-days 3
           appeal-window-days 7
           detection-type :fraud
           timeout-detection-probability 0.0
           reversal-detection-probability 0.0
           fraud-detection-probability 0.0
           fraud-slash-bps 0
           reversal-slash-bps 0
           timeout-slash-bps 200
           ;; MC-1: escrow-diversion upside for malicious resolvers.
           ;; 0.0 = original model (no upside); 0.22 = calibrated to adversarial suite.
           fraud-success-rate 0.0
            ;; Fraud payoff model:
            ;; :single-stage-ev       -> legacy scalar fraud-success-rate shortcut
            ;; :sequential-escalation -> appeal/escalation-aware survival probability
            ;; :strict-all-tiers      -> escrow loss only if L1 and L2 both fail
            ;;                          (assumes appeal/escalation always pursued)
            fraud-model :single-stage-ev
            escalation-assumptions econ/default-escalation-assumptions
            escalation-assumption-band :base
           ;; MC-4: model appeal-bond recovery for honest resolvers.
           ;; false = original model; true = resolver earns fraction of failed challenge bond.
           model-appeal-costs? false
           ;; Fraction of challenger appeal bond returned to honest resolver when appeal fails.
           appeal-bond-recovery-rate 0.5}}]

  (let [fee           (econ/calculate-fee escrow-wei fee-bps)
        appeal-bond   (econ/calculate-bond escrow-wei bond-bps)
        resolver-bond (econ/calculate-bond escrow-wei resolver-bond-bps)

        ;; Determine if resolver judges correctly (depends on strategy)
        verdict-correct?
        (case strategy
          :honest    true
          :lazy      (< (rng/next-double rng) 0.5)
          :malicious (< (rng/next-double rng) 0.3)
          :collusive (< (rng/next-double rng) 0.8))

        ;; Appeal rate depends on verdict correctness
        appeal-prob (if verdict-correct? appeal-prob-correct appeal-prob-wrong)
        appealed?   (< (rng/next-double rng) appeal-prob)

        ;; ── Detection mechanisms ─────────────────────────────────────────
        ;; Evaluated before escalation-level so l2-slashed? can inform level.

        ;; Generic L1 detection (wrong verdict caught by base mechanism)
        base-detection-prob
        (case strategy
          :honest    0.01
          :lazy      0.02
          :malicious detection-prob
          :collusive 0.05)

        l1-slashed?
        (and (not verdict-correct?) (< (rng/next-double rng) base-detection-prob))

        ;; Phase I: Fraud-specific detection (malicious only; 50% penalty when triggered)
        fraud-detected?
        (if (and (not verdict-correct?)
                 (> fraud-detection-probability 0)
                 (= strategy :malicious))
          (< (rng/next-double rng) fraud-detection-probability)
          false)

        ;; Phase I: Reversal detection (lazy/collusive; 25% penalty when triggered)
        reversal-detected?
        (if (and (not verdict-correct?)
                 (> reversal-detection-probability 0)
                 (or (= strategy :lazy) (= strategy :collusive)))
          (< (rng/next-double rng) reversal-detection-probability)
          false)

        ;; Bug C fix — timeout detection now reads timeout-detection-probability.
        ;; Applies to lazy and malicious resolvers who miss response deadlines.
        ;; Independent of verdict correctness: a resolver can have a correct verdict
        ;; but still be penalised for missing a deadline.
        timeout-detected?
        (if (and (> timeout-detection-probability 0)
                 (or (= strategy :lazy) (= strategy :malicious)))
          (< (rng/next-double rng) timeout-detection-probability)
          false)

        ;; Phase E1: L2 (Kleros) backstop — additional catch when case is appealed
        ;; Bug B fix — l2-slashed? now evaluated before effective-slash-multiplier
        ;; so the correct penalty branch can be selected below.
        l2-slashed?
        (if (and appealed? (not verdict-correct?) (> l2-detection-prob 0))
          (< (rng/next-double rng) l2-detection-prob)
          false)

        ;; ── Escalation ──────────────────────────────────────────────────
        ;; Bug A fix — escalation-level now reaches 2 when Kleros is involved.
        ;; Previously capped at 1; escalated? was limited to malicious only.
        escalation-level
        (cond
          l2-slashed? 2    ; Kleros backstop invoked
          appealed?   1    ; appealed, resolved at L1
          :else       0)

        escalated? (pos? escalation-level)

        ;; ── Slashing outcome ────────────────────────────────────────────
        ;; Final slashing: caught by any mechanism
        slashed-detected?
        (and allow-slashing?
             (or l1-slashed? fraud-detected? reversal-detected?
                 l2-slashed? timeout-detected?))

        ;; Phase I: Penalty selection — priority order matches docstring.
        ;; Bug B fix — l2-slashed? now has its own branch applying fraud-slash-bps
        ;;             (Kleros backstop = full fraud penalty, not generic slash-mult).
        ;; Bug C fix — timeout-detected? now has its own branch applying timeout-slash-bps.
        effective-slash-multiplier
        (cond
          fraud-detected?    (/ fraud-slash-bps 10000.0)
          reversal-detected? (/ reversal-slash-bps 10000.0)
          l2-slashed?        (/ fraud-slash-bps 10000.0)    ; L2 backstop = fraud penalty
          timeout-detected?  (/ timeout-slash-bps 10000.0)
          :else              slash-mult)   ; L1 generic catch uses legacy slash-mult

        ;; Phase D: Slashing reason (first matching mechanism wins)
        slash-reason
        (if slashed-detected?
          (cond
            fraud-detected?    :fraud
            reversal-detected? :reversal
            l2-slashed?        :fraud     ; L2 backstop is fraud escalation
            timeout-detected?  :timeout
            :else              :timeout)  ; L1 generic catch → classified as timeout
          nil)

        total-bond-slashing
        (econ/calculate-slashing-loss (+ appeal-bond resolver-bond)
                                      effective-slash-multiplier)
        bond-loss (if slashed-detected? total-bond-slashing 0)

        ;; Phase G: Slashing delay handling
        slashing-pending? (and slashed-detected? (> slashing-detection-delay-weeks 0))
        delay-weeks       (if slashing-pending? slashing-detection-delay-weeks 0)

        ;; Phase H: Realistic bond mechanics
        frozen? (and slashed-detected? freeze-on-detection?)

        ;; Timeline: T0 freeze → T0+freeze-duration can request unstake
        ;;           → T0+freeze+appeal-window slash executes
        ;;           → T0+freeze+appeal-window+unstaking-delay full withdrawal
        ;; Escape only possible if unstaking-delay < freeze-duration + appeal-window
        can-escape?        (and frozen?
                                (< unstaking-delay-days
                                   (+ freeze-duration-days appeal-window-days)))
        escaped?           (if frozen? (not can-escape?) false)

        effective-bond-loss
        (if (and slashed-detected? frozen? (not escaped?))
          bond-loss
          (if slashing-pending? 0 bond-loss))

        ;; MC-1/MC-SEQ: Escrow-diversion upside.
        ;; A malicious resolver who is NOT caught may redirect the escrow to a colluding
        ;; party. The gain is the escrow minus the fee already counted in profit-honest.
        ;; Legacy mode (fraud-model :single-stage-ev) uses scalar fraud-success-rate.
        ;; Sequential mode models survival through multi-tier appeal/escalation.
        ;; :strict-all-tiers assumes appeals are always pursued — models worst case for protocol.
        selected-assumptions (get escalation-assumptions escalation-assumption-band
                                  (get econ/default-escalation-assumptions :base))
        effective-has-kleros? (if (some? has-kleros?) has-kleros?
                                  (:has-kleros? selected-assumptions true))
        sequential-survival-prob
        (econ/fraud-survival-probability
         {:p-appeal-wrong  (or p-appeal-wrong appeal-prob-wrong (:p-appeal-wrong selected-assumptions))
          :p-l1-reversal   (or p-l1-reversal (:p-l1-reversal selected-assumptions))
          :has-kleros?     effective-has-kleros?
          :p-l2-escalation (or p-l2-escalation (:p-l2-escalation selected-assumptions))
          :p-l2-reversal   (or p-l2-reversal (:p-l2-reversal selected-assumptions))})
        fraud-success-prob
        (case fraud-model
          :sequential-escalation sequential-survival-prob
          :strict-all-tiers (* (- 1.0 (double (or p-l1-reversal (:p-l1-reversal selected-assumptions))))
                               (- 1.0 (double (or p-l2-reversal (:p-l2-reversal selected-assumptions)))))
          fraud-success-rate)
        fraud-upside
        (if (and (= strategy :malicious)
                 (not slashed-detected?)
                 (pos? fraud-success-prob))
          (long (* (- escrow-wei fee) fraud-success-prob))
          0)

        ;; MC-4: Appeal-bond recovery for honest resolvers.
        ;; When an appeal fails (verdict was correct), the challenger loses their bond.
        ;; The protocol may return a fraction to the resolver. Disabled by default.
        appeal-recovery
        (if (and model-appeal-costs? appealed? verdict-correct?)
          (long (* appeal-bond appeal-bond-recovery-rate))
          0)

        profit-honest (long (+ fee appeal-recovery))
        profit-malice (long (+ (- fee effective-bond-loss) fraud-upside))

        ;; MC-3: Slash distribution (insurance/protocol/burned split).
        ;; Only populated when the resolver is slashed.
        slash-distributed
        (when slashed-detected?
          (payoffs/calculate-slashing-distribution bond-loss 0))]

    {:dispute-correct?      verdict-correct?
     :appeal-triggered?     appealed?
     :l2-detected?          l2-slashed?
     :escalated?            escalated?
     :escalation-level      escalation-level
     :slashed?              slashed-detected?
     :frozen?               frozen?
     :escaped?              escaped?
     :slashing-pending?     slashing-pending?
     :slashing-delay-weeks  delay-weeks
     :slashing-reason       slash-reason
     :profit-honest         profit-honest
     :profit-malice         profit-malice
     :fraud-upside          fraud-upside
     :fraud-survival-prob   fraud-success-prob  ; probability that fraud outcome survives escalation
     :slash-distributed     slash-distributed
     :strategy              strategy}))

(defn multiple-disputes
  "Run N consecutive disputes with same parameters.

   Returns aggregated statistics including Phase B escalation metrics."
  [rng n-trials escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob]

  (let [results (repeatedly n-trials
                  #(resolve-dispute rng escrow-wei fee-bps bond-bps slash-mult
                                    strategy appeal-prob-correct appeal-prob-wrong
                                    detection-prob))
        profits-honest    (map :profit-honest results)
        profits-malice    (map :profit-malice results)
        mean-honest       (double (/ (reduce + profits-honest) n-trials))
        mean-malice       (double (/ (reduce + profits-malice) n-trials))
        appeal-count      (count (filter :appeal-triggered? results))
        slash-count       (count (filter :slashed? results))
        escalation-count  (count (filter :escalated? results))
        l2-count          (count (filter #(= (:escalation-level %) 2) results))]

    {:n-trials           n-trials
     :mean-profit-honest mean-honest
     :mean-profit-malice mean-malice
     :appeal-rate        (double (/ appeal-count n-trials))
     :slash-rate         (double (/ slash-count n-trials))
     :escalation-rate    (double (/ escalation-count n-trials))
     :l2-escalation-rate (double (/ l2-count n-trials))
     :honest-wins        (count (filter #(> (:profit-honest %) (:profit-malice %)) results))}))
