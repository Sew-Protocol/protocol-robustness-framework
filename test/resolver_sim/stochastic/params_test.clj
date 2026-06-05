(ns resolver-sim.stochastic.params-test
  (:require [clojure.test :refer :all]
            [resolver-sim.stochastic.params :as params]))

(deftest from-snap-preserves-zero-and-false
  (testing "from-snap preserves zero and false values when keys are present"
    (let [snap {:escrow-fee-bps 0
                :reversal-slash-bps 0
                :reversal-detection-probability 0.0}
          out (params/from-snap snap)]
      (is (= 0 (:fee-bps out)))
      (is (= 0 (:reversal-slash-bps out)))
      (is (zero? (:reversal-detection-probability out))))))

(deftest scenario-mc-params-merge-and-override
  (testing "scenario->mc-params merges protocol-params with :mc-params, latter wins"
    (let [scenario {:protocol-params {:resolver-fee-bps 150 :appeal-bond-bps 700}
                    :mc-params {:fee-bps 200}}
          out (params/scenario->mc-params scenario)]
      (is (= 200 (:fee-bps out)) ":mc-params overrides protocol-params")
      (is (= 700 (:bond-bps out)) "protocol-params field not in :mc-params survives"))))

(deftest scenario-mc-params-mc-only-fields
  (testing ":mc-params can contain MC-only fields absent from protocol-params"
    (let [scenario {:mc-params {:strategy-mix {:honest 0.8 :malicious 0.2}
                                :oracle-fixture {:mode :static-no-slash}
                                :fraud-detection-probability 0.25
                                :p-l1-reversal 0.75
                                :slash-multiplier 2.5
                                :oracle-roll-on-exhaustion :repeat-last}}
          out (params/scenario->mc-params scenario)]
      (is (= {:honest 0.8 :malicious 0.2} (:strategy-mix out)))
      (is (= :static-no-slash (get-in out [:oracle-fixture :mode])))
      (is (= 0.25 (:fraud-detection-probability out)))
      (is (= 0.75 (:p-l1-reversal out)))
      (is (= 2.5 (:slash-multiplier out)))
      (is (= :repeat-last (:oracle-roll-on-exhaustion out))))))

(deftest scenario-mc-params-missing-mc-params
  (testing "missing :mc-params does not invent a complete MC configuration"
    (let [scenario {:protocol-params {:resolver-fee-bps 150}}
          out (params/scenario->mc-params scenario)]
      (is (= 150 (:fee-bps out)) "protocol-params field still extracted")
      (is (nil? (:strategy-mix out)) "no strategy-mix invented")
      (is (nil? (:oracle-fixture out)) "no oracle-fixture invented")
      (is (nil? (:fraud-detection-probability out)) "no fraud-detection-probability invented")
      (is (= {} (dissoc out :fee-bps)) "no extra keys"))))
