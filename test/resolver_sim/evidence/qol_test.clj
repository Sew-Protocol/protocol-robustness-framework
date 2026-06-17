(ns resolver-sim.evidence.qol-test
  "Tests for researcher-quality-of-life additions: Query API, mechanism index,
   coverage report, and static coverage verification.
   
   These tests model researcher questions, not just implementation details:
   - \"Find all evidence for workflow 42\"
   - \"Which mechanisms have evidence artifacts?\"
   - \"Does this run have missing coverage?\"
   - \"Did we forget to add evidence to a new function?\""
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.io.event-evidence :as evidence]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.util.evidence :as ev]
            [resolver-sim.scripts.evidence-coverage :as coverage]))

;; ── Test Helpers ──────────────────────────────────────────────────────────────
;;
;; Each test uses a fresh temp directory to avoid cross-test contamination.
;; setup-artifacts! writes sample evidence files into the given dir.

(defn- write-sample-evidence
  "Write a sample evidence artifact to disk for query/index testing.
   Uses the same qualified-key writer as capture-event-evidence!."
  [dir m]
  (let [f (io/file dir (str (:evidence/type m) "-" (:event/seq m 0) ".json"))]
    (.mkdirs (io/file dir))
    (spit f (json/write-str m {:key-fn evidence/qualified-key :indent true}))
    (.getPath f)))

(defn- setup-artifacts!
  "Write a known set of sample evidence artifacts into dir."
  [dir]
  (let [base {:evidence/schema-version "event-evidence.v1"
              :run/id "qol-test"
              :scenario/id "qol-scenario"}
        escrow-created (assoc base :evidence/type "escrow-created"
                              :evidence/chain-seq 1
                              :evidence/group-id "qol-test:0:create_escrow"
                              :evidence/layer :targeted-protocol
                              :evidence/hash "abc111"
                              :escrow/workflow-id 0
                              :escrow/amount 1000
                              :event/seq 0)
        escrow-released (assoc base :evidence/type "escrow-released"
                               :evidence/chain-seq 2
                               :evidence/group-id "qol-test:1:release"
                               :evidence/layer :targeted-protocol
                               :evidence/hash "abc222"
                               :finalize/workflow-id 0
                               :event/seq 1)
        stake-registered (assoc base :evidence/type "stake-registered"
                                :evidence/chain-seq 3
                                :evidence/group-id "qol-test:2:stake/register"
                                :evidence/layer :targeted-protocol
                                :evidence/hash "abc333"
                                :stake/resolver "0xRes1"
                                :stake/amount 5000
                                :event/seq 2)
        dispute-raised (assoc base :evidence/type "dispute-raised"
                              :evidence/chain-seq 4
                              :evidence/group-id "qol-test:3:raise_dispute"
                              :evidence/layer :targeted-protocol
                              :evidence/hash "abc444"
                              :dispute/workflow-id 0
                              :event/seq 3)
        generic-trace (assoc base :evidence/type "transition"
                             :artifact-kind :transition
                             :evidence/hash "gen001"
                             :evidence/group-id "qol-test:0:create_escrow"
                             :evidence/layer :generic-trace
                             :event/seq 0)]
    (dorun (map #(write-sample-evidence dir %)
                [escrow-created escrow-released stake-registered
                 dispute-raised generic-trace]))))

;; ── Researcher Query: \"Find all evidence for workflow 0\" ─────────────────

(deftest find-evidence-by-workflow
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-workflow 0 :artifact-dir dir)]
    (is (= 3 (count results)) "Three artifacts reference workflow 0")
    (is (some #(= "escrow-created" (:evidence/type %)) results))
    (is (some #(= "dispute-raised" (:evidence/type %)) results))))

(deftest find-evidence-by-type
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :escrow-created :artifact-dir dir)]
    (is (= 1 (count results)))
    (is (= "escrow-created" (:evidence/type (first results))))
    (is (:evidence/chain-seq (first results)) "Summary includes chain-seq")
    (is (nil? (:escrow/amount (first results))) "Summary excludes body by default")))

(deftest find-evidence-by-type-include-body
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :escrow-created :include-body? true
                                         :artifact-dir dir)]
    (is (= 1 (count results)))
    (is (= 1000 (:escrow/amount (first results))) "include-body? reveals full fields")))

