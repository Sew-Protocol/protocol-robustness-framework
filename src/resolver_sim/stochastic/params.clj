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
    (:resolver-fee-bps snap)        (assoc :fee-bps (:resolver-fee-bps snap))
    (:appeal-bond-bps snap)         (assoc :bond-bps (:appeal-bond-bps snap))
    (:fraud-slash-bps snap)         (assoc :fraud-slash-bps (:fraud-slash-bps snap))
    (:reversal-slash-bps snap)      (assoc :reversal-slash-bps (:reversal-slash-bps snap))
    (:timeout-slash-bps snap)       (assoc :timeout-slash-bps (:timeout-slash-bps snap))
    (:resolver-bond-bps snap)       (assoc :resolver-bond-bps (:resolver-bond-bps snap))
    (:l2-detection-prob snap)       (assoc :l2-detection-prob (:l2-detection-prob snap))
    (:slashing-detection-prob snap) (assoc :detection-prob (:slashing-detection-prob snap))
    (:fraud-success-rate snap)      (assoc :fraud-success-rate (:fraud-success-rate snap))
    (:fraud-model snap)             (assoc :fraud-model (:fraud-model snap))
    (:escalation-assumption-band snap) (assoc :escalation-assumption-band (:escalation-assumption-band snap))
    (:p-appeal-wrong snap)          (assoc :p-appeal-wrong (:p-appeal-wrong snap))
    (:p-l1-reversal snap)           (assoc :p-l1-reversal (:p-l1-reversal snap))
    (some? (:has-kleros? snap))      (assoc :has-kleros? (:has-kleros? snap))
    (:p-l2-escalation snap)         (assoc :p-l2-escalation (:p-l2-escalation snap))
    (:p-l2-reversal snap)           (assoc :p-l2-reversal (:p-l2-reversal snap))
    (:fraud-detection-prob snap)    (assoc :fraud-detection-probability (:fraud-detection-prob snap))
    (:reversal-detection-prob snap) (assoc :reversal-detection-probability (:reversal-detection-prob snap))
    (:timeout-detection-prob snap)  (assoc :timeout-detection-probability (:timeout-detection-prob snap))))

(defn merge-snap
  "Merge a replay snapshot into a base MC param map.
   Snapshot fields override base fields. Fields absent from snap are kept as-is."
  [base-params snap]
  (merge base-params (from-snap snap)))
