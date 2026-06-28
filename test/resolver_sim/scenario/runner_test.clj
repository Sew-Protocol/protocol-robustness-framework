(ns resolver-sim.scenario.runner-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.forensic.provenance :as prov]
            [resolver-sim.io.scenario-runner :as scenario-runner]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.normalize :as norm]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.sim.fixtures :as fixtures]))

(deftest scenario-pass-respects-fixture-checks
  (testing "threshold and golden failures fail the entry"
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass :expected-fail? false
           :checks {:thresholds {:ok? false} :golden {:ok? true}}}
          {})))
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass :expected-fail? false
           :checks {:thresholds {:ok? true} :golden {:ok? false}}}
          {})))
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass :expected-fail? false
           :expected-halt-reason :invariant-violation
           :halt-reason :open-entities-at-end
           :checks {}}
          {})))))

(deftest scenario-pass-respects-expectations
  (testing "failed expectations fail even when outcome is pass"
    (is (false?
         (runner/scenario-pass?
          {:outcome :pass
           :expected-fail? false
           :checks {:expectations {:ok? false :violations [{:type :metric-violation}]}}}
          {})))))

(deftest build-entry-reuses-replay-expectations
  (let [path "scenarios/S108_negative-yield-mild.json"
        scenario (-> path sc/load-scenario-file norm/normalize-scenario)
        replay   (sew/replay-with-sew-protocol scenario)
        entry    (runner/build-entry-result
                  {:name          (:scenario-id scenario)
                   :replay-result replay
                   :scenario      scenario})]
    (is (= (:expectations replay) (:expectations (:checks entry)))
        "should not re-evaluate when replay already has expectations")))

(deftest yield-suite-summary-shape
  (let [summary (scenario-runner/run-paths
                 ["scenarios/S108_negative-yield-mild.json"]
                 {:suite-id :yield-scenarios})]
    (is (= 1 (:total summary)))
    (is (contains? summary :passed))
    (is (= :yield-scenarios (:suite-id summary)))))

(deftest yield-scenario-protocol-is-inferred-from-file
  (let [summary (scenario-runner/run-paths
                 ["scenarios/Y02_vault-shortfall-partial-withdraw.json"]
                 {:suite-id :yield-scenarios})]
    (is (= 1 (:total summary)))
    (is (= 1 (:passed summary)))
    (is (= 1 (count (:results summary))))))

(deftest yield-scenario-announces-inferred-protocol
  (let [out (with-out-str
              (scenario-runner/run-paths
               ["scenarios/Y02_vault-shortfall-partial-withdraw.json"]
               {:suite-id :yield-scenarios}))]
    (is (str/includes? out "[run:scenario]"))
    (is (str/includes? out "protocol yield-v1"))))

(deftest path-run-request-captures-stable-run-metadata
  (let [{request :scenario-run/request}
        (scenario-runner/resolve-path-run-request
         ["scenarios/Y02_vault-shortfall-partial-withdraw.json"]
         {:suite-id :yield-provider-scenarios})
        entry (first (:entries request))]
    (is (= :local-current (:runner/backend request)))
    (is (= :yield-provider-scenarios (:suite/key request)))
    (is (= 1 (:entry-count request)))
    (is (= "Y02_vault-shortfall-partial-withdraw" (:scenario-id entry)))
    (is (= "yield-v1" (:protocol entry)))
    (is (= :protocol/yield-v1 (:dispatcher-id entry)))
    (is (= "scenarios/Y02_vault-shortfall-partial-withdraw.json" (:scenario-path entry)))))

(deftest suite-scenario-details-uses-run-request-path
  (let [details (scenario-runner/suite-scenario-details
                 "scenarios/Y02_vault-shortfall-partial-withdraw.json")]
    (is (= "Y02_vault-shortfall-partial-withdraw" (:scenario/id details)))
    (is (= :yield-v1 (:scenario/protocol details)))
    (is (= :file (:scenario/source details)))
    (is (= :protocol/yield-v1 (:scenario/dispatcher-id details)))))

