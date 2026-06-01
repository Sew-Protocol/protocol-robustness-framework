(ns resolver-sim.oracle.detection
  "Fraud detection and penalty mechanisms.
   
   The Oracle is responsible for:
   1. Detecting fraud/reversal/timeout based on detection probability
   2. Applying penalties (slashing) based on detected violations
   3. Enforcing penalties with delay and freeze mechanics
   
   Pass :rng (SplittableRandom) in params for reproducible detection rolls."
  (:require [resolver-sim.stochastic.rng :as rng]))

(defprotocol Oracle
  "Fraud detection and penalty application."
  
  (detect-fraud? 
    [this dispute-outcome resolution-params]
    "Detect whether fraud occurred in this dispute.
     Returns: true | false")
  
  (apply-penalties
    [this detection-result dispute-outcome resolution-params]
    "Apply slashing/freezing based on detection.
     Returns: {:slashed? bool :frozen? bool :delay-weeks int}"))

;; ============ PHASE I ORACLE (Multi-mechanism detection) ============

(deftype PhaseIOracle []
  Oracle
  
  (detect-fraud? [_ _dispute-outcome params]
    (let [fraud-det-prob (:fraud-detection-probability params 0.0)]
      (< (rng/roll-double (:rng params)) fraud-det-prob)))
  
  (apply-penalties [_ detection-result dispute-outcome params]
    ;; Determine which violation occurred and apply corresponding slash
    (if (:fraud-detected? detection-result)
      {:slashed? true
       :slash-bps (:fraud-slash-bps params 5000)
       :slashing-reason :fraud
       :frozen? (:freeze-on-detection? params true)
       :freeze-duration-days (:freeze-duration-days params 3)
       :delay-weeks (:slashing-detection-delay-weeks params 0)}
      
      (if (:reversal-detected? detection-result)
        {:slashed? true
         :slash-bps (:reversal-slash-bps params 2500)
         :slashing-reason :reversal
         :frozen? false
         :delay-weeks (:slashing-detection-delay-weeks params 0)}
        
        (if (:timeout-detected? detection-result)
          {:slashed? true
           :slash-bps (:timeout-slash-bps params 200)
           :slashing-reason :timeout
           :frozen? false
           :delay-weeks 0}
          
          {:slashed? false
           :slashing-reason nil
           :frozen? false
           :delay-weeks 0})))))

;; ============ STATIC ORACLE (Phase H baseline - no detection) ============

(deftype StaticOracle []
  Oracle
  
  (detect-fraud? [_ dispute-outcome params]
    false)  ;; Never detect
  
  (apply-penalties [_ detection-result dispute-outcome params]
    ;; No penalties applied
    {:slashed? false
     :slashing-reason nil
     :frozen? false
     :delay-weeks 0}))

;; ============ HELPER FUNCTIONS ============

(defn detect-fraud
  "Detect fraud based on multiple mechanisms.

   Mechanisms (can co-occur):
   - fraud-detection-prob: Catches attacker forcing wrong outcome
   - reversal-detection-prob: Catches appeal reversal (honest outcome restored)
   - timeout-detection-prob: Catches missed deadlines

   Pass :rng (a SplittableRandom) in params for deterministic replay.

   Returns:
   {:fraud-detected? bool
    :reversal-detected? bool
    :timeout-detected? bool}"
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
  "Calculate penalty amount from slash basis points.
   
   Parameters:
   - bond: amount at risk (wei)
   - slash-bps: basis points to slash (10000 bps = 100%)
   
   Returns: amount slashed (wei)"
  [bond slash-bps]
  
  (if (zero? slash-bps)
    0
    (long (Math/ceil (* bond (/ slash-bps 10000.0))))))

(defn apply-freeze
  "Freeze account for specified duration.
   
   Returns:
   {:frozen? bool
    :freeze-duration-days int}"
  [params]
  
  (if (:freeze-on-detection? params true)
    {:frozen? true
     :freeze-duration-days (:freeze-duration-days params 3)}
    {:frozen? false
     :freeze-duration-days 0}))

(defn calculate-escape-window
  "Calculate time window during which resolver can unstake before penalties.
   
   Phase H adds realistic mechanics:
   - Unstaking delay: 14 days (in real contracts)
   - Freeze on detection: 3 days (prevents immediate unstake)
   - Appeal window: 7 days (can appeal during this time)
   
   Escape window = unstaking delay - freeze - appeal window = 4 days
   
   Returns: number of days attacker can escape"
  [params]
  
  (let [unstaking-delay (:unstaking-delay-days params 14)
        freeze-duration (:freeze-duration-days params 3)
        appeal-window (:appeal-window-days params 7)]
    
    ;; Attacker can unstake after appeals period closes
    ;; but must do so before penalties are enforced
    (max 0 (- unstaking-delay freeze-duration appeal-window))))

(defn detection-result->penalty
  "Convert detection results to penalty structure.
   
   Parameters:
   - detection: {:fraud-detected? bool :reversal-detected? bool :timeout-detected? bool}
   - params: resolution parameters
   
   Returns:
   {:penalty-applied? bool
    :slash-reason keyword  ;; :fraud | :reversal | :timeout | nil
    :slash-bps int
    :frozen? bool}"
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
