(ns resolver-sim.protocols.sew.snapshot-test
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.snapshot :as snap]
            [resolver-sim.protocols.sew.snapshot-presets :as presets]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.presets :as yield-presets]
            [resolver-sim.yield.registry :as yield-reg]))

(deftest test-make-escrow-snapshot-aliases-make-module-snapshot
  (let [params {:escrow-fee-bps 50 :max-dispute-duration 3600}]
    (is (= (snap/make-escrow-snapshot params)
           (t/make-module-snapshot params)))))

(deftest test-sew-preset-dispute-heavy-keyword
  (is (some? (presets/preset->protocol-params :sew.preset/dispute-heavy)))
  (is (pos? (:appeal-window-duration
             (presets/preset->snapshot :sew.preset/dispute-heavy)))))

(deftest test-validate-snapshot-structured-error-data
  (try
    (snap/validate-snapshot {:escrow-fee-bps -1})
    (is false "expected validation throw")
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (is (= :snapshot/non-negative-integer (:error/type d)))
        (is (= :escrow-fee-bps (:snapshot/field d)))
        (is (= -1 (:actual d)))
        (is (string? (:hint d)))))))

(deftest test-validate-snapshot-rejects-inconsistent-yield-fields
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"inconsistent"
                        (snap/validate-snapshot
                         {:escrow-fee-bps 0
                          :yield-profile :aave-v3
                          :yield-archetype :fixed-rate
                          :yield-generation-module :none}))))

(deftest test-snapshot-from-protocol-params-skips-validation-when-disabled
  (let [snap (snap/snapshot-from-protocol-params
              {:resolver-fee-bps -1 :yield-profile :aave-v3}
              {:validate? false})]
    (is (= -1 (:escrow-fee-bps snap)))))

(deftest test-validate-snapshot-with-world-requires-registered-yield-module
  (let [world (yield-reg/init-yield-modules {})]
    (is (snap/validate-snapshot
         {:escrow-fee-bps 50 :yield-generation-module :aave-v3}
         {:world world}))
    (try
      (snap/validate-snapshot
       {:escrow-fee-bps 50 :yield-generation-module :unknown-yield}
       {:world world})
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :snapshot/yield-module-not-registered (:error/type (ex-data e))))))))

(deftest test-snapshot-from-protocol-params-resolves-yield-profile
  (let [snap (snap/snapshot-from-protocol-params
              {:resolver-fee-bps 50 :yield-profile :aave-v3})]
    (is (= 50 (:escrow-fee-bps snap)))
    (is (= :aave-v3 (:yield-profile snap)))
    (is (= :yield.provider/liquid-lending (:yield-archetype snap)))
    (is (= :yield.provider/liquid-lending (:yield-generation-module snap)))))

(deftest test-aave-profile-snapshot-create-escrow-yield-coherence
  (testing "profile id, archetype id, registry dispatch, and yield-config aliases stay coherent"
    (let [pp {:resolver-fee-bps 50 :yield-profile :aave-v3}
          world0 (-> (t/empty-world 1000)
                     yield-reg/init-yield-modules
                     (yield-presets/apply-preset :yield.preset/aave-baseline))
          snap (snap/snapshot-from-protocol-params pp {:world world0 :validate-world? true})
          settings (t/make-escrow-settings {:yield-preset :to-recipient})
          {:keys [ok world workflow-id]}
          (lc/create-escrow world0 "sender" "USDC" "recipient" 10000 settings snap)
          ymid (:yield-generation-module snap)
          pos (get-in world [:yield/positions (t/escrow-yield-owner-id workflow-id)])]
      (is ok)
      (is (= :yield.provider/liquid-lending ymid))
      (is (= :aave-v3 (:yield-profile snap)))
      (is (= :yield.provider/liquid-lending
             (get-in world0 [:yield/module-aliases :aave-v3])))
      (is (= ymid (:module/id pos)))
      (is (= 0.05 (get-in world [:yield/rates :yield.provider/liquid-lending :USDC])))
      (is (contains? (:yield/modules world0) ymid))
      (is (contains? (:yield/modules world0) :aave-v3)))))

(deftest test-sew-preset-yield-aave
  (let [snap (presets/preset->snapshot :sew.preset/yield-aave)]
    (is (= :aave-v3 (:yield-profile snap)))
    (is (= :yield.provider/liquid-lending (:yield-archetype snap)))
    (is (= :yield.provider/liquid-lending (:yield-generation-module snap)))))

(deftest test-sew-preset-zero-fee
  (is (zero? (:escrow-fee-bps (presets/preset->snapshot :sew.preset/zero-fee)))))
