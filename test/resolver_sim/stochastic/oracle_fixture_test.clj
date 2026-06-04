(ns resolver-sim.stochastic.oracle-fixture-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.params :as io-params]
            [resolver-sim.stochastic.detection :as det]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.types :as types]))

(deftest validate-oracle-params-rejects-invalid-scope
  (testing ":scope must be a subset of #{:detection :appeal}"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"subset of"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic :scope #{:verdict}}})))))

(deftest validate-oracle-params-accepts-appeal-scope
  (testing ":scope #{:appeal} is valid for appeal-only fixtures"
    (let [params (det/validate-oracle-params!
                  {:oracle-fixture {:mode :stochastic :scope #{:appeal}}})]
      (is (= #{:appeal} (:scope (:oracle-effective params)))))))

(deftest validate-oracle-params-rejects-unknown-roll-kind
  (testing "per-kind :rolls keys must be known detection kinds or :default"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown oracle-fixture"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :fixed-roll-sequence
                            :rolls {:fraud-detect [0.1]}
                            :scope #{:detection}}})))))

(deftest validate-oracle-params-rejects-orphan-oracle-roll-sequence
  (testing ":oracle-roll-sequence is orphan when effective mode is not :fixed-roll-sequence"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Orphan oracle legacy"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic}
           :oracle-roll-sequence [0.1 0.2]})))))

(deftest validate-oracle-params-rejects-conflicting-oracle-mode
  (testing ":oracle-mode conflicts with :oracle-fixture :mode"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Orphan oracle legacy"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic}
           :oracle-mode :fixed-roll-sequence})))))

(deftest fixed-or-merges-over-oracle-fixture-and-validates
  (testing ":fixed-or wins on merge; effective mode is :fixed-roll-sequence"
    (let [params (det/validate-oracle-params!
                  {:oracle-fixture {:mode :stochastic :rolls [0.5]}
                   :fixed-or [0.99 0.01]
                   :oracle-roll-sequence [0.3]})
          effective (:oracle-effective params)]
      (is (= :fixed-roll-sequence (:mode effective)))
      (is (= [0.99 0.01] (:rolls effective))))))

(deftest appeal-fixture-scripts-l1-reversal
  (testing ":scope #{:appeal} uses fixed roll for L1 reversal threshold"
    (let [params (det/prepare-oracle-params
                  {:rng (rng/make-rng 1)
                   :oracle-fixture {:mode :fixed-roll-sequence
                                    :rolls {:l1-reversal [0.01]}
                                    :scope #{:appeal}
                                    :on-exhaustion :throw}
                   :p-l1-reversal 0.5
                   :has-kleros? false})
          outcome (det/appeal-reversal-outcome (rng/make-rng 99) params
                                             {:verdict-correct? false
                                              :appealed? true})]
      (is (true? (:l1-reversed? outcome)))
      (is (true? (:decision-reversed? outcome))))))

(deftest prepare-oracle-params-attaches-effective-and-cursors
  (testing "prepare-oracle-params supplies cursors and :oracle-effective"
    (let [params (det/prepare-oracle-params
                  {:oracle-fixture {:mode :static-no-slash}
                   :oracle-roll-trace-enabled? true})]
      (is (instance? clojure.lang.Atom (:oracle-roll-cursor params)))
      (is (= :static-no-slash (:mode (:oracle-effective params))))
      (is (instance? clojure.lang.Atom (:oracle-roll-trace params))))))

(deftest full-trial-control-edn-loads
  (testing "control-oracle-full-trial.edn validates and includes appeal scope"
    (let [merged (io-params/validate-and-merge
                  "data/params/control-oracle-full-trial.edn")]
      (is (= #{:detection :appeal} (:scope (:oracle-effective merged)))))))

(deftest resolve-dispute-fixed-or-per-kind-reversal-trace
  (testing "scripted reversal detection appears in :oracle-roll-trace"
    (let [rng   (rng/make-rng 101)
          result
          (dispute/resolve-dispute
           rng 10000 150 700 0 :collusive 0 1.0 0
           :p-l1-reversal 1.0
           :fraud-detection-probability 0
           :timeout-detection-probability 0
           :slashing-detection-probability 0
           :reversal-slash-bps 2500
           :reversal-detection-probability 0.25
           :oracle-fixture {:mode :fixed-roll-sequence
                            :rolls {:reversal-detection [0.20]
                                    :fraud-detection [0.99]
                                    :timeout-detection [0.99]
                                    :l1-detection [0.99]}
                            :scope #{:detection}
                            :on-exhaustion :throw}
           :oracle-roll-trace-enabled? true)]
      (is (seq (:oracle-roll-trace result)))
      (is (some #(= :reversal-detection (:roll/kind %)) (:oracle-roll-trace result)))
      (when (= :reversal (:slashing-reason result))
        (is (true? (:slashed? result))))))

(deftest control-oracle-fixed-roll-params-load
  (testing "control fixture EDN passes validate-and-merge"
    (let [merged (io-params/validate-and-merge
                  "data/params/control-oracle-fixed-roll-sequence.edn")]
      (is (= :fixed-roll-sequence
             (:mode (:oracle-effective merged)))))))

(deftest types-validate-scenario-runs-oracle-check
  (testing "validate-scenario invokes oracle validation"
    (is (types/validate-scenario
         (merge types/default-params
                {:oracle-fixture {:mode :static-no-slash}}))))))
