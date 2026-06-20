(ns resolver-sim.protocols.sew.yield.policy-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer :all]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.yield.registry :as yield-reg]))

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
          world2 (time-ctx/advance-time world1 {:seconds 31536000})
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
          world2 (time-ctx/advance-time world1 {:seconds 31536000})
          world3 (lc/accrue-yield world2 0)

          res3  (lc/release world3 0 "sender" (fn [_ _ _] {:allowed? true}))
          world4 (:world res3)]

      (is (:ok res1))
      (let [pos (get-in world3 [:yield/positions [:sew/escrow 0]])]
        (is (= 10000 (:principal pos)))
        (is (= 500 (:unrealized-yield pos))))

      (is (= 10500 (get-in world4 [:claimable 0 "recipient"]))))))

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