(defn- with-temp-scenario-file
  ([body f]
   (with-temp-scenario-file body ".json" f))
  ([body suffix f]
   (let [file (java.io.File/createTempFile "scenario-runner-" suffix)]
     (spit file body)
     (try
       (f (.getAbsolutePath file))
       (finally
         (io/delete-file file true))))))

(deftest json-scenario-load-emits-deprecation-warning
  (with-temp-scenario-file
    (str "{"
         "\"scenario-id\":\"json-deprecated\","
         "\"protocol\":\"sew-v1\","
         "\"events\":[]"
         "}")
    (fn [path]
      (let [out (with-out-str
                  (scenario-runner/resolve-path-run-request [path] {:suite-id :json-deprecation-suite}))]
        (is (str/includes? out "scenario-json-deprecated"))
        (is (str/includes? out "Executable JSON scenario input is deprecated"))))))

(deftest resolve-path-run-request-preserves-scenario-metadata-contract
  (with-temp-scenario-file
    (str
     "{"
     "\"scenario-id\":\"metadata-contract\","
     "\"protocol\":\"sew-v1\","
     "\"expected-outcome\":{\"status\":\"pass\"},"
     "\"theory\":{\"assumptions\":[\"steady-liquidity\"],\"claim-id\":\"claim.partial-fill\"},"
     "\"scenario/model-scope\":\"vault-shortfall\","
     "\"scenario/evidence-profile\":\"research\","
     "\"scenario/output-profile\":\"forensic\","
     "\"scenario/output-overrides\":{\"artifact-level\":\"summary\"},"
     "\"scenario/custom-tag\":\"carry-through\","
     "\"events\":[]"
     "}")
    (fn [path]
      (let [{request :scenario-run/request}
            (scenario-runner/resolve-path-run-request [path] {:suite-id :metadata-suite})
            entry (first (:entries request))
            metadata (:scenario-metadata entry)
            out (with-out-str
                  (scenario-runner/resolve-path-run-request [path] {:suite-id :metadata-suite}))]
        (is (= :metadata-suite (:suite/key request)))
        (is (= :research (:evidence/profile request)))
        (is (= :forensic (:output/profile request)))
        (is (= ["steady-liquidity"] (:scenario/assumptions metadata)))
        (is (= ["claim.partial-fill"] (:scenario/claim-intents metadata)))
        (is (= "vault-shortfall" (:scenario/model-scope metadata)))
        (is (= {:status "pass"} (:scenario/expected-outcome metadata)))
        (is (= :research (:scenario/evidence-profile metadata)))
        (is (= :forensic (:scenario/output-profile metadata)))
        (is (= {:artifact-level "summary"} (:scenario/output-overrides metadata)))
        (is (= {:scenario/custom-tag "carry-through"}
               (:scenario/metadata-extra metadata)))
        (is (str/includes? out "scenario-json-deprecated"))
        (is (str/includes? out "scenario-metadata-fallback"))
        (is (str/includes? out "scenario-metadata-extra"))))))

(deftest edn-scenario-loads-through-request-boundary
  (with-temp-scenario-file
    (pr-str {:scenario-id "edn-load"
             :protocol "yield-v1"
             :events []
             :scenario/evidence-profile :minimal})
    ".edn"
    (fn [path]
      (let [{request :scenario-run/request}
            (scenario-runner/resolve-path-run-request [path] {:suite-id :edn-suite})
            out (with-out-str
                  (scenario-runner/resolve-path-run-request [path] {:suite-id :edn-suite}))]
        (is (= :edn-suite (:suite/key request)))
        (is (= path (-> request :entries first :scenario-path)))
        (is (= "edn-load" (-> request :entries first :scenario-id)))
        (is (= :minimal (get-in request [:entries 0 :scenario-metadata :scenario/evidence-profile])))
        (is (empty? out))))))
(deftest resolve-path-run-request-rejects-unknown-supported-profile-values
  (with-temp-scenario-file
    (str
     "{"
     "\"scenario-id\":\"bad-profile\","
     "\"protocol\":\"sew-v1\","
     "\"scenario/evidence-profile\":\"not-a-real-profile\","
     "\"events\":[]"
     "}")
    (fn [path]
      (let [ex (try
                 (scenario-runner/resolve-path-run-request [path] {})
                 nil
                 (catch Exception e e))]
        (is ex)
        (is (str/includes? (.getMessage ex) "Unknown scenario evidence profile"))))))

