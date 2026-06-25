(ns resolver-sim.protocols.sew.evidence.slashing-test
  "Integration tests for the migrated pro-rata slash evidence claims path.
   Claims are now produced through claims.engine/evaluate-claims with explicit
   evidence-node references, not through raw allocation-input tunneling."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.claims.engine :as claims-engine]
            [resolver-sim.protocols.sew.evidence.slashing :as slashing]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.yield.pro-rata-claims :as pro-rata-claims]))

;; ── Test fixtures ──────────────────────────────────────────────────────

(def sample-world
  "Minimal world state for evidence envelope construction."
  (sew-types/empty-world 1000))

(def sample-allocation-input
  {:slash-obligation 11
   :slash-policy {:policy/id :test-policy}
   :liable-parties [{:id :resolver-a :slashable-stake 3}
                    {:id :resolver-b :slashable-stake 2}
                    {:id :resolver-c :slashable-stake 1}]})

(def sample-attribution
  "Sample researcher attribution context."
  {:ctx/scenario-id "test-scenario"
   :ctx/run-id "test-run"
   :ctx/event-index 5
   :ctx/event-type "execute_resolution"
   :subject/type :slash
   :subject/id "test-slash"
   :action/type :slash/execute
   :evidence/reason :fraud-slash-executed})

(defn- build-evidence
  "Build full pro-rata slash evidence from sample inputs."
  [& {:keys [world allocation-input attribution]
      :or {world sample-world
           allocation-input sample-allocation-input
           attribution sample-attribution}}]
  (let [allocation-result (sew-economics/calculate-sew-slash-allocation allocation-input)]
    (:evidence
      (slashing/build-prorata-slash-evidence
       {:world world
        :slash-id "test-slash"
        :workflow-id 0
        :resolver :test-resolver
        :epoch 0
        :trigger :test
        :allocation-input allocation-input
        :allocation-result allocation-result
        :transition-dependencies []
        :attribution attribution}))))

;; ── Integration tests ──────────────────────────────────────────────────

