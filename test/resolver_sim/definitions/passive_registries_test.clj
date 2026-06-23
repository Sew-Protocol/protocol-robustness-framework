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

(deftest passive-registries-validate
  (testing "all passive registries are internally valid"
    (is (:valid? (registries/validate-intent-registry)))
    (is (:valid? (registries/validate-projection-definition-registry)))
    (is (:valid? (registries/validate-claim-definition-registry)))
    (is (:valid? (registries/validate-attestor-registry)))
    (is (:valid? (registries/validate-passive-registries)))))

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

(deftest runtime-validation-is-permissive-by-default
  (with-redefs [registries/validate-passive-registries
                (constantly {:valid? false
                             :results []
                             :errors [{:error :test/forced-invalid}]})]
    (is (= {:valid? false
            :results []
            :errors [{:error :test/forced-invalid}]}
           (registries/validate-passive-registries!)))))

(deftest strict-validation-hard-fails
  (with-redefs [registries/validate-passive-registries
                (constantly {:valid? false
                             :results []
                             :errors [{:error :test/forced-invalid}]})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Passive registry validation failed"
         (registries/validate-passive-registries! {:strict? true})))
    (binding [registries/*strict-passive-registry-validation* true]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Passive registry validation failed"
           (registries/validate-passive-registries!))))))
