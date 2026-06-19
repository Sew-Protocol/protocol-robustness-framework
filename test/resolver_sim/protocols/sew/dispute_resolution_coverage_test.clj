(ns resolver-sim.protocols.sew.dispute-resolution-coverage-test
  "Deterministic test suite for dispute resolution coverage scenarios (S-DR-*).
   Each scenario is loaded from scenarios/S-DR-*.json, normalized, replayed
   with the Sew protocol, and verified for:
     - Deterministic pass/fail outcome
     - Zero invariant violations (unless expected-fail?)
     - Expected error codes (when :expected-errors is declared)
   
   Researcher-readable artifacts are written to results/test-artifacts/.
   After all scenarios run, an evidence-summary.json artifact is produced
   that surfaces world-before/world-after hashes for every evidence record."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [clojure.set :as cset]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.scenario.normalize :as norm]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.dispute-coverage :as dc]
            [resolver-sim.evidence.summary :as ev-sum]
            [resolver-sim.evidence.config :as evcfg])
  (:import [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; Evidence artifact fixture
;; ---------------------------------------------------------------------------

(defn clean-evidence-dir!
  "Remove all evidence files from the event-evidence directory."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        f (io/file dir)]
    (when (.isDirectory f)
      (doseq [file (.listFiles f)]
        (.delete file)))
    (println "Cleaned evidence directory:" dir)))

(defn prepare-evidence-summary!
  "Build and persist the evidence-summary.json artifact from accumulated
   evidence files. Call after all scenario replays have completed."
  []
  (ev-sum/write-evidence-summary!)
  (println "Evidence summary artifact emitted"))

(defn evidence-lifecycle-fixture
  "Fixture: clean evidence before, emit summary after."
  [f]
  (clean-evidence-dir!)
  (f)
  (prepare-evidence-summary!))

(use-fixtures :once evidence-lifecycle-fixture)

;; ---------------------------------------------------------------------------
;; Scenario file paths
;; ---------------------------------------------------------------------------

(def dr-scenario-paths
  "All S-DR-* dispute resolution scenario files, sorted."
  (sort (filter #(.contains % "S-DR-")
                (map #(str "scenarios/" %)
                     (.list (io/file "scenarios"))))))

(defn- load-scenario [path]
  (-> path sc/load-scenario-file norm/normalize-scenario))

(defn- replay-scenario [scenario]
  (sew/replay-with-sew-protocol scenario))

;; ---------------------------------------------------------------------------
;; Smoke test: all JSON files load without error
;; ---------------------------------------------------------------------------

(deftest test-all-dr-scenario-files-load
  (testing "All dispute resolution scenario files load without parse error"
    (let [results (for [path dr-scenario-paths]
                    (try {:path path :ok true :scenario (load-scenario path)}
                         (catch Exception e
                           {:path path :ok false :error (.getMessage e)})))]
      (doseq [r results]
        (is (:ok r) (str "Failed to load: " (:path r))))
      (is (pos? (count results)) "At least one S-DR-* scenario must exist"))))

;; ---------------------------------------------------------------------------
;; Smoke test: each scenario replays deterministically
;; ---------------------------------------------------------------------------

(deftest test-all-dr-scenarios-replay-deterministically
  (testing "Each S-DR scenario replays without error"
    (let [results (for [path dr-scenario-paths]
                    (try (let [s (load-scenario path)
                               r (replay-scenario s)]
                           {:scenario-id (:scenario-id s)
                            :ok true
                            :outcome (:outcome r)
                            :halt-reason (:halt-reason r)
                            :violations (get-in r [:metrics :invariant-violations] 0)})
                         (catch Exception e
                           {:scenario-id path :ok false :error (.getMessage e)})))]
      (doseq [r results]
        (is (:ok r) (str "Replay failed for " (:scenario-id r) ": " (:error r)))))))

;; ---------------------------------------------------------------------------
;; Test: expected-errors match actual reverts (via expected-reverts metric)
;; ---------------------------------------------------------------------------

