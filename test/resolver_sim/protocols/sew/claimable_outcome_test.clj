(ns resolver-sim.protocols.sew.claimable-outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.sim.fixtures :as fix]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.protocols.sew.claimable-outcome :as outcome]
            [resolver-sim.protocols.sew.projection :as proj]
            [resolver-sim.scenario.partial-liquidity-metrics :as plm]))

(defn- replay [path]
  (-> path sc/load-scenario-file fix/normalize-scenario sew/replay-with-sew-protocol))

(deftest s81-escrow-yield-partial-deferred-replay
  (testing "S81 reference scenario passes with expectations"
    (let [r (replay "scenarios/S81_escrow-yield-may-be-partially-deferred.json")]
      (is (= :pass (:outcome r)))
      (when-let [exp (:expectations r)]
        (is (:ok? exp) (:violations exp))))))

(deftest may-be-partially-deferred-at-release
  (testing "After release, escrow_yield outcome is may-be-partially-deferred"
    (let [r (replay "scenarios/S81_escrow-yield-may-be-partially-deferred.json")
          w (:world (nth (:trace r) 1))]
      (is (= :may-be-partially-deferred
             (outcome/escrow-yield-shortfall-outcome w 0))))))

(deftest projection-includes-escrow-yield-outcomes
  (testing "trace-end projection surfaces observed shortfall outcomes"
    (let [r (replay "scenarios/S81_escrow-yield-may-be-partially-deferred.json")
          p (proj/trace-end-projection r)
          detail (get-in p [:escrow-yield-outcomes 0])]
      (is (map? detail))
      (is (= "fully-immediate" (:manifest-label detail))))))

(deftest y03-partial-liquidity-provider-metrics
  (testing "Y03 provider replay maps to may-be-partially-deferred metrics"
    (let [scenario (-> "scenarios/yield/Y03_partial-liquidity-shortfall-affected.json"
                       sc/load-scenario-file
                       fix/normalize-scenario)
          r (replay/simple-replay yp/protocol scenario)
          m (plm/partial-liquidity-outcome-metrics r scenario)]
      (is (:pass? m))
      (is (= :may-be-partially-deferred (:yield/shortfall-outcome m)))
      (is (= 400 (:yield/position-deferred m)))
      (is (= 400 (:yield/position-realized m))))))
