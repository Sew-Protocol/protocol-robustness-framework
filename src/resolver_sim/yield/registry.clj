(ns resolver-sim.yield.registry
  "[Sew-INTEGRATED] Yield module registry and dispatch.

   Registry mechanics are reusable, but current policy/module assumptions are
   integrated with Sew world/accounting semantics."
  (:require [resolver-sim.yield.modules.aave :as aave]
            [resolver-sim.yield.modules.fixed :as fixed]
            [resolver-sim.yield.modules.none :as none]
            [resolver-sim.yield.modules.adversarial :as adversarial]
            [resolver-sim.yield.providers.liquid-lending :as liquid-lending]))

(def default-behavior-descriptor
  {:yield/provider-kind :immediate-withdrawal-lending
   :yield/accrual-model :deterministic-rate
   :yield/liquidity-model :full-liquidity
   :yield/loss-model :no-loss
   :yield/withdrawal-model :instant
   :yield/reward-model :none})

(defn- archetype->module-id [archetype]
  (case archetype
    :yield.provider/liquid-lending :yield.provider/liquid-lending
    nil))

(defn- profile->archetype [profile-id]
  (case profile-id
    :aave-v3 :yield.provider/liquid-lending
    :yield.profile/aave-v3-like :yield.provider/liquid-lending
    nil))

(defn- normalize-module-id [module-id]
  (cond
    (keyword? module-id) module-id
    (string? module-id)  (keyword module-id)
    :else                module-id))

(defn resolve-yield-profile
  "Resolve a profile/module id into {:profile-id :archetype :module-id}.
   Falls back to the provided id when no explicit profile mapping is known."
  [profile-or-module-id]
  (let [profile-id (normalize-module-id profile-or-module-id)
        archetype  (or (profile->archetype profile-id)
                       (when (= profile-id :yield.provider/liquid-lending)
                         :yield.provider/liquid-lending))
        module-id  (or (archetype->module-id archetype) profile-id)]
    {:profile-id profile-id
     :archetype  archetype
     :module-id  module-id}))

(def default-modules
  {:yield.provider/liquid-lending liquid-lending/liquid-lending-module
   :aave-v3 (liquid-lending/make-module :aave-v3 :yield.profile/aave-v3-like)
   :fixed-rate fixed/fixed-rate-module
   :none none/zero-yield-module
   :adversarial adversarial/adversarial-yield-module})

(defn register-module [world module]
  (assoc-in world [:yield/modules (:module/id module)] module))

(defn init-yield-modules [world]
  (reduce register-module world (vals default-modules)))

(defn- normalize-token-config [{:keys [initial-index initial-index-ray apy apy-bps
                                       liquidity-mode loss-mode rate-mode
                                       failure-modes]
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
   :failure-modes     (set (or failure-modes #{}))
   :raw/config        cfg})

(defn- normalize-behavior-descriptor [desc]
  (merge default-behavior-descriptor (or desc {})))

(defn- normalize-module-entry [module-id module-config]
  (let [mid* (normalize-module-id module-id)
        behavior (normalize-behavior-descriptor (:behavior module-config))
        archetype (or (:provider/archetype module-config)
                      (:provider/id module-config)
                      (profile->archetype mid*)
                      (get behavior :provider/archetype))
        resolved-mid (or (archetype->module-id archetype) mid*)]
    {:module-id mid*
     :resolved-module-id resolved-mid
     :behavior behavior
     :module-status (:module-status module-config)
     :tokens (:tokens module-config {})}))

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
   (fn [w raw-module-id module-config]
     (let [{normalized-module-id :module-id
            resolved-module-id   :resolved-module-id
            behavior             :behavior
            module-status        :module-status
            tokens               :tokens}
           (normalize-module-entry raw-module-id module-config)
           w* (-> w
                  (assoc-in [:yield/behavior normalized-module-id] behavior)
                  (assoc-in [:yield/module-aliases normalized-module-id] resolved-module-id)
                  (assoc-in [:yield/module-aliases resolved-module-id] resolved-module-id))
           w* (if-let [status module-status]
                (assoc-in w* [:yield/module-status resolved-module-id] status)
                w*)]
         (reduce-kv
          (fn [w' token token-config]
            (let [{:keys [initial-index apy liquidity-mode loss-mode rate-mode failure-modes]}
                  (normalize-token-config token-config)]
              (-> w'
                  (assoc-in [:yield/indices resolved-module-id token] initial-index)
                  (assoc-in [:yield/rates resolved-module-id token] apy)
                  (assoc-in [:yield/risk resolved-module-id token]
                            {:liquidity-mode liquidity-mode
                             :loss-mode      loss-mode
                             :rate-mode      rate-mode
                             :failure-modes  failure-modes}))))
          w*
          tokens)))
   world
   (:modules yield-config {})))