(deftest normalize-run-result-preserves-request-and-entry-metadata
  (with-temp-scenario-file
    (str
     "{"
     "\"scenario-id\":\"normalized-result-contract\","
     "\"protocol\":\"yield-v1\","
     "\"scenario/evidence-profile\":\"minimal\","
     "\"scenario/output-profile\":\"research\","
     "\"theory\":{\"claim-id\":\"claim.run.normalized\"},"
     "\"events\":[]"
     "}")
    (fn [path]
      (let [{request :scenario-run/request}
            (scenario-runner/resolve-path-run-request [path] {:suite-id :normalized-suite})
            scenario-id (-> request :entries first :scenario-id)
            result (scenario-runner/normalize-run-result
                    request
                    {:ok? true
                     :suite-id :normalized-suite
                     :results [{:scenario-id scenario-id :outcome :pass}]})
            normalized-entry (-> result :scenario-run/result :results first)]
        (is (= :pass (get-in result [:scenario-run/result :status])))
        (is (= :minimal (get-in result [:scenario-run/result :evidence/profile])))
        (is (= :research (get-in result [:scenario-run/result :output/profile])))
        (is (= path (:scenario-path normalized-entry)))
        (is (= :protocol/yield-v1 (:dispatcher-id normalized-entry)))
        (is (= {:backend :local-current
                :protocol-id "yield-v1"
                :dispatcher-id :protocol/yield-v1}
               (select-keys (:runner normalized-entry)
                            [:backend :protocol-id :dispatcher-id])))
        (is (= ["claim.run.normalized"]
               (get-in normalized-entry [:scenario-metadata :scenario/claim-intents])))))))

(deftest run-and-report-rejects-ambiguous-dispatch-maps
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Ambiguous dispatch map"
       (scenario-runner/run-and-report
        {:suite :yield-provider-scenarios
         :scenario "scenarios/Y02_vault-shortfall-partial-withdraw.json"}
        {}))))

(deftest run-and-report-prints-after-execution-and-writes-bundle-once
  (let [calls (atom [])
        request {:runner/backend :local-current
                 :runner-selection {:mode :pinned
                                    :runner-id :runner/local-bb}
                 :suite/key :yield-provider-scenarios
                 :protocol/default-id "yield-v1"
                 :entries [{:scenario-id "Y01"
                            :scenario-path "scenarios/Y01.json"
                            :protocol "yield-v1"
                            :dispatcher-id :protocol/yield-v1}
                           {:scenario-id "Y02"
                            :scenario-path "scenarios/Y02.json"
                            :protocol "yield-v1"
                            :dispatcher-id :protocol/yield-v1}]
                 :entry-count 2}
        summary {:scenario-run/request request
                 :suite-id :yield-provider-scenarios
                 :ok? false
                 :passed 1
                 :total 2
                 :elapsed-ms 12
                 :results [{:name "Y01"
                            :scenario-id "Y01"
                            :pass? true
                            :outcome :pass
                            :steps 1
                            :reverts 0}
                           {:name "Y02"
                            :scenario-id "Y02"
                            :pass? false
                            :outcome :fail
                            :steps 1
                            :reverts 0}]}
        output-file (.getAbsolutePath (java.io.File/createTempFile "bundle-root-" ".json"))]
    (try
      (with-redefs [suites/suite-paths
                    (fn [_] ["scenarios/Y01.json" "scenarios/Y02.json"])
                    suites/suite-protocol-id
                    (fn [_] "yield-v1")
                    scenario-runner/run-paths
                    (fn [_ _]
                      (swap! calls conj :execute)
                      summary)
                    report/print-report
                    (fn [report-summary _]
                      (swap! calls conj [:report (:total report-summary)])
                      1)
                    scenario-runner/write-result-json
                    (fn [_ payload]
                      (swap! calls conj [:write payload])
                      nil)
                    scenario-runner/populate-forensic-claims!
                    (fn []
                      (swap! calls conj :forensics)
                      nil)
                    prov/source-provenance
                    (fn []
                      {})
                    ev-node/with-execution-node+
                    (fn [_ thunk]
                      (swap! calls conj :execution-node-start)
                      (let [value (thunk)]
                        (swap! calls conj :execution-node-end)
                        {:result value
                         :execution-node {:node-hash "node-hash"
                                          :content-hash "content-hash"
                                          :record-hash "record-hash"}}))]
        (let [result (scenario-runner/run-and-report
                      {:suite :yield-provider-scenarios
                       :output-file output-file}
                      {})]
          (is (= [:execution-node-start
                  :execute
                  :execution-node-end
                  :report
                  :forensics
                  :write]
                 (mapv #(if (vector? %) (first %) %)
                       @calls))
              "reporting must happen after the execution thunk returns")
          (let [writes (filter #(and (vector? %)
                                     (= :write (first %)))
                               @calls)
                payload (second (first writes))]
            (is (= 1 (count writes)))
            (is (= [:report 2]
                   (some #(when (and (vector? %)
                                     (= :report (first %)))
                            %)
                         @calls)))
            (is (= "bundle-root.v1" (:bundle/schema-version payload)))
            (is (= {:total 2 :passed 1 :failed 1}
                   (get-in payload [:execution/summary :totals]))))
          (is (= {:total 2 :passed 1 :failed 1}
                 (get-in (:bundle-root result) [:execution/summary :totals])))))
      (finally
        (io/delete-file output-file true)))))

