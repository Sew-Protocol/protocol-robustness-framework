(ns resolver-sim.evidence.node-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.canonical :as hc]))

(defn- reorder-map
  [m]
  (into {} (reverse (seq m))))

(defn- temp-artifact-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "node-artifacts"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- base-node-spec
  ([] (base-node-spec {}))
  ([overrides]
   (merge {:execution-id :execution/diff
           :policy-id :evidence-policy/computed
           :timestamp "2026-06-23T00:00:00Z"
           :status :pass
           :inputs {:baseline-path "a.json"
                    :candidate-path "b.json"
                    :options {:strict? true :format :json}}
           :outputs {:exit-code 0
                     :summary {:matches 10 :mismatches 0}}
           :failure-details []
           :extensions {:note "metadata"}}
          overrides)))

(deftest evidence-node-intent-contract-is-registered
  (let [contract (hc/resolve-intent :evidence-node)]
    (is (= :evidence-node (:intent/name contract)))
    (is (= "EVIDENCE_NODE_V1" (:intent/domain-tag contract)))
    (is (= 1 (:intent/version contract)))
    (is (= #{:schema-version :parent-hashes :bootstrap-roots
             :execution :result :evidence :attestations :extensions}
           (:intent/includes contract)))
    (is (contains? hc/domain-tags :evidence-node))))

(deftest build-execution-node-includes-required-provenance-and-hashes
  (let [node (node/build-execution-node (base-node-spec))]
    (is (= 1 (:schema-version node)))
    (is (= (:node-id node) (:node-hash node)))
    (is (= :execution/diff (get-in node [:execution :execution-id])))
    (is (string? (get-in node [:execution :registry-hash])))
    (is (string? (get-in node [:execution :policy-hash])))
    (is (string? (get-in node [:evidence :inputs-hash])))
    (is (string? (get-in node [:evidence :outputs-hash])))
    (is (= :pass (get-in node [:result :status])))))

(deftest evidence-node-hash-stable-under-map-ordering-changes
  (let [property (prop/for-all [left (gen/such-that seq gen/string-alphanumeric)
                                right (gen/such-that seq gen/string-alphanumeric)]
                               (let [base (base-node-spec {:inputs {:baseline-path left
                                                                    :candidate-path right
                                                                    :options {:strict? true
                                                                              :format :json}}
                                                           :outputs {:exit-code 0
                                                                     :summary {:matches 10
                                                                               :mismatches 0}}})
                                     reordered (-> base
                                                   (update :inputs #(-> %
                                                                        (update :options reorder-map)
                                                                        reorder-map))
                                                   (update :outputs #(-> %
                                                                         (update :summary reorder-map)
                                                                         reorder-map))
                                                   reorder-map)]
                                 (= (:node-hash (node/build-execution-node base))
                                    (:node-hash (node/build-execution-node reordered)))))
        result (tc/quick-check 50 property)]
    (is (:pass? result) (pr-str result))))

(deftest evidence-node-hash-deterministic-across-repeated-runs
  (let [spec (base-node-spec)
        h1 (:node-hash (node/build-execution-node spec))
        h2 (:node-hash (node/build-execution-node spec))
        h3 (:node-hash (node/build-execution-node spec))]
    (is (= h1 h2 h3))))

(deftest evidence-node-hash-changes-on-semantic-change
  (let [base (:node-hash (node/build-execution-node (base-node-spec {:outputs {:exit-code 0}})))
        status-change (:node-hash (node/build-execution-node (base-node-spec {:status :fail
                                                                              :outputs {:exit-code 1}})))
        output-change (:node-hash (node/build-execution-node (base-node-spec {:outputs {:exit-code 2}})))]
    (is (not= base status-change))
    (is (not= base output-change))))

