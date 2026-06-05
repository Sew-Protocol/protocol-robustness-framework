(ns resolver-sim.stochastic.params
  "Param bridge: translate replay engine snapshot maps to MC param maps.

   The replay engine uses module-snapshot maps (see protocols/sew/snapshot.clj).
   The MC engine uses flat param maps (see data/params/*.edn).

   Two bridge functions serve different input sources:

     from-snap           — read a ModuleSnapshot (from make-escrow-snapshot or
                           snapshot-from-protocol-params) and extract the small
                           subset of fields that have MC equivalents.

     scenario->mc-params — read an invariant scenario map and build an MC param
                           map by merging shared fields from :protocol-params
                           with the optional :mc-params map. :mc-params wins
                           on conflict.

   Neither function produces a complete MC parameter set. Callers must merge
   the result with default-params (stochastic/types.clj) for fields not covered
   by the snapshot or scenario."

  (:require [resolver-sim.stochastic.types :as types]))

;; ── ModuleSnapshot → MC param bridge ──────────────────────────────────────────

(def snapshot->mc-keys
  "Mapping of ModuleSnapshot keys (from make-escrow-snapshot) to their MC param
   equivalents. Only fields that genuinely exist in both models are listed.
   See protocols/sew/snapshot.clj for the full ModuleSnapshot schema."
  {:escrow-fee-bps                :fee-bps
   :appeal-bond-bps               :bond-bps
   :reversal-slash-bps            :reversal-slash-bps
   :resolver-bond-bps             :resolver-bond-bps
   :reversal-detection-probability :reversal-detection-probability})

(defn from-snap
  "Translate a replay ModuleSnapshot map to MC param keys.

   Only fields that genuinely exist in a real ModuleSnapshot
   (produced by make-escrow-snapshot) are mapped. Unknown fields
   are dropped.

   Returns a map with at most 5 keys. Callers should merge this into
   their base MC param map:

     (merge default-params (params/from-snap snap))

   See snapshot->mc-keys for the full mapping."
  [snap]
  (reduce-kv (fn [m snap-key mc-key]
               (if (contains? snap snap-key)
                 (assoc m mc-key (get snap snap-key))
                 m))
             {}
             snapshot->mc-keys))

;; ── Scenario → MC param bridge ────────────────────────────────────────────────

(def protocol-params->mc-keys
  "Mapping of invariant scenario :protocol-params keys to their MC param
   equivalents. Only fields that exist in both the scenario protocol-params
   map and the MC param vocabulary are listed."
  {:resolver-fee-bps                :fee-bps
   :appeal-bond-bps                 :bond-bps
   :reversal-slash-bps              :reversal-slash-bps
   :resolver-bond-bps               :resolver-bond-bps
   :reversal-detection-probability  :reversal-detection-probability})

(defn scenario->mc-params
  "Build an MC param map from an invariant scenario map.

   Merges two sources (latter wins):

     1. Shared fields derived from :protocol-params that have MC
        equivalents (see protocol-params->mc-keys).
     2. The optional :mc-params map.

   Does NOT produce a complete MC param set. Callers must merge the
   result with default-params or a base MC param map for fields not
   covered by either source.

   Example:

     (merge types/default-params
            (params/scenario->mc-params scenario))"
  [scenario]
  (let [pp (:protocol-params scenario {})
        mc (:mc-params scenario {})
        shared (reduce-kv (fn [m pp-key mc-key]
                            (if (contains? pp pp-key)
                              (assoc m mc-key (get pp pp-key))
                              m))
                          {}
                          protocol-params->mc-keys)]
    (merge shared mc)))

(defn merge-snap
  "Merge a replay ModuleSnapshot into a base MC param map.

   Snapshot fields override base fields. Fields absent from snap
   are kept as-is. This is a convenience wrapper over from-snap.

   Example:
     (merge-snap default-params snap)"
  [base-params snap]
  (merge base-params (from-snap snap)))
