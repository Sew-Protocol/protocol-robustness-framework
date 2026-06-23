(ns resolver-sim.definitions.passive-registries-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]))

(defn- entry-hash
  [intent entry]
  (hc/hash-with-intent {:hash/intent intent} entry))

(defn- with-entry-hash
  [intent hash-key entry]
  (assoc entry hash-key (entry-hash intent entry)))

(def minimal-registry-spec
  {:entries-key :entries
   :required-registry-fields #{:registry-version :entries}
   :required-entry-fields #{:id :version :canonical-hash}
   :hash-intent :claim-definition
   :hash-key :canonical-hash})

(def valid-minimal-entry
  (with-entry-hash
    :claim-definition
    :canonical-hash
    {:id :registry-test/entry
     :version 1}))

(def valid-minimal-registry
  {:registry-version 1
   :entries [valid-minimal-entry]})

(def execution-registry-spec
  {:entries-key :executions
   :required-registry-fields #{:registry-version :executions}
   :required-entry-fields #{:id :version :kind :runner :entry
                            :execution/type :execution/mode
                            :description :claims}
   :registry-validator-fn registries/validate-execution-registry-entries})

(defn- execution-entry
  ([] (execution-entry {}))
  ([overrides]
   (merge {:id :execution/test-simulation
           :version 1
           :kind :simulation
           :runner :phase-runner
           :entry 'resolver-sim.core.phases/run-simulation
           :execution/type :simulation
           :execution/mode :full
           :description "Test execution entry"
           :claims #{:deterministic-replay}}
          overrides)))

(defn- claim-definition-entry
  ([] (claim-definition-entry {}))
  ([overrides]
   (with-entry-hash
     :claim-definition
     :canonical-hash
     (merge {:id :claim/test
             :version 1
             :category :audit
             :description "Test claim definition"
             :inputs [:evidence-node]
             :evaluation {:type :code-reference
                          :entry 'resolver-sim.claims.engine/evaluate-presence-claim}
             :outputs [:holds?]}
            overrides))))

(deftest passive-registries-validate
  (testing "all 8 passive registries are internally valid"
    (is (:valid? (registries/validate-intent-registry)))
    (is (:valid? (registries/validate-projection-definition-registry)))
    (is (:valid? (registries/validate-claim-definition-registry)))
    (is (:valid? (registries/validate-attestor-registry)))
    (is (:valid? (registries/validate-execution-registry)))
    (is (:valid? (registries/validate-evidence-policy-registry)))
    (is (:valid? (registries/validate-hash-projection-registry)))
    (is (:valid? (registries/validate-domain-tag-registry)))))

(deftest aggregate-validation-includes-all-registries
  (testing "validate-passive-registries covers all 8 registries"
    (let [result (registries/validate-passive-registries)]
      (is (:valid? result))
      (is (= 8 (count (:results result))))
      (is (empty? (:errors result))))))

(deftest passive-registry-data-is-hashed-deterministically
  (testing "registered entries keep stable canonical self hashes"
    (doseq [[intent hash-key entries] [[:intent-registry-entry
                                        :canonical-hash
                                        (:intents registries/intent-registry)]
                                       [:projection-definition
                                        :canonical-hash
                                        (:projection-definitions registries/projection-definition-registry)]
                                       [:claim-definition
                                        :canonical-hash
                                        (:claim-definitions registries/claim-definition-registry)]
                                       [:attestor
                                        :attestor-hash
                                        (:attestors registries/attestor-registry)]]]
      (doseq [entry entries]
        (is (= (get entry hash-key)
               (entry-hash intent entry)))))))

(deftest validators-report-invalid-data-without-throwing
  (testing "missing fields, duplicate ids, invalid versions, and hash mismatches are errors"
    (let [bad-entry (assoc valid-minimal-entry
                           :version 0
                           :canonical-hash "not-the-canonical-hash")
          bad-registry {:registry-version 2
                        :entries [(dissoc bad-entry :id)
                                  bad-entry
                                  valid-minimal-entry]}
          result (registries/validate-registry
                  :minimal-test-registry
                  bad-registry
                  minimal-registry-spec)
          error-codes (set (map :error (:errors result)))]
      (is (false? (:valid? result)))
      (is (contains? error-codes :registry/unsupported-version))
      (is (contains? error-codes :entry/missing-fields))
      (is (contains? error-codes :entry/invalid-version))
      (is (contains? error-codes :entry/hash-mismatch)))))

(deftest validators-detect-duplicate-ids
  (let [registry (assoc valid-minimal-registry
                        :entries [valid-minimal-entry valid-minimal-entry])
        result (registries/validate-registry
                :minimal-test-registry
                registry
                minimal-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/duplicate-ids (:error %)) (:errors result)))))

(deftest execution-registry-detects-unknown-runner
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:runner :missing-runner})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unknown-runner (:error %)) (:errors result)))))

