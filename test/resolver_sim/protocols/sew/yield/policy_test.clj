(ns resolver-sim.protocols.sew.yield.policy-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer :all]
            [resolver-sim.io.scenarios :as scen-io]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.yield.policy :as yield-policy]
            [resolver-sim.yield.registry :as yield-reg]
            [resolver-sim.yield.ops :as yield-ops]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew.claimable-outcome :as claim-outcome]
            [resolver-sim.time.model                  :as time.model]))

(defn- load-scenario [path]
  (scen-io/load-scenario-file path))

(deftest test-json-string-yield-preset-routes-to-recipient-on-release
  (testing "String yield-preset from JSON scenarios matches policy keywords"
    (let [scenario {:initial-block-time 1000}
          world (-> (proto/init-world sew/protocol scenario)
                    (assoc-in [:yield/rates :fixed-rate "USDC"] 0.05))
          snapshot (snap-fix/escrow-snapshot {:yield-generation-module :fixed-rate})
          res1 (lc/create-escrow world "sender" "USDC" "recipient" 10000
                                 (t/make-escrow-settings {:yield-preset "to-recipient"})
                                 snapshot)
          world1 (:world res1)
          world2 (assoc world1 :block-time (+ 1000 31536000))
          world3 (lc/accrue-yield world2 0)
          res3 (lc/release world3 0 "sender" (fn [_ _ _] {:allowed? true}))
          world4 (:world res3)]
      (is (:ok res1))
      (is (pos? (get-in world3 [:escrow-transfers 0 :accumulated-yield])))
      (is (= 10500 (get-in world4 [:claimable 0 "recipient"]))))))

(deftest test-fixed-yield-lifecycle
  (testing "Escrow with fixed-rate yield accrues correctly"
    (let [scenario {:initial-block-time 1000}
          world (-> (proto/init-world sew/protocol scenario)
                    (assoc-in [:yield/rates :fixed-rate "USDC"] 0.05)) ; 5% APY
          
          snapshot (snap-fix/escrow-snapshot {:yield-generation-module :fixed-rate})
          res1  (lc/create-escrow world "sender" "USDC" "recipient" 10000 
                                 (t/make-escrow-settings {:yield-preset :to-recipient})
                                 snapshot)
          world1 (:world res1)
          world2 (assoc world1 :block-time (+ 1000 31536000))
          world3 (lc/accrue-yield world2 0)
          
          res3  (lc/release world3 0 "sender" (fn [_ _ _] {:allowed? true}))
          world4 (:world res3)]
      
      (is (:ok res1))
      (let [pos (get-in world3 [:yield/positions [:sew/escrow 0]])]
        (is (= 10000 (:principal pos)))
        (is (= 500 (:unrealized-yield pos)))) 
      
      (is (= 10500 (get-in world4 [:claimable 0 "recipient"]))))))

(deftest test-aave-yield-replay-lifecycle
  (testing "Replay scenario can configure and settle Aave v3 yield through the existing yield DSL"
    (let [scenario {:scenario-id "yield-aave-v3-release"
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :yield-config {:modules {:aave-v3 {:tokens {"USDC" {:initial-index 1.0
                                                                        :apy 0.05
                                                                        :liquidity-mode :available
                                                                        :loss-mode :none}}}}}
                    :protocol-params {:yield-generation-module :aave-v3
                                      :yield-protocol-fee-bps 1000}
                    :agents [{:id "sender" :address "sender"}
                             {:id "recipient" :address "recipient"}]
                    :events [{:seq 0 :time 1000 :agent "sender" :action "create_escrow"
                              :params {:token "USDC" :to "recipient" :amount 10000
                                       :yield-preset :to-recipient}}
                             {:seq 1 :time (+ 1000 31536000) :agent "sender" :action "release"
                              :params {:workflow-id 0}}]}
          result   (replay/replay-with-protocol sew/protocol scenario)
          world    (-> result :trace last :projection)]
      (is (= :pass (:outcome result)))
      (is (= :released (get-in world [:escrow-transfers 0 :escrow-state])))
      ;; Replay projection intentionally omits :yield/positions and :claimable to
      ;; remain EVM-comparable; those are covered via lower-level lifecycle tests.
      ;; With Aave yield enabled here, fee bucket includes escrow fee (50)
      ;; + yield protocol fee (49) = 99 due integer truncation.
      (is (== 99 (get-in world [:total-fees "USDC"]))))))

