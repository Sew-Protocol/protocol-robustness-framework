(ns resolver-sim.sim.stochastic-equilibrium-test
  "Tests for stochastic-equilibrium claim evaluators:
   - evaluate-participation-stable (per-strategy decomposition)
   - evaluate-mech-budget-balance  (flow-conservation reconciliation)"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.stochastic-equilibrium :as sut]))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-participation-stable
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-participation-stable-malice-attrition-passes
  (testing "High malicious attrition with strong honest retention passes"
    (let [result {:initial-resolver-count 130
                  :initial-composition {:honest-count 50 :lazy-count 50
                                        :malicious-count 25 :collusive-count 5
                                        :malice-count 30 :total-count 130
                                        :honest-share 0.385 :malice-share 0.231}
                  :aggregated-stats {:total-resolver-exits 30
                                     :honest-exit-count 0
                                     :lazy-exit-count 0
                                     :malicious-exit-count 25
                                     :collusive-exit-count 5
                                     :honest-cumulative-profit 0.0
                                     :malice-cumulative-profit 0.0
                                     :honest-avg-win-rate 0.0
                                     :malice-avg-win-rate 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :participation-stable (:claim-id %)) %) (:claim-results report))]
      (is (= :pass (:status claim))
          "0 honest/lazy exits out of 100 → productive rate 0% < 20%")
      (is (some? (:productive-exit-rate (:evidence claim)))
          "evidence includes :productive-exit-rate")
      (is (= 0.0 (get-in claim [:evidence :productive-exit-rate]))
          "productive-exit-rate is 0.0")
      (is (= 30 (:total-exits (:evidence claim)))
          "evidence shows 30 total exits, all malicious/collusive")
      (is (= 25 (get-in claim [:evidence :malicious-exits]))
          "25 malicious exits reported"))))

(deftest test-participation-stable-honest-attrition-fails
  (testing "High honest attrition fails even when overall exits are below 40%"
    (let [result {:initial-resolver-count 130
                  :initial-composition {:honest-count 50 :lazy-count 50
                                        :malicious-count 25 :collusive-count 5
                                        :malice-count 30 :total-count 130
                                        :honest-share 0.385 :malice-share 0.231}
                  :aggregated-stats {:total-resolver-exits 25
                                     :honest-exit-count 25
                                     :lazy-exit-count 0
                                     :malicious-exit-count 0
                                     :collusive-exit-count 0
                                     :honest-cumulative-profit 0.0
                                     :malice-cumulative-profit 0.0
                                     :honest-avg-win-rate 0.0
                                     :malice-avg-win-rate 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :participation-stable (:claim-id %)) %) (:claim-results report))]
      (is (= :fail (:status claim))
          "25/100 productive exits = 25% ≥ 20%, aggregate=19.2% < 40% → still fail")
      (is (= (double 0.25) (get-in claim [:evidence :productive-exit-rate]))
          "productive-exit-rate is 0.25")
      (is (= (double (/ 25 130)) (get-in claim [:evidence :aggregate-exit-rate]))
          "aggregate-exit-rate is ~0.192"))))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-mech-budget-balance
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-budget-balance-flow-conservation-passes
  (testing "Positive resolver profit funded by explicit fees passes budget balance"
    (let [;; Flow identity: fees = resolver_net + bond_loss - fraud_upside
          ;; resolver_net = 80000 + (-10000) = 70000
          ;; 100000 - 70000 - 30000 + 0 = 0 → pass
          result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:dominance-ratio 1.0 :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 80000.0
                                     :malice-cumulative-profit -10000.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5
                                     :flow-total-fees-collected 100000.0
                                     :flow-total-bond-loss 30000.0
                                     :flow-total-fraud-upside 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])
          residual (get-in mech [:evidence :residual])]
      (is (= :pass (:status mech))
          "flow conserved: 100000 - 70000 - 30000 + 0 = 0")
      (is (<= (Math/abs (double residual)) 1.0)
          (format "residual = %.0f (should be ~0)" residual)))))

(deftest test-budget-balance-flow-conservation-fails
  (testing "An unaccounted value creation fails budget balance"
    (let [;; Inconsistent data: fees alone suggest resolver_net should be 70000
          ;; but actual resolver_net is only 50000 (60000 + -10000)
          ;; residual = 100000 - 50000 - 30000 + 0 = 20000 ≫ 1
          result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:dominance-ratio 1.0 :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 60000.0
                                     :malice-cumulative-profit -10000.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5
                                     :flow-total-fees-collected 100000.0
                                     :flow-total-bond-loss 30000.0
                                     :flow-total-fraud-upside 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])
          residual (get-in mech [:evidence :residual])]
      (is (= :fail (:status mech))
          "unaccounted: 100000 - 50000 - 30000 + 0 = 20000 > 1 wei → fail")
      (is (not (<= (Math/abs (double residual)) 1.0))
          (format "residual = %.0f should be ≫ 0" residual)))))

(deftest test-budget-balance-reports-all-components
  (testing "Every reported balance component is included in the reconciliation"
    (let [;; Consistent data: resolver_net = 96000 + (-40750) = 55250
          ;; 100000 - 55250 - 45000 + 250 = 0 → pass
          result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:dominance-ratio 1.0 :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 96000.0
                                     :malice-cumulative-profit -40750.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5
                                     :flow-total-fees-collected 100000.0
                                     :flow-total-bond-loss 45000.0
                                     :flow-total-fraud-upside 250.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])
          ev (:evidence mech)]
      (is (= 5 (count ev))
          "evidence map has exactly 5 keys (fees, resolver-net, bond, fraud, residual)")
      (is (contains? ev :total-fees-collected) "evidence includes :total-fees-collected")
      (is (contains? ev :resolver-profit-net-sum) "evidence includes :resolver-profit-net-sum")
      (is (contains? ev :total-bond-loss) "evidence includes :total-bond-loss")
      (is (contains? ev :total-fraud-upside) "evidence includes :total-fraud-upside")
      (is (contains? ev :residual) "evidence includes :residual")
      (let [fees (:total-fees-collected ev)
            rnet (:resolver-profit-net-sum ev)
            bond (:total-bond-loss ev)
            fraud (:total-fraud-upside ev)
            residual (:residual ev)
            expected-residual (+ (- fees rnet bond) fraud)]
        (is (= (double expected-residual) (double residual))
            (format "residual = %.0f matches expected = %.0f" residual expected-residual))))))
