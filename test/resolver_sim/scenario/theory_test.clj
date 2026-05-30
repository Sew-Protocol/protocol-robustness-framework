(ns resolver-sim.scenario.theory-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.theory :as theory]))

(deftest test-suite-theory-engine
  (let [metrics {:m1 10 :m2 20}
        result {:metrics metrics :protocol (sew/->SewProtocol) :terminal-world {:total-held {:USDC 1000}} :trace [] :events [] :states {}}]
    (testing "Result contains canonical context envelope"
      (let [res (theory/evaluate-theory result {:falsifies-if [{:metric :m1 :op :< :value 5}]})]
        (is (= :not-falsified (:status res)))
        (is (some? (:evidence res)))))

    (testing "Inconclusive reason when metrics missing"
      (let [result-missing {:metrics {:m1 10} :protocol (sew/->SewProtocol) :terminal-world {}}
            theory {:falsifies-if [{:metric :m99 :op :> :value 5}]}
            res (theory/evaluate-theory result-missing theory)]
        (is (= :inconclusive (:status res)))
        (is (= :metrics-missing-in-trace (:reason res)))))

    (testing "Always predicate"
      (let [trace [{:metrics {:m1 5}} {:metrics {:m1 10}}]
            result-trace {:metrics metrics :protocol (sew/->SewProtocol) :terminal-world {} :trace trace}
            theory {:falsifies-if {:always {:metric :m1 :op :> :value 2}}}
            res (theory/evaluate-theory result-trace theory)]
        (is (= :falsified (:status res)))))

    (testing "Legacy flat list"
      (let [theory {:falsifies-if [{:metric :m1 :op :> :value 5}]}
            res (theory/evaluate-theory result theory)]
        (is (= :not-falsified (:status res)))))

    (testing "State predicate"
      (let [theory {:falsifies-if {:state {:query [:party/net-position {:party "buyer" :token :USDC}] :op :>= :value 10}}}
            res (theory/evaluate-theory result theory)]
        (is (= :not-falsified (:status res)))))))
