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

;; ── Cross-Layer Linking: \"What happened in this replay event?\" ──────────

(defn- setup-linked-group
  "Write artifacts for linked-evidence-group tests into dir.
   Returns the group-id the artifacts share."
  [dir]
  (.mkdirs (java.io.File. dir))
  (let [gid "test:0:create_escrow"
        base {:evidence/schema-version "event-evidence.v1"}
        generic (assoc base :evidence/type "transition"
                       :evidence/chain-seq nil
                       :evidence/group-id gid
                       :evidence/layer "generic-trace"
                       :evidence/hash "gen001"
                       :action {:type :create_escrow})
        escrow (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id gid
                      :evidence/layer "targeted-protocol"
                      :evidence/hash "abc111"
                      :escrow/workflow-id 0)
        stake (assoc base :evidence/type "stake-registered"
                     :evidence/chain-seq 3
                     :evidence/group-id gid
                     :evidence/layer "targeted-protocol"
                     :evidence/hash "abc333"
                     :stake/resolver "0xRes")
        dispute (assoc base :evidence/type "dispute-raised"
                       :evidence/chain-seq 2
                       :evidence/group-id gid
                       :evidence/layer "targeted-protocol"
                       :evidence/hash "abc222"
                       :dispute/workflow-id 0)]
    (doseq [m [generic escrow dispute stake]]
      (spit (io/file dir (str "ev-" (:evidence/hash m) ".json"))
            (json/write-str m {:key-fn evidence/qualified-key :indent true})))
    gid))

(deftest linked-group-returns-nil-for-missing-group
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        result (evidence/linked-evidence-group "nonexistent-group" dir)]
    (is (nil? result) "Missing group-id returns nil")))

(deftest linked-group-returns-generic-and-targeted
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)
        result (evidence/linked-evidence-group gid dir)]
    (is (some? result) "Result is not nil")
    (is (= gid (:group-id result)) "Group-id matches")
    (is (some? (:generic-trace result)) "Generic trace is present")
    (is (= "transition" (:evidence/type (:generic-trace result))) "Generic trace type is transition")
    (is (= 3 (:targeted-count (:diagnostics result)))
        "Three targeted artifacts found")
    (is (= 4 (:artifact-count (:diagnostics result)))
        "Four total artifacts found")))

(deftest linked-group-sorts-targeted-by-chain-seq
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)
        result (evidence/linked-evidence-group gid dir)
        chain-seqs (map :evidence/chain-seq (:targeted result))]
    (is (= [1 2 3] chain-seqs) "Targeted artifacts sorted by chain-seq")))

(deftest linked-group-tolerates-malformed-files
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid (setup-linked-group dir)]
    ;; Write a malformed JSON file into the directory
    (spit (io/file dir "corrupt.json") "{not valid json}")
    (let [result (evidence/linked-evidence-group gid dir)]
      (is (some? result) "Malformed files do not prevent valid results")
      (is (= 4 (:artifact-count (:diagnostics result)))
          "Malformed file is silently skipped"))))

(deftest linked-group-accepts-keyword-layer
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid "test:keyword-layer"
        base {:evidence/schema-version "event-evidence.v1"}
        generic (assoc base :evidence/type "transition"
                       :evidence/group-id gid
                       :evidence/layer :generic-trace
                       :evidence/hash "gen001")
        escrow (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id gid
                      :evidence/layer :targeted-protocol
                      :evidence/hash "abc111")]
    (.mkdirs (java.io.File. dir))
    (doseq [m [generic escrow]]
      (spit (io/file dir (str "ev-" (:evidence/hash m) ".json"))
            (json/write-str m {:key-fn evidence/qualified-key :indent true})))
    (let [result (evidence/linked-evidence-group gid dir)]
      (is (some? result) "Keyword :evidence/layer is accepted")
      (is (:generic-trace-found? (:diagnostics result)) "Generic trace found with keyword layer"))))

