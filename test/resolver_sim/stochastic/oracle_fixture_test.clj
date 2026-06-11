(ns resolver-sim.stochastic.oracle-fixture-test
  "Oracle fixture / :fixed-or validation, control EDN loads, and resolve-dispute traces.

   See also resolver-sim.stochastic.reproducibility-test (roll consumption, static modes).
   Param authoring: data/params/PHASES.md (Oracle Fixtures section)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.params :as io-params]
            [resolver-sim.stochastic.detection :as det]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.types :as types]))

(def ^:private control-oracle-param-files
  {"data/params/control-oracle-fixed-roll-sequence.edn" :fixed-roll-sequence
   "data/params/control-oracle-full-trial.edn"           :fixed-roll-sequence
   "data/params/control-oracle-always-detect.edn"        :static-always-detect
   "data/params/control-oracle-static-no-slash.edn"      :static-no-slash})

(def ^:private resolve-dispute-optional-keys
  [:fraud-detection-probability :timeout-detection-probability
   :reversal-detection-probability :reversal-slash-bps :fraud-slash-bps
   :timeout-slash-bps :l2-detection-prob :p-l1-reversal :p-l2-escalation
   :p-l2-reversal :has-kleros? :oracle-fixture :oracle-mode
   :oracle-roll-sequence :oracle-roll-on-exhaustion :fixed-or
   :oracle-roll-trace-enabled?])

(defn- optional-kw-args
  "Keyword args for resolve-dispute — only keys present on p (false booleans kept)."
  [p]
  (mapcat (fn [k]
            (when (contains? p k) [k (get p k)]))
          resolve-dispute-optional-keys))

