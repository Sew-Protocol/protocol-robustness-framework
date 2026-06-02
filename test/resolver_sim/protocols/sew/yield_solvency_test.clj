(ns resolver-sim.protocols.sew.yield-solvency-test
  "Solvency accounting for yield-bearing resolver stakes vs escrow shortfall deferrals."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.modules.liquid-lending :as liquid]))

(def ^:private module (liquid/make-liquid-lending-module :aave-v3))

(defn- resolver-world-with-unwinding-stake []
  (-> (t/empty-world 1000)
      (assoc-in [:total-held :USDC] 10000)
      (assoc-in [:resolver-stakes "0xR"] 10000)
      (assoc-in [:total-principal-deposited :USDC] 10000)
      (assoc-in [:yield/indices :aave-v3 :USDC] 2.0)
      (assoc-in [:yield/risk :aave-v3 :USDC]
                {:liquidity-mode :available :failure-modes #{:partial-liquidity}})
      (assoc-in [:yield/positions "resolver:0xR"]
                {:owner/id "resolver:0xR"
                 :module/id :aave-v3
                 :token :USDC
                 :principal 10000
                 :shares 10000
                 :entry-index 1.0
                 :status :active
                 :unrealized-yield 10000
                 :realized-yield 0})))

(deftest solvency-holds-after-resolver-yield-partial-withdraw
  (let [world (liquid/withdraw (resolver-world-with-unwinding-stake)
                               module
                               {:owner/id "resolver:0xR"})]
    (is (= :unwinding (get-in world [:yield/positions "resolver:0xR" :status])))
    (is (pos? (get-in world [:yield/positions "resolver:0xR" :shortfall :deferred-amount] 0)))
    (is (:holds? (inv/solvency-holds? world nil))
        "deferred resolver yield must not double-count against resolver-stakes")))

(deftest solvency-still-counts-escrow-yield-deferred
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:total-held :USDC] 500)
                  (assoc-in [:escrow-transfers 0]
                            {:token :USDC
                             :amount-after-fee 1000
                             :escrow-state :released
                             :from "0xA"
                             :to "0xB"})
                  (assoc-in [:yield/positions [:sew/escrow 0]]
                            {:token :USDC
                             :status :unwinding
                             :unrealized-yield 0
                             :realized-yield 0
                             :shortfall {:deferred-amount 500}}))]
    (is (:holds? (inv/solvency-holds? world nil))
        "escrow deferred remainder remains a yield-held liability after finalize")))
