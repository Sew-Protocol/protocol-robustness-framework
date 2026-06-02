(ns resolver-sim.oracle.detection
  "Oracle protocol adapters over stochastic/detection (pure detection helpers).

   Pass :rng (SplittableRandom) in params for reproducible detection rolls."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.detection :as det]))

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

(deftype PhaseIOracle []
  Oracle

  (detect-fraud? [_ _dispute-outcome params]
    (let [fraud-det-prob (:fraud-detection-probability params 0.0)]
      (< (rng/roll-double (:rng params)) fraud-det-prob)))

  (apply-penalties [_ detection-result _dispute-outcome params]
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

(deftype StaticOracle []
  Oracle

  (detect-fraud? [_ _dispute-outcome _params]
    false)

  (apply-penalties [_ _detection-result _dispute-outcome _params]
    {:slashed? false
     :slashing-reason nil
     :frozen? false
     :delay-weeks 0}))

;; Re-export stochastic detection API for sim layers and tests
(def appeal-reversal-outcome det/appeal-reversal-outcome)
(def reversal-slashed-live? det/reversal-slashed-live?)
(def reversal-pending-live? det/reversal-pending-live?)
(def detect-probabilistic-violations det/detect-probabilistic-violations)
(def select-slash-reason det/select-slash-reason)
(def slash-amount-for-reason det/slash-amount-for-reason)
(def detect-fraud det/detect-fraud-rolls)
(def apply-slashing det/apply-slashing)
(def apply-freeze det/apply-freeze)
(def calculate-escape-window det/calculate-escape-window)
(def detection-result->penalty det/detection-result->penalty)
