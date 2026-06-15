(ns resolver-sim.protocols.sew.runner-parity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.runner :as runner]
            [resolver-sim.util.attribution :as attr]))

(defn seeded-rng [seed]
  (let [r (java.util.Random. seed)]
    (fn [] (.nextDouble r))))

(deftest test-runner-parity
  (testing "Legacy loop vs Monadic loop parity"
    (let [params {:escrow-size 10000
                  :resolver-fee-bps 50
                  :appeal-bond-bps 100
                  :appeal-window-duration 3600
                  :strategy :malicious
                  :slashing-detection-probability 0.5
                  :escalation-probability-if-correct 0.1
                  :escalation-probability-if-wrong 0.8}
          seed 42]
      (let [rng1 (seeded-rng seed)
            legacy-result (runner/run-trial rng1 (assoc params :attributed? false))
            
            rng2 (seeded-rng seed)
            monadic-result (runner/run-trial rng2 (assoc params :attributed? true))]
        
        (is (= legacy-result monadic-result)
            "Legacy and Monadic loops should produce identical results with same seed")))))

(deftest test-runner-parity-multi-seed
  (testing "Parity across multiple random seeds"
    (let [params {:escrow-size 50000
                  :resolver-fee-bps 100
                  :appeal-bond-bps 200
                  :appeal-window-duration 86400
                  :strategy :lazy}
          seeds [1 2 3 4 5 10 20 50 100 1000]]
      (doseq [seed seeds]
        (let [rng1 (seeded-rng seed)
              legacy (runner/run-trial rng1 (assoc params :attributed? false))
              
              rng2 (seeded-rng seed)
              monadic (runner/run-trial rng2 (assoc params :attributed? true))]
          (is (= legacy monadic) (str "Failed parity for seed " seed)))))))
