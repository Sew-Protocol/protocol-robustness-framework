(ns resolver-sim.stochastic.params
  "Param bridge: translate replay engine snapshot maps to MC param maps.

   The replay engine uses module-snapshot maps (see protocols/sew/types.clj).
   The MC engine uses flat param maps (see data/params/*.edn).

   from-snap translates a replay snapshot into MC params so both engines
   can be driven from the same numeric inputs without copy-paste. Only
   fields that exist in both models are mapped; unknown fields are dropped.

   Snapshot keys → MC param keys
   ─────────────────────────────
   :resolver-fee-bps          → :fee-bps
   :appeal-bond-bps           → :bond-bps
   :fraud-slash-bps           → :fraud-slash-bps
   :reversal-slash-bps        → :reversal-slash-bps
   :timeout-slash-bps         → :timeout-slash-bps
   :resolver-bond-bps         → :resolver-bond-bps
   :l2-detection-prob         → :l2-detection-prob
   :slashing-detection-prob   → :detection-prob
   :fraud-success-rate        → :fraud-success-rate
   :fraud-model               → :fraud-model
   :escalation-assumption-band → :escalation-assumption-band
   :p-appeal-wrong            → :p-appeal-wrong
   :p-l1-reversal             → :p-l1-reversal
   :p-l2-escalation           → :p-l2-escalation
   :p-l2-reversal             → :p-l2-reversal
   :fraud-detection-prob      → :fraud-detection-probability
   :reversal-detection-prob   → :reversal-detection-probability
   :timeout-detection-prob    → :timeout-detection-probability")

(defn from-snap
  "Translate a replay module-snapshot map to an MC-compatible param map.

   Only known fields are translated. Callers should merge the result into
   their base MC param map so that fields not present in the snapshot
   retain their defaults.

   Example:
     (merge base-params (params/from-snap snap))"
  [snap]
  (cond-> {}
    (contains? snap :resolver-fee-bps)        (assoc :fee-bps (get snap :resolver-fee-bps))
    (contains? snap :appeal-bond-bps)         (assoc :bond-bps (get snap :appeal-bond-bps))
    (contains? snap :fraud-slash-bps)         (assoc :fraud-slash-bps (get snap :fraud-slash-bps))
    (contains? snap :reversal-slash-bps)      (assoc :reversal-slash-bps (get snap :reversal-slash-bps))
    (contains? snap :timeout-slash-bps)       (assoc :timeout-slash-bps (get snap :timeout-slash-bps))
    (contains? snap :resolver-bond-bps)       (assoc :resolver-bond-bps (get snap :resolver-bond-bps))
    (contains? snap :l2-detection-prob)       (assoc :l2-detection-prob (get snap :l2-detection-prob))
    (contains? snap :slashing-detection-prob) (assoc :detection-prob (get snap :slashing-detection-prob))
    (contains? snap :fraud-success-rate)      (assoc :fraud-success-rate (get snap :fraud-success-rate))
    (contains? snap :fraud-model)             (assoc :fraud-model (get snap :fraud-model))
    (contains? snap :escalation-assumption-band) (assoc :escalation-assumption-band (get snap :escalation-assumption-band))
    (contains? snap :p-appeal-wrong)          (assoc :p-appeal-wrong (get snap :p-appeal-wrong))
    (contains? snap :p-l1-reversal)           (assoc :p-l1-reversal (get snap :p-l1-reversal))
    (contains? snap :has-kleros?)             (assoc :has-kleros? (get snap :has-kleros?))
    (contains? snap :p-l2-escalation)         (assoc :p-l2-escalation (get snap :p-l2-escalation))
    (contains? snap :p-l2-reversal)           (assoc :p-l2-reversal (get snap :p-l2-reversal))
    (contains? snap :fraud-detection-prob)    (assoc :fraud-detection-probability (get snap :fraud-detection-prob))
    (contains? snap :reversal-detection-prob) (assoc :reversal-detection-probability (get snap :reversal-detection-prob))
    (contains? snap :timeout-detection-prob)  (assoc :timeout-detection-probability (get snap :timeout-detection-prob))))

(defn merge-snap
  "Merge a replay snapshot into a base MC param map.
   Snapshot fields override base fields. Fields absent from snap are kept as-is."
  [base-params snap]
  (merge base-params (from-snap snap)))
