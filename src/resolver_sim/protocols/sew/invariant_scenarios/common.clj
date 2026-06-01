(ns resolver-sim.protocols.sew.invariant-scenarios.common
  "Shared protocol-param sets for invariant scenario definitions.")

;; ---------------------------------------------------------------------------
;; Shared protocol-param sets
;; ---------------------------------------------------------------------------

(def dr3
  {:resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 2592000})

(def dr3-module
  {:resolver-fee-bps 150 :resolution-module "0xresolver"
   :appeal-window-duration 0 :max-dispute-duration 2592000})

(def ieo
  {:resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 2592000})

(def ieo-timeout
  {:resolver-fee-bps 0 :appeal-window-duration 0 :max-dispute-duration 300})

(def timeout
  {:resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 300})

(def stake-cascade
  ;; Zero fee so AFA = amount; short timeout for quick testing.
  ;; resolver-bond-bps=0 skips the creation-time stake-capacity guard,
  ;; letting us register stake separately and observe it deplete under slashing.
  {:resolver-fee-bps 0 :max-dispute-duration 300
   :dispute-resolver "0xresolver" :resolver-bond-bps 0})

(def appeal
  {:resolver-fee-bps 150 :appeal-window-duration 120 :max-dispute-duration 600})

(def appeal-60
  {:resolver-fee-bps 150 :appeal-window-duration 60 :max-dispute-duration 2592000})

(def kleros
  {:resolver-fee-bps 150
   :resolution-module "0xkleros-proxy"
   :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}
   :appeal-window-duration 0
   :max-dispute-duration 2592000})

(def kleros-appeal
  {:resolver-fee-bps 150
   :resolution-module "0xkleros-proxy"
   :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}
   :appeal-window-duration 60
   :max-dispute-duration 2592000})

(def scenario-author "@grifma")
