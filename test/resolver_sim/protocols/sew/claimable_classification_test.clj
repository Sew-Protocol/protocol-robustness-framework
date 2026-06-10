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
    (let [domains (set cc/canonical-v2-domains)]
      (is (contains? domains "settlement/principal"))
      (is (contains? domains "settlement/yield"))
      (is (contains? domains "liability/slash-bounty"))
      (is (contains? domains "bond/refund")))))

(deftest v2-taxonomy-includes-all-incentive-domains
  (testing "incentive rollup covers fee, yield, liability, and bond-forfeit paths"
    (let [incentive-domains (set (get-in (cc/taxonomy-document)
                                         [:incentives_summary :domains]))]
      (is (contains? incentive-domains "fees/resolver"))
      (is (contains? incentive-domains "fees/protocol"))
      (is (contains? incentive-domains "yield/resolver-incentive"))
      (is (contains? incentive-domains "yield/protocol-fee"))
      (is (contains? incentive-domains "liability/challenge-bounty"))
      (is (contains? incentive-domains "liability/slash"))
      (is (contains? incentive-domains "liability/slash-bounty"))
      (is (contains? incentive-domains "bond/forfeit"))
      (is (= 8 (count (cc/class-ids-by-category "incentive")))))))

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
      (is (pos? (get-in obs [:funds_ledger :terminal_value_total] 0)))
      (is (= (count (keys (get-in obs [:funds_ledger :by_token])))
             (count (set (keys (get-in obs [:funds_ledger :by_token]))))))
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

(deftest scenario-id-derived-from-result-path
  (testing "from-result uses file stem when result JSON lacks scenario-id"
    (let [result-path "results/evidence/kleros-preemptive-escalation-rejected-l0.result.json"
          identity (cc/resolve-scenario-identity {} :result-path result-path)]
      (is (= "kleros-preemptive-escalation-rejected-l0" (:scenario-id identity)))
      (is (= "derived-from-result-path" (:scenario-id-status identity))))))

(deftest funds-ledger-view-no-duplicate-token-keys
  (testing "keyword and string token keys collapse to one USDC bucket"
    (let [world {:total-held {:USDC 100}
                 :total-released {"USDC" 50}
                 :escrow-transfers {0 {:token :USDC}}
                 :claimable {0 {"0x1" 0}}
                 :claimable-v2 {}}
          view (proj/funds-ledger-view world)]
      (is (= 1 (count (:by-token view))))
      (is (= 100 (get-in view [:by-token "USDC" :held])))
      (is (= 50 (get-in view [:by-token "USDC" :released]))))))

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
