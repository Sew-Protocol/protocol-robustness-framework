(ns resolver-sim.contract-model.replay-simple-characterization-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as flags]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.protocols.dummy :as dummy]
            [resolver-sim.protocols.yield :as yp]))

;; ===========================================================================
;; Generic protocol path — current behaviour characterization
;; ===========================================================================

(def minimal-scenario
  {:scenario-id "simple-char-minimal"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest simple-replay-missing-schema-version-defaults-to-1-0
  (let [no-version (dissoc minimal-scenario :schema-version)
        result (replay/simple-replay dummy/protocol no-version)]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-supplied-schema-version-preserved
  (let [scenario (assoc minimal-scenario :schema-version "1.0")
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result))
        "schema-version \"1.0\" accepted and preserved")))

(deftest simple-replay-no-evidence-chain-finalization
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))
    (is (not (contains? result :evidence/hash)))
    (is (not (contains? result :evidence/signature)))))

(deftest simple-replay-no-theory-diagnostics
  (let [scenario (assoc minimal-scenario :theory {:expectations []})
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (not (contains? result :evidence/hash))
        "no evidence/hash")))

(deftest simple-replay-temporal-disabled-by-default
  (let [scenario (assoc minimal-scenario :temporal-evidence {:enabled? true})
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-expectations-disabled-by-default
  (let [scenario (assoc minimal-scenario :expected-errors [{:seq 0 :action "noop" :error "some-error"}])
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (pos? (:events-processed result)))))

(deftest simple-replay-dispatch-and-invariants-run
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= 1 (:events-processed result)))
    (is (vector? (:trace result)))
    (is (= :ok (:result (first (:trace result)))))))

(deftest simple-replay-result-contains-core-keys
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (contains? result :outcome))
    (is (contains? result :trace))
    (is (contains? result :metrics))
    (is (contains? result :events-processed))
    (is (contains? result :protocol))
    (is (contains? result :replay-profile))
    (is (contains? result :protocol-id))
    (is (= :simple (:replay-profile result)))))

;; ===========================================================================
;; Yield path — current behaviour characterization
;; ===========================================================================

(def yield-scenario
  {:scenario-id "yield-char-test"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "vault" :address "0xVault"}]
   :protocol-params {:yield-profile "aave-v3" :default-owner-id "vault"}
   :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"}}}}}
   :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
             :params {:token "USDC" :amount 10000}}
            {:seq 1 :time 2000 :agent "vault" :action "yield_accrue"
             :params {:token "USDC" :dt 1000}}]})

(deftest yield-replay-opts-are-ignored
  (let [result (replay/simple-replay yp/protocol yield-scenario {:run-id "ignored" :minimal false})]
    (is (= :pass (:outcome result)))
    (is (= :yield-thin-loop (get-in result [:execution :engine])))))

(deftest yield-replay-run-id-ignored
  (let [result (replay/simple-replay yp/protocol yield-scenario {:run-id "custom-run-id"})]
    (is (= :pass (:outcome result)))
    (is (= "yield-char-test" (:scenario-id result))
        "scenario-id unchanged; run-id affects context metadata not scenario-id")))

(deftest yield-replay-flag-overrides-ignored
  (let [result (replay/simple-replay yp/protocol yield-scenario {:flags {:strict-validation? true}})]
    (is (= :pass (:outcome result)))
    (is (= :yield-thin-loop (get-in result [:execution :engine])))))

(deftest yield-replay-result-shape-differs-from-generic
  (let [simple-result (replay/simple-replay yp/protocol yield-scenario)]
    (is (= :pass (:outcome simple-result)))
    (is (contains? simple-result :replay-profile)
        "Yield path includes :replay-profile")
    (is (contains? simple-result :protocol-id)
        "Yield path includes :protocol-id")
    (is (contains? simple-result :execution)
        "Yield path includes :execution")
    (is (= :yield-thin-loop (get-in simple-result [:execution :engine]))
        "Yield path uses :yield-thin-loop engine")
    (is (true? (get-in simple-result [:execution :adapter?]))
        "Yield path marks adapter? true")))

(deftest yield-replay-metrics-use-yield-provider-profile
  (let [generic-result (replay/simple-replay dummy/protocol minimal-scenario)
        yield-result (replay/simple-replay yp/protocol yield-scenario)]
    (is (= :pass (:outcome yield-result)))
    (is (not= (keys (:metrics generic-result))
              (keys (:metrics yield-result)))
        "Yield metrics keys differ from generic metrics keys")
    (is (contains? (:metrics yield-result) :yield/principal)
        "Yield metrics include yield-specific keys")))

(deftest yield-replay-unchanged-by-unsupported-opts
  (doseq [opt [:evidence-mode :signing-key :tsa-url]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Simple replay does not support"
          (replay/simple-replay yp/protocol yield-scenario {opt "some-value"}))
        (str "Option " opt " rejected on yield path"))))

(deftest simple-replay-generic-result-includes-execution
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (contains? result :execution))
    (is (= :canonical-loop (get-in result [:execution :engine])))
    (is (= :simple (get-in result [:execution :profile])))))

(deftest simple-replay-accepts-replay-opts-on-generic
  (let [result (replay/simple-replay dummy/protocol minimal-scenario {:run-id "custom-id"})]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-preparation-adds-normalizations
  (let [no-version (dissoc minimal-scenario :schema-version)
        result (replay/simple-replay dummy/protocol no-version)]
    (is (= :pass (:outcome result)))
    (is (contains? result :scenario-normalizations))
    (is (= :schema-version (-> result :scenario-normalizations first :field)))
    (is (= "1.0" (-> result :scenario-normalizations first :value)))))

(deftest simple-replay-preparation-no-normalization-when-version-present
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))
    (is (not (contains? result :scenario-normalizations))
        "No normalizations when :schema-version is present")))

(deftest simple-replay-explicit-arities-work
  (let [r2 (replay/simple-replay dummy/protocol minimal-scenario)
        r3 (replay/simple-replay dummy/protocol minimal-scenario {:run-id "test"})]
    (is (= :pass (:outcome r2)))
    (is (= :pass (:outcome r3)))))

(deftest preparation-is-deterministic
  (let [no-version (dissoc minimal-scenario :schema-version)
        p1 (replay/prepare-simple-scenario no-version)
        p2 (replay/prepare-simple-scenario no-version)]
    (is (= p1 p2))))
