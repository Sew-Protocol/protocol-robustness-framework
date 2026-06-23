(ns resolver-sim.yield.pro-rata-claims-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.claims.engine :as claims-engine]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.yield.pro-rata-claims :as claims]))

(def phase-6-claims
  #{:projection-deterministic
    :projection-canonical-safe
    :allocation-complete
    :non-negative
    :conservation
    :rounding-bounded
    :ordering-independent})

(def representative-fixtures
  [{:slash-obligation 11
    :slash-policy {:policy/id :test-policy}
    :liable-parties [{:id :resolver-a
                      :slashable-stake 3}
                     {:id :resolver-b
                      :slashable-stake 2}
                     {:id :resolver-c
                      :slashable-stake 1}]}
   {:slash-obligation 10
    :liable-parties [{:id :resolver-a
                      :slashable-stake 5}
                     {:id :resolver-b
                      :slashable-stake 3}
                     {:id :resolver-c
                      :slashable-stake 2}]}
   {:slash-obligation 7
    :basis :custom-weight
    :cap-field :custom-cap
    :liable-parties [{:id :resolver-a
                      :custom-weight 4}
                     {:id :resolver-b
                      :custom-weight 2}
                     {:id :resolver-c
                      :custom-weight 1}]}])

(defn- build-claim-evaluation-node
  "Build a claim-evaluation evidence node from a SEW slash allocation input,
   matching the shape produced by evidence/slashing.clj."
  [allocation-input]
  (let [direct-result (sew-economics/calculate-sew-slash-allocation allocation-input)
        projection-artifact (sew-economics/build-sew-slash-projection-artifact allocation-input)
        projection-artifact-again (sew-economics/build-sew-slash-projection-artifact allocation-input)
        projection-result (sew-economics/calculate-sew-slash-allocation-from-projection projection-artifact)
        content {:claims/input-context
                 {:liable-parties (:liable-parties allocation-input [])
                  :total-basis (long (:total-basis direct-result 0))
                  :slash-obligation (or (:slash-obligation allocation-input)
                                        (:slash-amount allocation-input)
                                        0)
                  :basis-field (:basis allocation-input :slashable-stake)
                  :cap-field (:cap-field allocation-input :available-slashable)
                  :unmet-policy (:unmet-policy allocation-input :record-only)}
                 :claims/direct-result direct-result
                 :claims/projection-artifact projection-artifact
                 :claims/projection-artifact-again projection-artifact-again
                 :claims/projection-result projection-result}
        node-hash (hc/hash-with-intent {:hash/intent :evidence-record} content)]
    {:node-hash node-hash
     :result content
     :claims/evaluation-context true}))

(defn- evaluate-claims-from-input
  "Evaluate all pro-rata claims from a raw allocation input, building
   evidence nodes and passing through claims.engine/evaluate-claims."
  [allocation-input]
  (let [node (build-claim-evaluation-node allocation-input)
        requests (mapv (fn [claim-id]
                         {:claim-id claim-id
                          :evidence-references [(:node-hash node)]})
                       (claims/registered-claim-ids))
        {:keys [claim-results]}
        (claims-engine/evaluate-claims
         requests [node]
         {:evaluator-resolver claims/evaluator-resolver})]
    (into {} (map (juxt :claim-id identity) claim-results))))

(deftest registered-claims-cover-phase-6-contract
  (testing "the passive claim registry exposes the phase 6 claim set"
    (is (= phase-6-claims (set (claims/registered-claim-ids))))))

(deftest all-phase-6-claims-pass-on-representative-fixtures
  (testing "claim evaluators pass on representative fixtures via claims engine"
    (doseq [input representative-fixtures]
      (let [result (evaluate-claims-from-input input)]
        (is (= phase-6-claims (set (keys result))))
        (is (every? true? (map :holds? (vals result))))
        (is (empty? (mapcat :violations (vals result))))))))

(deftest missing-evidence-node-produces-failure
  (testing "evaluator returns :missing-evidence-content when no evidence node provided"
    (let [result (claims/evaluate-claim :conservation {:evidence-nodes []})]
      (is (false? (:holds? result)))
      (is (= [{:type :missing-evidence-content}] (:violations result))))))

(deftest unknown-claim-id-throws
  (testing "evaluating an unknown claim id throws an error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (claims/evaluate-claim :unknown-claim {:evidence-nodes []})))))

(deftest claims-engine-integrates-with-evaluator-resolver
  (testing "claims.engine/evaluate-claims resolves pro-rata evaluators correctly"
    (let [input (first representative-fixtures)
          node (build-claim-evaluation-node input)
          requests [{:claim-id :conservation
                     :evidence-references [(:node-hash node)]}]
          {:keys [claim-results validation]}
          (claims-engine/evaluate-claims
           requests [node]
           {:evaluator-resolver claims/evaluator-resolver})]
      (is (= 1 (count claim-results)))
      (is (= :conservation (:claim-id (first claim-results))))
      (is (true? (:holds? (first claim-results))))
      (is (:valid? validation)))))
