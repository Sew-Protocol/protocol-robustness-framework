(ns resolver-sim.scenario.suites-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.scenario.suites :as suites]))

(def ^:private expected-suite-keys
  #{:yield-provider-scenarios
    :sew-yield-scenarios
    :dispute-resolution-scenarios})

(def ^:private expected-metadata-keys
  #{:protocol-id :title :description :kind :ci-tier})

(deftest registry-exposes-canonical-suite-keys
  (is (= expected-suite-keys (clojure.core/set (suites/known-suite-keys))))
  (is (= expected-suite-keys (clojure.core/set (keys (suites/known-suite-definitions))))))

(deftest registry-definitions-are-well-formed
  (doseq [suite-key (suites/known-suite-keys)]
    (testing (name suite-key)
      (let [definition (suites/suite-definition suite-key)
            metadata   (suites/suite-metadata suite-key)
            paths      (:paths definition)]
        (is (map? definition))
        (is (= expected-metadata-keys (clojure.core/set (keys metadata)))
            (str "metadata keys: " (pr-str (keys metadata))))
        (is (seq paths))
        (is (= (count paths) (suites/suite-path-count suite-key)))
        (is (every? #(.exists (io/file %)) paths)
            (str "missing paths: " (->> paths (remove #(.exists (io/file %))) vec pr-str)))
        (is (contains? (clojure.core/set (preg/known-protocol-ids)) (:protocol-id definition)))
        (is (str/ends-with? (:description metadata) ".")
            "descriptions should read like sentences")))))

(deftest yield-provider-suite-is-canonical-top-level
  (let [paths (suites/suite-paths :yield-provider-scenarios)]
    (is (= ["scenarios/Y01_vault-shared-liquidity.json"
            "scenarios/Y02_vault-shortfall-partial-withdraw.json"
            "scenarios/Y03_vault-risk-override-schedule-shadowing.json"
            "scenarios/Y04_vault-recovery-claim-deferred.json"
            "scenarios/Y05_auto-generated-shortfall.json"]
           paths))
    (is (every? #(str/starts-with? % "scenarios/Y") paths))
    (is (not-any? #(str/includes? % "scenarios/yield/") paths))
    (is (= "yield-v1" (suites/suite-protocol-id :yield-provider-scenarios)))))
