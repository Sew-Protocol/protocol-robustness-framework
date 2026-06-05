(ns resolver-sim.stochastic.detection
  "Pure detection and penalty helpers for the Monte Carlo dispute model.

   Live-aligned reversal slashing (deterministic on appeal outcome, stake basis)
   and probabilistic fraud/timeout detection. Used by stochastic/dispute and
   re-exported from oracle/detection for sim-layer adapters."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.economics.payoffs :as payoffs]))

(def oracle-detection-roll-kinds
  "Roll kinds routed through oracle fixtures when :scope contains :detection."
  #{:fraud-detection :timeout-detection :reversal-detection :l1-detection
    :l2-detection :pending-evidence})

(def oracle-appeal-roll-kinds
  "Roll kinds routed through oracle fixtures when :scope contains :appeal."
  #{:l1-reversal :l2-escalation :l2-reversal})

(def oracle-fixture-roll-kinds
  (into oracle-detection-roll-kinds oracle-appeal-roll-kinds))

(def ^:private supported-oracle-scope-tags
  #{:detection :appeal})

;; Back-compat alias for internal references
(def ^:private detection-roll-kinds oracle-detection-roll-kinds)

(def oracle-roll-consumption-order
  "Order of oracle-roll-event calls in resolve-dispute for one trial (when paths fire).

   Appeal scope (:appeal in :scope):
   1. :l1-reversal       — appeal-reversal-outcome (wrong verdict, appealed)
   2. :l2-escalation     — appeal-reversal-outcome (L1 did not reverse, Kleros on)
   3. :l2-reversal       — appeal-reversal-outcome (after L2 escalation)

   Detection scope (:detection in :scope):
   4. :reversal-detection — reversal-slashed-live? (MC oracle; not replay.clj)
   5. :pending-evidence   — reversal-pending-live?
   6. :fraud-detection    — detect-probabilistic-violations
   7. :timeout-detection  — detect-probabilistic-violations
   8. :l1-detection       — detect-probabilistic-violations
   9. :l2-detection       — l2-slashed?

   Verdict correctness and whether an appeal is filed still use trial :rng.
   Prefer per-kind :rolls maps over a shared vector."
   [:l1-reversal :l2-escalation :l2-reversal
    :reversal-detection :pending-evidence
    :fraud-detection :timeout-detection :l1-detection :l2-detection])

(def default-slash-bps
  "Canonical defaults for slash-bps parameter fallbacks.
   Matches default-params in types.clj."
  {:fraud   0
   :reversal 0
   :timeout 200})

(defn- normalize-oracle-mode
  "Mode aliases. :fixed-or is shorthand for :fixed-roll-sequence."
  [mode]
  (if (= mode :fixed-or) :fixed-roll-sequence mode))

(defn- fixture-from-fixed-or
  "Build a partial :oracle-fixture map from top-level :fixed-or shorthand.

   Accepts:
   - vector → {:mode :fixed-roll-sequence :rolls <vector>}
   - map    → {:mode :fixed-roll-sequence ...} (mode :fixed-or is normalized)"
  [fixed-or]
  (when fixed-or
    (cond
      (vector? fixed-or)
      {:mode :fixed-roll-sequence :rolls fixed-or}

      (map? fixed-or)
      (let [m (if (:mode fixed-or)
                (update fixed-or :mode normalize-oracle-mode)
                (assoc fixed-or :mode :fixed-roll-sequence))]
        (update m :mode normalize-oracle-mode))

      :else
      (throw (ex-info "Invalid :fixed-or — use a roll vector or fixture map"
                      {:fixed-or fixed-or})))))

(defn normalize-oracle-fixture
  "Normalize legacy flat oracle fixture params into canonical nested shape.

   Canonical shape:
   {:mode :stochastic|:static-no-slash|:static-always-detect|:fixed-roll-sequence
    :rolls [0.1 0.9 ...]                  ; fixed mode
    :scope #{:detection}                  ; default detection-only
    :on-exhaustion :throw|:repeat-last|:cycle}

   :on-exhaustion is MC-only (stochastic/dispute). replay.clj does not consume it.
   For replay-comparable evidence, prefer :throw with fully specified :rolls.

   Flat aliases (normalized internally):
   - :oracle-mode :fixed-or               → :fixed-roll-sequence
   - :fixed-or [0.99 0.01]                → fixed-roll fixture
   - :fixed-or {:rolls {...} :scope ...}  → fixed-roll fixture"
  [params]
  (let [legacy-mode (some-> (:oracle-mode params) normalize-oracle-mode)
        legacy-rolls (:oracle-roll-sequence params)
        legacy-on-exhaustion (:oracle-roll-on-exhaustion params)
        fixture (merge (:oracle-fixture params {})
                     (fixture-from-fixed-or (:fixed-or params)))
         mode (normalize-oracle-mode (or (:mode fixture) legacy-mode :stochastic))
        scope (or (:scope fixture) #{:detection})
        on-exhaustion (or (:on-exhaustion fixture) legacy-on-exhaustion :throw)
        on-unknown-roll-kind (or (:on-unknown-roll-kind fixture) :throw)
        rolls (or (:rolls fixture) legacy-rolls [])]
    {:mode mode
     :scope scope
     :on-exhaustion on-exhaustion
     :on-unknown-roll-kind on-unknown-roll-kind
     :rolls rolls}))

(defn- orphan-legacy-oracle-keys
  "Top-level legacy keys that are ignored given the effective normalized fixture."
  [params effective]
  (let [mode (:mode effective)
        fixed-mode? (= mode :fixed-roll-sequence)]
    (cond-> []
      (and (seq (:oracle-roll-sequence params))
           (not fixed-mode?))
      (conj :oracle-roll-sequence)

      (and (:oracle-mode params)
           (:oracle-fixture params)
           (:mode (:oracle-fixture params))
           (not= (normalize-oracle-mode (:mode (:oracle-fixture params)))
                 (normalize-oracle-mode (:oracle-mode params))))
      (conj :oracle-mode))))

(defn validate-oracle-params!
  "Validate merged oracle-fixture / :fixed-or / legacy flat keys.

   Throws ex-info on invalid :scope, unknown per-kind roll keys, empty fixed rolls,
   conflicting legacy vs nested modes, or orphan legacy keys that would be ignored.

   MC-only: live replay does not use oracle fixtures."
  [params]
  (let [effective (normalize-oracle-fixture params)
        {:keys [mode scope rolls]} effective]
    (when (or (empty? scope)
              (not (every? supported-oracle-scope-tags scope)))
      (throw (ex-info "oracle-fixture :scope must be a non-empty subset of #{:detection :appeal}"
                      {:scope scope :allowed supported-oracle-scope-tags})))
    (when (map? rolls)
      (doseq [[k v] rolls]
        (when-not (or (= k :default)
                      (contains? oracle-fixture-roll-kinds k))
          (throw (ex-info "Unknown oracle-fixture :rolls key"
                          {:roll-kind k
                           :allowed (conj (vec oracle-fixture-roll-kinds) :default)})))
        (when (empty? v)
          (throw (ex-info "oracle-fixture :rolls sequence must be non-empty"
                          {:roll-kind k})))
        (doseq [val v]
          (when (not (<= 0.0 (double val) 1.0))
            (throw (ex-info "oracle-fixture per-kind roll value out of [0,1] range"
                            {:roll-kind k :roll-value val}))))))
    (when (and (= mode :fixed-roll-sequence) (vector? rolls) (empty? rolls))
      (throw (ex-info "oracle-fixture :fixed-roll-sequence requires non-empty :rolls"
                      {:rolls rolls})))
    (when (and (= mode :fixed-roll-sequence) (vector? rolls) (seq rolls))
      (doseq [val rolls]
        (when (not (<= 0.0 (double val) 1.0))
          (throw (ex-info "oracle-fixture roll value out of [0,1] range"
                          {:roll-value val})))))
    (when-let [orphans (seq (orphan-legacy-oracle-keys params effective))]
      (throw (ex-info "Orphan oracle legacy keys ignored by effective fixture"
                      {:orphan-keys orphans
                       :effective-mode mode
                       :hint (str "Remove " orphans " or align :oracle-fixture :mode")})))
    (assoc params :oracle-effective effective)))

(defn oracle-roll-in-scope?
  "True when roll-kind is controlled by the fixture for the given :scope set."
  [scope roll-kind]
  (cond
    (contains? oracle-detection-roll-kinds roll-kind)
    (contains? scope :detection)

    (contains? oracle-appeal-roll-kinds roll-kind)
    (contains? scope :appeal)

    :else false))

(defn- in-oracle-scope?
  [scope roll-kind]
  (oracle-roll-in-scope? scope roll-kind))

(defn prepare-oracle-params
  "Attach :oracle-effective (when absent), fresh roll cursors, and optional trace atom.

   Call after validate-oracle-params! / validate-scenario for REPL and trial runners."
  [params]
  (let [effective (or (:oracle-effective params)
                      (normalize-oracle-fixture params))]
    (cond-> (assoc params
                   :oracle-effective effective
                   :oracle-roll-cursor (atom 0)
                   :oracle-roll-cursors (atom {})
                   :oracle-fixture/exhausted? (atom false))
      (and (:oracle-roll-trace-enabled? params)
           (nil? (:oracle-roll-trace params)))
      (assoc :oracle-roll-trace (atom [])))))

(defn collect-oracle-fixture-warnings
  "Warnings for MC oracle fixture configuration. Not used by replay.clj.

   opts:
     :evidence-quality? — when true (benchmark/regression artifacts), exhausted
     :repeat-last is :error; configured-but-not-exhausted :repeat-last is :info."
  [params & [{:keys [evidence-quality?]}]]
  (let [effective (or (:oracle-effective params)
                      (normalize-oracle-fixture params))
        policy (:on-exhaustion effective :throw)
        exhausted? (boolean (when-let [a (:oracle-fixture/exhausted? params)] @a))]
    (cond-> []
      (= policy :repeat-last)
      (conj {:level (cond
                      (and evidence-quality? exhausted?) :error
                      exhausted? :warning
                      :else :info)
             :code :oracle-fixture-repeat-last
             :message
             (str "Oracle fixture uses :on-exhaustion :repeat-last (MC-only; "
                  "not consumed by replay.clj)."
                  (when exhausted?
                    " Roll sequence exhausted; subsequent values repeat the last scripted roll.")
                  (when (and evidence-quality? (not exhausted?))
                    " Evidence-quality run: prefer :throw unless exploratory."))})

      (and (= policy :cycle) exhausted?)
      (conj {:level :info
             :code :oracle-fixture-cycle-exhausted
             :message
             "Oracle roll sequence exhausted; subsequent values cycle (MC-only; not replay)."}))))

(defn roll-detect?
  "True when uniform roll value is below threshold (Bernoulli with rate ≈ threshold)."
  [roll-value threshold]
  (< (double roll-value) (double threshold)))

(defn normalize-detection-probabilities
  "Normalize legacy detection keys into a single map for live stochastic code.

   slashing-detection-prob is the positional :detection-prob from resolve-dispute
   (generic L1 catch rate for :malicious)."
  [params slashing-detection-prob]
  (let [l1-malicious (double (or slashing-detection-prob
                                 (:slashing-detection-probability params)
                                 0.1))]
    {     :fraud (double (or (:fraud-detection-probability params)
                        l1-malicious
                        0.0))
     :timeout (double (:timeout-detection-probability params 0.0))
     :reversal (double (:reversal-detection-probability params 1.0))
     :l1-honest (double (:l1-honest-detection-probability params 0.01))
     :l1-lazy (double (:l1-lazy-detection-probability params 0.02))
     :l1-malicious l1-malicious
     :l1-collusive (double (:l1-collusive-detection-probability params 0.05))
     :l1-unknown (double (:l1-unknown-strategy-detection-probability params 0.0))}))

(defn- l1-base-detection-prob
  [strategy probs]
  (case strategy
    :honest (:l1-honest probs)
    :lazy (:l1-lazy probs)
    :malicious (:l1-malicious probs)
    :collusive (:l1-collusive probs)
    (:l1-unknown probs 0.0)))

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

(defn- mark-fixture-exhausted!
  [params on-exhaustion]
  (when-let [flag (:oracle-fixture/exhausted? params)]
    (reset! flag true))
  params)

(defn- trace-decision!
  [params roll-event threshold detected?]
  (when (trace-enabled? params)
    (append-roll-trace!
     params
     (merge (select-keys roll-event #{:roll/kind :roll/source :roll/value
                                       :roll/index :roll/count :roll/exhausted?
                                       :roll/on-exhaustion :roll/repeated-index
                                       :roll/cycled-index})
            {:threshold (double threshold)
             :detected? (boolean detected?)}))))

(defn- make-roll-event
  [roll-kind source value & [extra]]
  (merge {:roll/kind roll-kind
          :roll/source source
          :roll/value (double value)}
         extra))

(defn- fixed-roll-event
  "Build a fixed-roll-sequence event; marks exhaustion when index >= count."
  [params roll-kind rolls* i n on-exhaustion]
  (cond
    (< i n)
    (make-roll-event roll-kind :fixed-roll-sequence (nth rolls* i)
                     {:roll/index i :roll/count n})

    (= on-exhaustion :throw)
    (throw (ex-info "Oracle fixed roll sequence exhausted"
                    {:roll-kind roll-kind
                     :mode :fixed-roll-sequence
                     :on-exhaustion on-exhaustion
                     :requested-index i
                     :roll-count n}))

    (= on-exhaustion :repeat-last)
    (let [last-idx (dec n)]
      (mark-fixture-exhausted! params on-exhaustion)
      (make-roll-event roll-kind :fixed-roll-sequence (nth rolls* last-idx)
                       {:roll/index i
                        :roll/count n
                        :roll/exhausted? true
                        :roll/on-exhaustion on-exhaustion
                        :roll/repeated-index last-idx}))

    (= on-exhaustion :cycle)
    (let [cidx (mod i n)]
      (mark-fixture-exhausted! params on-exhaustion)
      (make-roll-event roll-kind :fixed-roll-sequence (nth rolls* cidx)
                       {:roll/index i
                        :roll/count n
                        :roll/exhausted? true
                        :roll/on-exhaustion on-exhaustion
                        :roll/cycled-index cidx}))

    :else
    (throw (ex-info "Unsupported oracle fixture exhaustion policy"
                    {:roll-kind roll-kind
                     :mode :fixed-roll-sequence
                     :on-exhaustion on-exhaustion}))))

(defn oracle-roll-event
  "Return a roll event map for one oracle draw.

   Fixed-roll events may include :roll/index, :roll/count, :roll/exhausted?,
   :roll/on-exhaustion, :roll/repeated-index, or :roll/cycled-index when the
   sequence is past its end (:repeat-last / :cycle). MC-only — replay.clj ignores these."
  [params roll-kind]
  (let [{:keys [mode scope on-exhaustion on-unknown-roll-kind rolls]}
        (or (:oracle-effective params)
            (normalize-oracle-fixture params))
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
              n (count rolls*)]
          (if (zero? n)
            (case on-unknown-roll-kind
              :throw
              (throw (ex-info "Empty per-kind :rolls sequence for :fixed-roll-sequence"
                              {:roll-kind roll-kind :mode mode :rolls rolls
                               :on-unknown-roll-kind on-unknown-roll-kind}))
              :stochastic
              (make-roll-event roll-kind :stochastic (rng/roll-double (:rng params)))
              (throw (ex-info "Unsupported :on-unknown-roll-kind policy"
                              {:roll-kind roll-kind
                               :on-unknown-roll-kind on-unknown-roll-kind
                               :supported #{:throw :stochastic}})))
            (let [i (dec idx)]
              (fixed-roll-event params roll-kind rolls* i n on-exhaustion))))

        :stochastic
        (make-roll-event roll-kind :stochastic (rng/roll-double (:rng params)))

        (throw (ex-info "Unsupported oracle fixture mode"
                        {:roll-kind roll-kind :mode mode}))))))

(defn oracle-roll
  "Return one deterministic-or-stochastic roll for oracle/detection decisions.

   roll-kind is metadata for diagnostics and future scope branching."
  [params roll-kind]
  (:roll/value (oracle-roll-event params roll-kind)))

(defn- appeal-threshold-roll
  [rng params roll-kind threshold]
  (let [roll-event (oracle-roll-event params roll-kind)
        detected? (roll-detect? (:roll/value roll-event) threshold)]
    (trace-decision! params roll-event threshold detected?)
    detected?))

(defn appeal-reversal-outcome
  "Sample whether an appealed wrong verdict is reversed at L1 and/or L2.

   Uses :p-l1-reversal, :p-l2-escalation, :p-l2-reversal (or escalation-assumptions band).
   When :scope contains :appeal, those draws use oracle-roll-event (fixed or static modes).
   Otherwise draws use trial :rng. Returns {:l1-reversed? :l2-escalated? :l2-reversed?
   :decision-reversed?}."
  [rng params {:keys [verdict-correct? appealed?]}]
  (let [band-assumptions (get (:escalation-assumptions params)
                              (:escalation-assumption-band params :base))
        p-l1 (double (or (:p-l1-reversal params) (:p-l1-reversal band-assumptions 0.75)))
        p-l2-esc (double (or (:p-l2-escalation params) (:p-l2-escalation band-assumptions 0.55)))
        p-l2-rev (double (or (:p-l2-reversal params) (:p-l2-reversal band-assumptions 0.88)))
        scope (:scope (:oracle-effective params)
                       (:scope (normalize-oracle-fixture params) #{:detection}))
        has-kleros? (if (some? (:has-kleros? params))
                      (:has-kleros? params)
                      (:has-kleros? band-assumptions true))
        wrong-and-appealed? (and (not verdict-correct?) appealed?)
        l1-reversed?
        (and wrong-and-appealed?
             (if (oracle-roll-in-scope? scope :l1-reversal)
               (appeal-threshold-roll rng params :l1-reversal p-l1)
               (roll-detect? (rng/next-double rng) p-l1)))
        l2-escalated?
        (and wrong-and-appealed?
             (not l1-reversed?)
             has-kleros?
             (if (oracle-roll-in-scope? scope :l2-escalation)
               (appeal-threshold-roll rng params :l2-escalation p-l2-esc)
               (roll-detect? (rng/next-double rng) p-l2-esc)))
        l2-reversed?
        (and l2-escalated?
             (if (oracle-roll-in-scope? scope :l2-reversal)
               (appeal-threshold-roll rng params :l2-reversal p-l2-rev)
               (roll-detect? (rng/next-double rng) p-l2-rev)))
        decision-reversed? (or l1-reversed? l2-reversed?)]
    {:l1-reversed? l1-reversed?
     :l2-escalated? l2-escalated?
     :l2-reversed? l2-reversed?
     :decision-reversed? decision-reversed?}))

(defn reversal-slashed-live?
  "Monte Carlo reversal-slash model: appeal overturns a wrong verdict and oracle
   detection succeeds (oracle-roll-event, including fixture exhaustion policy).

   Not used by replay.clj protocol replay; on-chain replay uses deterministic
   reversal slashing in protocols/sew/resolution.

   Defaults :reversal-detection-probability to 1.0 to preserve historic
   deterministic behavior unless explicitly lowered."
  [params {:keys [verdict-correct? appealed? decision-reversed?]}]
  (let [threshold (:reversal-detection-probability params 1.0)]
    (if (and (not verdict-correct?)
             appealed?
             decision-reversed?
             (pos? (:reversal-slash-bps params 0)))
      (let [roll-event (oracle-roll-event params :reversal-detection)
            detected? (roll-detect? (:roll/value roll-event) threshold)]
        (trace-decision! params roll-event threshold detected?)
        detected?)
      false)))

(defn reversal-pending-live?
  "Monte Carlo pending-slash model: new evidence after reversal slash (oracle-roll-event).

   Not used by replay.clj; see protocols/sew/resolution for deterministic Track 2."
  [params {:keys [reversal-slashed?]}]
  (let [threshold (:new-evidence-probability params 0)]
    (if (and reversal-slashed? (pos? threshold))
      (let [roll-event (oracle-roll-event params :pending-evidence)
            detected? (roll-detect? (:roll/value roll-event) threshold)]
        (trace-decision! params roll-event threshold detected?)
        detected?)
      false)))

(defn l2-slashed?
  "L2 (Kleros) backstop detection when case is appealed with wrong verdict."
  [params {:keys [verdict-correct? appealed?]}]
  (let [threshold (:l2-detection-prob params 0)]
    (if (and appealed? (not verdict-correct?) (pos? threshold))
      (let [roll-event (oracle-roll-event params :l2-detection)
            detected? (roll-detect? (:roll/value roll-event) threshold)]
        (trace-decision! params roll-event threshold detected?)
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
            (let [roll-event (oracle-roll-event params :fraud-detection)
                  detected? (roll-detect? (:roll/value roll-event) (:fraud probs))]
              (trace-decision! params roll-event (:fraud probs) detected?)
              detected?)
            false)
          timeout-detected?
          (if (and (> (:timeout probs) 0)
                   (or (= strategy :lazy) (= strategy :malicious)))
            (let [roll-event (oracle-roll-event params :timeout-detection)
                  detected? (roll-detect? (:roll/value roll-event) (:timeout probs))]
              (trace-decision! params roll-event (:timeout probs) detected?)
              detected?)
            false)
          l1-slashed?
          (if (not verdict-correct?)
            (let [roll-event (oracle-roll-event params :l1-detection)
                  detected? (roll-detect? (:roll/value roll-event) base-detection-prob)]
              (trace-decision! params roll-event base-detection-prob detected?)
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
    :reversal (payoffs/calculate-reversal-slash resolver-stake (:reversal-slash-bps params (:reversal default-slash-bps)))
    :fraud    (long (* bond-total (/ (:fraud-slash-bps params (:fraud default-slash-bps)) 10000.0)))
    :timeout  (if timeout-detected?
                (long (* bond-total (/ (:timeout-slash-bps params (:timeout default-slash-bps)) 10000.0)))
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
        fraud-detected? (roll-detect? (:roll/value fraud-roll) (:fraud probs))
        reversal-roll (oracle-roll-event params :reversal-detection)
        reversal-detected? (roll-detect? (:roll/value reversal-roll) (:reversal probs))
        timeout-roll (oracle-roll-event params :timeout-detection)
        timeout-detected? (roll-detect? (:roll/value timeout-roll) (:timeout probs))]
    (trace-decision! params fraud-roll (:fraud probs) fraud-detected?)
    (trace-decision! params reversal-roll (:reversal probs) reversal-detected?)
    (trace-decision! params timeout-roll (:timeout probs) timeout-detected?)
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
