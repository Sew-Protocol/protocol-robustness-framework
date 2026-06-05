(ns resolver-sim.stochastic.dispute
  "Dispute lifecycle and resolution mechanics."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.stochastic.detection :as detection]))

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
     2. reversal-slashed? — appeal reversed wrong verdict (stake × reversal-slash-bps, live-aligned)
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
    :slash-distributed map        ; {:insurance :protocol :retained} — nil when not slashed
    :strategy keyword}            ; Strategy used"
  [rng escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob
   & {:keys [senior-resolver-skill resolver-bond-bps resolver-stake-wei l2-detection-prob
             slashing-detection-delay-weeks allow-slashing?
             unstaking-delay-days freeze-on-detection? freeze-duration-days appeal-window-days
             detection-type timeout-detection-probability reversal-detection-probability
             fraud-detection-probability fraud-slash-bps reversal-slash-bps timeout-slash-bps
             new-evidence-probability
             fraud-success-rate fraud-model escalation-assumptions escalation-assumption-band
             p-appeal-wrong p-l1-reversal has-kleros? p-l2-escalation p-l2-reversal
             model-appeal-costs? appeal-bond-recovery-rate
             oracle-fixture oracle-mode oracle-roll-sequence oracle-roll-on-exhaustion
             fixed-or oracle-roll-trace-enabled? evidence-quality?]
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
           reversal-detection-probability 1.0
           fraud-detection-probability 0.0
           fraud-slash-bps 0
           reversal-slash-bps 0
           timeout-slash-bps 200
           new-evidence-probability 0.0
           resolver-stake-wei nil
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
           appeal-bond-recovery-rate 0.5
           oracle-roll-trace-enabled? false
           evidence-quality? false}}]

  (let [fee           (econ/calculate-fee escrow-wei fee-bps)
        appeal-bond     (econ/calculate-bond escrow-wei bond-bps)
        resolver-bond   (econ/calculate-bond escrow-wei resolver-bond-bps)
        bond-total      (+ appeal-bond resolver-bond)
        resolver-stake  (long (or resolver-stake-wei escrow-wei))

        oracle-params   (detection/prepare-oracle-params
                         {:rng rng
                          :fraud-detection-probability fraud-detection-probability
                          :timeout-detection-probability timeout-detection-probability
                          :reversal-detection-probability reversal-detection-probability
                          :l2-detection-prob l2-detection-prob
                          :fraud-slash-bps fraud-slash-bps
                          :reversal-slash-bps reversal-slash-bps
                          :timeout-slash-bps timeout-slash-bps
                          :new-evidence-probability new-evidence-probability
                          :freeze-on-detection? freeze-on-detection?
                          :freeze-duration-days freeze-duration-days
                          :appeal-window-days appeal-window-days
                          :unstaking-delay-days unstaking-delay-days
                          :slashing-detection-delay-weeks slashing-detection-delay-weeks
                          :escalation-assumptions escalation-assumptions
                          :escalation-assumption-band escalation-assumption-band
                          :p-l1-reversal p-l1-reversal
                          :p-l2-escalation p-l2-escalation
                          :p-l2-reversal p-l2-reversal
                          :has-kleros? has-kleros?
                          :oracle-fixture oracle-fixture
                          :oracle-mode oracle-mode
                          :oracle-roll-sequence oracle-roll-sequence
                          :oracle-roll-on-exhaustion oracle-roll-on-exhaustion
                          :fixed-or fixed-or
                          :oracle-roll-trace-enabled? oracle-roll-trace-enabled?
                          :evidence-quality? evidence-quality?})

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

        ;; ── Live-aligned appeal reversal (deterministic slash, stake basis) ──
        {:keys [l1-reversed? l2-escalated? l2-reversed? decision-reversed?]}
        (detection/appeal-reversal-outcome rng oracle-params
                                        {:verdict-correct? verdict-correct?
                                         :appealed? appealed?})

        reversal-slashed?
        (detection/reversal-slashed-live? oracle-params
                                       {:verdict-correct? verdict-correct?
                                        :appealed? appealed?
                                        :decision-reversed? decision-reversed?})

        reversal-pending?
        (detection/reversal-pending-live? oracle-params {:reversal-slashed? reversal-slashed?})

        ;; ── Probabilistic detection (oracle) ─────────────────────────────
        {:keys [fraud-detected? timeout-detected? l1-slashed?]}
        (detection/detect-probabilistic-violations oracle-params strategy verdict-correct? detection-prob)

        ;; Phase E1: L2 (Kleros) backstop — additional catch when case is appealed
        l2-slashed?
        (detection/l2-slashed? oracle-params
                               {:verdict-correct? verdict-correct?
                                :appealed? appealed?})

        ;; ── Escalation ──────────────────────────────────────────────────
        escalation-level
        (cond
          (or l2-slashed? l2-reversed?) 2
          l2-escalated?                 2
          (or l1-reversed? appealed?)   1
          :else                         0)

        escalated? (pos? escalation-level)

        ;; ── Slashing outcome ────────────────────────────────────────────
        slashed-detected?
        (and allow-slashing?
             (or l1-slashed? fraud-detected? reversal-slashed?
                 l2-slashed? timeout-detected?))

        slash-reason
        (when slashed-detected?
          (detection/select-slash-reason {:fraud-detected? fraud-detected?
                                       :reversal-slashed? reversal-slashed?
                                       :l2-slashed? l2-slashed?
                                       :timeout-detected? timeout-detected?
                                       :l1-slashed? l1-slashed?}))

        bond-loss
        (if slashed-detected?
          (detection/slash-amount-for-reason slash-reason oracle-params
                                          {:bond-total bond-total
                                           :resolver-stake resolver-stake
                                           :slash-mult slash-mult
                                           :timeout-detected? timeout-detected?})
          0)

        ;; Phase G / Track 2: delayed slashing (detection delay or new-evidence pending)
        slashing-pending? (and slashed-detected?
                             (or reversal-pending?
                                 (> slashing-detection-delay-weeks 0)))
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
     :oracle-roll-trace     (when oracle-roll-trace-enabled?
                              @(:oracle-roll-trace oracle-params))
     :oracle-fixture/exhausted?
     (boolean (when-let [a (:oracle-fixture/exhausted? oracle-params)] @a))
     :oracle-fixture/warnings
     (detection/collect-oracle-fixture-warnings
      oracle-params {:evidence-quality? evidence-quality?})
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
