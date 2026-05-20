(ns resolver-sim.protocols.sew.yield.invariants
  "Sew-specific invariants for yield-bearing escrows."
  (:require [resolver-sim.protocols.sew.types :as t]))

(defn check-sew-yield-exposure
  "Check that the protocol has enough funds in held to cover all escrow principal and realized yield."
  [world]
  (let [tokens (into #{} (map :token (vals (:yield/positions world {}))))
        live-states #{:pending :disputed}]
    (every? (fn [token]
              (let [held (get-in world [:total-held token] 0)
                    total-needed (reduce (fn [acc [oid pos]]
                                           ;; Only count positions owned by Sew escrows that are still live
                                           (let [[owner-type escrow-id] oid
                                                 et (t/get-transfer world escrow-id)]
                                             (if (and (= owner-type :sew/escrow)
                                                      (= (:token pos) token)
                                                      (contains? live-states (:escrow-state et))
                                                      (= (:status pos) :active))
                                               (+ acc (:principal pos 0) (:realized-yield pos 0))
                                               acc)))
                                         0
                                         (:yield/positions world {}))]
                (>= held total-needed)))
            tokens)))

(defn check-all [world]
  {:sew/yield-exposure (check-sew-yield-exposure world)})
