(ns resolver-sim.yield.market-state
  "Unify schedules and policies into a composite yield market state at a specific time."
  (:require [resolver-sim.yield.schedule :as schedule]))

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
        _ (println (str "[market-state] DEBUG: mid=" module-id ", token=" token ", resolved-mid=" resolved-mid ", path=" path ", found=" schedules))
        ;; Default APY from legacy path if no schedule exists
        default-apy (if resolved-mid
                      (or (get-in world [:yield/rates resolved-mid token]) 0.04)
                      0.04)]
    (let [schedules* (or schedules {:rate-schedule nil :index-schedule nil :liquidity-schedule nil :module-state-schedule nil})]
      (let [schedules* (or schedules {:rate-schedule nil :index-schedule nil :liquidity-schedule nil :module-state-schedule nil})]
        {:apy (try (schedule/get-value-at-time (:rate-schedule schedules*) time default-apy)
                   (catch Exception e (println (str "[market-state] ERROR: apy lookup failed: " e)) 0.0))
         :index (try (schedule/get-value-at-time (:index-schedule schedules*) time nil)
                     (catch Exception e (println (str "[market-state] ERROR: index lookup failed: " e)) nil))
         :available-ratio (try (schedule/get-value-at-time (:liquidity-schedule schedules*) time 1.0)
                               (catch Exception e (println (str "[market-state] ERROR: liquidity lookup failed: " e)) 1.0))
         :module-state (try (schedule/get-value-at-time (:module-state-schedule schedules*) time :normal)
                            (catch Exception e (println (str "[market-state] ERROR: module-state lookup failed: " e)) :normal))
         :shortfall-model (if resolved-mid
                            (or (get-in world [:yield/shortfall-models resolved-mid token])
                                default-shortfall-model)
                            default-shortfall-model)
         :withdrawal-policy (if resolved-mid
                              (or (get-in world [:yield/withdrawal-policies resolved-mid token])
                                  default-withdrawal-policy)
                              default-withdrawal-policy)})))
)
