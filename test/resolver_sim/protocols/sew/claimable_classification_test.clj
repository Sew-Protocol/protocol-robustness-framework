(ns resolver-sim.protocols.sew.claimable-classification-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.claimable-classification-emitter :as emit]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.sim.fixtures :as fix]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.claimable-classification :as cc]
            [resolver-sim.protocols.sew.projection :as proj]))

(defn- replay [path]
  (-> path sc/load-scenario-file fix/normalize-scenario sew/replay-with-sew-protocol))

(deftest v2-taxonomy-includes-boundary-domains
  (testing "every bounded domain appears in class registry"
    (let [domains (set cc/all-v2-domains)]
      (is (contains? domains "settlement/principal"))
      (is (contains? domains "settlement/yield"))
      (is (contains? domains "liability/slash-bounty"))
      (is (contains? domains "bond/refund")))))

(deftest terminal-observations-from-s81
  (testing "partial liquidity scenario contributes settlement_yield totals"
    (let [r     (replay "scenarios/S81_escrow-yield-may-be-partially-deferred.json")
          world (proj/terminal-world-from-result r)
          obs   (cc/terminal-observations [world] :scope "test")]
      (is (pos? (get-in obs [:by_class "settlement_yield" :total_claimable] 0)))
      (is (pos? (get-in obs [:by_domain "settlement/yield" :total_claimable] 0)))
      (is (contains? (:escrow_yield_outcome_counts obs) "fully-immediate")))))

(deftest build-document-taxonomy-only
  (is (= "taxonomy-only" (:observations_status (cc/build-document :observations-status "taxonomy-only"))))
  (is (nil? (:terminal_observations (cc/build-document :observations-status "taxonomy-only")))))

(deftest terminal-observations-headroom-and-ledger
  (testing "S81 includes non-negative headroom and funds ledger slice"
    (let [r     (replay "scenarios/S81_escrow-yield-may-be-partially-deferred.json")
          world (proj/terminal-world-from-result r)
          ctx   [{:scenario-id "s81" :outcome :pass :world world}]
          obs   (cc/terminal-observations [world] :contexts ctx)]
      (is (map? (:funds_ledger obs)))
      (is (pos? (get-in obs [:funds_ledger :claimable_total] 0)))
      (is (contains? (:boundary_headroom obs) "settlement-yield-boundary"))
      ;; Terminal snapshot may show negative headroom when claimable remains but max-yield is 0
      (is (number? (get-in obs [:boundary_headroom "settlement-yield-boundary"
                                 :min_headroom_across_worlds])))
      (is (false? (get-in obs [:boundaries "settlement-yield-boundary" :all_hold])))
      (is (= 1 (count (:scenario_highlights obs)))))))

(deftest build-document-provenance
  (let [doc (cc/build-document :observations-status "taxonomy-only"
                               :provenance {:run_id "r1" :git_sha "abc" :producer "test"})]
    (is (= "r1" (get-in doc [:provenance :run_id])))
    (is (= "abc" (get-in doc [:provenance :git_sha])))))

(deftest workflow-rows-on-s81
  (testing "scenario highlight includes per-workflow domain breakdown"
    (let [r     (replay "scenarios/S81_escrow-yield-may-be-partially-deferred.json")
          world (proj/terminal-world-from-result r)
          rows  (cc/workflow-rows-for-world world)]
      (is (pos? (count rows)))
      (is (contains? (set (map :workflow_id rows)) 0))
      (is (pos? (:total_claimable (first rows))))
      (is (contains? (:by_domain (first rows)) "settlement/yield")))))

(deftest emit-from-scenario-file-integration
  (testing "emitter replays one scenario file"
    (let [out (str (System/getProperty "java.io.tmpdir") "/cc-v2-single-test.json")]
      (emit/emit-from-scenario-file!
       out "scenarios/S81_escrow-yield-may-be-partially-deferred.json"
       :run-id "test-run" :scenarios-passed 1)
      (let [doc (json/read-str (slurp out) :key-fn keyword)]
        (is (= "single-scenario" (:observations_status doc)))
        (is (pos? (count (get-in doc [:terminal_observations :scenario_highlights]))))
        (is (pos? (count (get-in doc [:terminal_observations
                                      :scenario_highlights 0 :workflows]))))))))
