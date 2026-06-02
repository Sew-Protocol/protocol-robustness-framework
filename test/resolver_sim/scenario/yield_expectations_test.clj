(ns resolver-sim.scenario.yield-expectations-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.sim.fixtures :as fix]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.yield-scenario-lint :as lint]))

(def ^:private negative-yield-scenarios
  ["scenarios/S108_negative-yield-mild.json"
   "scenarios/S103_negative-yield-shortfall-cascade.json"
   "scenarios/S78_yield-negative-yield-release-path.json"
   "scenarios/S79_yield-negative-yield-dispute-refund-path.json"
   "scenarios/S109_negative-yield-severe-repair.json"])

(defn- load-normalized [path]
  (-> path sc/load-scenario-file fix/normalize-scenario))

(defn- replay [path]
  (-> path load-normalized sew/replay-with-sew-protocol))

(deftest negative-yield-scenario-structure
  (doseq [path negative-yield-scenarios]
    (testing path
      (let [scenario (load-normalized path)
            issues   (lint/lint-negative-yield-scenario scenario)]
        (is (empty? issues)
            (str "lint for " path ": " (pr-str issues)))))))

(deftest negative-yield-scenario-expectations-pass
  (doseq [path negative-yield-scenarios]
    (testing path
      (let [r (replay path)]
        (is (= :pass (:outcome r)) (str "replay outcome for " path))
        (when-let [exp (:expectations r)]
          (is (:ok? exp) (str "expectations for " path " violations: "
                              (:violations exp))))))))
