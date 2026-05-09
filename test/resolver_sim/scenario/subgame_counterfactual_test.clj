(ns resolver-sim.scenario.subgame-counterfactual-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [resolver-sim.scenario.subgame-counterfactual :as cf]))

(defn -main
  [& _]
  (run-tests 'resolver-sim.scenario.subgame-counterfactual-test))

(deftest evaluate-subgame-counterfactual-basic-pass
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:bond-balances {"e1" {"buyer" 50}}}}
                                {:world {:claimable {"e1" {"buyer" 150}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out (cf/evaluate-subgame-counterfactual projection)]
    (is (= :pass (:status out)))
    (is (= 0 (:max-regret out)))
    (is (= 1 (:checked-nodes out)))
    (is (= {:mode :trace-following :version "v1" :invalid-trace-action :mark-inconclusive}
           (:continuation-policy out)))
    (is (= {:type :terminal-realized-v1 :version "v1" :undefined-policy :inconclusive}
           (:utility-spec out)))
    (is (= :preserve (get-in out [:replay-boundary :ordering-mode])))))

(deftest evaluate-subgame-counterfactual-basic-fail-and-deterministic
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:bond-balances {"e1" {"buyer" 50}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out1 (cf/evaluate-subgame-counterfactual projection)
        out2 (cf/evaluate-subgame-counterfactual projection)]
    (is (= :fail (:status out1)))
    (is (= 50 (:max-regret out1)))
    (is (= (:regret-table out1) (:regret-table out2)))
    (is (= (:continuation-policy out1) (:continuation-policy out2)))
    (is (= (:replay-boundary out1) (:replay-boundary out2)))
    (is (= (:utility-spec out1) (:utility-spec out2)))))

(deftest evaluate-subgame-counterfactual-phase-a-config-overrides
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:claimable {"e1" {"buyer" 10}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 1
                                 :continuation-policy {:mode :policy-response :version "v1-custom"}
                                 :replay-boundary {:ordering-mode :reopen :exogenous-events :modeled}
                                 :utility-spec {:type :terminal-realized-v1 :version "v1-custom"}}}
        out (cf/evaluate-subgame-counterfactual projection)]
    (is (= :policy-response (get-in out [:continuation-policy :mode])))
    (is (= "v1-custom" (get-in out [:continuation-policy :version])))
    (is (= :reopen (get-in out [:replay-boundary :ordering-mode])))
    (is (= :modeled (get-in out [:replay-boundary :exogenous-events])))
    (is (= "v1-custom" (get-in out [:utility-spec :version])))))

(deftest evaluate-subgame-counterfactual-phase-b-information-set-and-classification
  (let [projection {:raw-trace [{:world {:block-time 1000
                                         :pending-count 1
                                         :live-states {"e1" :disputed}
                                         :dispute-levels {"e1" 0}
                                         :claimable {"e1" {"buyer" 0}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "unknown_action"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out (cf/evaluate-subgame-counterfactual projection)
        row (first (:regret-table out))]
    (is (= :inconclusive (:status out)))
    (is (= 1 (get-in out [:class-counts :inapplicable-node-type])))
    (is (= :inapplicable-node-type (:classification row)))
    (is (= "unknown_action" (get-in row [:information-set :decision-action])))))

(deftest evaluate-subgame-counterfactual-phase-c-epsilon-and-bounded-alternatives
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 100}}}}
                                {:world {:claimable {"e1" {"buyer" 90}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 100
                                 :max-alternatives-per-node 1
                                 :epsilon-abs 5.0
                                 :epsilon-rel 0.0}}
        out (cf/evaluate-subgame-counterfactual projection)
        row (first (:regret-table out))]
    (is (= 1 (count (:alternatives row))))
    (is (= :fail (:status out)))
    (is (number? (:mean-regret out)))
    (is (= 1 (:exceed-epsilon-count out)))
    (is (= 1 (get-in out [:regret-distribution :positive])))))

(deftest evaluate-subgame-counterfactual-phase-e-memoization-and-timing-variants
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    ;; duplicate nodes to force in-evaluation cache hits
                    :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}
                                {:seq 1 :agent "buyer" :action "raise_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 100
                                 :max-alternatives-per-node 7
                                 :enable-timing-variants? true
                                 :enable-exogenous-variants? true}}
        out (cf/evaluate-subgame-counterfactual projection)
        row (first (:regret-table out))]
    (is (= true (get-in out [:memoization :enabled])))
    (is (pos? (get-in out [:memoization :entries])))
    (is (pos? (get-in out [:memoization :hits])))
    (is (some #({"wait_same_block" "wait_next_block"} %) (:alternatives row)))
    (is (some #({"hold_exogenous_fixed" "allow_exogenous_shift"} %) (:alternatives row)))))
