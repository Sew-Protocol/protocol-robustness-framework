(ns resolver-sim.contract-model.replay-simple-parity-test
  "Transition-level parity tests between simple-replay and replay-events.

   The purpose is to prove that 'simple' changes instrumentation and evaluation
   profile, not transition semantics.

   Excluded from comparison:
   - evidence records (:evidence-mode differs)
   - :execution descriptor (profile vs mode differs)
   - :context/version, :context/source (normalized separately)
   - :replay-profile, :protocol-id (simple-only additions)
   - :scenario-normalizations (simple-only addition)"
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as flags]
            [resolver-sim.protocols.dummy :as dummy]
            [resolver-sim.io.scenarios :as io-scenarios]))

;; ===========================================================================
;; Helpers
;; ===========================================================================

(defn- load-scenario
  "Load a scenario from a resource path."
  [path]
  (io-scenarios/load-scenario-file path))

(defn- compare-results
  "Compare simple-replay and replay-events for equivalence on core fields.
   Returns nil when equivalent, or a description of the first difference."
  [simple-result events-result]
  (cond
    (not= (:outcome simple-result) (:outcome events-result))
    (str "outcome differs: simple=" (:outcome simple-result) " events=" (:outcome events-result))

    (not= (:halt-reason simple-result) (:halt-reason events-result))
    (str "halt-reason differs: simple=" (:halt-reason simple-result) " events=" (:halt-reason events-result))

    (not= (:events-processed simple-result) (:events-processed events-result))
    (str "events-processed differs: simple=" (:events-processed simple-result) " events=" (:events-processed events-result))

    (not= (mapv (juxt :seq :result :error) (:trace simple-result))
          (mapv (juxt :seq :result :error) (:trace events-result)))
    (str "trace (seq/result/error) differs")

    :else nil))

(defn- test-parity
  "Assert that simple-replay and replay-events produce equivalent results."
  [protocol scenario & [replay-opts]]
  (let [simple-result (replay/simple-replay protocol scenario replay-opts)
        events-result (replay/replay-events protocol scenario
                                            (merge {:minimal true} replay-opts))
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL:" diff)
          (is false diff))
      (is true (str "Parity OK: " (:scenario-id simple-result))))))

;; ===========================================================================
;; 1. Successful scenario
;; ===========================================================================

(def success-scenario
  {:scenario-id "parity-success"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest parity-successful-scenario
  (test-parity dummy/protocol success-scenario))

;; ===========================================================================
;; 2. Expected-error scenario
;; ===========================================================================

(def expected-error-scenario
  {:scenario-id "parity-expected-error"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :expected-errors [{:seq 0 :action "noop" :error "some-error"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}
            {:seq 1 :time 1100 :agent "a" :action "noop" :params {}}]})

(deftest parity-expected-error-scenario
  (test-parity dummy/protocol expected-error-scenario))

;; ===========================================================================
;; 3. Invalid scenario
;; ===========================================================================

(def invalid-scenario
  {:scenario-id "parity-invalid"
   ;; no :schema-version — simple-replay will default it
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest parity-invalid-scenario
  (let [simple-result (replay/simple-replay dummy/protocol invalid-scenario)
        ;; replay-events doesn't auto-default schema-version, so pre-normalize
        events-result (replay/replay-events dummy/protocol
                                            (assoc invalid-scenario :schema-version "1.0")
                                            {:minimal true})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL:" diff) (is false diff))
      (is true "Parity OK: parity-invalid"))))

;; ===========================================================================
;; 4. Scenario with explicit flag overrides
;; ===========================================================================

(deftest parity-flag-overrides
  (let [simple-result (replay/simple-replay dummy/protocol success-scenario
                                            {:flags {:evaluate-expectations? false}})
        events-result (replay/replay-events dummy/protocol success-scenario
                                            {:minimal true
                                             :flags {:evaluate-expectations? false}})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL:" diff) (is false diff))
      (is true "Parity OK: parity-flag-overrides"))))

;; ===========================================================================
;; 5. Smoke-level comparison with replay-with-protocol
;; ===========================================================================

(deftest parity-smoke-vs-replay-with-protocol
  (let [scenario {:scenario-id "parity-smoke-vs-full"
                  :schema-version "1.0"
                  :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        simple-result (replay/simple-replay dummy/protocol scenario)
        full-result (replay/replay-with-protocol dummy/protocol scenario
                                                 {:flags flags/minimal-replay-flags
                                                  :skip-finalize true})]
    (is (= (:outcome simple-result) (:outcome full-result))
        "outcome matches")
    (is (= (:events-processed simple-result) (:events-processed full-result))
        "events-processed matches")
    (is (= (mapv (juxt :seq :result :error) (:trace simple-result))
           (mapv (juxt :seq :result :error) (:trace full-result)))
        "trace shape matches")))

;; ===========================================================================
;; 6. prepare-simple-scenario purity
;; ===========================================================================

(deftest prepare-scenario-purity
  (let [scenario {:scenario-id "purity-test" :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        original (assoc scenario :schema-version "1.0")
        prep-result (replay/prepare-simple-scenario scenario)]
    (is (= "1.0" (get-in prep-result [:scenario :schema-version])))
    (is (not (contains? scenario :schema-version))
        "input not mutated")
    (is (= (:normalizations prep-result)
           [{:field :schema-version :value "1.0" :reason :simple-replay-default}]))))

(deftest prepare-scenario-explicit-preserved
  (let [scenario {:scenario-id "explicit-test" :schema-version "1.0"
                  :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        prep-result (replay/prepare-simple-scenario scenario)]
    (is (= "1.0" (get-in prep-result [:scenario :schema-version])))
    (is (empty? (:normalizations prep-result)))))

;; ===========================================================================
;; 7. normalize-simple-result structure
;; ===========================================================================

(deftest normalize-simple-result-keys
  (let [raw {:outcome :pass :scenario-id "test" :events-processed 0 :trace [] :metrics {}}
        result (replay/normalize-simple-result raw dummy/protocol
                                                [{:field :schema-version :value "1.0" :reason :simple-replay-default}]
                                                {:profile :simple :engine :canonical-loop}
                                                "my-run")]
    (is (= :simple (:replay-profile result)))
    (is (= "dummy" (:protocol-id result)))
    (is (= {:profile :simple :engine :canonical-loop} (:execution result)))
    (is (= "1.0" (:context/version result)))
    (is (= {:scenario-id "test" :run-id "my-run"} (:context/source result)))
    (is (seq (:scenario-normalizations result)))))
