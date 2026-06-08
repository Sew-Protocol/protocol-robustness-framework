(ns resolver-sim.yield.market-state
  "Unify schedules and policies into a composite yield market state at a specific time."
  (:require [resolver-sim.yield.schedule :as schedule]
            [resolver-sim.logging :as log]))

(def default-shortfall-model
  {:type :liquidity-only
   :recoverable true
   :loss-recognition :defer})

(def default-withdrawal-policy
  {:on-shortfall :partial-fill
   :defer-unfilled true
   :record-claimable-shortfall true})

(defn get-market-state
  "Resolve the composite market state for a module/token at a specific time.
   Reads from world paths populated by apply-yield-config."
  [world module-id token time]
  (let [resolved-mid (if module-id
                       (get-in world [:yield/module-aliases module-id] module-id)
                       nil)
        path (if resolved-mid [:yield/schedules resolved-mid token] [:yield/schedules nil])
        schedules (get-in world path)
        ;; Default APY from legacy path if no schedule exists
        default-apy (if resolved-mid
                      (or (get-in world [:yield/rates resolved-mid token]) 0.04)
                      0.04)]
    (let [schedules* (or schedules {:rate-schedule nil :index-schedule nil :liquidity-schedule nil :module-state-schedule nil})]
      (let [schedules* (or schedules {:rate-schedule nil :index-schedule nil :liquidity-schedule nil :module-state-schedule nil})]
        {:apy (try (schedule/get-value-at-time (:rate-schedule schedules*) time default-apy)
                    (catch Exception e (log/error! "apy-lookup-failed" {:time time}) default-apy))
          :index (try (schedule/get-value-at-time (:index-schedule schedules*) time nil)
                      (catch Exception e (log/error! "index-lookup-failed" {:time time}) nil))
          :available-ratio (try (schedule/get-value-at-time (:liquidity-schedule schedules*) time 1.0)
                                (catch Exception e (log/error! "liquidity-lookup-failed" {:time time}) 1.0))
          :module-state (try (schedule/get-value-at-time (:module-state-schedule schedules*) time :normal)
                             (catch Exception e (log/error! "module-state-lookup-failed" {:time time}) :normal))
         :shortfall-model (if resolved-mid
                            (or (get-in world [:yield/shortfall-models resolved-mid token])
                                default-shortfall-model)
                            default-shortfall-model)
         :withdrawal-policy (if resolved-mid
                              (or (get-in world [:yield/withdrawal-policies resolved-mid token])
                                  default-withdrawal-policy)
                              default-withdrawal-policy)})))
)
