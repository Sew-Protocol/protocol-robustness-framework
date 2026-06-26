(ns resolver-sim.protocols.sew.governance-gates-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]))

(def alice {:id "alice" :type "honest" :address "0xAlice"})
(def resolver {:id "resolver" :type "honest" :address "0xResolver"})

(def base-scenario
  {:scenario-id "gov-gates-test"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [alice resolver]
   :protocol-params {:resolver-fee-bps 50}
   :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
             :params {:token "0xUSDC" :to "0xBob" :amount 10000}}]})

(deftest test-governance-full-mode
  (testing "Governance actions proceed normally in :full mode"
    (let [scenario (-> base-scenario
                       (assoc-in [:protocol-params :governance-mode] :full)
                       (assoc :events
                              [{:seq 0 :time 1000 :agent "resolver" :action "set-paused" :params {:paused? true}}]))
          result (sew/replay-with-sew-protocol scenario)]
      (is (= :pass (:outcome result)))
      (is (true? (get-in result [:world :paused?]))))))