(deftest report-surfaces-expectation-violations
  (let [lines (report/format-check-failures
               {:checks {:expectations {:ok? false
                                        :violations [{:type :metric-violation
                                                      :name :yield/escrow-principal
                                                      :op :>
                                                      :expected 100
                                                      :actual 99}]}}})]
    (is (pos? (count lines)))
    (is (str/includes? (first lines) "expectation:"))
    (is (str/includes? (first lines) "yield/escrow-principal"))))

(deftest runner-opts-theory-defaults
  (testing "scenario with :theory evaluates theory by default"
    (is (true? (:evaluate-theory? (runner/runner-opts-for-scenario {:theory {:claim-id :x}})))))
  (testing "scenario without :theory skips theory by default"
    (is (false? (:evaluate-theory? (runner/runner-opts-for-scenario {})))))
  (testing "suite opts can suppress theory on a theory scenario"
    (is (false? (:evaluate-theory? (runner/runner-opts-for-scenario
                                    {:theory {:claim-id :x}}
                                    {:evaluate-theory? false})))))
  (testing "suite opts can force theory evaluation flag (no-op without :theory block)"
    (is (true? (:evaluate-theory? (runner/runner-opts-for-scenario
                                   {}
                                   {:evaluate-theory? true}))))))

(deftest build-entry-theory-eval-once
  (let [path "scenarios/S108_negative-yield-mild.json"
        scenario (-> path sc/load-scenario-file norm/normalize-scenario)
        replay   (sew/replay-with-sew-protocol scenario)
        forced-off (runner/build-entry-result
                    {:name (:scenario-id scenario) :replay-result replay :scenario scenario}
                    (runner/runner-opts-for-scenario scenario {:evaluate-theory? false}))]
    (is (nil? (get-in forced-off [:checks :theory]))
        "suppress via runner-opts must skip theory check")))

(deftest theory-check-present-when-scenario-declares-theory
  (let [scenario (fixtures/compose-suite :traces/spe-reg-v4-fail-slashed-resolver-bounded)
        replay   (sew/replay-with-sew-protocol scenario)
        opts     (runner/runner-opts-for-scenario scenario)
        entry    (runner/build-entry-result
                  {:name (:scenario-id scenario) :replay-result replay :scenario scenario}
                  opts)]
    (is (true? (:evaluate-theory? opts)))
    (is (contains? (:checks entry) :theory))
    (is (nil? (get-in (runner/build-entry-result
                       {:name (:scenario-id scenario) :replay-result replay :scenario scenario}
                       (assoc opts :evaluate-theory? false))
                      [:checks :theory])))))