(defn- resolve-dispute-from-merged-params
  [p]
  (let [rng (rng/make-rng (or (:rng-seed p) 42))]
    (apply dispute/resolve-dispute
           rng
           (long (or (:escrow-size p) (get-in p [:escrow-distribution :mean] 10000)))
           (:resolver-fee-bps p 150)
           (:appeal-bond-bps p 700)
           (:slash-multiplier p 2.5)
           (or (:force-strategy p) :honest)
           (:appeal-probability-if-correct p 0.05)
           (:appeal-probability-if-wrong p 0.40)
           (:slashing-detection-probability p 0.1)
           (optional-kw-args p))))

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
  (testing ":fixed-or wins on merge; :oracle-roll-sequence is not an orphan once mode is :fixed-roll-sequence"
    (let [params (det/validate-oracle-params!
                  {:oracle-fixture {:mode :stochastic :rolls [0.5]}
                   :fixed-or [0.99 0.01]
                   ;; Legacy key is allowed here (not orphan) because :fixed-or sets effective mode.
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

(deftest all-control-oracle-param-files-validate
  (testing "every data/params/control-oracle-*.edn loads with expected :oracle-effective :mode"
    (doseq [[path expected-mode] control-oracle-param-files]
      (let [merged (io-params/validate-and-merge path)]
        (is (= expected-mode (:mode (:oracle-effective merged)))
            (str path " effective mode"))
        (is (map? (:oracle-effective merged))
            (str path " has :oracle-effective")))))
  (testing "control-oracle-full-trial.edn includes appeal + detection scope"
    (let [merged (io-params/validate-and-merge
                  "data/params/control-oracle-full-trial.edn")]
      (is (= #{:detection :appeal} (:scope (:oracle-effective merged)))))))

(deftest control-oracle-full-trial-resolve-dispute-trace
  (testing "control-oracle-full-trial.edn drives appeal + reversal rolls in :oracle-roll-trace"
    (let [p (io-params/validate-and-merge "data/params/control-oracle-full-trial.edn")
          result (resolve-dispute-from-merged-params p)
          kinds (set (map :roll/kind (:oracle-roll-trace result)))]
      (is (true? (:oracle-roll-trace-enabled? p)))
      (is (seq (:oracle-roll-trace result)))
      (is (true? (:appeal-triggered? result)))
      (is (contains? kinds :l1-reversal)
          "appeal scope scripts L1 reversal roll")
      (is (contains? kinds :reversal-detection)
          "detection scope scripts reversal slash roll")
      (is (= :reversal (:slashing-reason result))))))

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

(deftest types-validate-scenario-runs-oracle-check
  (testing "validate-scenario invokes oracle validation"
    (is (types/validate-scenario
         (merge types/default-params
                {:oracle-fixture {:mode :static-no-slash}})))))

;; ── Per-kind detection activation tests ─────────────────────────────────

(deftest fixed-or-fraud-detection-active
  (testing ":fixed-or :fraud-detection rolls are consumed when fraud-detection-probability > 0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 0.05 0.40 0.1
                  :fraud-detection-probability 0.5
                  :fixed-or {:rolls {:fraud-detection [0.01]}
                             :scope #{:detection}
                             :on-exhaustion :throw
                             :on-unknown-roll-kind :stochastic}
                  :oracle-roll-trace-enabled? true)]
      (is (some #(= :fraud-detection (:roll/kind %))
                (:oracle-roll-trace result))
          ":fraud-detection roll consumed when threshold > 0"))))

(deftest fixed-or-timeout-detection-active
  (testing ":fixed-or :timeout-detection rolls are consumed when timeout-detection-probability > 0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 43) 10000 150 700 2.5 :malicious 0.05 0.40 0.1
                  :timeout-detection-probability 0.5
                  :fixed-or {:rolls {:timeout-detection [0.01]}
                             :scope #{:detection}
                             :on-exhaustion :throw
                             :on-unknown-roll-kind :stochastic}
                  :oracle-roll-trace-enabled? true)]
      (is (some #(= :timeout-detection (:roll/kind %))
                (:oracle-roll-trace result))
          ":timeout-detection roll consumed when threshold > 0"))))

(deftest fixed-or-l2-detection-active
  (testing ":fixed-or :l2-detection rolls are consumed when l2-detection-prob > 0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 44) 10000 150 700 2.5 :malicious 0.05 0.40 0.1
                  :l2-detection-prob 0.5
                  :p-l1-reversal 1.0
                  :fixed-or {:rolls {:l2-detection [0.01]}
                             :scope #{:detection}
                             :on-exhaustion :throw
                             :on-unknown-roll-kind :stochastic}
                  :oracle-roll-trace-enabled? true)]
      (is (some #(= :l2-detection (:roll/kind %))
                (:oracle-roll-trace result))
          ":l2-detection roll consumed when appeal fires and threshold > 0"))))

;; ── All-9 roll kinds integration test ───────────────────────────────────

(deftest fixed-or-all-roll-kinds-consumed
  (testing "all 9 oracle roll kinds consumed in one trial with :fixed-or"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 45) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0.5
                  :timeout-detection-probability 0.5
                  :reversal-detection-probability 0.5
                  :new-evidence-probability 0.5
                  :l2-detection-prob 0.5
                  :reversal-slash-bps 2500
                  :p-l1-reversal 1.0
                  :p-l2-escalation 1.0
                  :p-l2-reversal 1.0
                  :has-kleros? true
                  :fixed-or {:rolls {:fraud-detection [0.01]
                                     :timeout-detection [0.01]
                                     :pending-evidence [0.01]
                                     :l2-detection [0.01]
                                     :reversal-detection [0.01]
                                     :l1-detection [0.01]
                                     :l1-reversal [0.01]
                                     :l2-escalation [0.01]
                                     :l2-reversal [0.01]}
                             :scope #{:detection :appeal}
                             :on-exhaustion :throw}
                  :oracle-roll-trace-enabled? true)
          trace (:oracle-roll-trace result)
          kinds (set (map :roll/kind trace))]
      (is (seq trace) "roll trace should be non-empty")
      (is (contains? kinds :fraud-detection) ":fraud-detection consumed")
      (is (contains? kinds :timeout-detection) ":timeout-detection consumed")
      (is (contains? kinds :l1-detection) ":l1-detection consumed")
      (is (contains? kinds :reversal-detection) ":reversal-detection consumed")
      (is (contains? kinds :pending-evidence) ":pending-evidence consumed")
      (is (contains? kinds :l2-detection) ":l2-detection consumed")
      (is (contains? kinds :l1-reversal) ":l1-reversal consumed")
      (is (contains? kinds :l2-escalation) ":l2-escalation consumed")
      (is (contains? kinds :l2-reversal) ":l2-reversal consumed")))))
