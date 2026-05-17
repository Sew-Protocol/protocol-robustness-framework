(ns resolver-sim.protocols.registry
  "Central protocol registry for framework entrypoints.

   NOTE: This namespace is a transition point while decoupling framework code
   from concrete protocols."
  (:require [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.dummy :as dummy]))

(def ^:private protocol-registry
  {"sew-v1" sew/protocol
   "dummy"  dummy/protocol})

(def ^:private invariant-runners
  {"sew-v1" 'resolver-sim.protocols.sew.invariant-runner/run-and-report})

(def default-protocol-id
  "sew-v1")

(defn known-protocol-ids []
  (keys protocol-registry))

(defn get-protocol [protocol-id]
  (get protocol-registry protocol-id))

(defn get-invariant-runner [protocol-id]
  (get invariant-runners protocol-id))