(deftest claims-section-produced-through-engine
  (testing "pro-rata claims section is populated through claims.engine/evaluate-claims"
    (let [evidence (build-evidence)
          result (:evidence/result evidence)
          claims (get-in result [:pro-rata :claims])]
      (is (= 7 (count claims)) "all 7 pro-rata claims present")
      (is (every? :claim-id claims) "each claim has :claim-id")
      (is (every? :claim-definition-hash claims) "each claim has :claim-definition-hash")
      (is (every? :claim-result-hash claims) "each claim has :claim-result-hash")
      (is (every? #(contains? #{:pass :fail} (:status %)) claims)
          "each claim has :pass/:fail status")
      (is (every? true? (map :holds? claims)) "all claims hold"))))

(deftest deterministic-evaluation
  (testing "same inputs produce identical claim results"
    (let [e1 (build-evidence)
          e2 (build-evidence)
          c1 (get-in e1 [:evidence/result :pro-rata :claims])
          c2 (get-in e2 [:evidence/result :pro-rata :claims])
          pairs (map vector c1 c2)]
      (is (every? (fn [[a b]]
                    (and (= (:claim-id a) (:claim-id b))
                         (= (:holds? a) (:holds? b))
                         (= (:claim-definition-hash a) (:claim-definition-hash b))
                         (= (:claim-result-hash a) (:claim-result-hash b))))
                  pairs)
          "all claim result fields are deterministic"))))

(deftest envelope-shape-preserved
  (testing "existing evidence envelope fields are preserved after migration"
    (let [evidence (build-evidence)
          result (:evidence/result evidence)]
      (is (some? (:evidence/hash evidence)) "evidence envelope has :evidence/hash")
      (is (some? (get-in result [:projection :projection-hash]))
          "envelope has projection-hash")
      (is (some? (get-in result [:projection :projection-definition-hash]))
          "envelope has projection-definition-hash")
      (is (map? (get result :pro-rata)) "envelope has :pro-rata section")
      (is (map? (get-in result [:pro-rata :summary])) "envelope has claims summary")
      (is (contains? (get-in result [:pro-rata :summary]) :holds?)
          "summary has :holds?")
      (is (number? (get-in result [:pro-rata :summary :claim-count]))
          "summary has claim count")
      (is (some? (get-in result [:pro-rata :allocation-hash]))
          "envelope has allocation-hash")
      (is (map? (get result :allocation)) "envelope has original allocation-result")
      (is (some? (:evidence/subject evidence)) "envelope has subject")
      (is (some? (:evidence/frame evidence)) "envelope has frame"))))

(deftest claims-engine-validates-missing-evidence-node
  (testing "claims engine detects evidence reference that does not exist"
    (let [node-hash "nonexistent-hash"
          requests [{:claim-id :conservation
                     :evidence-references [node-hash]}]
          {:keys [validation]}
          (claims-engine/evaluate-claims
           requests []
           {:evaluator-resolver pro-rata-claims/evaluator-resolver})]
      (is (false? (:valid? validation)))
      (is (some #(= :claim/missing-evidence (:error %))
                (:errors validation))))))

(deftest claims-engine-throws-for-missing-definition
  (testing "claims engine throws when claim definition is not in registry"
    (let [eval-node (slashing/build-claim-evaluation-node
                     sample-allocation-input
                     (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
                     (sew-economics/calculate-sew-slash-allocation sample-allocation-input)
                     (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
                     (sew-economics/calculate-sew-slash-allocation-from-projection
                      (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)))
          requests [{:claim-id :nonexistent-claim
                     :evidence-references [(:node-hash eval-node)]}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown claim definition"
                            (claims-engine/evaluate-claims
                             requests [eval-node]
                             {:evaluator-resolver pro-rata-claims/evaluator-resolver}))))))

(deftest evaluate-claims-produces-matching-results
  (testing "claims engine results match direct evaluator results for same evidence"
    (let [eval-node (slashing/build-claim-evaluation-node
                     sample-allocation-input
                     (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
                     (sew-economics/calculate-sew-slash-allocation sample-allocation-input)
                     (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)
                     (sew-economics/calculate-sew-slash-allocation-from-projection
                      (sew-economics/build-sew-slash-projection-artifact sample-allocation-input)))
          requests (mapv (fn [claim-id]
                           {:claim-id claim-id
                            :evidence-references [(:node-hash eval-node)]})
                         (pro-rata-claims/registered-claim-ids))
          {:keys [claim-results validation]}
          (claims-engine/evaluate-claims
           requests [eval-node]
           {:evaluator-resolver pro-rata-claims/evaluator-resolver})
          direct-results (pro-rata-claims/evaluate-all {:evidence-nodes [eval-node]})]
      (is (:valid? validation))
      (doseq [cr claim-results]
        (let [claim-id (:claim-id cr)
              direct (get direct-results claim-id)]
          (is (some? direct) (str "direct result exists for " claim-id))
          (is (= (:holds? direct) (:holds? cr))
              (str "holds? matches for " claim-id)))))))

(deftest slashing-evidence-calls-engine-not-direct-evaluator
  (testing "the slashing evidence builder produces proper engine-shaped claim results"
    (let [evidence (build-evidence)
          result (:evidence/result evidence)
          claims (get-in result [:pro-rata :claims])]
      ;; Verify engine-shaped result fields
      (is (every? :claim-id claims))
      (is (every? :claim-definition-hash claims))
      (is (every? :claim-result-hash claims))
      ;; Verify no raw input fields leaked into claim results
      (is (every? (fn [c] (not (contains? c :sew-slash-input))) claims))
      (is (every? (fn [c] (not (contains? c :allocation-input))) claims))
      (is (every? (fn [c] (not (contains? c :claims/input-context))) claims))
      ;; Verify violation shape
      (is (every? (fn [c] (vector? (:violations c))) claims)))))

(deftest evidence-hash-stable-for-same-inputs
  (testing "evidence hash is deterministic for the same allocation inputs"
    (let [h1 (:evidence/hash (build-evidence))
          h2 (:evidence/hash (build-evidence))]
      (is (= h1 h2) "same inputs produce identical evidence hash"))))