(deftest test-dr-expected-errors
  (testing "Scenarios with :expected-errors have expected-reverts count > 0"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when (and scenario (seq (:expected-errors scenario)))
          (let [replay (replay-scenario scenario)
                expected-count (count (:expected-errors scenario))
                actual-expected (get-in replay [:metrics :expected-reverts] 0)
                actual-unexpected (get-in replay [:metrics :unexpected-reverts] 0)]
            (testing (str (:scenario-id scenario) " expected-errors")
              (is (pos? actual-expected)
                  (str (:scenario-id scenario) " expected " expected-count
                       " reverts but expected-reverts=" actual-expected
                       " unexpected-reverts=" actual-unexpected)))))))))

;; ---------------------------------------------------------------------------
;; Test: invariant violations are zero
;; ---------------------------------------------------------------------------

(deftest test-dr-no-invariant-violations
  (testing "All scenarios have zero invariant violations"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [replay (replay-scenario scenario)
                violations (get-in replay [:metrics :invariant-violations] 0)]
            (testing (str (:scenario-id scenario) " invariant violations")
              (is (zero? violations)
                  (str (:scenario-id scenario) " has " violations
                       " invariant violation(s)")))))))))

;; ---------------------------------------------------------------------------
;; Test: scenario tags exist
;; ---------------------------------------------------------------------------

(deftest test-dr-scenario-tags
  (testing "Each S-DR scenario has :suite/dispute-resolution and :coverage/* tags"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [tags (:tags scenario [])]
            (testing (str (:scenario-id scenario) " tags")
              (is (some #(= "suite/dispute-resolution" %) tags)
                  (str (:scenario-id scenario) " missing suite/dispute-resolution tag"))
              (is (some #(clojure.string/starts-with? % "coverage/") tags)
                  (str (:scenario-id scenario) " missing coverage/* tag")))))))))

;; ---------------------------------------------------------------------------
;; Test: deterministic outcome (same on two consecutive runs)
;; ---------------------------------------------------------------------------

(deftest test-dr-deterministic-outcome
  (testing "Each S-DR scenario produces the same outcome on two consecutive runs"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [run1 (replay-scenario scenario)
                run2 (replay-scenario scenario)
                outcome1 (:outcome run1)
                outcome2 (:outcome run2)]
            (testing (str (:scenario-id scenario) " determinism")
              (is (= outcome1 outcome2)
                  (str (:scenario-id scenario) " outcome differs between runs: "
                       outcome1 " vs " outcome2)))))))))

;; ---------------------------------------------------------------------------
;; Test: Build entry result (runner compatibility)
;; ---------------------------------------------------------------------------

(deftest test-dr-build-entry-result
  (testing "Each S-DR scenario can produce a runner entry result"
    (doseq [path dr-scenario-paths]
      (let [scenario (try (load-scenario path) (catch Exception _ nil))]
        (when scenario
          (let [replay (replay-scenario scenario)
                entry (runner/build-entry-result
                        {:name (:scenario-id scenario)
                         :replay-result replay
                         :scenario scenario})]
            (testing (str (:scenario-id scenario) " entry shape")
              (is (contains? entry :pass?))
              (is (contains? entry :outcome))
              (is (contains? entry :steps))
              (is (contains? entry :violations)))))))))

;; ---------------------------------------------------------------------------
;; Coverage: minimum threshold per category
;; ---------------------------------------------------------------------------

