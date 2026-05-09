(ns resolver-sim.scenario.projection-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.scenario.projection :as proj]))

(defn -main
  "Allow direct execution via: clojure -M:test -m resolver-sim.scenario.projection-test"
  [& _]
  (run-tests 'resolver-sim.scenario.projection-test))

 (defn- replay-result
   [{:keys [world metrics trace halt-reason agents]
     :or   {metrics {}
            trace   []
            agents  []}}]
   {:trace       (if (seq trace) trace [{:world world}])
    :metrics     metrics
    :halt-reason halt-reason
    :agents      agents})

 (deftest test-trace-end-projection-terminal-world
   (testing "terminal world produces terminal=true and stable terminal-state set"
     (let [result (replay-result
                   {:world {:live-states {0 :released 1 :refunded}
                            :total-held {"USDC" 0}
                            :total-fees {"USDC" 50}
                            :escrow-amounts {0 1000 1 2000}
                            :dispute-resolvers {0 "0xR" 1 "0xR"}
                            :dispute-levels {0 0 1 1}
                            :pending-count 0
                            :resolver-stakes {"0xR" 1000}
                            :block-time 1300}
                    :metrics {:disputes-triggered 1
                              :resolutions-executed 2}})
           p      (proj/trace-end-projection result)]
       (is (= true (get-in p [:terminal-world :terminal?])))
       (is (= #{:released :refunded}
              (get-in p [:terminal-world :all-terminal-states])))
       (is (= {"USDC" 0} (get-in p [:terminal-world :total-held-by-token])))
       (is (= 1300 (get-in p [:trace-summary :terminal-time]))))))

 (deftest test-trace-end-projection-open-dispute
   (testing "open dispute keeps terminal=false and preserves halt reason"
     (let [result (replay-result
                   {:world {:live-states {0 :disputed}
                            :total-held {"USDC" 9950}
                            :total-fees {"USDC" 50}
                            :pending-count 0
                            :block-time 1060}
                    :halt-reason :open-disputes-at-end
                    :metrics {:disputes-triggered 1}})
           p      (proj/trace-end-projection result)]
       (is (= false (get-in p [:terminal-world :terminal?])))
       (is (= :open-disputes-at-end (get-in p [:trace-summary :halt-reason])))
       (is (= 1 (get-in p [:trace-summary :dispute-count]))))))

 (deftest test-trace-end-projection-multi-token
   (testing "multi-token accounting maps are preserved with defaults"
     (let [result (replay-result
                   {:world {:live-states {0 :released 1 :refunded}
                            :total-held {"USDC" 0 "DAI" 0}
                            :total-fees {"USDC" 25 "DAI" 40}
                            :block-time 1400}
                    :metrics {}})
           p      (proj/trace-end-projection result)]
       (is (= {"USDC" 0 "DAI" 0} (get-in p [:terminal-world :total-held-by-token])))
       (is (= {"USDC" 25 "DAI" 40} (get-in p [:terminal-world :total-fees-by-token])))
       (is (= 0 (get-in p [:metrics :attack-successes])))
       (is (nil? (get-in p [:metrics :coalition-net-profit]))))))

 (deftest test-trace-end-projection-escalation-summary
   (testing "escalation levels are derived from escalation actions and actors are distinct"
     (let [trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                   :world {:live-states {0 :pending}
                           :total-held {"USDC" 5000}
                           :total-fees {"USDC" 0}
                           :block-time 1000}}
                  {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                   :world {:live-states {0 :disputed}
                           :total-held {"USDC" 5000}
                           :total-fees {"USDC" 0}
                           :block-time 1060}}
                  {:seq 2 :time 1120 :agent "buyer" :action "escalate_dispute"
                   :extra {:level 1}
                   :world {:live-states {0 :disputed}
                           :total-held {"USDC" 5000}
                           :total-fees {"USDC" 0}
                           :block-time 1120}}
                  {:seq 3 :time 1180 :agent "buyer" :action "escalate_dispute"
                   :extra {:level 2}
                   :world {:live-states {0 :released}
                           :total-held {"USDC" 0}
                           :total-fees {"USDC" 0}
                           :block-time 1180}}]
           p (proj/trace-end-projection (replay-result {:trace trace :metrics {:disputes-triggered 1}}))]
       (is (= #{1 2} (get-in p [:trace-summary :escalation-levels])))
       (is (= #{"buyer"} (get-in p [:trace-summary :actors])))
       (is (= 4 (get-in p [:trace-summary :events-count]))))))
