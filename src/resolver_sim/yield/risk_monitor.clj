(ns resolver-sim.yield.risk-monitor
  "Systemic risk event accumulation using with-attribution.

   Captures short-circuit events and liquidity-cap hits from the yield engine's
   attribution context, enabling post-simulation queries like \"which modules
   hit the recoverable-liquidity-cap, and what was the total deferred amount?\""
  (:require [resolver-sim.util.attribution :as attr]))

(def ^:private ^:dynamic *risk-events* (atom []))

(defn clear!
  "Clear accumulated risk events between simulations."
  []
  (clojure.core/reset! *risk-events* []))


(defn events
  "Return accumulated risk events as a vector of maps."
  []
  @*risk-events*)


(defn summary
  "Aggregate risk events by short-circuit type.
   Returns {:short-circuit-type {:count N :total-deferred N ...}}."
  []
  (let [events @*risk-events*]
    (reduce (fn [acc e]
              (let [sc (first (:short-circuits e))
                    row (get acc sc {:count 0 :total-deferred 0 :total-delta 0 :modules #{}})
                    deferred (get e :deferred-delta 0)]
                (assoc acc sc
                       (-> row
                           (update :count inc)
                           (update :total-deferred + (max 0 deferred))
                           (update :total-delta + (:yield-delta e 0))
                           (update :modules conj (:module-id e))
                           (update :samples (fnil conj []) (select-keys e [:ts :module-id :yield-delta :deferred-delta]))))))
            {}
            events)))


(defn capture-if-risk-event
  "Check the current attribution context for yield short circuits. If a
   risk-relevant short circuit is present, record it in *risk-events*.

   Should be called inside a with-attribution block (e.g., inside
   apply-accrual-decision-with-attribution) where *attribution*
   contains :accrual/short-circuits and related keys."
  []
  (let [attr* attr/*attribution*
        short-circuits (:accrual/short-circuits attr*)]
    (when (and (seq short-circuits)
               (some #{:recoverable-liquidity-cap
                       :negative-yield-floor-breached
                       :max-index-delta-capped
                       :module-frozen-zero-accrual}
                     short-circuits))
      (swap! *risk-events* conj
             {:ts (or (:accrual/now attr*)
                      (:replay/event-time attr*)
                      (:replay/block-time attr*)
                      0)
              :short-circuits short-circuits
              :accrual-mode (:accrual/accrual-mode attr*)
              :yield-delta (:accrual/yield-delta attr*)
              :deferred-delta (:accrual/deferred-delta attr* 0)
              :module-id (:accrual/module-id attr*)
              :token (:accrual/token attr*)
              :position-id (:accrual/position-id attr*)}))))
