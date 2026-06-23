(ns resolver-sim.hash.canonical-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.util Arrays]
           [java.security MessageDigest]
           [java.time Instant]))

(defn bytes= [a b]
  (Arrays/equals a b))

;; ──────────────────────────────────────────────────────────────────────────────
;; Primitives
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-null
  (is (bytes= (byte-array [0x00]) (hc/canonical-bytes nil))))

(deftest test-booleans
  (is (bytes= (byte-array [0x02]) (hc/canonical-bytes true)))
  (is (bytes= (byte-array [0x01]) (hc/canonical-bytes false))))

(deftest test-integers
  (is (bytes= (byte-array [0x10 0x00]) (hc/canonical-bytes 0)))
  (is (bytes= (byte-array [0x10 0x02]) (hc/canonical-bytes 1)))
  (is (bytes= (byte-array [0x10 0x01]) (hc/canonical-bytes -1)))
  (is (bytes= (byte-array [0x10 (unchecked-byte 0xF6) 0x01]) (hc/canonical-bytes 123)))
  (is (bytes= (byte-array [0x10 (unchecked-byte 0xF5) 0x01]) (hc/canonical-bytes -123))))

(deftest test-string
  (let [expected (byte-array [0x20 0x06 0x61 0x63 0x74 0x69 0x76 0x65])]
    (is (bytes= expected (hc/canonical-bytes "active")))))

(deftest test-keyword
  (let [expected (byte-array [0x22 0x06 0x61 0x63 0x74 0x69 0x76 0x65])]
    (is (bytes= expected (hc/canonical-bytes :active)))))

