(ns resolver-sim.io.scenario-fixture-parity-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenario-export :as export]
            [resolver-sim.io.scenario-fixture-sync :as sync]
            [resolver-sim.protocols.sew.invariant-scenarios :as scenarios]
            [resolver-sim.protocols.sew.invariant-scenarios.doc-summaries :as doc]))

(def ^:private baseline-s01-s23-ids
  #{"s01-baseline-happy-path"
    "s02-dr3-dispute-release"
    "s03-dr3-dispute-refund"
    "s04-dispute-timeout-autocancel"
    "s05-pending-settlement-execute"
    "s06-mutual-cancel"
    "s07-unauthorized-resolver-rejected"
    "s08-state-machine-attack-gauntlet"
    "s09-multi-escrow-solvency"
    "s10-double-finalize-rejected"
    "s11-zero-fee-edge-case"
    "s12a-snapshot-isolation-fee-zero"
    "s12b-snapshot-isolation-fee-500"
    "s13-pending-settlement-refund"
    "s14-dr3-module-authorized"
    "s15-dr3-module-unauthorized-rejected"
    "s16-ieo-create-release"
    "s17-ieo-dispute-no-resolver-timeout"
    "s18-dr3-kleros-l0-resolves"
    "s19-dr3-kleros-escalation-rejected-l0-resolves"
    "s20-dr3-kleros-max-escalation-guard"
    "s21-dr3-kleros-pending-cleared-on-escalation"
    "s22-status-leak-agree-cancel-over-dispute"
    "s23-preemptive-escalation-blocked"})

(defn- scenario-by-id [sid]
  (some #(when (= sid (:scenario-id %)) %) (sync/all-invariant-scenario-maps)))

(deftest baseline-doc-summaries-complete
  (testing "every baseline S01–S23 scenario-id has a doc summary"
    (let [missing (remove doc/summary baseline-s01-s23-ids)]
      (is (empty? missing)
          (str "Add summaries in doc-summaries.clj for: " (vec missing))))))

(deftest trace-contract-aligned-with-clojure-source
  (testing "trace.json contract fields match invariant scenario maps"
    (let [drifts (sync/collect-trace-contract-drifts)]
      (is (empty? drifts)
          (str "Re-run scripts/sync_invariant_trace_fixtures.clj — drifts: "
               (pr-str drifts))))))

(defn- scenarios-with-strict-expected-errors []
  (filter #(true? (:strict-expected-errors? %))
          (sync/all-invariant-scenario-maps)))

(deftest public-json-strict-expected-errors-match-source
  (testing "exported scenarios/*.json carries strict expected-errors contract"
    (doseq [s (scenarios-with-strict-expected-errors)
            :let [sid   (:scenario-id s)
                  fname (export/scenario-id->public-json-filename sid)
                  path  (when fname (str "scenarios/" fname))
                  doc   (when (and path (.exists (io/file path)))
                          (json/read-str (slurp path) :key-fn keyword))]
            :when doc]
      (is (true? (:strict-expected-errors? doc))
          (str sid " public JSON missing :strict-expected-errors?"))
      (is (= (count (:expected-errors s))
             (count (:expected-errors doc)))
          (str sid " public JSON expected-errors count mismatch")))))

(deftest exported-trace-contract-matches-clojure-for-strict-scenarios
  (testing "trace export from Clojure matches contract fields (strict scenarios)"
    (doseq [s (scenarios-with-strict-expected-errors)
            :let [exported (export/scenario->trace-document
                             s
                             (get export/default-export-metadata (:scenario-id s)))
                  drift  (sync/trace-contract-drift s exported)]]
      (is (nil? drift)
          (str "Export drift for " (:scenario-id s) ": " (pr-str drift))))))
