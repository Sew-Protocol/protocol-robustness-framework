(ns resolver-sim.sim.strategy-adaptation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.defection :as d]
            [resolver-sim.sim.stochastic-equilibrium :as se]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.evidence-costs :as ec]))

(deftest load-optimal-selector-can-produce-lazy
  (testing "under heavy load, load-optimal selector can move resolvers to :lazy"
    (let [resolver-count 8
          histories (into {}
                          (map (fn [i]
                                 [(str "resolver-" i)
                                  {:strategy :honest
                                   :epoch-history {:epoch-1 {:trials 8 :profit 10.0}}}])
                               (range resolver-count)))
          {:keys [updated-histories defection-events diagnostics]}
          (d/apply-strategy-defection
            (rng/make-rng 7)
            histories
            1
            {:n-trials-per-epoch 2000
             :escrow-size 10000
             :resolver-fee-bps 150
             :slashing-detection-probability 0.1
             :slash-multiplier 2.0
             :strategy-adaptation {:enabled true
                                   :rate 1.0
                                   :selector :load-optimal
                                   :allowed-targets #{:honest :lazy :malicious}}
             :strategy-space #{:honest :lazy :malicious}})
          final-strategies (set (map :strategy (vals updated-histories)))]
      (is (empty? diagnostics))
      (is (contains? final-strategies :lazy))
      (is (some #(= :lazy (:to %)) defection-events))
      (is (every? #(= :load-optimal (:selector %)) defection-events)))))

(deftest load-optimal-lazy-persists-in-final-mix
  (testing "when lazy is allowed, lazy remains countable in final strategy mix"
    (let [histories {"resolver-1" {:strategy :honest :epoch-history {:epoch-1 {:trials 5 :profit 5.0}}}
                     "resolver-2" {:strategy :malicious :epoch-history {:epoch-1 {:trials 5 :profit 5.0}}}}
          {:keys [updated-histories]}
          (d/apply-strategy-defection
            (rng/make-rng 13)
            histories
            1
            {:n-trials-per-epoch 2000
             :strategy-adaptation {:enabled true
                                   :rate 1.0
                                   :selector :load-optimal
                                   :allowed-targets #{:honest :lazy :malicious}}
             :strategy-space #{:honest :lazy :malicious}})
          final-mix (frequencies (map :strategy (vals updated-histories)))]
      (is (pos? (get final-mix :lazy 0))))))

(deftest load-level-changes-target-strategy
  (testing "light load selects honest, heavy load selects lazy in evidence model"
    (let [light-opt (ec/optimal-strategy-under-load (rng/make-rng 1) 10 100 0.10 2.0 150.0)
          heavy-opt (ec/optimal-strategy-under-load (rng/make-rng 1) 500 100 0.10 2.0 150.0)]
      (is (= :honest light-opt))
      (is (= :lazy heavy-opt)))))

(deftest unsupported-optimal-strategy-is-diagnostic
  (testing "if optimal strategy is lazy but lazy is disallowed, adaptation emits diagnostics"
    (let [histories {"resolver-07" {:strategy :honest
                                    :epoch-history {:epoch-3 {:trials 12 :profit 9.0}}}}
          {:keys [updated-histories defection-events diagnostics]}
          (d/apply-strategy-defection
            (rng/make-rng 11)
            histories
            3
            {:n-trials-per-epoch 2000
             :strategy-adaptation {:enabled true
                                   :rate 1.0
                                   :selector :load-optimal
                                   :allowed-targets #{:honest :malicious}}
             :strategy-space #{:honest :malicious}})]
      (is (empty? defection-events))
      (is (= :honest (get-in updated-histories ["resolver-07" :strategy])))
      (is (= :target-outside-strategy-space (-> diagnostics first :reason))))))

(deftest transition-summary-is-strategy-general
  (testing "summary emits transition matrix and initial/final strategy mix"
    (let [events [{:resolver-id "r1" :from :honest :to :lazy}
                  {:resolver-id "r2" :from :lazy :to :honest}
                  {:resolver-id "r3" :from :honest :to :malicious}]
          before {"r1" {:strategy :honest}
                  "r2" {:strategy :lazy}
                  "r3" {:strategy :honest}}
          after  {"r1" {:strategy :lazy}
                  "r2" {:strategy :honest}
                  "r3" {:strategy :malicious}}
          summary (d/defection-summary events before after [] {:selector :load-optimal :defaults-used #{}})]
      (is (= 1 (get-in summary [:strategy-transitions [:honest :lazy]])))
      (is (= 1 (get-in summary [:strategy-transitions [:lazy :honest]])))
      (is (= 1 (get-in summary [:strategy-transitions [:honest :malicious]])))
      (is (= {:honest 2 :lazy 1} (get-in summary [:strategy-mix :initial])))
      (is (= {:lazy 1 :honest 1 :malicious 1} (get-in summary [:strategy-mix :final]))))))

(deftest rng-propagation-is-reproducible-and-seed-sensitive
  (testing "same seed is reproducible, different seed can change adaptation outcomes"
    (let [histories (into {}
                          (map (fn [i]
                                 [(str "resolver-" i)
                                  {:strategy :honest
                                   :epoch-history {:epoch-1 {:trials 5 :profit 10.0}}}])
                               (range 50)))
          params {:n-trials-per-epoch 2000
                  :strategy-adaptation {:enabled true
                                        :rate 0.5
                                        :selector :load-optimal
                                        :allowed-targets #{:honest :lazy :malicious}}
                  :strategy-space #{:honest :lazy :malicious}}
          run-a1 (d/apply-strategy-defection (rng/make-rng 101) histories 1 params)
          run-a2 (d/apply-strategy-defection (rng/make-rng 101) histories 1 params)
          run-b  (d/apply-strategy-defection (rng/make-rng 202) histories 1 params)
          mix-a1 (frequencies (map :strategy (vals (:updated-histories run-a1))))
          mix-b  (frequencies (map :strategy (vals (:updated-histories run-b))))]
      (is (= run-a1 run-a2))
      (is (not= mix-a1 mix-b)))))

(deftest max-switch-probability-cap-override
  (testing "binary selector respects max-switch-probability cap"
    (let [histories {"h1" {:strategy :honest :epoch-history {:epoch-1 {:trials 10 :profit 1.0}}}
                     "m1" {:strategy :malicious :epoch-history {:epoch-1 {:trials 10 :profit 10000.0}}}}
          no-switch (d/apply-strategy-defection
                     (rng/make-rng 1) histories 1
                     {:defection-rate 1.0
                      :max-switch-probability 0.0})
          switch-ok (d/apply-strategy-defection
                     (rng/make-rng 1) histories 1
                     {:defection-rate 1.0
                      :max-switch-probability 1.0})]
      (is (empty? (:defection-events no-switch)))
      (is (= 1 (count (:defection-events switch-ok)))))))

(deftest defaults-are-surfaced-in-resolved-config
  (testing "resolved config includes defaults and defaults-used provenance"
    (let [result (d/apply-strategy-defection
                  (rng/make-rng 1)
                  {"h1" {:strategy :honest :epoch-history {:epoch-1 {:trials 1 :profit 5.0}}}
                   "m1" {:strategy :malicious :epoch-history {:epoch-1 {:trials 1 :profit 10.0}}}}
                  1
                  {:defection-rate 0.1})
          cfg (:resolved-config result)]
      (is (= 0.8 (:max-switch-probability cfg)))
      (is (= :inconclusive (:blocked-target-policy cfg)))
      (is (contains? (:defaults-used cfg) :max-switch-probability))
      (is (contains? (:defaults-used cfg) :blocked-target-policy)))))

(deftest blocked-target-policy-modes
  (testing "blocked-target-policy controls compatibility classification"
    (let [base-result {:epoch-results [{:defection {:diagnostics [{:reason :target-outside-strategy-space}]
                                                  :adaptation/resolved-config {:blocked-target-policy :inconclusive}}}]}
          warn-result (assoc-in base-result [:epoch-results 0 :defection :adaptation/resolved-config :blocked-target-policy] :warn)
          fail-result (assoc-in base-result [:epoch-results 0 :defection :adaptation/resolved-config :blocked-target-policy] :fail)
          inc-claim (se/evaluate-strategy-adaptation-compatibility base-result)
          warn-claim (se/evaluate-strategy-adaptation-compatibility warn-result)
          fail-claim (se/evaluate-strategy-adaptation-compatibility fail-result)]
      (is (= :inconclusive (:status inc-claim)))
      (is (= :pass (:status warn-claim)))
      (is (= :fail (:status fail-claim))))))
