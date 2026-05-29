(ns resolver-sim.scenario.theory-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.theory :as theory]))

(deftest test-eval-predicate
  (let [metrics {:m1 10 :m2 20}
        result {:metrics metrics :protocol (sew/->SewProtocol) :terminal-world {}}]
    (testing "Legacy flat list"
      (let [theory {:falsifies-if [{:metric :m1 :op :> :value 5}]}
            res (theory/evaluate-theory result theory)]
        (is (= :not-falsified (:status res)))))
        
    (testing "AND predicate"
      (let [theory {:falsifies-if {:and [{:metric :m1 :op :> :value 5}
                                        {:metric :m2 :op :< :value 30}]}}
            res (theory/evaluate-theory result theory)]
        (is (= :not-falsified (:status res)))))

    (testing "State predicate"
      (let [theory {:falsifies-if {:state {:query [:party/net-position {:party "buyer"}] :op :>= :value 10}}}
            res (theory/evaluate-theory result theory)]
        (is (= :not-falsified (:status res)))))
    
    (testing "State predicate falsified"
      (let [theory {:falsifies-if {:state {:query [:party/net-position {:party "buyer"}] :op :>= :value 20}}}
            res (theory/evaluate-theory result theory)]
        (is (= :falsified (:status res)))))
        
    (testing "AND predicate falsified"
      (let [theory {:falsifies-if {:and [{:metric :m1 :op :> :value 5}
                                        {:metric :m2 :op :< :value 15}]}}
            result (theory/evaluate-theory {:metrics metrics} theory)]
        (is (= :falsified (:status result)))))

    (testing "OR predicate falsified"
      (let [theory {:falsifies-if {:or [{:metric :m1 :op :> :value 20}
                                       {:metric :m2 :op :< :value 15}]}}
            result (theory/evaluate-theory {:metrics metrics} theory)]
        (is (= :not-falsified (:status result)))))

    (testing "OR predicate triggered"
      (let [theory {:falsifies-if {:or [{:metric :m1 :op :> :value 5}
                                       {:metric :m2 :op :< :value 15}]}}
            result (theory/evaluate-theory {:metrics metrics} theory)]
        (is (= :falsified (:status result)))))

    (testing "NOT predicate"
      (let [theory {:falsifies-if {:not {:metric :m1 :op :> :value 20}}}
            result (theory/evaluate-theory {:metrics metrics} theory)]
        (is (= :falsified (:status result)))))

    (testing "IMPLIES predicate (False => True = True)"
      (let [theory {:falsifies-if {:implies {:if {:metric :m1 :op :> :value 20}
                                            :then {:metric :m2 :op :< :value 30}}}}
            result (theory/evaluate-theory {:metrics metrics} theory)]
        (is (= :not-falsified (:status result)))))

    (testing "IMPLIES predicate (True => False = False/Falsified)"
      (let [theory {:falsifies-if {:implies {:if {:metric :m1 :op :< :value 20}
                                            :then {:metric :m2 :op :> :value 30}}}}
            result (theory/evaluate-theory {:metrics metrics} theory)]
        (is (= :falsified (:status result)))))))
