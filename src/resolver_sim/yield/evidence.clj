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


(defn emit-shortfall-event
  "Record a shortfall lifecycle event in world state.
   Returns updated world with event appended to :yield/events."
  [world event-type position-id event-data]
  (update world :yield/events (fnil conj [])
          (merge {:event/type event-type
                  :event/time (:block-time world 0)
                  :position/id position-id}
                 event-data)))

(defn sum-recognized-losses
  "Sum all recognized principal losses for a token across all positions."
  [world token]
  (let [positions (:yield/positions world {})
        losses (keep (fn [[_ pos]]
                       (when-let [sf (:shortfall pos)]
                         (when (#{:principal-loss :negative-carry-loss} (:reason sf))
                           (:haircut-amount sf 0))))
                     positions)]
    (reduce + 0 losses)))

(defn canonical-yield-evidence
  "Produces a canonical yield evidence summary for projection."
  [m]
  m)
