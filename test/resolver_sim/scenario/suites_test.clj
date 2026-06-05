(ns resolver-sim.scenario.suites-test
  (:require [clojure.test :refer :all]
            [resolver-sim.scenario.suites :as suites]))

(deftest yield-scenarios-alias-resolves-to-sew-paths
  (is (= (suites/suite-paths :sew-yield-scenarios)
         (suites/suite-paths :yield-scenarios)))
  (is (= "sew-v1" (suites/suite-protocol-id :yield-scenarios)))
  (is (= "yield-v1" (suites/suite-protocol-id :yield-provider-scenarios))))
