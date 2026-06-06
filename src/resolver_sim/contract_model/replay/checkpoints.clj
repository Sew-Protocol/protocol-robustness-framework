(ns resolver-sim.contract-model.replay.checkpoints
  "World-checkpoint retention for replay results.

   Checkpoints are captured for every event during replay but may be trimmed
   before results are returned. SPE fork replay only needs pre-decision worlds
   at strategic action seqs.")

(def ^:private strategic-checkpoint-actions
  #{"raise_dispute" "escalate_dispute" "execute_resolution"})

(defn decision-node-seqs
  "Return the set of trace `:seq` values at strategic decision actions."
  [trace]
  (into #{}
        (keep (fn [entry]
                (when (contains? strategic-checkpoint-actions (:action entry))
                  (:seq entry)))
              trace)))

(defn apply-checkpoint-policy
  "Trim `:world-checkpoints` on a replay result according to `policy`.

   Policies:
   - `:retain-all` — keep every captured checkpoint (debug / fork tooling)
   - `:decision-nodes-only` — keep only pre-decision worlds at strategic seqs
   - `:omit` — drop all checkpoints (library-style / memory-sensitive replay)"
  [policy trace checkpoints]
  (let [cps (or checkpoints {})]
    (case policy
      :retain-all           cps
      :omit                 {}
      :decision-nodes-only  (select-keys cps (decision-node-seqs trace))
      cps)))

(defn apply-checkpoint-policy-to-result
  "Update `result` with checkpoint retention applied."
  [policy result]
  (update result :world-checkpoints
          #(apply-checkpoint-policy policy (:trace result) %)))