(deftest test-dr-coverage-minimums
  (testing "Dispute resolution coverage meets minimum thresholds"
    (let [scenarios (for [path dr-scenario-paths]
                      (try (load-scenario path) (catch Exception _ nil)))
          valid (remove nil? scenarios)
          tags (mapcat :tags valid)
          coverage-tags (filter #(clojure.string/starts-with? % "coverage/") tags)
          by-coverage (frequencies coverage-tags)]
      (is (>= (get by-coverage "coverage/basic-lifecycle" 0) 3)
          "At least 3 basic-lifecycle scenarios")
      (is (>= (get by-coverage "coverage/evidence" 0) 1)
          "At least 1 evidence scenario")
      (is (>= (get by-coverage "coverage/strategic" 0) 1)
          "At least 1 strategic scenario")
      (is (>= (get by-coverage "coverage/resolver-integrity" 0) 1)
          "At least 1 resolver-integrity scenario")
      (is (>= (get by-coverage "coverage/finality" 0) 1)
          "At least 1 finality scenario"))))

;; ---------------------------------------------------------------------------
;; Report: coverage report function works
;; ---------------------------------------------------------------------------

(deftest test-dr-coverage-report-shape
  (testing "The coverage report function returns the expected shape"
    (let [report (dc/dispute-resolution-coverage-report)
          readiness (:researcher-readiness report)]
      (is (= :dispute-resolution (:suite report)))
      (is (contains? report :total-scenarios))
      (is (contains? report :by-coverage))
      (is (contains? report :gaps))
      (is (pos? (:total-scenarios report)))
      (is (contains? report :researcher-readiness))
      (testing "researcher-readiness flags are booleans reflecting artifact existence")
      (doseq [flag [:trace-summary? :evidence-summary? :evidence-world-hashes?
                    :financial-outcome? :linked-evidence-group?
                    :invariant-results? :dispute-summary?]]
        (is (contains? readiness flag)
            (str "researcher-readiness must contain " flag))
        (is (instance? Boolean (get readiness flag))
            (str flag " must be a boolean"))))))

;; ---------------------------------------------------------------------------
;; Evidence summary artifact
;; ---------------------------------------------------------------------------

(defn clean-evidence-dir!
  "Remove all evidence files from the event-evidence directory."
  []
  (let [dir (str (evcfg/artifact-dir) "/event-evidence")
        f (io/file dir)]
    (when (.isDirectory f)
      (doseq [file (.listFiles f)]
        (.delete file)))
    (println "Cleaned evidence directory:" dir)))

(defn prepare-evidence-summary!
  "Build and persist the evidence-summary.json artifact from accumulated
   evidence files. Call after all scenario replays have completed."
  []
  (ev-sum/write-evidence-summary!)
  (println "Evidence summary artifact emitted"))

;; Use :once fixture to clean evidence before and emit summary after
(defn evidence-fixture
  [f]
  (clean-evidence-dir!)
  (f)
  (prepare-evidence-summary!))

;; Register fixture once for the namespace
(defn setup-evidence-fixture
  []
  (use-fixtures :once evidence-fixture))

;; ---------------------------------------------------------------------------
;; Evidence summary: verify world hashes are present in dispute evidence
;; ---------------------------------------------------------------------------

(deftest test-evidence-summary-world-hashes
  (testing "Evidence summary shows world-before/world-after hashes for dispute events"
    (let [summary (ev-sum/build-evidence-summary)]
      (is (pos? (:evidence-count summary))
          "At least one evidence record must exist")
      (let [dispute-records (:dispute-records summary)]
        (when (seq dispute-records)
          (doseq [r dispute-records]
            (testing (str "evidence " (:evidence/type r))
              (is (not (cstr/blank? (:world/before-hash r)))
                    (str (:evidence/type r) " must have world/before-hash"))
              (is (not (cstr/blank? (:world/after-hash r)))
                  (str (:evidence/type r) " must have world/after-hash"))))))
      (println (str "Evidence summary has " (:evidence-count summary) " records"))
      (doseq [r (take 5 (:records summary))]
        (println (str "  " (:evidence/type r)
                      " before=" (when (seq (:world/before-hash r))
                                   (str (subs (:world/before-hash r) 0 30) "..."))
                      " after=" (when (seq (:world/after-hash r))
                                  (str (subs (:world/after-hash r) 0 30) "..."))))))))

;; ---------------------------------------------------------------------------
;; New invariants: execute on basic dispute world
;; ---------------------------------------------------------------------------

(deftest test-new-dispute-invariants-on-sample
  (testing "New dispute invariants execute without error on a basic dispute world"
    (let [scenario (load-scenario "scenarios/S-DR-001-basic-release-ruling.json")
          replay (replay-scenario scenario)
          world (:world replay)]
      (testing "evidence-on-state-change"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/evidence-on-state-change?) world)]
          (is (contains? result :holds?))
          (is (contains? result :violations))))
      (testing "no-duplicate-dispute"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/no-duplicate-dispute?) world)]
          (is (true? (:holds? result)))))
      (testing "resolver-decision-attributable"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/resolver-decision-attributable?) world)]
          (is (contains? result :holds?))
          (is (contains? result :violations))))
      (testing "appeal-reversal-detectable"
        (let [result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/appeal-reversal-detectable?) world)]
          (is (contains? result :holds?))
          (is (contains? result :reversals)))))
    (testing "S-DR-030 biased-resolver world triggers reversal detection"
      (let [scenario (load-scenario "scenarios/S-DR-030-biased-resolver-appealed.json")
            replay (replay-scenario scenario)
            world (:world replay)
            result ((resolve 'resolver-sim.protocols.sew.invariants.dispute/appeal-reversal-detectable?) world)]
        (is (contains? result :reversals))
        (is (pos? (count (:reversals result)))
            "Expected at least one detectable reversal in appealed scenario")))))

;; ---------------------------------------------------------------------------
;; Structural: governance dispatch audit
;;
;; Every action that modifies protocol-level state (pause, token crunch,
;; yield risk, resolver rotation, fee update, slash actions) must use
;; with-governance-actor. This test reads the source file to verify.
;; ---------------------------------------------------------------------------

(deftest test-governance-dispatch-audit
  (testing "Governance-sensitive actions must use with-governance-actor"
    (let [source (slurp "src/resolver_sim/protocols/sew.clj")
          ;; Actions that MUST have governance gates (by protocol design)
          must-be-gated #{:rotate-dispute-resolver
                          :set-paused
                          :withdraw-fees
                          :governance-update-fee
                          :propose-fraud-slash
                          :resolve-appeal}
          ;; Simulation-only actions that intentionally skip governance
          known-simulation #{:set-token-liquidity-crunch :set-yield-risk}
          all-gated must-be-gated
          violations (for [action all-gated
                            :let [p (re-pattern
                                     (str "defmethod apply-action \"" (name action) "\""
                                          "[^#]*?"
                                          "with-governance-actor"))]
                            :when (not (re-find p source))]
                        (str (name action) " missing with-governance-actor"))
          must-violations (for [action must-be-gated
                                :let [p (re-pattern
                                         (str "defmethod apply-action \"" (name action) "\""
                                              "[^#]*?"
                                              "with-governance-actor"))]
                                :when (not (re-find p source))]
                            (name action))]
      (doseq [v violations]
        (println (str "  GOVERNANCE GAP: " v)))
      (is (empty? must-violations)
          (str "Must-have governance gates missing: " (pr-str must-violations))))))

