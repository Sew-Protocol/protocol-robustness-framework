(ns resolver-sim.db.telemetry
  "Adapter between the contract-model runner and the eval-engine XTDB store.

   Converts run-trial / run-with-divergence-check output into the generic record
   format expected by resolver-sim.db.store and writes it to XTDB.

   Records are stored in the protocol-agnostic sim_trial_results table.
   Protocol-specific fields are serialised as a metrics_edn blob, populated via
   DisputeProtocol/io-projection with the :telemetry-record target.

   All write functions accept a datasource as their first argument.
   Passing nil is safe: writes become no-ops, enabling offline simulation
   runs and unit tests without a live XTDB instance.

   Typical usage:

     ;; With XTDB running:
     (def ds (evaluation.store/->datasource))
     (telemetry/record-trial! ds protocol batch-id trial-id params result)

     ;; Without XTDB (default, tests):
     (telemetry/record-trial! nil protocol batch-id trial-id params result)"
  (:require [resolver-sim.db.store               :as ss]
            [resolver-sim.protocols.protocol      :as engine]
            [resolver-sim.protocols.sew.db        :as sew-db])
  (:import [java.util Date UUID]))

;; ---------------------------------------------------------------------------
;; Conversion helpers (pure — no I/O)
;; ---------------------------------------------------------------------------

(defn- sim-date
  "Convert a simulated block-time long (unix seconds) to a java.util.Date.
   If block-time is nil, returns the current wall-clock time."
  [block-time]
  (if block-time
    (Date. (* ^long block-time 1000))
    (Date.)))

(defn trial->outcome-record
  "Convert a run-trial result (plus contextual metadata) into the generic map
   expected by resolver-sim.db.store/insert-trial-result!.

   Arguments:
     protocol   — a DisputeProtocol instance (used for protocol-id and metrics blob)
     trial-id   — unique string identifier for this trial
     batch-id   — string or keyword identifying the simulation batch
     params     — the params map passed to run-trial
     result     — the map returned by run-trial or run-with-divergence-check

   The returned map has:
     Top-level generic fields: :id, :batch-id, :protocol-id, :outcome,
       :invariants-ok?, :divergence?, :params, :violations, :valid-from
     :metrics blob: protocol-specific fields from io-projection :telemetry-record.

   When result is a run-with-divergence-check map (contains :contract),
   the :contract sub-map is used for trial fields and :divergence for diffs.\"
  [protocol trial-id batch-id params result]
  (let [cm    (if (contains? result :contract) (:contract result) result)
        div   (get result :divergence {})
        btime (get params :block-time 1000)]
    {:id             trial-id
     :batch-id       batch-id
     :protocol-id    (engine/protocol-id protocol)
     :outcome        (get cm :cm/final-state)
     :invariants-ok? (boolean (get cm :cm/invariants-ok? true))
     :divergence?    (boolean (get div :divergence?))
     :params         params
     :metrics        (engine/io-projection protocol result :telemetry-record)
     :violations     (get cm :cm/inv-violations)
     :valid-from     (sim-date btime)}))

(defn trial->event-records
  "Derive a sequence of sim_entity_events records from a run-trial result.

   Delegates to (engine/io-projection protocol {:trial-id :params :result} :event-records).
   The protocol is responsible for defining its own event timeline.
   Returns [] when the protocol does not support event record projection."
  [protocol trial-id params result]
  (or (engine/io-projection protocol {:trial-id trial-id :params params :result result} :event-records)
      []))

;; ---------------------------------------------------------------------------
;; Write functions (side-effecting — require XTDB datasource)
;; ---------------------------------------------------------------------------

(defn record-trial!
  "Write one trial outcome and its entity event sequence to XTDB.

   All writes are skipped when ds is nil.
   Returns the outcome record map (useful for chaining / inspection)."
  [ds protocol batch-id trial-id params result]
  (let [outcome (trial->outcome-record protocol trial-id batch-id params result)
        events  (trial->event-records  protocol trial-id params result)]
    (ss/insert-trial-result! ds outcome)
    (doseq [ev events]
      (ss/insert-entity-event! ds ev))
    outcome))

(defn record-batch!
  "Write a collection of trial results to XTDB.

   trials — sequence of maps, each with keys:
     :trial-id  — unique string (auto-generated if absent)
     :params    — the params map for this trial
     :result    — the map returned by run-trial or run-with-divergence-check

   Returns a vector of outcome record maps.
   All writes are skipped when ds is nil."
  [ds protocol batch-id trials]
  (mapv (fn [{:keys [trial-id params result]}]
          (let [tid (or trial-id (str (UUID/randomUUID)))]
            (record-trial! ds protocol batch-id tid params result)))
        trials))

;; ---------------------------------------------------------------------------
;; Query helpers (protocol-agnostic)
;; ---------------------------------------------------------------------------

(defn batch-summary
  \"Return summary statistics for a stored batch.

   Fetches all trial outcomes for batch-id from XTDB and computes
   aggregate statistics using the protocol's summarise-batch method.

   Returns {} when ds is nil.\"
  [ds protocol batch-id]
  (if (nil? ds)
    {}
    (let [outcomes (case (engine/protocol-id protocol)
                     \"sew-v1\" (sew-db/sew-trial-outcomes ds {:batch-id batch-id})
                     [])] ; Fallback for unknown protocols
      (engine/summarise-batch protocol outcomes))))
