(ns resolver-sim.yield.evidence
  "Protocol-neutral builders for canonical yield evidence artifacts."
  (:require [resolver-sim.yield.registry :as yreg]))

(def default-supported-failure-modes
  #{:partial-liquidity :withdraw-fails :negative-yield
    :provider-paused :emergency-unwind-fails :deposit-fails})

(defn canonical-yield-evidence
  [{:keys [routing-by-workflow
           withdrawal-events
           recovery-events
           assumptions
           supported-failure-modes
           ;; Added forensic tracking fields
           principal
           total-held
           yield-delta
           shortfall
           entitled
           withdrawable-now]}]
  {:routing-by-workflow routing-by-workflow
   :supported-failure-modes (or supported-failure-modes default-supported-failure-modes)
   :yield/available-liquidity nil
   :yield/requested-withdrawal nil
   :yield/withdrawn-immediately nil
   :yield/deferred-amount nil
   :yield/recovery-events (or recovery-events [])
   :yield/withdrawal-events (or withdrawal-events [])
   ;; Added forensic fields to output structure
   :yield/principal principal
   :yield/total-held total-held
   :yield/yield-delta yield-delta
   :yield/shortfall shortfall
   :yield/entitled entitled
   :yield/withdrawable-now withdrawable-now
   :yield/assumptions (or assumptions
                          ["Archetype evidence is protocol-neutral; settlement semantics are protocol-specific."])})

(defn resolve-profile-archetype
  [profile-or-module-id]
  (yreg/resolve-yield-profile profile-or-module-id))