(deftest evidence-node-hash-stable-under-metadata-only-changes
  (let [base (node/build-execution-node (base-node-spec))
        changed (-> base
                    (assoc :timestamp "2026-06-23T12:00:00Z")
                    (assoc :policy-output {:visible {:status :pass :outputs {:human "changed view"}}}))]
    (is (= (:node-hash base)
           (node/compute-node-hash changed))
        "timestamp and policy-filtered visible output do not affect node identity")
    (is (= (:node-hash base)
           (:node-hash changed)))))

(deftest evidence-node-persists-to-disk-and-registers-artifact
  (node/reset-node-registry!)
  (chain/reset-registry!)
  (let [artifact-dir (temp-artifact-dir)
        built (node/build-execution-node (base-node-spec {:execution-id :execution/replay}))]
    (with-redefs [evcfg/artifact-dir (constantly artifact-dir)]
      (let [{:keys [path artifact-entry]} (node/persist-execution-node! built)
            readback (node/read-persisted-node path)
            registry (chain/build-registry)
            registry-entry (some #(when (= (:artifact/path %) path) %) (:artifacts registry))
            verification (node/verify-persisted-node-artifact! path artifact-entry)]
        (is (.exists (io/file path)))
        (is (= (:node-hash built) (:node-hash readback)))
        (is (= artifact-entry registry-entry))
        (is (= (:node-hash built) (:artifact/hash artifact-entry)))
        (is (:valid? verification))
        (is (= (:node-hash built) (get-in verification [:node :node-hash])))))))

(deftest persisted-node-dag-validation-spans-parent-and-child
  (node/reset-node-registry!)
  (chain/reset-registry!)
  (let [artifact-dir (temp-artifact-dir)
        parent (node/build-execution-node (base-node-spec {:execution-id :execution/simulation}))
        child (node/build-execution-node
               (base-node-spec {:execution-id :execution/replay
                                :parent-hashes [(:node-hash parent)]
                                :status :fail
                                :outputs {:exit-code 1}
                                :failure-details [{:failure-type :unexpected
                                                   :class :unexpected
                                                   :message "child failure"
                                                   :expected? false}]}))]
    (with-redefs [evcfg/artifact-dir (constantly artifact-dir)]
      (let [parent-result (node/persist-execution-node! parent)
            _ (node/register-node! parent)
            child-result (node/persist-execution-node! child)
            verification (node/verify-persisted-node-artifacts!
                          artifact-dir
                          [(:artifact-entry parent-result)
                           (:artifact-entry child-result)])]
        (is (:valid? verification))
        (is (:valid? (:dag verification)))
        (is (= 2 (count (:paths verification))))
        (is (true? (get-in verification [:checks :artifacts-matched?])))))))

(deftest evidence-policy-filters-expected-failures-and-excluded-classes
  (let [node (node/build-execution-node
              (base-node-spec {:status :fail
                               :outputs {:exit-code 1}
                               :failure-details [{:failure-type :known-regression
                                                  :class :known-regression
                                                  :message "expected failure"
                                                  :expected? true}
                                                 {:failure-type :environment
                                                  :class :environment
                                                  :message "env failure"
                                                  :expected? false}
                                                 {:failure-type :unexpected
                                                  :class :unexpected
                                                  :message "real failure"
                                                  :expected? false}]}))
        visible (get-in node [:policy-output :visible])
        summary (get-in node [:result :summary])]
    (is (= :fail (:status visible)))
    (is (= 3 (:failure-count summary)))
    (is (= 1 (:expected-failure-count summary)))
    (is (= 1 (:visible-failure-count summary)))
    (is (= 2 (:filtered-failure-count summary)))
    (is (= [:unexpected]
           (mapv :class (:failures visible))))
    (is (not-any? #(= :environment (:class %)) (:failures visible)))
    (is (not-any? :expected? (:failures visible)))))

(deftest filtered-policy-output-does-not-affect-node-hash
  (let [node (node/build-execution-node
              (base-node-spec {:status :fail
                               :outputs {:exit-code 1}
                               :failure-details [{:failure-type :unexpected
                                                  :class :unexpected
                                                  :message "visible"
                                                  :expected? false}]}))
        changed-visible (assoc node :policy-output {:visible {:status :fail
                                                              :outputs {:exit-code 1}
                                                              :failures [{:class :unexpected
                                                                          :message "rewritten presentation"}]}})]
    (is (= (:node-hash node)
           (node/compute-node-hash changed-visible)))))

(deftest validate-node-accepts-bootstrap-roots-and-rejects-missing-parents
  (let [bootstrap-hash "bootstrap-root-hash"
        valid-node (node/build-execution-node
                    (base-node-spec {:parent-hashes [bootstrap-hash]
                                     :bootstrap-roots [bootstrap-hash]}))
        invalid-node (node/build-execution-node
                      (base-node-spec {:parent-hashes ["missing-parent"]}))]
    (is (:valid? (node/validate-node valid-node)))
    (is (not (:valid? (node/validate-node invalid-node))))
    (is (some #(= :node/missing-parents (:error %))
              (:errors (node/validate-node invalid-node))))))

(deftest validate-node-dag-rejects-cycles-and-hash-mismatches
  (let [a {:schema-version 1
           :node-id "a"
           :node-hash "a"
           :parent-hashes ["b"]
           :bootstrap-roots []
           :execution {:execution-id :execution/diff
                       :execution-kind :differential
                       :runner :differential-runner
                       :registry-hash "r"
                       :policy-id :evidence-policy/computed
                       :policy-hash "p"}
           :result {:status :pass :summary {}}
           :evidence {:inputs-hash "i" :outputs-hash "o"}
           :attestations []
           :extensions {}}
        b (assoc a :node-id "b" :node-hash "b" :parent-hashes ["a"])
        result (node/validate-node-dag [a b])]
    (is (not (:valid? result)))
    (is (some #(= :node/cycle (:error %)) (:errors result)))
    (is (some #(= :node/hash-mismatch (:error %)) (:errors result)))))

(deftest with-execution-node-emits-pass-fail-and-error-nodes
  (node/reset-node-registry!)
  (chain/reset-registry!)
  (let [artifact-dir (temp-artifact-dir)]
    (with-redefs [evcfg/artifact-dir (constantly artifact-dir)]
      (testing "pass node"
        (is (= 0
               (node/with-execution-node
                 {:execution-id :execution/diff
                  :inputs {:baseline "a" :candidate "b"}
                  :status-fn #(if (zero? %) :pass :fail)}
                 (fn [] 0))))
        (is (= :pass (get-in (last (node/all-nodes)) [:result :status]))))
      (testing "fail node"
        (is (= 1
               (node/with-execution-node
                 {:execution-id :execution/diff
                  :inputs {:baseline "a" :candidate "b"}
                  :status-fn #(if (zero? %) :pass :fail)
                  :failure-details-fn (fn [_]
                                        [{:failure-type :trace-divergence
                                          :class :unexpected
                                          :message "diverged"
                                          :expected? false}])}
                 (fn [] 1))))
        (is (= :fail (get-in (last (node/all-nodes)) [:result :status]))))
      (testing "error node"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"boom"
             (node/with-execution-node
               {:execution-id :execution/diff
                :inputs {:baseline "a" :candidate "b"}}
               (fn [] (throw (ex-info "boom" {}))))))
        (is (= :error (get-in (last (node/all-nodes)) [:result :status]))))
      (is (= 3 (count (node/all-nodes))))
      (is (:valid? (node/verify-persisted-node-artifacts!
                    artifact-dir
                    (:artifacts (chain/build-registry))))))))

(deftest evidence-node-intent-registry-validates
  (is (nil? (hc/validate-registry!))))

;; ── validate-node-detailed ───────────────────────────────────────────────────

(deftest validate-node-detailed-passes-for-valid-node
  (let [node (node/build-execution-node (base-node-spec))
        result (node/validate-node-detailed node)]
    (is (:valid? result))
    (is (every? #(= :pass (:check/status %)) (:checks result)))
    (is (= 4 (:detailed-checks-total (:summary result))))
    (is (= 0 (:detailed-checks-failed (:summary result))))))

(deftest validate-node-detailed-detects-node-id-mismatch
  (let [node (assoc (node/build-execution-node (base-node-spec))
                    :node-id "tampered-id")
        result (node/validate-node-detailed node)]
    (is (not (:valid? result)))
    (is (some #(= :node-id-equals-hash (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-node-detailed-detects-missing-inputs
  (let [node (-> (node/build-execution-node (base-node-spec))
                 (assoc-in [:evidence :inputs-hash] ""))
        result (node/validate-node-detailed node)]
    (is (not (:valid? result)))
    (is (some #(= :node-inputs-present (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-node-detailed-detects-missing-outputs
  (let [node (-> (node/build-execution-node (base-node-spec))
                 (assoc-in [:evidence :outputs-hash] ""))
        result (node/validate-node-detailed node)]
    (is (not (:valid? result)))
    (is (some #(= :node-inputs-present (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-node-detailed-rejects-non-vector-attestations
  (let [node (assoc (node/build-execution-node (base-node-spec))
                    :attestations :not-a-vector)
        result (node/validate-node-detailed node)]
    (is (not (:valid? result)))
    (is (some #(= :node-attestations-present (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-node-detailed-detects-unresolvable-parents
  (let [node (node/build-execution-node
              (base-node-spec {:parent-hashes ["nonexistent-parent"]}))
        result (node/validate-node-detailed node)]
    (is (not (:valid? result)))
    (is (some #(= :node-parents-resolvable (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-node-detailed-accepts-bootstrap-roots-as-parents
  (let [bootstrap "bootstrap-root"
        node (node/build-execution-node
              (base-node-spec {:parent-hashes [bootstrap]
                               :bootstrap-roots [bootstrap]}))
        result (node/validate-node-detailed node)]
    (is (:valid? result))))

;; ── validate-dag-detailed ────────────────────────────────────────────────────

(deftest validate-dag-detailed-passes-for-valid-chain
  (let [p1 (node/build-execution-node (base-node-spec))
        p2 (node/build-execution-node
            (base-node-spec {:parent-hashes [(:node-hash p1)]
                             :execution-id :execution/replay}))
        result (node/validate-dag-detailed [p1 p2])]
    (is (:valid? result))
    (is (= 3 (:detailed-checks-total (:summary result))))
    (is (= 0 (:detailed-checks-failed (:summary result))))))

(deftest validate-dag-detailed-detects-duplicate-hashes
  (let [node (node/build-execution-node (base-node-spec))
        duplicate (assoc node :parent-hashes [(:node-hash node)])
        result (node/validate-dag-detailed [node duplicate])]
    (is (not (:valid? result)))
    (is (some #(= :dag-no-duplicate-hashes (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-dag-detailed-detects-multiple-roots
  (let [p1 (node/build-execution-node (base-node-spec))
        p2 (node/build-execution-node (base-node-spec))
        result (node/validate-dag-detailed [p1 p2])]
    (is (not (:valid? result)))
    (is (some #(= :dag-single-root (:check/id %))
              (filter #(= :fail (:check/status %)) (:checks result))))))

(deftest validate-dag-detailed-rejects-cycle
  (let [a {:schema-version 1
           :node-id "a" :node-hash "a"
           :parent-hashes ["b"] :bootstrap-roots []
           :execution {:execution-id :execution/diff :execution-kind :differential
                       :runner :differential-runner :registry-hash "r"
                       :policy-id :evidence-policy/computed :policy-hash "p"}
           :result {:status :pass :summary {}}
           :evidence {:inputs-hash "i" :outputs-hash "o"}
           :attestations [] :extensions {}}
        b (assoc a :node-id "b" :node-hash "b" :parent-hashes ["a"])
        result (node/validate-dag-detailed [a b])]
    (is (not (:valid? result)))
    (is (some #(= :node/cycle (:error %)) (:errors result)))))