(deftest test-keyword-vs-string-distinct
  (is (not (bytes= (hc/canonical-bytes :active) (hc/canonical-bytes "active")))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Composites
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-empty-vector
  (is (bytes= (byte-array [0x30 0x00]) (hc/canonical-bytes []))))

(deftest test-empty-map
  (is (bytes= (byte-array [0x31 0x00]) (hc/canonical-bytes {}))))

(deftest test-vector-of-ints
  (is (bytes= (byte-array [0x30 0x03 0x10 0x02 0x10 0x04 0x10 0x06])
              (hc/canonical-bytes [1 2 3]))))

(deftest test-map-a-1
  (is (bytes= (byte-array [0x31 0x01 0x22 0x01 0x61 0x10 0x02])
              (hc/canonical-bytes {:a 1}))))

(deftest test-map-ordered-by-key
  (let [unordered {:b 2 :a 1}
        ordered (hc/canonical-bytes unordered)]
    (is (bytes= (byte-array [0x31 0x02
                             0x22 0x01 0x61 0x10 0x02
                             0x22 0x01 0x62 0x10 0x04])
                ordered))))

(deftest test-vector-hetero
  (let [bytes (hc/canonical-bytes [1 "two" :three])]
    (is (= 0x30 (aget bytes 0)))
    (is (= 3 (aget bytes 1)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Domain Hashing
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-domain-hash-deterministic
  (is (= (hc/domain-hash :evidence-record {:a 1})
         (hc/domain-hash :evidence-record {:a 1}))))

(deftest test-domain-hash-differs-by-tag
  (is (not (= (hc/domain-hash :evidence-record {:a 1})
              (hc/domain-hash :registry {:a 1})))))

(deftest test-domain-hash-differs-by-input
  (is (not (= (hc/domain-hash :evidence-record 1)
              (hc/domain-hash :evidence-record 2)))))

(deftest test-hash-bytes-returns-32-bytes
  (is (= 32 (count (hc/hash-bytes (hc/canonical-bytes nil))))))

(deftest test-hash-bytes-raw-not-hex
  (is (instance? (Class/forName "[B") (hc/hash-bytes (hc/canonical-bytes nil)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Type Validation
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validates-canonical-types
  (is (nil? (hc/validate-canonical-value! {:a 1 :b [1 2 3]})))
  (is (nil? (hc/validate-canonical-value! nil)))
  (is (nil? (hc/validate-canonical-value! "hello")))
  (is (nil? (hc/validate-canonical-value! :keyword))))

(deftest test-rejects-ratio
  (is (thrown? Exception (hc/validate-canonical-value! (/ 1 3)))))

(deftest test-rejects-symbol
  (is (thrown? Exception (hc/validate-canonical-value! 'foo))))

(deftest test-rejects-set
  (is (thrown? Exception (hc/validate-canonical-value! #{:a :b}))))

(deftest test-rejects-non-string-map-key
  (is (thrown? Exception (hc/validate-canonical-value! {1 "one"}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Conformance Test Vectors
;; ──────────────────────────────────────────────────────────────────────────────

(defn- load-vector [id]
  (let [path (str "resources/test-vectors/canonical-hash-v1/" id ".json")
        file (clojure.java.io/file path)]
    (when (.exists file)
      (json/read-str (slurp file) :key-fn keyword))))

(deftest test-conformance-null
  (let [v (load-vector "null")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record nil))))))

(deftest test-conformance-true
  (let [v (load-vector "true")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record true))))))

(deftest test-conformance-false
  (let [v (load-vector "false")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record false))))))

(deftest test-conformance-keyword-active
  (let [v (load-vector "keyword-active")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record :active))))))

(deftest test-conformance-string-active
  (let [v (load-vector "string-active")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record "active"))))))

(deftest test-conformance-int-123
  (let [v (load-vector "int-123")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record 123))))))

(deftest test-conformance-map-a-1
  (let [v (load-vector "map-a-1")]
    (when v
      (is (= (:hash_hex v) (hc/domain-hash :evidence-record {:a 1}))))))

(deftest test-projected-value-passes-validation
  (let [input {:a 1 :b "hello" :c [1 2 3]}
        result (hc/project-world-to-structure-view input :test-intent)]
    (is (= :test-intent (:intent result)))
    (is (= input (:structure result)))))

(deftest test-projection-converts-set-to-vector
  (let [result (hc/project-world-to-structure-view {:tags #{:a :b :c}} :test-intent)]
    (is (vector? (get-in result [:structure :tags])))
    (is (= [:a :b :c] (get-in result [:structure :tags])))))

(deftest test-projection-converts-empty-set
  (let [result (hc/project-world-to-structure-view #{} :test-intent)]
    (is (= [] (:structure result)))))

(deftest test-projection-converts-instant-to-string
  (let [now (Instant/now)
        result (hc/project-world-to-structure-view {:ts now} :test-intent)]
    (is (string? (get-in result [:structure :ts])))
    (is (= (.toString now) (get-in result [:structure :ts])))))

(deftest test-projection-converts-double-to-tagged
  (let [result (hc/project-world-to-structure-view {:rate 3.14} :test-intent)
        rate (get-in result [:structure :rate])]
    (is (= :float64 (:type rate)))
    (is (string? (:value-str rate)))
    (is (re-matches #"3\.14.*" (:value-str rate)))))

(deftest test-projection-converts-float-to-tagged
  (let [result (hc/project-world-to-structure-view {:rate (float 1.5)} :test-intent)
        rate (get-in result [:structure :rate])]
    (is (= :float64 (:type rate)))
    (is (= "1.50000000000000000" (:value-str rate)))))

(deftest test-projection-converts-function-to-struct-marker
  (let [r1 (hc/project-world-to-structure-view (fn [x] x) :test-intent)
        r2 (hc/project-world-to-structure-view map :test-intent)]
    (is (= {:type :fn} (:structure r1)))
    (is (= {:type :fn} (:structure r2)))))

(deftest test-projection-converts-list-to-vector
  (let [result (hc/project-world-to-structure-view '(1 2 3) :test-intent)]
    (is (= [1 2 3] (:structure result)))))

(deftest test-projection-nested-map-with-sets
  (let [input {:yield/risk {"m1" {"t1" {:failure-modes #{:a :b :c
                                                         :liquidity-crunch}
                                        :rate-mode :fixed}}}
               :resolver-unavailable #{:addr1 :addr2}
               :token-liquidity-crunch #{}}
        s (get-in (hc/project-world-to-structure-view input :test-intent) [:structure])]
    (is (vector? (get-in s [:yield/risk "m1" "t1" :failure-modes])))
    (is (vector? (:resolver-unavailable s)))
    (is (= [] (:token-liquidity-crunch s)))))

(deftest test-projection-idempotent
  (let [input {:nested {:set #{3 1 2}
                        :vec [1 2 3]
                        :kw :hello}
               :instant (Instant/now)
               :fn (fn [x] x)}
        once (hc/project-world-to-structure-view input :test-intent)
        twice (hc/project-world-to-structure-view (:structure once) :test-intent)]
    (is (= once twice))
    (is (nil? (hc/validate-canonical-value! once)))))

(deftest test-projection-rejects-unsupported
  (is (thrown? Exception (hc/project-world-to-structure-view (java.util.Date.) :test-intent))))

(deftest test-projected-value-passes-validation
  (let [world {:step 42
               :run-id "test-run"
               :resolver-unavailable #{:a :b}
               :context/time {:instant (Instant/now)
                              :step 42}
               :yield/risk {"m1" {"t1" {:failure-modes #{:liquidity-crunch}
                                        :rate-mode (fn [x] x)}}}}
        result (hc/project-world-to-structure-view world :test-intent)]
    (is (nil? (hc/validate-canonical-value! result)))
    (is (= :test-intent (:intent result)))))

(deftest test-world-hash-roundtrip
  (let [world-1 {:step 1 :resolver-unavailable #{:addr1}}
        world-2 {:step 1 :resolver-unavailable [:addr1]}]
    (is (= (hc/domain-hash :world-state world-1)
           (hc/domain-hash :world-state world-2)))
    (is (= 64 (count (hc/domain-hash :world-state world-1))))))

(deftest test-projection-converts-ratio-to-tagged
  (let [result (hc/project-world-to-structure-view {:ratio (/ 1 3)} :test-intent)
        ratio (get-in result [:structure :ratio])]
    (is (= :ratio (:type ratio)))
    (is (string? (:value-str ratio)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Hash Intent Declaration
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-hash-with-intent-basic
  (is (string? (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})))
  (is (= 64 (count (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})))))

(deftest test-hash-with-intent-world-structure
  (let [world {:step 1 :resolver-unavailable #{:addr1}}]
    (is (string? (hc/hash-with-intent {:hash/intent :world-structure} world)))))

(deftest test-hash-with-intent-evidence-content
  (let [data {:scenario/id "test" :event/seq 1}]
    (is (string? (hc/hash-with-intent {:hash/intent :evidence-content} data)))))

(deftest test-hash-with-intent-rejects-unknown
  (is (thrown? Exception (hc/hash-with-intent {:hash/intent :does-not-exist} {:a 1}))))

(deftest test-hash-intents-differ
  (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
            (hc/hash-with-intent {:hash/intent :world-structure} {:a 1})))
  (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
            (hc/hash-with-intent {:hash/intent :evidence-content} {:a 1}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Registry Contract Tests
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-resolve-intent-returns-contract
  (let [contract (hc/resolve-intent :world-structure)]
    (is (= :world-structure (:intent/name contract)))
    (is (string? (:intent/description contract)))
    (is (set? (:intent/includes contract)))
    (is (set? (:intent/excludes contract)))
    (is (fn? (:intent/projection-fn contract)))
    (is (string? (:intent/domain-tag contract)))
    (is (integer? (:intent/version contract)))))

(deftest test-resolve-intent-rejects-unknown
  (is (thrown? Exception (hc/resolve-intent :does-not-exist))))

(deftest test-all-intents-have-contract-fields
  (doseq [[kw contract] hc/hash-intents]
    (testing (str "Intent " kw)
      (is (= kw (:intent/name contract)))
      (is (string? (:intent/description contract))
          (str "Missing :intent/description for " kw))
      (is (set? (:intent/includes contract))
          (str "Missing :intent/includes for " kw))
      (is (set? (:intent/excludes contract))
          (str "Missing :intent/excludes for " kw))
      (is (fn? (:intent/projection-fn contract))
          (str "Missing :intent/projection-fn for " kw))
      (is (string? (:intent/domain-tag contract))
          (str "Missing :intent/domain-tag for " kw))
      (is (integer? (:intent/version contract))
          (str "Missing :intent/version for " kw)))))

(deftest test-intent-includes-excludes-are-disjoint
  (doseq [[kw contract] hc/hash-intents]
    (testing (str "Intent " kw " has disjoint includes and excludes")
      (is (empty? (set/intersection (:intent/includes contract)
                                    (:intent/excludes contract)))
          (str "Includes and excludes overlap for " kw)))))

(deftest test-intent-descriptions-are-unique
  (let [descriptions (set (map :intent/description (vals hc/hash-intents)))]
    (is (= (count hc/hash-intents) (count descriptions))
        "Every intent must have a unique description")))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Constraint Enforcement
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validate-intent-constraints-passes-clean-data
  (is (nil? (hc/validate-intent-constraints! :evidence-record {:a 1 :b "hello"})))
  (is (nil? (hc/validate-intent-constraints! :world-structure {:step 42 :run-id "r"}))))

(deftest test-validate-intent-constraints-catches-excluded-types
  (is (thrown? Exception
               (hc/validate-intent-constraints! :world-structure {:fn (fn [x] x)})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :world-structure {:set #{:a}})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :world-structure {:instant (Instant/now)})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :world-structure {:ratio (/ 1 3)})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :world-structure {:double 3.14}))))

(deftest test-validate-intent-constraints-catches-root-structural-violations
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-record {:evidence/hash "abc"
                                                                  :a 1})))
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-record {:evidence/timestamp "2024"
                                                                  :a 1}))))

(deftest test-validate-intent-constraints-checks-keywords-for-evidence-content
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-content {:evidence/type :core}))))

(deftest test-validate-intent-constraints-rejects-unknown-intent
  (is (thrown? Exception
               (hc/validate-intent-constraints! :does-not-exist {:a 1}))))

(deftest test-validate-intent-constraints-throws-on-hash-fields
  (is (thrown? Exception
               (hc/validate-intent-constraints! :evidence-content
                                                {:some-hash "abc" :data "test"}))))

(deftest test-validate-intent-constraints-nested-excludes
  (is (thrown? Exception
               (hc/validate-intent-constraints! :world-structure
                                                {:nested {:fn (fn [x] x)}})))
  (is (nil? (hc/validate-intent-constraints! :world-structure
                                             {:nested {:a 1}}))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Dynamic Var Validation in hash-with-intent
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-dynamic-var-validation-inline
  (let [clean-data {:a 1}
        dirty-data {:evidence/hash "abc" :a 1}]
    ;; Validation disabled (default) — no throw
    (is (string? (hc/hash-with-intent {:hash/intent :evidence-record} dirty-data)))
    ;; Validation enabled — throw
    (binding [hc/*validate-intent-constraints* true]
      (is (thrown? Exception
                   (hc/hash-with-intent {:hash/intent :evidence-record} dirty-data)))
      ;; Clean data passes even with validation enabled
      (is (string? (hc/hash-with-intent {:hash/intent :evidence-record} clean-data))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; intent-hash=
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-intent-hash=-same-strings
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})]
    (is (hc/intent-hash= h h))))

(deftest test-intent-hash=-different-strings
  (is (not (hc/intent-hash= "abc" "def"))))

(deftest test-intent-hash=-with-maps
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
        h2 (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
        h3 (hc/hash-with-intent {:hash/intent :world-structure} {:a 1})]
    (is (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h}
                         {:hash/intent :evidence-record :hash/hex h2}))
    (is (not (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h}
                              {:hash/intent :world-structure :hash/hex h3})))))

(deftest test-intent-hash=-string-and-map
  (let [h (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})]
    (is (hc/intent-hash= h {:hash/intent :evidence-record :hash/hex h}))))

(deftest test-intent-hash=-allows-cross-intent
  (let [h-same "abc123"
        h-diff "def456"]
    ;; string vs map with same hex, different intents — blocked by default
    (is (not (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h-same}
                              {:hash/intent :world-structure :hash/hex h-same})))
    ;; allow-cross-intent? true — compare hex regardless
    (is (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h-same}
                         {:hash/intent :world-structure :hash/hex h-same}
                         {:allow-cross-intent? true}))
    ;; different hex still false even with allow-cross-intent?
    (is (not (hc/intent-hash= {:hash/intent :evidence-record :hash/hex h-same}
                              {:hash/intent :world-structure :hash/hex h-diff}
                              {:allow-cross-intent? true})))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Golden Hash Vectors
;; ──────────────────────────────────────────────────────────────────────────────
;; Each fixture is hashed with its intent and the result is compared against a
;; known golden value. If the intent contract (projection, domain tag, encoding)
;; changes, the golden value will break — alerting the developer.

(def ^:private golden-fixtures
  "Intent-specific fixtures for golden hash regression testing."
  {:world-structure   {:step 42 :resolver-unavailable #{:addr1} :total-held {"0xtoken" 1000}}
   :evm-projection    (let [w {:step 42 :escrow-transfers {0 {:amount-after-fee 1000
                                                              :escrow-state :pending}}
                               :total-held {"0xtoken" 1000}
                               :total-fees {"0xtoken" 10}
                               :dispute-levels {0 0}
                               :block-time 1000}]
                        (assoc (select-keys w [:escrow-transfers :total-held
                                               :total-fees :dispute-levels
                                               :block-time])
                               :accounting-consistent? true))
   :evidence-record   {:a 1 :b "hello" :scenario/id "test"}
   :evidence-content  {:scenario/id "test" :event/seq 1 :evidence/type "stake-registered"}
   :evidence-chain    {:cursor/scope :targeted-evidence :cursor/final-seq 5
                       :cursor/final-self-hash "abc123" :cursor/total-captured 3}
   :manifest          {:suite {:scenario "sew-core"} :run-id "test-run" :status :pass}
   :bundle-root       {:benchmark/id "bm-1" :passed 10 :total 10}
   :registry          {:registry-hash nil :evidence-count 3 :artifacts []}
   :provenance        {:provenance/lineage [:evidence-chain]
                       :provenance/links [{:from "a" :to "b"}]}
   :state-diff        {:changes [{:path [:escrow :state] :before nil :after nil}]}
   :params-manifest   {:scenario "sew-core" :seed 42 :n-epochs 10}})

(def ^:private golden-hashes
  "Known golden hash values for each intent's fixture.
   If an intent contract changes, these must be recomputed."
  {:world-structure   "587bc682a51dd99b9830399487cc4eb10017a9fa81485c5dae38763e66531bd1"
   :evm-projection    "6cf3532b14f8ae4242483bd37784aa6be982418e6ccb899472cf73c21d406e30"
   :evidence-record   "bb94b6ff9457c613af3fefce33130c4b4e68a8a0f5b587c00124c18cfa848ace"
   :evidence-content  "aa84921b0d4b447aaad5a450ffee9b36e2e3f9d3d39991c0eb3842162b05630d"
   :evidence-chain    "365e311bef1cd758d3961a2b08dc59fffc8d636f8826ff4901328cfd4a49a84b"
   :manifest          "b6398eb7538ee05172ce62d656c2c4042819ad7d62fe1694c5dfafbf3ab242b7"
   :bundle-root       "689fc33fa03ad8e031515bb68dfbb90a7b8afc8b341cdbffdba215313afe70db"
   :registry          "ec2edbfaec899d9134ed51aec519e683d23cd7c58e36d9c6f42d5e1af15a6e37"
   :provenance        "43c51800d4418215e9a0c1c4b25a25733bf4c38e1288fe2e1cdc3be35578196a"
   :state-diff        "ece887daefd0565ff3770592def75aceb969d8903d8be7156617daea2d1e13a1"
   :params-manifest   "bf3cd3d2e0b9bf288bad087217c8868ef68e0d5d56e453ff2eaea24972cbc54e"})

(deftest test-golden-hash-vectors
  (doseq [[intent fixture] golden-fixtures]
    (testing (str "Golden hash for intent " intent)
      (let [expected (get golden-hashes intent)
            actual (hc/hash-with-intent {:hash/intent intent} fixture)]
        (is (= expected actual)
            (str "Golden hash mismatch for " intent " — intent contract or encoding may have changed"))))))

(deftest test-golden-hash-vectors-deterministic
  (doseq [[intent fixture] golden-fixtures]
    (testing (str "Deterministic hash for intent " intent)
      (is (= (hc/hash-with-intent {:hash/intent intent} fixture)
             (hc/hash-with-intent {:hash/intent intent} fixture))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Intent Registry Validation (IA-level)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-validate-registry-passes
  (is (nil? (hc/validate-registry!))
      "validate-registry! must pass on well-formed registry"))

(deftest test-validate-registry-detects-missing-field
  (with-redefs [hc/hash-intents (assoc hc/hash-intents
                                       :bad-intent
                                       {:intent/name :bad-intent})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest test-validate-registry-detects-wrong-type
  (with-redefs [hc/hash-intents (assoc hc/hash-intents
                                       :bad-intent
                                       {:intent/name         :bad-intent
                                        :intent/domain-tag   :not-a-string
                                        :intent/description  "bad"
                                        :intent/includes     #{}
                                        :intent/excludes     #{}
                                        :intent/projection-fn identity
                                        :intent/version      1})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(deftest test-validate-registry-detects-negative-version
  (with-redefs [hc/hash-intents (assoc hc/hash-intents
                                       :bad-intent
                                       {:intent/name         :bad-intent
                                        :intent/domain-tag   "BAD_V1"
                                        :intent/description  "bad"
                                        :intent/includes     #{}
                                        :intent/excludes     #{}
                                        :intent/projection-fn identity
                                        :intent/version      -1})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

(def ^:private phase-1-projection-intents
  [:intent-dsl
   :intent-registry-entry
   :intent-registry
   :projection-definition
   :projection-definition-registry
   :projection-artifact
   :claim-definition
   :attestor])

(def ^:private phase-1-projection-fixtures
  {:intent-dsl
   {:intent/type :pro-rata/allocation
    :intent/version 1
    :intent/purpose :slash-obligation-allocation
    :intent/scope {:protocol :sew :module :slashing :domain :economic-allocation}
    :intent/inputs #{:obligations :weights :caps}
    :intent/constraints #{:conservation :non-negative}
    :intent/output :allocation}

   :intent-registry-entry
   {:intent/name :projection-definition
    :intent/domain-tag "PROJECTION_DEFINITION_V1"
    :intent/description "Projection definition identity"
    :intent/includes #{:id :version :projection-type}
    :intent/excludes #{:canonical-hash}
    :intent/projection-fn 'resolver-sim.hash.canonical/project-projection-definition
    :intent/version 1}

   :intent-registry
   {:registry-version 1
    :intent-definitions [{:intent/name :intent-dsl
                          :intent/domain-tag "INTENT_DSL_V1"}]
    :intent-hashes ["abc"]
    :registry-hash "self-hash-to-strip"}

   :projection-definition
   {:id :projection/pro-rata-slash-obligation
    :version 1
    :projection-type :pro-rata-allocation
    :intent-types #{:pro-rata/allocation}
    :intent-purposes #{:slash-obligation-allocation}
    :source {:type :world-state}
    :include-paths [[:resolver-stakes] [:pending-slashes]]
    :exclude-paths [[:runtime/cache]]
    :transforms [{:op 'resolver-sim.projection/sort-by-id}]
    :output {:type :allocation-basis}
    :claims [{:claim-id :projection-deterministic}]
    :canonical-hash "self-hash-to-strip"}

   :projection-definition-registry
   {:registry-version 1
    :projection-definitions [{:id :projection/world-structure :version 1}]
    :definition-hashes ["def"]
    :registry-hash "self-hash-to-strip"}

   :projection-artifact
   {:schema-version 1
    :projection-id "proj-1"
    :projection-type :pro-rata-allocation
    :projection-version 1
    :intent {:intent/type :pro-rata/allocation
             :intent/version 1
             :intent/purpose :slash-obligation-allocation}
    :projection-definition-hash "definition-hash"
    :source {:type :world-state :world-hash "world-hash"}
    :projection {:liable-parties [{:id :alice :weight 2} {:id :bob :weight 1}]
                 :observed-at (Instant/ofEpochSecond 0)}
    :summary {:party-count 2}
    :claims [{:claim-id :projection-canonical-safe}]
    :metadata {:debug "not identity"}
    :projection-hash "self-hash-to-strip"}

   :claim-definition
   {:id :accounting-consistency
    :version 1
    :category :invariant
    :description "Accounting balances sum correctly"
    :inputs [:world-state :projection-artifact]
    :evaluation {:type :pure-predicate
                 :fn 'resolver-sim.claims/accounting-consistency}
    :outputs [:passed? :violations]
    :canonical-hash "self-hash-to-strip"}

   :attestor
   {:id :github-actions
    :type :ci-runner
    :display-name "GitHub Actions"
    :status :active
    :verification {:type :public-key
                   :algorithm :ed25519
                   :key-id "key-001"
                   :public-key "abc"}
    :metadata {:owner :protocol-robustness}
    :attestor-hash "self-hash-to-strip"}})

(deftest test-phase-1-projection-intents-registered
  (doseq [intent phase-1-projection-intents]
    (testing (str intent)
      (let [contract (hc/resolve-intent intent)]
        (is (= intent (:intent/name contract)))
        (is (string? (:intent/domain-tag contract)))
        (is (fn? (:intent/projection-fn contract)))))))

(deftest test-phase-1-domain-tags-registered
  (doseq [intent phase-1-projection-intents]
    (testing (str intent)
      (let [contract (hc/resolve-intent intent)]
        (is (= (:intent/domain-tag contract) (get hc/domain-tags intent)))))))

(deftest test-phase-1-projections-produce-canonical-safe-data
  (doseq [[intent fixture] phase-1-projection-fixtures]
    (testing (str intent)
      (let [project-fn (:intent/projection-fn (hc/resolve-intent intent))
            projection (project-fn fixture intent)]
        (is (nil? (hc/validate-canonical-value! projection)))
        (is (= intent (:intent projection)))))))

(deftest test-phase-1-hashes-are-deterministic-and-domain-separated
  (doseq [[intent fixture] phase-1-projection-fixtures]
    (testing (str intent)
      (let [project-fn (:intent/projection-fn (hc/resolve-intent intent))
            projected (project-fn fixture intent)
            hash-a (hc/hash-with-intent {:hash/intent intent} fixture)
            hash-b (hc/hash-with-intent {:hash/intent intent} fixture)
            other-domain-hash (hc/domain-hash "EVIDENCE_RECORD_V1" projected)]
        (is (= hash-a hash-b))
        (is (= 64 (count hash-a)))
        (is (not= hash-a other-domain-hash))))))

(deftest test-phase-1-self-hash-fields-do-not-affect-artifact-identity
  (doseq [[intent self-key] {:intent-registry :registry-hash
                             :projection-definition :canonical-hash
                             :projection-definition-registry :registry-hash
                             :projection-artifact :projection-hash
                             :claim-definition :canonical-hash
                             :attestor :attestor-hash}]
    (testing (str intent)
      (let [fixture (get phase-1-projection-fixtures intent)
            a (assoc fixture self-key "aaa")
            b (assoc fixture self-key "bbb")]
        (is (= (hc/hash-with-intent {:hash/intent intent} a)
               (hc/hash-with-intent {:hash/intent intent} b)))))))

(deftest test-phase-1-identity-fields-affect-artifact-identity
  (let [fixture (:projection-artifact phase-1-projection-fixtures)
        changed (assoc fixture :projection-id "proj-2")]
    (is (not= (hc/hash-with-intent {:hash/intent :projection-artifact} fixture)
              (hc/hash-with-intent {:hash/intent :projection-artifact} changed)))))

(deftest test-validate-registry-detects-duplicate-domain-tags
  (with-redefs [hc/hash-intents (assoc-in hc/hash-intents
                                          [:attestor :intent/domain-tag]
                                          "INTENT_DSL_V1")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hc/validate-registry!)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; New Intent Contract Tests (Evidence Layers 2, 7, 8, 9)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-invariant-attestation-intent
  (let [contract (hc/resolve-intent :invariant-attestation)]
    (is (= :invariant-attestation (:intent/name contract)))
    (is (string? (:intent/domain-tag contract)))
    (is (contains? (:intent/includes contract) :invariants))
    (is (contains? (:intent/includes contract) :invariant-set-hash))))

(deftest test-projection-evidence-intent
  (let [contract (hc/resolve-intent :projection-evidence)]
    (is (= :projection-evidence (:intent/name contract)))
    (is (contains? (:intent/includes contract) :projection-hash))))

(deftest test-checkpoint-evidence-intent
  (let [contract (hc/resolve-intent :checkpoint-evidence)]
    (is (= :checkpoint-evidence (:intent/name contract)))
    (is (contains? (:intent/includes contract) :checkpoint-id))))

(deftest test-benchmark-certification-intent
  (let [contract (hc/resolve-intent :benchmark-certification)]
    (is (= :benchmark-certification (:intent/name contract)))
    (is (contains? (:intent/includes contract) :all-invariants-pass))))

(deftest test-invariant-attestation-hash-is-deterministic
  (let [data {:step 1
              :invariants [{:id :conservation-of-value :result :pass}
                           {:id :no-negative-balances :result :pass}]
              :passed 2 :failed 0}
        h1 (hc/hash-with-intent {:hash/intent :invariant-attestation} data)
        h2 (hc/hash-with-intent {:hash/intent :invariant-attestation} data)]
    (is (= h1 h2))))

(deftest test-invariant-attestation-hash-changes-on-result-change
  (let [pass-data {:step 1 :invariants [{:id :test :result :pass}] :passed 1 :failed 0}
        fail-data {:step 1 :invariants [{:id :test :result :fail}] :passed 0 :failed 1}
        pass-hash (hc/hash-with-intent {:hash/intent :invariant-attestation} pass-data)
        fail-hash (hc/hash-with-intent {:hash/intent :invariant-attestation} fail-data)]
    (is (not= pass-hash fail-hash))))

(deftest test-invariant-attestation-domain-separated
  (let [data {:step 1 :invariants [] :passed 0 :failed 0}
        attest-hash (hc/hash-with-intent {:hash/intent :invariant-attestation} data)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} data)]
    (is (not= attest-hash world-hash))))

(deftest test-projection-evidence-hash-is-deterministic
  (let [data {:step 1 :world-hash "abc" :projection-hash "def" :projection-version 1}
        h1 (hc/hash-with-intent {:hash/intent :projection-evidence} data)
        h2 (hc/hash-with-intent {:hash/intent :projection-evidence} data)]
    (is (= h1 h2))))

(deftest test-checkpoint-evidence-hash-is-deterministic
  (let [data {:checkpoint-id "cp-1" :event-seq 1 :world-hash "abc" :chain-head 0}
        h1 (hc/hash-with-intent {:hash/intent :checkpoint-evidence} data)
        h2 (hc/hash-with-intent {:hash/intent :checkpoint-evidence} data)]
    (is (= h1 h2))))

(deftest test-benchmark-certification-hash-is-deterministic
  (let [data {:benchmark-id "bm-1" :scenario-count 10 :all-invariants-pass true
              :final-state-hash nil :evidence-chain-root nil
              :invariant-summary {:conservation {:passed 10 :total 10}}}
        h1 (hc/hash-with-intent {:hash/intent :benchmark-certification} data)
        h2 (hc/hash-with-intent {:hash/intent :benchmark-certification} data)]
    (is (= h1 h2))))

;; ──────────────────────────────────────────────────────────────────────────────
;; New Intent Contract Tests (Evidence Layers 4, 6)
;; ──────────────────────────────────────────────────────────────────────────────

(deftest test-decision-evidence-intent
  (let [contract (hc/resolve-intent :decision-evidence)]
    (is (= :decision-evidence (:intent/name contract)))
    (is (contains? (:intent/includes contract) :decision-id))
    (is (contains? (:intent/includes contract) :alternatives))
    (is (contains? (:intent/includes contract) :selected))))

(deftest test-invariant-failure-intent
  (let [contract (hc/resolve-intent :invariant-failure)]
    (is (= :invariant-failure (:intent/name contract)))
    (is (contains? (:intent/includes contract) :invariant-ids))
    (is (contains? (:intent/includes contract) :halt-reason))))

(deftest test-decision-evidence-hash-is-deterministic
  (let [data {:decision-id "resolve-0-1" :step 5
              :alternatives [:release :refund] :selected :release
              :reasoning "Resolver alice released escrow 0"
              :caller "alice" :workflow-id "0"}
        h1 (hc/hash-with-intent {:hash/intent :decision-evidence} data)
        h2 (hc/hash-with-intent {:hash/intent :decision-evidence} data)]
    (is (= h1 h2))))

(deftest test-decision-evidence-hash-changes-on-different-selection
  (let [release-data {:decision-id "resolve-0-1" :step 5
                      :alternatives [:release :refund] :selected :release
                      :reasoning "Release" :caller "alice" :workflow-id "0"}
        refund-data {:decision-id "resolve-0-1" :step 5
                     :alternatives [:release :refund] :selected :refund
                     :reasoning "Refund" :caller "alice" :workflow-id "0"}
        release-hash (hc/hash-with-intent {:hash/intent :decision-evidence} release-data)
        refund-hash (hc/hash-with-intent {:hash/intent :decision-evidence} refund-data)]
    (is (not= release-hash refund-hash))))

(deftest test-invariant-failure-hash-is-deterministic
  (let [data {:step 5 :scenario-id "scen-1"
              :invariant-ids [:no-negative-balances]
              :details {"no-negative-balances" "balance -5"}
              :halt-reason :invariant-violation}
        h1 (hc/hash-with-intent {:hash/intent :invariant-failure} data)
        h2 (hc/hash-with-intent {:hash/intent :invariant-failure} data)]
    (is (= h1 h2))))

(deftest test-invariant-failure-hash-changes-on-different-invariant
  (let [data-a {:step 5 :scenario-id "scen-1"
                :invariant-ids [:no-negative-balances]
                :details {} :halt-reason :invariant-violation}
        data-b {:step 5 :scenario-id "scen-1"
                :invariant-ids [:conservation-of-value]
                :details {} :halt-reason :invariant-violation}
        hash-a (hc/hash-with-intent {:hash/intent :invariant-failure} data-a)
        hash-b (hc/hash-with-intent {:hash/intent :invariant-failure} data-b)]
    (is (not= hash-a hash-b))))

(deftest test-decision-evidence-domain-separated
  (let [data {:decision-id "d1" :step 1 :alternatives [] :selected :none
              :reasoning "test" :caller "alice" :workflow-id "0"}
        dec-hash (hc/hash-with-intent {:hash/intent :decision-evidence} data)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} data)]
    (is (not= dec-hash world-hash))))

(deftest test-invariant-failure-domain-separated
  (let [data {:step 1 :scenario-id "s" :invariant-ids [] :details {} :halt-reason :invariant-violation}
        fail-hash (hc/hash-with-intent {:hash/intent :invariant-failure} data)
        world-hash (hc/hash-with-intent {:hash/intent :world-structure} data)]
    (is (not= fail-hash world-hash))))