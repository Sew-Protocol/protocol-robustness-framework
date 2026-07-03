(ns resolver-sim.protocols.sew.governance-gates-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]))

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

(deftest governance-envelope-is-normalized-for-set-paused
  (let [ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}}
        event {:seq 0 :time 1000 :agent "gov" :action "set_paused" :params {:paused? true}}
        result (sew/apply-action ctx (t/empty-world 1000) event)
        envelope (get-in result [:extra :authorization/provenance])
        mutation (last (get-in result [:world :governance-mutations]))]
    (is (:ok result))
    (is (= envelope (:authorization/provenance mutation)))
    (is (= "governance-authorization.v1" (:authorization/schema-version envelope)))
    (is (= :governance (:authorization/type envelope)))
    (is (= :scenario-declared (:authorization/basis envelope)))
    (is (= "gov" (:authorization/actor-id envelope)))
    (is (= :with-governance-actor (:authorization/check envelope)))
    (is (= :replay-context/agent-index (:authorization/source envelope)))
    (is (= "set-paused" (:authorization/action envelope)))
    (is (= "0xGov" (:authorization/address envelope)))))

(deftest activate-resolver-overflow-record-carries-normalized-envelope
  (let [ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
             :resolver-overflow-policy {:allowed-reasons #{:resolver-overcapacity}
                                        :default-max-workflows 3
                                        :default-duration 3600
                                        :failover-resolvers ["0xOverflow"]}}
        world (assoc-in (t/empty-world 1000)
                        [:resolver-capacities "0xResolver"]
                        {:current-active 1 :max-concurrent 1})
        event {:seq 0
               :time 1000
               :agent "gov"
               :action "activate_resolver_overflow"
               :params {:resolver "0xResolver"
                        :reason :resolver-overcapacity}}
        result (sew/apply-action ctx world event)
        envelope (get-in result [:extra :authorization/provenance])
        record (get-in result [:world :resolver-overflows 0])]
    (is (:ok result))
    (is (= envelope (:authorization/provenance record)))
    (is (= [{:authorization/action "activate-resolver-overflow"
             :authorization/provenance envelope}]
           (:authorization/history record)))
    (is (= :governance (:authorization/type envelope)))
    (is (= :scenario-declared (:authorization/basis envelope)))
    (is (= "gov" (:authorization/actor-id envelope)))
    (is (= :with-governance-actor (:authorization/check envelope)))
    (is (= :replay-context/agent-index (:authorization/source envelope)))))
