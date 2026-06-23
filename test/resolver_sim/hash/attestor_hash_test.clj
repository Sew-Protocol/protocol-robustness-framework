(ns resolver-sim.hash.attestor-hash-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.hash.canonical :as hc]))

(defn- attestor-fixture
  ([] (attestor-fixture {}))
  ([overrides]
   (merge {:id :ci-validation
           :version 1
           :type :ci-runner
           :display-name "CI validation runner"
           :status :active
           :verification {:type :public-key
                          :algorithm :ed25519
                          :key-id "ci-validation-v1"
                          :public-key "ci-validation-placeholder-public-key"}
           :delegates [{:id :ci-validation-signing-key
                        :status :active}]
           :key-history [{:key-id "ci-validation-v0"
                          :status :retired}
                         {:key-id "ci-validation-v1"
                          :status :active}]
           :attestor-hash "self-hash-placeholder"
           :canonical-hash "legacy-self-hash-placeholder"
           :cached-verification-data {:last-checked-at "2026-06-23T00:00:00Z"
                                      :verification-result :ok}
           :runtime-state {:pid 42}
           :metadata {:intended-use #{:validation :attestation}}}
          overrides)))

(defn- reorder-map
  [m]
  (into {} (reverse (seq m))))

(deftest attestor-intent-contract-is-registered
  (let [contract (hc/resolve-intent :attestor)]
    (is (= :attestor (:intent/name contract)))
    (is (= "ATTESTOR_V1" (:intent/domain-tag contract)))
    (is (= 1 (:intent/version contract)))
    (is (= #{:id :type :status :verification :delegates :key-history}
           (:intent/includes contract)))
    (is (contains? hc/domain-tags :attestor))))

(deftest attestor-projection-includes-canonical-fields-only
  (let [fixture (attestor-fixture)
        projected (:artifact (hc/project-attestor fixture :attestor))]
    (is (= #{:id :type :status :verification :delegates :key-history}
           (set (keys projected))))
    (is (= :ci-validation (:id projected)))
    (is (= :ci-runner (:type projected)))
    (is (= :active (:status projected)))
    (is (= [{:id :ci-validation-signing-key :status :active}]
           (:delegates projected)))
    (is (= [{:key-id "ci-validation-v0" :status :retired}
            {:key-id "ci-validation-v1" :status :active}]
           (:key-history projected)))
    (is (nil? (:display-name projected)))
    (is (nil? (:metadata projected)))
    (is (nil? (:attestor-hash projected)))
    (is (nil? (:canonical-hash projected)))
    (is (nil? (:cached-verification-data projected)))
    (is (nil? (:runtime-state projected)))))

(deftest attestor-projection-normalizes-missing-collections
  (let [fixture (-> (attestor-fixture)
                    (dissoc :delegates)
                    (dissoc :key-history))
        projected (:artifact (hc/project-attestor fixture :attestor))]
    (is (= [] (:delegates projected)))
    (is (= [] (:key-history projected)))))

(deftest attestor-hash-stable-under-map-ordering-changes
  (let [property (prop/for-all [key-id (gen/such-that seq gen/string-alphanumeric)
                                public-key (gen/such-that seq gen/string-alphanumeric)
                                delegate-id (gen/such-that seq gen/string-alphanumeric)]
                               (let [base (attestor-fixture
                                           {:verification {:type :public-key
                                                           :algorithm :ed25519
                                                           :key-id key-id
                                                           :public-key public-key}
                                            :delegates [{:id delegate-id
                                                         :status :active}]
                                            :key-history [{:key-id "v0" :status :retired}
                                                          {:key-id key-id :status :active}]})
                                     reordered (-> base
                                                   (update :verification reorder-map)
                                                   (update :delegates #(mapv reorder-map %))
                                                   (update :key-history #(mapv reorder-map %))
                                                   reorder-map)]
                                 (= (hc/hash-with-intent {:hash/intent :attestor} base)
                                    (hc/hash-with-intent {:hash/intent :attestor} reordered))))
        result (tc/quick-check 50 property)]
    (is (:pass? result) (pr-str result))))

(deftest attestor-hash-changes-when-verification-keys-change
  (let [property (prop/for-all [key-a (gen/such-that seq gen/string-alphanumeric)
                                key-b (gen/such-that seq gen/string-alphanumeric)]
                               (if (= key-a key-b)
                                 true
                                 (let [base (attestor-fixture {:verification {:type :public-key
                                                                              :algorithm :ed25519
                                                                              :key-id "ci-validation-v1"
                                                                              :public-key key-a}})
                                       changed (attestor-fixture {:verification {:type :public-key
                                                                                 :algorithm :ed25519
                                                                                 :key-id "ci-validation-v1"
                                                                                 :public-key key-b}})]
                                   (not= (hc/hash-with-intent {:hash/intent :attestor} base)
                                         (hc/hash-with-intent {:hash/intent :attestor} changed)))))
        result (tc/quick-check 50 property)]
    (is (:pass? result) (pr-str result))))

(deftest attestor-hash-changes-when-delegates-change
  (let [property (prop/for-all [delegate-a (gen/such-that seq gen/string-alphanumeric)
                                delegate-b (gen/such-that seq gen/string-alphanumeric)]
                               (if (= delegate-a delegate-b)
                                 true
                                 (let [base (attestor-fixture {:delegates [{:id delegate-a :status :active}]})
                                       changed (attestor-fixture {:delegates [{:id delegate-b :status :active}]})]
                                   (not= (hc/hash-with-intent {:hash/intent :attestor} base)
                                         (hc/hash-with-intent {:hash/intent :attestor} changed)))))
        result (tc/quick-check 50 property)]
    (is (:pass? result) (pr-str result))))

(deftest attestor-hash-changes-when-status-changes
  (let [property (prop/for-all [status (gen/elements [:revoked :retired])]
                               (not= (hc/hash-with-intent {:hash/intent :attestor}
                                                          (attestor-fixture {:status :active}))
                                     (hc/hash-with-intent {:hash/intent :attestor}
                                                          (attestor-fixture {:status status}))))
        result (tc/quick-check 50 property)]
    (is (:pass? result) (pr-str result))))

(deftest validate-registry-detects-duplicate-domain-tags
  (with-redefs [hc/hash-intents (assoc-in hc/hash-intents
                                          [:attestor :intent/domain-tag]
                                          "INTENT_DSL_V1")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest validate-registry-detects-unregistered-domain-tags
  (with-redefs [hc/domain-tags (dissoc hc/domain-tags :attestor)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest validate-registry-detects-nondeterministic-projections
  (with-redefs [hc/hash-intents (assoc-in hc/hash-intents
                                          [:attestor :intent/projection-fn]
                                          (fn [_ intent]
                                            {:intent intent
                                             :artifact {:nonce (str (java.util.UUID/randomUUID))}}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))