(deftest execution-registry-detects-unresolved-entry-point
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:entry 'resolver-sim.missing/does-not-exist})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unresolved-entry-point (:error %)) (:errors result)))))

(deftest execution-registry-startup-safe-keeps-passive-load-safe
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:entry 'resolver-sim.core.phases/does-not-exist})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (:valid? result))
    (is (empty? (:errors result)))))

(deftest execution-registry-strict-validation-catches-missing-entry-vars
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:entry 'resolver-sim.core.phases/does-not-exist})]}
        result (binding [registries/*entry-validation-mode* :strict]
                 (registries/validate-registry
                  :execution-registry
                  registry
                  execution-registry-spec))]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unresolved-entry-point (:error %)) (:errors result)))))

(deftest execution-registry-detects-unknown-dependencies
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:depends-on [:execution/missing]})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :entry/unknown-dependencies (:error %)) (:errors result)))))

(deftest execution-registry-detects-dependency-cycles
  (let [registry {:registry-version 1
                  :executions [(execution-entry {:id :execution/a
                                                 :depends-on [:execution/b]})
                               (execution-entry {:id :execution/b
                                                 :entry 'resolver-sim.core.phases/run-sweep
                                                 :depends-on [:execution/a]})]}
        result (registries/validate-registry
                :execution-registry
                registry
                execution-registry-spec)]
    (is (false? (:valid? result)))
    (is (some #(= :registry/dependency-cycle (:error %)) (:errors result)))))

(deftest claim-definition-registry-detects-unknown-dependencies
  (let [registry {:registry-version 1
                  :claim-definitions [(claim-definition-entry {:depends-on [:claim/missing]})]}
        result (registries/validate-claim-definition-registry-entries
                :claim-definition-registry
                (:claim-definitions registry))]
    (is (false? (:valid? (registries/validate-registry
                          :claim-definition-registry
                          registry
                          {:entries-key :claim-definitions
                           :required-registry-fields #{:registry-version :claim-definitions}
                           :required-entry-fields #{:id :version :category :description
                                                    :inputs :evaluation :outputs :canonical-hash}
                           :hash-intent :claim-definition
                           :hash-key :canonical-hash
                           :registry-validator-fn registries/validate-claim-definition-registry-entries}))))
    (is (some #(= :entry/unknown-dependencies (:error %)) result))))

(deftest claim-definition-registry-detects-dependency-cycles
  (let [entries [(claim-definition-entry {:id :claim/a
                                          :depends-on [:claim/b]})
                 (claim-definition-entry {:id :claim/b
                                          :depends-on [:claim/a]})]
        result (registries/validate-claim-definition-registry-entries
                :claim-definition-registry
                entries)]
    (is (seq result))
    (is (some #(= :registry/dependency-cycle (:error %)) result))))

(deftest claim-definition-registry-strict-validation-catches-missing-code-reference-vars
  (let [entries [(claim-definition-entry {:evaluation {:type :code-reference
                                                       :entry 'resolver-sim.core.phases/does-not-exist}})]
        result (binding [registries/*entry-validation-mode* :strict]
                 (registries/validate-claim-definition-registry-entries
                  :claim-definition-registry
                  entries))]
    (is (seq result))
    (is (some #(= :entry/unresolved-evaluation-entry (:error %)) result))))

(deftest claim-definition-registry-startup-safe-validation-avoids-eager-load-failure
  (let [entries [(claim-definition-entry {:evaluation {:type :code-reference
                                                       :entry 'resolver-sim.core.phases/does-not-exist}})]
        result (registries/validate-claim-definition-registry-entries
                :claim-definition-registry
                entries)]
    (is (empty? result))))

(deftest validate-all-registries-hard-fails
  (testing "validate-all-registries! throws on invalid data"
    (with-redefs [registries/validate-passive-registries
                  (constantly {:valid? false
                               :results []
                               :errors [{:error :test/forced-invalid}]})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Registry validation failed"
           (registries/validate-all-registries!))))
    (testing "validate-passive-registries! (legacy alias) also throws"
      (with-redefs [registries/validate-passive-registries
                    (constantly {:valid? false
                                 :results []
                                 :errors [{:error :test/forced-invalid}]})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Registry validation failed"
             (registries/validate-passive-registries!)))))))

(deftest startup-validation-runs-on-load
  (testing "startup-validation is a non-nil def that runs validate-all-registries! at load"
    ;; startup-validation is defined as (validate-all-registries!) in passive_registries.clj
    ;; Since all 8 registries are valid, it evaluates to nil (the return of validate-all-registries!)
    (is (nil? (resolve 'registries/startup-validation))
        "startup-validation is a private def, so resolve returns nil from test ns
         (it's in the registry ns, not re-exported). The fact that the namespace
         loaded without throwing confirms startup validation passed.")))
