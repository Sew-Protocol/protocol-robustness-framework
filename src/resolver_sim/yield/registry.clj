(ns resolver-sim.yield.registry
  "Registry of available yield modules."
  (:require [resolver-sim.yield.modules.aave :as aave]
            [resolver-sim.yield.modules.fixed :as fixed]
            [resolver-sim.yield.modules.none :as none]
            [resolver-sim.yield.modules.adversarial :as adversarial]))

(def default-modules
  {:aave-v3 aave/aave-v3-module
   :fixed-rate fixed/fixed-rate-module
   :none none/zero-yield-module
   :adversarial adversarial/adversarial-yield-module})

(defn register-module [world module]
  (assoc-in world [:yield/modules (:module/id module)] module))

(defn init-yield-modules [world]
  (reduce register-module world (vals default-modules)))

(defn- normalize-module-id [module-id]
  (cond
    (keyword? module-id) module-id
    (string? module-id)  (keyword module-id)
    :else                module-id))

(defn- normalize-token-config [{:keys [initial-index initial-index-ray apy apy-bps
                                       liquidity-mode loss-mode rate-mode]
                                :as cfg}]
  {:initial-index     (or initial-index
                          (when initial-index-ray
                            (/ (bigdec initial-index-ray)
                               1000000000000000000000000000M))
                          1.0)
   :apy               (or apy
                          (when apy-bps (/ apy-bps 10000.0))
                          0.0)
   :liquidity-mode    (or liquidity-mode :available)
   :loss-mode         (or loss-mode :none)
   :rate-mode         (or rate-mode :scenario)
   :raw/config        cfg})

(defn apply-yield-config
  "Apply scenario-level yield configuration to an initialized world.

   Expected DSL shape:

   Example shape:
   modules -> module id -> tokens -> token symbol -> config map.

   Also accepts :initial-index-ray and :apy-bps for integer/fixed-point-facing
   fixture definitions. This config initializes the existing generic yield DSL
   world paths rather than introducing a second test-only API."
  [world yield-config]
  (reduce-kv
   (fn [w module-id module-config]
     (let [mid (normalize-module-id module-id)]
       (let [w* (if-let [status (:module-status module-config)]
                  (assoc-in w [:yield/module-status mid] status)
                  w)]
         (reduce-kv
          (fn [w' token token-config]
            (let [{:keys [initial-index apy liquidity-mode loss-mode rate-mode]}
                  (normalize-token-config token-config)]
              (-> w'
                  (assoc-in [:yield/indices mid token] initial-index)
                  (assoc-in [:yield/rates mid token] apy)
                  (assoc-in [:yield/risk mid token]
                            {:liquidity-mode liquidity-mode
                             :loss-mode      loss-mode
                             :rate-mode      rate-mode}))))
          w*
          (:tokens module-config {})))))
   world
   (:modules yield-config {})))
