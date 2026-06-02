(ns resolver-sim.stochastic.detection
  "Pure detection and penalty helpers for the Monte Carlo dispute model.

   Live-aligned reversal slashing (deterministic on appeal outcome, stake basis)
   and probabilistic fraud/timeout detection. Used by stochastic/dispute and
   re-exported from oracle/detection for sim-layer adapters."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.economics.payoffs :as payoffs]))

(def ^:private detection-roll-kinds
  "Roll kinds routed through oracle fixtures when :scope contains :detection."
  #{:fraud-detection :timeout-detection :reversal-detection :l1-detection
    :l2-detection :pending-evidence})

(defn normalize-oracle-fixture
  "Normalize legacy flat oracle fixture params into canonical nested shape.

   Canonical shape:
   {:mode :stochastic|:static-no-slash|:static-always-detect|:fixed-roll-sequence
    :rolls [0.1 0.9 ...]                  ; fixed mode
    :scope #{:detection}                  ; default detection-only
    :on-exhaustion :throw|:repeat-last|:cycle}"
  [params]
  (let [legacy-mode (:oracle-mode params)
        legacy-rolls (:oracle-roll-sequence params)
        legacy-on-exhaustion (:oracle-roll-on-exhaustion params)
        fixture (:oracle-fixture params)
        mode (or (:mode fixture) legacy-mode :stochastic)
        scope (or (:scope fixture) #{:detection})
        on-exhaustion (or (:on-exhaustion fixture) legacy-on-exhaustion :throw)
        rolls (or (:rolls fixture) legacy-rolls [])]
    {:mode mode
     :scope scope
     :on-exhaustion on-exhaustion
     :rolls rolls}))

(defn- in-oracle-scope?
  [scope roll-kind]
  (and (contains? scope :detection)
       (contains? detection-roll-kinds roll-kind)))

(defn normalize-detection-probabilities
  "Normalize legacy detection keys into a single map for live stochastic code.

   slashing-detection-prob is the positional :detection-prob from resolve-dispute
   (generic L1 catch rate for :malicious)."
  [params slashing-detection-prob]
  (let [l1-malicious (double (or slashing-detection-prob
                                 (:slashing-detection-probability params)
                                 0.1))]
    {:fraud (double (or (:fraud-detection-probability params)
                        l1-malicious
                        0.0))
     :timeout (double (:timeout-detection-probability params 0.0))
     :reversal (double (:reversal-detection-probability params 1.0))
     :l1-honest 0.01
     :l1-lazy 0.02
     :l1-malicious l1-malicious
     :l1-collusive 0.05}))

(defn- l1-base-detection-prob
  [strategy probs]
  (case strategy
    :honest (:l1-honest probs)
    :lazy (:l1-lazy probs)
    :malicious (:l1-malicious probs)
    :collusive (:l1-collusive probs)
    0.0))

(defn- valid-roll-seq?
  [xs]
  (and (vector? xs)
       (every? number? xs)))

(defn- resolve-roll-seq
  "Resolve roll sequence for fixed-roll mode.

   Supports:
   - vector rolls: shared global sequence
   - map rolls: per-kind sequence keyed by roll kind, optionally :default"
  [rolls roll-kind]
  (cond
    (valid-roll-seq? rolls) rolls
    (map? rolls) (or (get rolls roll-kind)
                     (get rolls :default)
                     [])
    :else []))

(defn- trace-enabled?
  [params]
  (and (:oracle-roll-trace-enabled? params)
       (some? (:oracle-roll-trace params))))

(defn- append-roll-trace!
  [params entry]
  (when (trace-enabled? params)
    (swap! (:oracle-roll-trace params) conj entry)))

(defn- trace-decision!
  [params roll-kind roll-source roll-value threshold detected?]
  (append-roll-trace!
   params
   {:roll/kind roll-kind
    :roll/source roll-source
    :roll/value (double roll-value)
    :threshold (double threshold)
    :detected? (boolean detected?)}))

(defn- make-roll-event
  [roll-kind source value]
  {:roll/kind roll-kind
   :roll/source source
   :roll/value (double value)})

(defn oracle-roll-event
  "Return a map {:roll/kind :roll/source :roll/value} for one oracle roll."
  [params roll-kind]
  (let [{:keys [mode scope on-exhaustion rolls]} (normalize-oracle-fixture params)
        in-scope? (in-oracle-scope? scope roll-kind)]
    (if (not in-scope?)
      (make-roll-event roll-kind :stochastic (rng/roll-double (:rng params)))
      (case mode
        :static-no-slash
        (make-roll-event roll-kind :static-no-slash 1.0)

        :static-always-detect
        (make-roll-event roll-kind :static-always-detect 0.0)

        :fixed-roll-sequence
        (let [rolls* (resolve-roll-seq rolls roll-kind)
              map-cursors (:oracle-roll-cursors params)
              shared-cursor (:oracle-roll-cursor params)
              idx (cond
                    (map? rolls)
                    (do
                      (when-not map-cursors
                        (throw (ex-info "Missing :oracle-roll-cursors for per-kind fixed roll sequence"
                                        {:roll-kind roll-kind :mode mode})))
                      (get (swap! map-cursors update roll-kind (fnil inc 0))
                           roll-kind))
                    :else
                    (do
                      (when-not shared-cursor
                        (throw (ex-info "Missing :oracle-roll-cursor for :fixed-roll-sequence fixture"
                                        {:roll-kind roll-kind :mode mode})))
                      (swap! shared-cursor inc)))
              i (dec idx)
              n (count rolls*)]
          (when (zero? n)
            (throw (ex-info "Empty :oracle-fixture :rolls for :fixed-roll-sequence"
                            {:roll-kind roll-kind :mode mode :rolls rolls})))
          (cond
            (< i n)
            (make-roll-event roll-kind :fixed-roll-sequence (nth rolls* i))

            (= on-exhaustion :throw)
            (throw (ex-info "Oracle fixed roll sequence exhausted"
                            {:roll-kind roll-kind
                             :mode mode
                             :on-exhaustion on-exhaustion
                             :requested-index i
                             :roll-count n}))

            (= on-exhaustion :repeat-last)
            (make-roll-event roll-kind :fixed-roll-sequence (nth rolls* (dec n)))

            (= on-exhaustion :cycle)
            (make-roll-event roll-kind :fixed-roll-sequence (nth rolls* (mod i n)))

            :else
            (throw (ex-info "Unsupported oracle fixture exhaustion policy"
                            {:roll-kind roll-kind
                             :mode mode
                             :on-exhaustion on-exhaustion}))))

        :stochastic
        (make-roll-event roll-kind :stochastic (rng/roll-double (:rng params)))

        (throw (ex-info "Unsupported oracle fixture mode"
                        {:roll-kind roll-kind :mode mode}))))))

(defn oracle-roll
  "Return one deterministic-or-stochastic roll for oracle/detection decisions.

   roll-kind is metadata for diagnostics and future scope branching."
  [params roll-kind]
  (:roll/value (oracle-roll-event params roll-kind)))

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
  "Track 1 (live replay): reversal slash when appeal overturns a wrong verdict
   and oracle detection succeeds.

   Defaults :reversal-detection-probability to 1.0 to preserve historic
   deterministic behavior unless explicitly lowered."
  [params {:keys [verdict-correct? appealed? decision-reversed?]}]
  (let [threshold (:reversal-detection-probability params 1.0)]
    (if (and (not verdict-correct?)
             appealed?
             decision-reversed?
             (pos? (:reversal-slash-bps params 0)))
      (let [{:keys [roll/source roll/value]} (oracle-roll-event params :reversal-detection)
            detected? (< value threshold)]
        (trace-decision! params :reversal-detection source value threshold detected?)
        detected?)
      false)))

