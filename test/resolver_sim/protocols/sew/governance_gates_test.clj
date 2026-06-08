(ns resolver-sim.protocols.sew.governance-gates-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.registry :as reg]))

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
    (let [scenario (assoc base-scenario :events
                          [{:seq 0 :time 1000 :agent "resolver" :action "set-paused" :params {:paused? true}}])
          result (sew/replay-with-sew-protocol scenario)]
      (is (= :pass (:outcome result)))
      (is (true? (get-in result [:world :paused?]))))))

(deftest test-governance-excluded-mode
  (testing "Governance actions are rejected in :excluded mode"
    (let [scenario (assoc base-scenario 
                          :options {:governance-mode :governance/excluded}
                          :events [{:seq 0 :time 1000 :agent "resolver" :action "set-paused" :params {:paused? true}}])
          result (sew/replay-with-sew-protocol scenario)]
      (is (= :pass (:outcome result))) ;; rejection is not a halt
      (is (= :rejected (get-in result [:trace 0 :result])))
      (is (= :governance-excluded (get-in result [:trace 0 :error])))
      (is (false? (get-in result [:world :paused?]))))))

(deftest test-governance-frozen-mode
  (testing "Mutation actions are rejected in :frozen mode, but observation/automated might proceed"
    (let [scenario (assoc base-scenario
                          :options {:governance-mode :governance/frozen}
                          :events [{:seq 0 :time 1000 :agent "resolver" :action "set-paused" :params {:paused? true}}])
          result (sew/replay-with-sew-protocol scenario)]
      (is (= :pass (:outcome result)))
      (is (= :rejected (get-in result [:trace 0 :result])))
      (is (= :governance-frozen (get-in result [:trace 0 :error]))))))

(deftest test-governance-invariants-applicability
  (testing "Governance invariants are marked :not-applicable in :excluded mode"
    (let [scenario (assoc base-scenario :options {:governance-mode :governance/excluded})
          result (sew/replay-with-sew-protocol scenario)
          violations (get-in result [:trace 0 :violations])]
      ;; Check some governance-specific invariants
      (let [res-results (get-in result [:trace 0 :world :check-all-results])]
        ;; Note: The actual results of check-all are in the trace entry
        (let [trace-entry (first (:trace result))
              inv-results (:results (replay/sew-check-invariants-single (:world trace-entry)))]
          (is (:not-applicable? (:slash-status-consistent inv-results)))
          (is (= :governance-restricted (:reason (:slash-status-consistent inv-results)))))))))