(deftest linked-group-targeted-only-no-generic
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))
        gid "test:targeted-only"
        base {:evidence/schema-version "event-evidence.v1"}
        escrow (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id gid
                      :evidence/layer "targeted-protocol"
                      :evidence/hash "abc111")]
    (.mkdirs (java.io.File. dir))
    (spit (io/file dir "ev-abc111.json")
          (json/write-str escrow {:key-fn evidence/qualified-key :indent true}))
    (let [result (evidence/linked-evidence-group gid dir)]
      (is (some? result) "Result is not nil")
      (is (nil? (:generic-trace result)) "No generic trace when none exists")
      (is (false? (:generic-trace-found? (:diagnostics result))) "diagnostics reflects missing trace")
      (is (= 1 (:targeted-count (:diagnostics result))) "One targeted artifact"))))

(deftest linked-group-empty-directory
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-link-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. dir))
    (let [result (evidence/linked-evidence-group "any-id" dir)]
      (is (nil? result) "Empty directory returns nil"))))

;; ── Evidence Links Index: persistable cross-layer index for VCS tracking ──

(deftest write-links-index-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)
        path (evidence/write-evidence-links-index! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [idx (json/read-str (slurp path) :key-fn keyword)]
      (is (seq idx) "Links index has entries")
      (is (some #(-> % val :artifacts seq) idx) "Entries have artifact lists"))))

(deftest links-index-groups-by-group-id
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-lidx-" (java.util.UUID/randomUUID))
        _ (setup-linked-group dir)
        idx (evidence/build-evidence-links-index dir)]
    (is (= 1 (count idx)) "All artifacts share one group-id")
    (let [gid (first (keys idx))
          group (get idx gid)]
      (is (= 4 (count (:artifacts group))) "Group has 4 artifacts")
      (is (some #(= "generic-trace" (:layer %)) (:artifacts group)) "Has generic-trace")
      (is (some #(= "targeted-protocol" (:layer %)) (:artifacts group)) "Has targeted-protocol"))))

(deftest write-coverage-report-creates-file
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-test-" (java.util.UUID/randomUUID))
        ev-dir (str dir "/event-evidence")
        _ (setup-artifacts! ev-dir)
        path (evidence/write-evidence-coverage-report! dir)]
    (is (string? path))
    (is (.exists (io/file path)))
    (let [report (json/read-str (slurp path) :key-fn keyword)]
      (is (contains? report :targeted-evidence-count)))))

;; ── Chain Integrity: \"Can I verify the evidence chain from artifacts alone?\" ─

(defn- setup-chain-artifacts
  "Write a valid 3-artifact chain into dir."
  [dir]
  (.mkdirs (java.io.File. dir))
  (let [base {:evidence/schema-version "event-evidence.v1"}
        a1 (assoc base :evidence/type "escrow-created"
                  :evidence/chain-seq 1
                  :evidence/chain-prev-hash nil
                  :evidence/chain-self-hash "h1"
                  :evidence/hash "h1"
                  :evidence/group-id "g1")
        a2 (assoc base :evidence/type "escrow-released"
                  :evidence/chain-seq 2
                  :evidence/chain-prev-hash "h1"
                  :evidence/chain-self-hash "h2"
                  :evidence/hash "h2"
                  :evidence/group-id "g1")
        a3 (assoc base :evidence/type "dispute-raised"
                  :evidence/chain-seq 3
                  :evidence/chain-prev-hash "h2"
                  :evidence/chain-self-hash "h3"
                  :evidence/hash "h3"
                  :evidence/group-id "g1")]
    (doseq [m [a1 a2 a3]]
      (spit (io/file dir (str "ev-" (:evidence/hash m) ".json"))
            (json/write-str m {:key-fn evidence/qualified-key :indent true})))))

(deftest chain-integrity-valid-chain
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ci-" (java.util.UUID/randomUUID))
        _ (setup-chain-artifacts dir)
        result (evidence/verify-chain-integrity dir)]
    (is (:valid result) "Valid chain passes")
    (is (= 3 (:artifact-count result)))
    (is (empty? (:violations result)))))

(deftest chain-integrity-gap
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ci-" (java.util.UUID/randomUUID))
        _ (setup-chain-artifacts dir)]
    ;; Delete artifact with chain-seq 2
    (doseq [f (.listFiles (java.io.File. dir))]
      (when (.contains (.getName f) "h2")
        (.delete f)))
    (let [result (evidence/verify-chain-integrity dir)]
      (is (not (:valid result)) "Chain with gap is invalid")
      (is (some #(= 2 %) (:gaps result)) "Seq 2 reported as gap"))))

(deftest chain-integrity-self-hash-mismatch
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ci-" (java.util.UUID/randomUUID))
        _ (setup-chain-artifacts dir)]
    ;; Tamper with the first artifact: re-write with wrong self-hash
    (let [path (str dir "/ev-h1.json")
          data (json/read-str (slurp path) :key-fn keyword)
          tampered (assoc data :evidence/chain-self-hash "tampered")]
      (spit (java.io.File. path)
            (json/write-str tampered {:key-fn evidence/qualified-key :indent true}))
      (let [result (evidence/verify-chain-integrity dir)]
        (is (not (:valid result)) "Tampered chain is invalid")
        (is (= 1 (:self-hash-failed result)) "Self-hash mismatch detected")))))

;; ── Scenario Evidence Verification: \"Did this scenario produce expected evidence?\" ─

(deftest scenario-evidence-verification-matches-all
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ev-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        scenario {:expected-evidence
                  [{:type "escrow-created" :importance :core}
                   {:type "escrow-released" :importance :core}
                   {:type "stake-registered" :importance :diagnostic}
                   {:type "dispute-raised" :importance :core}]}
        result (evidence/verify-scenario-evidence scenario dir)]
    (is (some? result))
    (is (= 4 (count (:matched result))) "All 4 expected types matched")
    (is (empty? (:missing result)) "No missing entries")
    (is (seq (:unexpected result)) "Has unexpected types (transition generic trace)")))

(deftest scenario-evidence-verification-detects-missing
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ev-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        scenario {:expected-evidence
                  [{:type "nonexistent-type" :importance :core}
                   {:type "another-missing" :importance :diagnostic}]}
        result (evidence/verify-scenario-evidence scenario dir)]
    (is (some? result))
    (is (empty? (:matched result)) "No matched entries")
    (is (= 2 (count (:missing result))) "Both expected types missing")))

(deftest scenario-evidence-verification-no-expected-evidence
  (let [dir (str (System/getProperty "java.io.tmpdir") "/qol-ev-" (java.util.UUID/randomUUID))
        _ (setup-artifacts! dir)
        scenario {}  ;; No :expected-evidence key
        result (evidence/verify-scenario-evidence scenario dir)]
    (is (nil? result) "No :expected-evidence returns nil")))

;; ── Cross-Run Evidence Diff: \"What changed between two runs?\" ───────────

(defn- setup-diff-dirs
  "Create two artifact directories with overlapping and unique artifacts."
  [dir-a dir-b]
  (let [base {:evidence/schema-version "event-evidence.v1"}
        common (assoc base :evidence/type "escrow-created"
                      :evidence/chain-seq 1
                      :evidence/group-id "g1"
                      :evidence/hash "abc111")
        only-a (assoc base :evidence/type "stake-registered"
                      :evidence/chain-seq 2
                      :evidence/group-id "g1"
                      :evidence/hash "abc222")
        only-b (assoc base :evidence/type "dispute-raised"
                      :evidence/chain-seq 3
                      :evidence/group-id "g1"
                      :evidence/hash "abc333")]
    (doseq [[dir entries] [[dir-a [common only-a]] [dir-b [common only-b]]]]
      (.mkdirs (java.io.File. dir))
      (doseq [m entries]
        (spit (io/file dir (str (:evidence/hash m) ".json"))
              (json/write-str m {:key-fn evidence/qualified-key :indent true}))))))

(deftest diff-finds-added-missing-and-common
  (let [dir-a (str (System/getProperty "java.io.tmpdir") "/qol-da-" (java.util.UUID/randomUUID))
        dir-b (str (System/getProperty "java.io.tmpdir") "/qol-db-" (java.util.UUID/randomUUID))
        _ (setup-diff-dirs dir-a dir-b)
        result (evidence/diff-evidence-directories dir-a dir-b)]
    (is (some? result))
    (is (= 1 (count (:added result))) "One artifact added in B")
    (is (= 1 (count (:missing result))) "One artifact missing from B")
    (is (= 1 (count (:unchanged result))) "One artifact unchanged")
    (is (= 1 (:b-only (:summary result))))))

(deftest diff-identical-directories
  (let [dir-a (str (System/getProperty "java.io.tmpdir") "/qol-da-" (java.util.UUID/randomUUID))
        dir-b (str (System/getProperty "java.io.tmpdir") "/qol-db-" (java.util.UUID/randomUUID))
        base {:evidence/schema-version "event-evidence.v1"}
        art (assoc base :evidence/type "escrow-created"
                   :evidence/chain-seq 1
                   :evidence/group-id "g1"
                   :evidence/hash "abc111")]
    (.mkdirs (java.io.File. dir-a))
    (.mkdirs (java.io.File. dir-b))
    (doseq [dir [dir-a dir-b]]
      (spit (io/file dir "a.json")
            (json/write-str art {:key-fn evidence/qualified-key :indent true})))
    (let [result (evidence/diff-evidence-directories dir-a dir-b)]
      (is (zero? (count (:added result))) "No added")
      (is (zero? (count (:missing result))) "No missing")
      (is (= 1 (count (:unchanged result))) "One unchanged"))))

(deftest diff-empty-directories
  (let [dir-a (str (System/getProperty "java.io.tmpdir") "/qol-da-" (java.util.UUID/randomUUID))
        dir-b (str (System/getProperty "java.io.tmpdir") "/qol-db-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. dir-a))
    (.mkdirs (java.io.File. dir-b))
    (let [result (evidence/diff-evidence-directories dir-a dir-b)]
      (is (zero? (count (:added result))))
      (is (zero? (count (:missing result))))
      (is (zero? (count (:unchanged result)))))))

;; ── Evidence Bundle Export: \"Share this event's evidence with a collaborator\" ─

(deftest export-bundle-creates-manifest
  (let [src (str (System/getProperty "java.io.tmpdir") "/qol-eb-" (java.util.UUID/randomUUID))
        out (str (System/getProperty "java.io.tmpdir") "/qol-eb-out-" (java.util.UUID/randomUUID))
        gid (setup-linked-group src)
        result (evidence/export-evidence-bundle gid out src)]
    (is (= out result) "Returns output directory path")
    (is (.exists (io/file out "manifest.json")) "Manifest file exists")
    (let [manifest (json/read-str (slurp (io/file out "manifest.json")) :key-fn keyword)]
      (is (= 4 (:artifact-count manifest)) "All 4 artifacts exported")
      (is (= gid (first (:group-ids manifest))) "Group-id in manifest"))))

(deftest export-bundle-multiple-group-ids
  (let [src (str (System/getProperty "java.io.tmpdir") "/qol-eb-" (java.util.UUID/randomUUID))
        out (str (System/getProperty "java.io.tmpdir") "/qol-eb-out-" (java.util.UUID/randomUUID))
        _ (setup-linked-group src)
        result (evidence/export-evidence-bundle ["g1" "nonexistent"] out src)]
    (is (= out result))
    (let [files (.listFiles (java.io.File. out))]
      (is (some #(= "manifest.json" (.getName %)) files)))))

(deftest export-bundle-missing-group-id
  (let [src (str (System/getProperty "java.io.tmpdir") "/qol-eb-" (java.util.UUID/randomUUID))
        out (str (System/getProperty "java.io.tmpdir") "/qol-eb-out-" (java.util.UUID/randomUUID))
        _ (.mkdirs (java.io.File. src))
        result (evidence/export-evidence-bundle "nonexistent-group" out src)]
    (is (= out result))
    (let [files (.listFiles (java.io.File. out))]
      (is (= 1 (count files)) "Only manifest.json written")
      (is (= "manifest.json" (.getName (first files)))))))
