(ns resolver-sim.yield.evidence
  "Extract explainable yield evidence for trace annotations."
  (:require [resolver-sim.yield.market-state :as market-state]))

(defn- extract-position-evidence [oid pos ms]
  {:owner-id (:owner/id pos)
   :module-id (:module/id pos)
   :token (:token pos)
   :principal (:principal pos)
   :shares (:shares pos)
   :entry-index (:entry-index pos)
   :current-index (:current-index pos)
   :current-value (:current-value pos)
   :unrealized-yield (:unrealized-yield pos 0)
   :realized-yield (:realized-yield pos 0)
   :shortfall (when-let [sf (:shortfall pos)]
                {:kind (:reason sf)
                 :basis (:basis-amount sf)
                 :fulfilled (:fulfilled-amount sf)
                 :deferred (:deferred-amount sf)
                 :haircut (:haircut-amount sf)})
   :market-state {:available-ratio (:available-ratio ms)
                  :apy (:apy ms)}})

(defn get-evidence
  "Return a summary of yield state for evidence/trace purposes."
  [world]
  (let [positions (:yield/positions world {})]
    (into {} (map (fn [[oid pos]]
                    (let [ms (market-state/get-market-state world (:module/id pos) (:token pos) (:block-time world))]
                      [oid (extract-position-evidence oid pos ms)]))
                  positions))))

(defn canonical-yield-evidence
  "Produces a canonical yield evidence summary for projection."
  [m]
  m)
