(ns resolver-sim.protocols.sew.yield.invariants
  "Sew-specific invariants for yield-bearing escrows."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.invariants :as yield-inv]))

(defn- sew-live-position?
  [world oid pos]
  (if (vector? oid)
    (let [[owner-type escrow-id] oid]
      (and (= owner-type :sew/escrow)
           (contains? t/live-states (:escrow-state (t/get-transfer world escrow-id)))))
      ;; For resolver stakes, we consider them always live if the status is active
    (t/resolver-yield-owner-id? oid)))

(defn check-sew-yield-exposure
  "Check that the protocol has enough funds in held to cover all escrow principal and realized yield."
  [world]
  (yield-inv/check-yield-exposure
   world
   (partial sew-live-position? world)
   #(get-in world [:total-held %] 0)))

(defn check-all [world]
  {:sew/yield-exposure (check-sew-yield-exposure world)})
