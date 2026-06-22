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
  (let [input {:a 1 :b "hello" :c [1 2 3]}]
    (is (= input (hc/project-world-to-structure-view input)))))

(deftest test-projection-converts-set-to-vector
  (let [result (hc/project-world-to-structure-view {:tags #{:a :b :c}})]
    (is (vector? (:tags result)))
    (is (= [:a :b :c] (:tags result)))))

(deftest test-projection-converts-empty-set
  (is (= [] (hc/project-world-to-structure-view #{}))))

(deftest test-projection-converts-instant-to-string
  (let [now (Instant/now)
        result (hc/project-world-to-structure-view {:ts now})]
    (is (string? (:ts result)))
    (is (= (.toString now) (:ts result)))))

(deftest test-projection-converts-double-to-string
  (let [result (hc/project-world-to-structure-view {:rate 3.14})]
    (is (string? (:rate result)))))

(deftest test-projection-converts-float-to-string
  (let [result (hc/project-world-to-structure-view {:rate (float 1.5)})]
    (is (string? (:rate result)))))

(deftest test-projection-converts-function-to-fn
  (is (= :fn (hc/project-world-to-structure-view (fn [x] x))))
  (is (= :fn (hc/project-world-to-structure-view map))))

(deftest test-projection-converts-list-to-vector
  (is (= [1 2 3] (hc/project-world-to-structure-view '(1 2 3)))))

(deftest test-projection-nested-map-with-sets
  (let [input {:yield/risk {"m1" {"t1" {:failure-modes #{:a :b :c
                                                         :liquidity-crunch}
                                        :rate-mode :fixed}}}
               :resolver-unavailable #{:addr1 :addr2}
               :token-liquidity-crunch #{}}
        result (hc/project-world-to-structure-view input)]
    (is (vector? (get-in result [:yield/risk "m1" "t1" :failure-modes])))
    (is (vector? (:resolver-unavailable result)))
    (is (= [] (:token-liquidity-crunch result)))))

(deftest test-projection-idempotent
  (let [input {:nested {:set #{3 1 2}
                        :vec [1 2 3]
                        :kw :hello}
               :instant (Instant/now)
               :fn (fn [x] x)}
        once (hc/project-world-to-structure-view input)
        twice (hc/project-world-to-structure-view once)]
    (is (= once twice))
    (is (nil? (hc/validate-canonical-value! once)))))

(deftest test-projection-rejects-unsupported
  (is (thrown? Exception (hc/project-world-to-structure-view (java.util.Date.)))))

(deftest test-projected-value-passes-validation
  (let [world {:step 42
               :run-id "test-run"
               :resolver-unavailable #{:a :b}
               :context/time {:instant (Instant/now)
                              :step 42}
               :yield/risk {"m1" {"t1" {:failure-modes #{:liquidity-crunch}
                                        :rate-mode (fn [x] x)}}}}]
    (is (nil? (hc/validate-canonical-value!
               (hc/project-world-to-structure-view world))))))

(deftest test-world-hash-roundtrip
  (let [world-1 {:step 1 :resolver-unavailable #{:addr1}}
        world-2 {:step 1 :resolver-unavailable [:addr1]}
        h1 (hc/domain-hash :world-state (hc/project-world-to-structure-view world-1))
        h2 (hc/domain-hash :world-state (hc/project-world-to-structure-view world-2))]
    (is (= h1 h2))
    (is (= 64 (count h1)))))

(deftest test-projection-converts-ratio-to-string
  (let [result (hc/project-world-to-structure-view {:ratio (/ 1 3)})]
    (is (string? (:ratio result)))))

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
    (is (set? (:intent/scope contract)))
    (is (set? (:intent/excludes contract)))
    (is (fn? (:project contract)))
    (is (keyword? (:domain contract)))))

(deftest test-resolve-intent-rejects-unknown
  (is (thrown? Exception (hc/resolve-intent :does-not-exist))))

(deftest test-all-intents-have-contract-fields
  (doseq [[kw contract] hc/hash-intents]
    (testing (str "Intent " kw)
      (is (= kw (:intent/name contract)))
      (is (string? (:intent/description contract))
          (str "Missing :intent/description for " kw))
      (is (set? (:intent/scope contract))
          (str "Missing :intent/scope for " kw))
      (is (set? (:intent/excludes contract))
          (str "Missing :intent/excludes for " kw))
      (is (fn? (:project contract))
          (str "Missing :project for " kw))
      (is (keyword? (:domain contract))
          (str "Missing :domain for " kw)))))

(deftest test-intent-scope-excludes-are-disjoint
  (doseq [[kw contract] hc/hash-intents]
    (testing (str "Intent " kw " has disjoint scope and excludes")
      (is (empty? (set/intersection (:intent/scope contract)
                                    (:intent/excludes contract)))
          (str "Scope and excludes overlap for " kw)))))

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