(deftest find-evidence-by-mechanism
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-mechanism :escrow-lifecycle :artifact-dir dir)]
    (is (= 2 (count results)) "Two artifacts in escrow-lifecycle")
    (is (some #(= "escrow-created" (:evidence/type %)) results))
    (is (some #(= "escrow-released" (:evidence/type %)) results))))

(deftest find-evidence-by-chain-seq-range
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-chain-seq {:from 1 :to 2} :artifact-dir dir)
        chain-seqs (set (map :evidence/chain-seq results))]
    (is (= 2 (count results)) "Two artifacts in chain-seq 1-2")
    (is (chain-seqs 1) "Chain-seq 1 is included")
    (is (chain-seqs 2) "Chain-seq 2 is included")))

(deftest find-evidence-by-group-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-group-id "qol-test:0:create_escrow" :artifact-dir dir)]
    (is (= 2 (count results)) "Two artifacts share this group-id")
    (is (some #(= "generic-trace" (:evidence/layer %)) results))
    (is (some #(= "targeted-protocol" (:evidence/layer %)) results))))

(deftest find-evidence-by-run-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-run-id "qol-test" :artifact-dir dir)]
    (is (seq results))))

(deftest find-evidence-no-results
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :nonexistent-type :artifact-dir dir)]
    (is (empty? results))))

(deftest find-evidence-missing-directory
  (let [results (evidence/find-evidence :artifact-dir "/nonexistent-path-qol-test")]
    (is (empty? results))))

(deftest find-evidence-and-combined-filters
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        results (evidence/find-evidence :by-type :dispute-raised :by-workflow 0 :artifact-dir dir)]
    (is (= 1 (count results)) "AND-combined filters narrow results")))

;; ── Researcher Query: Mechanism Index ─────────────────────────────────

(deftest mechanism-index-groups-correctly
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        idx (evidence/build-mechanism-index dir)]
    (is (contains? idx :escrow-lifecycle))
    (is (contains? idx :staking))
    (is (contains? idx :dispute-resolution))
    (is (= 2 (:count (get idx :escrow-lifecycle))) "Two escrow lifecycle artifacts")))

(deftest mechanism-index-summaries
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        idx (evidence/build-mechanism-index dir)]
    (is (seq (:artifacts (get idx :staking)))
        "Staking mechanism has artifact summaries")
    (is (:evidence/chain-seq (first (:artifacts (get idx :staking))))
        "Summary has chain-seq")
    (is (:path (first (:artifacts (get idx :staking))))
        "Summary has path")
    (is (:evidence/hash (first (:artifacts (get idx :staking))))
        "Summary has hash")))

;; ── Researcher Query: Coverage Report ──────────────────────────────────

(deftest coverage-report-basic-structure
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        report (evidence/build-evidence-coverage-report dir)]
    (is (contains? report :generic-trace-event-count))
    (is (contains? report :targeted-evidence-count))
    (is (contains? report :mechanism-counts))
    (is (contains? report :type-counts))
    (is (vector? (:warnings report)))))

(deftest coverage-report-empty-directory
  (let [report (evidence/build-evidence-coverage-report
                 (str (System/getProperty "java.io.tmpdir") "/qol-empty-" (java.util.UUID/randomUUID)))]
    (is (zero? (:generic-trace-event-count report)))
    (is (zero? (:targeted-evidence-count report)))))

;; ── Static Coverage: \"Did we forget to add evidence to a new function?\" ──

(deftest static-coverage-finds-missing-evidence
  (let [report (coverage/check-evidence-coverage
                 "src/resolver_sim/protocols/sew/resolution.clj"
                 :allowed-missing #{'rotate-dispute-resolver
                                    'cleanup-orphaned-slashes
                                    'update-unavailability
                                    'withdraw-stake})]
    (is (number? (:total-fns report)))
    (is (number? (:functions-with-evidence report)))
    ;; Report should include warnings for read-only/helper fns
    (is (vector? (:warnings report)))
    ;; CI-failures should be fns that modify state without evidence
    (is (every? #(not (re-find #"\?$" (:name %))) (:ci-failures report))
        "Predicate/query functions should be warnings, not CI failures")))

(deftest static-coverage-allows-missing
  (let [report (coverage/check-evidence-coverage
                 "src/resolver_sim/protocols/sew/registry.clj"
                 :allowed-missing #{'get-stake 'get-resolver-yield-profile
                                    'resolver-in-escrow?})]
    (is (number? (:total-fns report)))
    (is (nil? (some #(re-find #"\?$" (:name %)) (:ci-failures report)))
        "Predicate functions are warnings, not CI failures")))

(deftest static-coverage-directory-scan
  (let [reports (coverage/check-directory-coverage
                  "src/resolver_sim/protocols/sew/")]
    (is (seq reports))
    (is (some #(.endsWith (:file %) "resolution.clj") reports))
    (is (some #(.endsWith (:file %) "registry.clj") reports))
    (is (some #(.endsWith (:file %) "lifecycle.clj") reports))))

;; ── Write Verification ──────────────────────────────────────────────────

(deftest write-mechanism-index-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        path (evidence/write-mechanism-index! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [idx (json/read-str (slurp path) :key-fn keyword)]
      (is (seq idx)))))

(deftest write-coverage-report-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        path (evidence/write-evidence-coverage-report! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [report (json/read-str (slurp path) :key-fn keyword)]
      (is (contains? report :targeted-evidence-count)))))