(deftest test-yield-long-horizon-aave-s68
  (testing "10y horizon replay remains deterministic and invariant-safe for Aave"
    (let [scenario (load-scenario "scenarios/S68_yield-aave-long-horizon-10y-monthly-accrual.json")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state])))
      (is (pos? (get-in p1 [:total-fees "USDC"]))))))

(deftest test-yield-long-horizon-fixed-s69
  (testing "10y horizon replay remains deterministic and invariant-safe for fixed-rate"
    (let [scenario (load-scenario "scenarios/S69_yield-fixed-long-horizon-10y-quarterly-accrual.json")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state])))
      (is (pos? (get-in p1 [:total-fees "USDC"]))))))

(deftest test-yield-rounding-drift-s73
  (testing "Low-APY repeated accrual path remains deterministic and invariant-safe"
    (let [scenario (load-scenario "scenarios/S73_yield-rounding-drift-repeated-small-accruals.json")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state])))
      ;; Rounding-sensitive run should not create negative/invalid fee states.
      (is (<= 0 (get-in p1 [:total-fees "USDC"]))))))

(deftest test-snapshot-yield-module-identity-immutable
  (testing "Escrow snapshot keeps yield module identity/profile/archetype even after world yield config changes"
    (let [scenario {:initial-block-time 1000}
          world0   (-> (proto/init-world sew/protocol scenario)
                       (yield-reg/apply-yield-config
                        {:modules {:aave-v3 {:tokens {"USDC" {:initial-index 1.0 :apy 0.05}}}}}))
          snapshot (snap-fix/escrow-snapshot
                    {:yield-module-id :module/aave-yield
                     :yield-profile :aave-v3
                     :yield-archetype :yield.provider/liquid-lending
                     :yield-generation-module :yield.provider/liquid-lending})
          created  (lc/create-escrow world0 "sender" "USDC" "recipient" 10000
                                     (t/make-escrow-settings {:yield-preset :to-recipient})
                                     snapshot)
          world1   (:world created)
          ;; mutate global/module config after creation
          world2   (-> world1
                       (assoc-in [:yield/module-aliases :aave-v3] :fixed-rate)
                       (assoc-in [:yield/module-status :yield.provider/liquid-lending] :paused))
          snap'    (t/get-snapshot world2 0)]
      (is (:ok created))
      (is (= :module/aave-yield (:yield-module-id snap')))
      (is (= :aave-v3 (:yield-profile snap')))
      (is (= :yield.provider/liquid-lending (:yield-archetype snap')))
      (is (= :yield.provider/liquid-lending (:yield-generation-module snap'))))))

(deftest test-yield-partial-liquidity-release-s78
  (testing "Partial-liquidity yield profile still replays deterministically and settles release path"
    (let [scenario (load-scenario "scenarios/S78_yield-aave-partial-liquidity-release.json")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state])))
      (is (pos? (get-in p1 [:total-fees "USDC"]))))))

(deftest test-yield-partial-liquidity-release-s81
  (testing "S81 exercises may-be-partially-deferred through release and deferred recovery"
    (let [scenario (load-scenario "scenarios/S81_escrow-yield-may-be-partially-deferred.json")
          r        (replay/replay-with-protocol sew/protocol scenario)
          w-release (:world (nth (:trace r) 1))]
      (is (= :pass (:outcome r)))
      (is (= :may-be-partially-deferred
             (claim-outcome/escrow-yield-shortfall-outcome w-release 0))))))

(deftest test-yield-partial-liquidity-dispute-resolution-s79
  (testing "Partial-liquidity profile under dispute resolution remains deterministic and invariant-safe"
    (let [scenario (load-scenario "scenarios/S79_yield-aave-partial-liquidity-dispute-resolution.json")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state])))
      (is (pos? (get-in p1 [:total-fees "USDC"]))))))

(deftest test-yield-partial-liquidity-governance-disable-post-create-s80
  (testing "Partial-liquidity + disabled-for-new-deposits remains deterministic and settled for existing escrow"
    (let [scenario (load-scenario "scenarios/S80_yield-aave-partial-liquidity-governance-disable-post-create.json")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state])))
      (is (pos? (get-in p1 [:total-fees "USDC"]))))))
