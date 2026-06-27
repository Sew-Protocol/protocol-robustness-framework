(ns dev.scratch
  (:require
    [dev.scenarios :as scenarios]))


(tap> (scenarios/run-scenario :S18))
(scenarios/run-yield-shortfall-demo)
(scenarios/run-baseline)

