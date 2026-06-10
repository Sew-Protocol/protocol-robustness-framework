(ns resolver-sim.stochastic.types
  "Parameter schemas and types for the Sew Protocol dispute resolution simulation."
  (:require [resolver-sim.stochastic.detection :as detection]))

;; Scenario configuration schema
(def scenario-schema
  {:description string?
   :scenario-id string?
   :rng-seed integer?
   :escrow-distribution map?
   :strategy-mix (fn [m] (and (map? m)
                               (every? #(>= (get m % 0) 0) [:honest :lazy :malicious :collusive])
                               (let [sum (reduce + (vals m))]
                                 (or (== sum 1.0) (== sum 1)))))
   :resolver-fee-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :appeal-bond-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :resolver-bond-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :slash-multiplier (fn [x] (and (number? x) (>= x 0)))  ; 0 = no slashing (DR1)
   :appeal-probability-if-correct (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :appeal-probability-if-wrong (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :slashing-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :fraud-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :fraud-slash-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :l2-slash-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :reversal-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :reversal-slash-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
    :timeout-slash-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
    :l1-honest-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
    :l1-lazy-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
    :l1-collusive-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
    :l1-unknown-strategy-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
    :oracle-fixture (fn [m]
                     (and (map? m)
                          (contains? #{:stochastic :static-no-slash :static-always-detect
                                       :fixed-roll-sequence :fixed-or}
                                     (:mode m :stochastic))
                         (or (nil? (:rolls m))
                             (vector? (:rolls m))
                             (and (map? (:rolls m))
                                  (every? (fn [[k v]]
                                            (and (keyword? k)
                                                 (vector? v)
                                                 (every? number? v)))
                                          (:rolls m))))
                          (or (nil? (:scope m)) (set? (:scope m)))
                          (or (nil? (:on-exhaustion m))
                              (contains? #{:throw :repeat-last :cycle}
                                         (:on-exhaustion m)))
                          (or (nil? (:on-unknown-roll-kind m))
                              (contains? #{:throw :stochastic}
                                         (:on-unknown-roll-kind m)))))
   :oracle-mode (fn [x] (contains? #{:stochastic :static-no-slash :static-always-detect
                                   :fixed-roll-sequence :fixed-or} x))
   :fixed-or (fn [x]
               (or (vector? x)
                   (and (map? x)
                        (or (nil? (:mode x))
                            (contains? #{:fixed-or :fixed-roll-sequence} (:mode x)))
                        (or (nil? (:rolls x))
                            (vector? (:rolls x))
                            (and (map? (:rolls x))
                                 (every? (fn [[_ v]] (vector? v)) (:rolls x)))))))
   :oracle-roll-sequence vector?
   :oracle-roll-on-exhaustion (fn [x] (contains? #{:throw :repeat-last :cycle} x))
   :oracle-roll-trace-enabled? boolean?
   :evidence-quality? boolean?
   :fraud-model (fn [x] (contains? #{:single-stage-ev :sequential-escalation :strict-all-tiers} x))
   :escalation-assumption-band (fn [x] (contains? #{:conservative :base :optimistic} x))
   :p-appeal-wrong (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :p-l1-reversal (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :p-l2-escalation (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :p-l2-reversal (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :n-trials (fn [x] (and (integer? x) (> x 0)))
   :n-seeds (fn [x] (and (integer? x) (> x 0)))
    :parallelism (fn [x] (or (keyword? x) (integer? x)))
    :new-evidence-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
    :l2-detection-prob (fn [x] (and (number? x) (>= x 0) (<= x 1)))
    :detection-type (fn [x] (contains? #{:fraud :timeout :reversal} x))
    :timeout-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))})

;; Trial outcome record
(defrecord TrialOutcome
  [resolver-strategy
   dispute-correct?
   appeal-triggered?
   slashed?
   honest-profit
   malice-profit
   fee-earned
   slashing-loss])

;; Batch aggregation record
(defrecord BatchSummary
  [n-trials
   mean-honest
   mean-malice
   std-honest
   std-malice
   appeal-rate
   slash-rate
   honest-wins-fraction
   collusion-success-rate])

;; Run metadata record
(defrecord RunMetadata
  [scenario-id
   git-commit
   git-dirty?
   jvm-version
   timestamp
   seed
   params])

;; Defaults
(def default-params
  {:resolver-bond-bps 1000         ; DR3: 10% bond (DR1=0, DR2=500)
   :panel-size 3
   :majority-ratio (/ 2.0 3.0)
   :appeal-threshold 0.6
   :fraud-detection-probability 0.0              ; Phase I: fraud detection disabled by default
   :fraud-slash-bps 0                           ; Phase I: fraud slashing disabled (0 bps)
   :reversal-detection-probability 1.0          ; Keep historical deterministic reversal behavior
   :reversal-slash-bps 0                        ; Phase I: reversal slashing disabled (0 bps)
   :timeout-slash-bps 200                       ; Phase I: timeout penalty (2% = 200 bps, from contracts)
   :l1-honest-detection-probability 0.01        ; L1 false-positive catch rate for honest resolvers
   :l1-lazy-detection-probability 0.02          ; L1 catch rate for lazy resolvers
   :l1-collusive-detection-probability 0.05     ; L1 catch rate for collusive resolvers
   :l1-unknown-strategy-detection-probability 0.0 ; L1 catch rate for unrecognized strategies
   :fraud-model :single-stage-ev                ; legacy default for backward compatibility
   :escalation-assumption-band :base            ; for :sequential-escalation mode
   :p-appeal-wrong 0.40
   :p-l1-reversal 0.75
   :p-l2-escalation 0.55
   :p-l2-reversal 0.88
   :n-trials 1000
   :n-seeds 1
   :parallelism :auto
   :slashing-detection-delay-weeks 0      ; Phase G: delay before slashing hits
   :force-strategy nil                    ; Phase G: override strategy-mix (for control baselines)
   :allow-slashing? true                  ; Phase G: if false, never slash (control baseline)
   :unstaking-delay-days 14               ; Phase H: days to unstake (RESOLVER_UNBOND_DELAY)
   :freeze-on-detection? true             ; Phase H: immediate freeze when detected?
   :freeze-duration-days 3                ; Phase H: 72 hours freeze duration
   :appeal-window-days 7                  ; Phase H: days before slash executes
   :detection-type :fraud                 ; Phase H: :fraud (explicit), :timeout (automatic), :reversal (on appeal)
   :timeout-detection-probability 0.0     ; Phase H: detection on appeal (separate from fraud)
   :oracle-fixture {:mode :stochastic}    ; Stochastic default oracle behavior
   :oracle-roll-trace-enabled? false
   :evidence-quality? false})

;; Schema keys that are optional — present in default-params or phase-specific EDN files,
;; but not required in every scenario map. Add new optional keys here rather than inline.
(def optional-schema-keys
  #{:panel-size
    :majority-ratio
    :appeal-threshold
    :sweep-params
    :attacker-extra-capital-multiplier
    :resolver-bond-bps
    :slashing-detection-delay-weeks
    :force-strategy
    :allow-slashing?
    :unstaking-delay-days
    :freeze-on-detection?
    :freeze-duration-days
    :appeal-window-days
    :detection-type
    :timeout-detection-probability
    :reversal-detection-probability
    :fraud-detection-probability
    :fraud-slash-bps
    :l2-slash-bps
    :reversal-slash-bps
    :timeout-slash-bps
    :fraud-model
    :escalation-assumption-band
    :escalation-assumptions
    :p-appeal-wrong
    :p-l1-reversal
    :p-l2-escalation
    :p-l2-reversal
    :resolver-stake-wei
    :new-evidence-probability
    :ring-spec
    :l2-detection-prob
    :senior-resolver-skill
    :oracle-fixture
    :oracle-mode
    :oracle-roll-sequence
    :oracle-roll-on-exhaustion
    :fixed-or
    :oracle-roll-trace-enabled?
    :oracle-effective
    ;; Optional author metadata
    :author
    :author-id
    :evidence-quality?
    :l1-honest-detection-probability
    :l1-lazy-detection-probability
    :l1-collusive-detection-probability
    :l1-unknown-strategy-detection-probability})

(defn validate-scenario
  "Validate scenario params against schema. Throws if invalid."
  [scenario]
  (doseq [[k validator] scenario-schema]
    (if-let [v (get scenario k)]
      (when-not (validator v)
        (throw (ex-info (format "Invalid param %s: %s" k v) {:param k :value v})))
      (when-not (optional-schema-keys k)
        (throw (ex-info (format "Missing required param %s" k) {:param k})))))
  (detection/validate-oracle-params! scenario))