(defn reversal-pending-live?
  "Track 2 (live replay): new evidence → pending slash (not counted in immediate loss)."
  [params {:keys [reversal-slashed?]}]
  (let [threshold (:new-evidence-probability params 0)]
    (if (and reversal-slashed? (pos? threshold))
      (let [{:keys [roll/source roll/value]} (oracle-roll-event params :pending-evidence)
            detected? (< value threshold)]
        (trace-decision! params :pending-evidence source value threshold detected?)
        detected?)
      false)))

(defn l2-slashed?
  "L2 (Kleros) backstop detection when case is appealed with wrong verdict."
  [params {:keys [verdict-correct? appealed?]}]
  (let [threshold (:l2-detection-prob params 0)]
    (if (and appealed? (not verdict-correct?) (pos? threshold))
      (let [{:keys [roll/source roll/value]} (oracle-roll-event params :l2-detection)
            detected? (< value threshold)]
        (trace-decision! params :l2-detection source value threshold detected?)
        detected?)
      false)))

(defn detect-probabilistic-violations
  "Fraud, timeout, and generic L1 detection rolls.

   Pass :rng in params. Returns {:fraud-detected? :timeout-detected? :l1-slashed?}."
  [params strategy verdict-correct? detection-prob]
  (let [probs (normalize-detection-probabilities params detection-prob)
        base-detection-prob (l1-base-detection-prob strategy probs)]
    (let [fraud-detected?
          (if (and (not verdict-correct?)
                   (> (:fraud probs) 0)
                   (= strategy :malicious))
            (let [{:keys [roll/source roll/value]} (oracle-roll-event params :fraud-detection)
                  detected? (< value (:fraud probs))]
              (trace-decision! params :fraud-detection source value (:fraud probs) detected?)
              detected?)
            false)
          timeout-detected?
          (if (and (> (:timeout probs) 0)
                   (or (= strategy :lazy) (= strategy :malicious)))
            (let [{:keys [roll/source roll/value]} (oracle-roll-event params :timeout-detection)
                  detected? (< value (:timeout probs))]
              (trace-decision! params :timeout-detection source value (:timeout probs) detected?)
              detected?)
            false)
          l1-slashed?
          (if (not verdict-correct?)
            (let [{:keys [roll/source roll/value]} (oracle-roll-event params :l1-detection)
                  detected? (< value base-detection-prob)]
              (trace-decision! params :l1-detection source value base-detection-prob detected?)
              detected?)
            false)]
      {:fraud-detected? fraud-detected?
       :timeout-detected? timeout-detected?
       :l1-slashed? l1-slashed?})))

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
  (let [probs (normalize-detection-probabilities params
                                                 (:slashing-detection-probability params 0.1))
        fraud-roll (oracle-roll-event params :fraud-detection)
        fraud-detected? (< (:roll/value fraud-roll) (:fraud probs))
        reversal-roll (oracle-roll-event params :reversal-detection)
        reversal-detected? (< (:roll/value reversal-roll) (:reversal probs))
        timeout-roll (oracle-roll-event params :timeout-detection)
        timeout-detected? (< (:roll/value timeout-roll) (:timeout probs))]
    (trace-decision! params :fraud-detection (:roll/source fraud-roll) (:roll/value fraud-roll) (:fraud probs) fraud-detected?)
    (trace-decision! params :reversal-detection (:roll/source reversal-roll) (:roll/value reversal-roll) (:reversal probs) reversal-detected?)
    (trace-decision! params :timeout-detection (:roll/source timeout-roll) (:roll/value timeout-roll) (:timeout probs) timeout-detected?)
    {:fraud-detected? fraud-detected?
     :reversal-detected? reversal-detected?
     :timeout-detected? timeout-detected?}))

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