;; ---------------------------------------------------------------------------
;; Theory-falsification scenarios
;;
;; Scenarios with purpose=theory-falsification intentionally demonstrate a
;; protocol vulnerability.  They should PASS (the replay executes correctly)
;; but be displayed as XFAIL (expected failure) — the scenario successfully
;; falsifies the theory that the protocol is robust against this attack.
;;
;; The economic security finding (S-DR-075) is the canonical example:
;; the protocol state machine correctly permits a malicious resolver to
;; profit from fraud at current bond/detection parameters.  The scenario
;; passes at the replay level but FAILS at the economic security level.
;; ---------------------------------------------------------------------------

(deftest test-theory-falsification-scenarios
  (testing "Theory-falsification scenarios demonstrate known vulnerabilities"
    (let [paths (filter #(.contains % "S-DR-")
                        (map #(str "scenarios/" %) (.list (io/file "scenarios"))))
          tf-scenarios (for [path paths]
                         (try (load-scenario path) (catch Exception _ nil)))
          tf-valid (filter (fn [s]
                         (and (= "theory-falsification" (:purpose s))
                              (not (some #(= "status/todo-stub" %) (:tags s [])))))
                       tf-scenarios)]
      (println "\n=== THEORY-FALSIFICATION SCENARIOS (XFAIL) ===")
      (is (pos? (count tf-valid)) "At least one theory-falsification scenario must exist")
      (doseq [s tf-valid]
        (let [r (replay-scenario s)
              outcome (:outcome r)
              violated-any? (pos? (get-in r [:metrics :invariant-violations] 0))
              ok? (= :pass outcome)]
          (println (str "  " (:scenario-id s)
                        "  replay=" (name outcome)
                        "  invariants-violated=" violated-any?
                        "  interpretation=XFAIL"
                        "  (proves vulnerability exists)"))
          (is ok? (str (:scenario-id s) " must replay successfully to demonstrate the vulnerability")))))))

;; ---------------------------------------------------------------------------
;; Structural: error code coverage audit
;;
;; Every error code used in (t/fail :) across the sew protocol must be
;; in either sew-state-error-codes or sew-guard-error-codes.  Otherwise
;; the metrics system silently ignores expected reverts for that code.
;;
;; The known-sets variable below must be kept in sync with the actual
;; defs in sew.clj.  If you add an error code to one of those sets,
;; update this list too.
;; ---------------------------------------------------------------------------

(def ^:private known-error-codes
  "Every error code that SHOULD be classified in sew-state-error-codes
   or sew-guard-error-codes.  Cross-referenced against actual t/fail
   calls below."
  #{:transfer-not-pending :transfer-not-in-dispute
    :invalid-state-for-release :invalid-state-for-refund
    :resolution-without-settlement :invalid-resolver :invalid-workflow-id
    :transfer-not-finalized :has-pending-settlement
    :dispute-timeout-not-exceeded :invalid-token :amount-zero
    :invalid-amount :invalid-recipient :cannot-set-both-auto-times
    :insufficient-module-liquidity :token-liquidity-crunch
    :circuit-breaker-active :resolver-at-capacity :resolver-frozen
    :insufficient-resolver-stake :active-disputes-block-withdrawal
    :pending-slash-blocks-withdrawal :missing-fee-bps
    :no-fees-to-withdraw :liquidity-insufficient :no-claimable-balance
    :no-bond-to-slash :no-bond-to-return :senior-not-registered
    :senior-coverage-exceeded :insufficient-stake :protocol-paused
    ;; Guard codes
    :no-resolution-to-appeal :appeal-window-expired
    :appeal-window-not-expired :escalation-not-allowed
    :escalation-not-configured :resolution-already-pending
    :resolver-capacity-exceeded :not-participant
    :not-authorized-resolver :not-governance :not-resolver
    :not-sender :not-recipient :no-pending-slash :invalid-slash-state
    :slash-not-pending :slash-already-pending :invalid-slash-amount
    :invalid-resolver-addr :slash-resolver-mismatch
    :slash-exceeds-max-per-offense :slash-epoch-cap-exceeded
    :timelock-not-expired :workflow-not-slashable
    :missing-caller-context :invalid-new-resolver})

(deftest test-error-code-coverage-audit
  (testing "All t/fail error codes are classified in the error code sets"
    (let [clj-files (filter #(.endsWith (.getName %) ".clj")
                            (remove #(.isDirectory %)
                                    (file-seq (java.io.File. "src"))))
          all-codes (into #{}
                          (comp
                           (mapcat #(clojure.string/split-lines (slurp %)))
                           (keep (fn [line]
                                   (when-let [m (re-find #"t/fail\s+:([a-z0-9-]+)" line)]
                                     (second m))))
                           (map keyword))
                          clj-files)
          unclassified (cset/difference all-codes known-error-codes)]
      (doseq [c (sort unclassified)]
        (println (str "  UNCLASSIFIED: :" c)))
      (is (empty? unclassified)
          (str (count unclassified) " t/fail code(s) not in known-error-codes")))))
