(ns resolver-sim.util.evidence-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.util.evidence :as ev]
            [resolver-sim.util.attribution :as attr]))

;; Sample data for tests
(def ^:private sample-world
  {:escrows {"0xabc" {:balance 1000 :owner "0x1"}}
   :resolvers {"0xres" {:stake 500 :status :active}}})

(def ^:private sample-action
  {:type :release
   :escrow-id "0xabc"
   :actor "0xres"})

(def ^:private sample-result
  {:success? true
   :released 1000
   :fees 15})

(def ^:private sample-attribution
  {:ctx/run-id "test-run-1"
   :ctx/scenario-id "test-scenario"
   :ctx/step 5
   :ctx/event-id "evt-001"})

(def ^:private partial-attribution
  {:ctx/run-id "test-run-1"})

;; ── Internal Context Embedding ───────────────────────────────────────────────

(deftest test-attributed-state-wrapping
  (let [state {:balance 100}
        attribution {:ctx/run-id "run-1"}
        attributed (attr/wrap-state state attribution)]
    (is (= state (attr/unwrap-state attributed)))
    (is (= attribution (:attribution attributed)))))

(deftest test-get-attribution-from-attributed-state
  (let [state {:balance 100}
        attribution {:ctx/run-id "run-1"}
        attributed (attr/wrap-state state attribution)]
    (is (= attribution (attr/get-attribution attributed)))
    (binding [attr/*attribution* {:ctx/run-id "dynamic"}]
      (is (= {:ctx/run-id "dynamic"} (attr/get-attribution nil))))))

;; ── require-attribution! ─────────────────────────────────────────────────────

(deftest require-attribution-passes-with-complete-context
  (binding [attr/*attribution* sample-attribution]
    (let [result (ev/require-attribution! :transition)]
      (is (map? result))
      (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result))))))

(deftest require-attribution-throws-on-missing-keys
  (binding [attr/*attribution* partial-attribution]
    (try
      (ev/require-attribution! :transition)
      (is false "Expected exception was not thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :transition (:evidence-kind data)))
          (is (some #{:ctx/scenario-id} (:missing data)))
          (is (some #{:ctx/step} (:missing data)))
          (is (some #{:ctx/event-id} (:missing data))))))))

(deftest require-attribution-accepts-empty-attribution-for-unknown-kind
  (binding [attr/*attribution* {}]
    (let [result (ev/require-attribution! :unknown-kind)]
      (is (map? result)))))

;; ── stable-hash ──────────────────────────────────────────────────────────────

(deftest stable-hash-is-deterministic
  (let [data {:a 1 :b [2 3] :c {:nested "value"}}
        h1 (ev/stable-hash data)
        h2 (ev/stable-hash data)
        h3 (ev/stable-hash (into (sorted-map) data))]
    (is (string? h1))
    (is (= 64 (count h1)))
    (is (= h1 h2))
    (is (= h1 h3))))

(deftest stable-hash-differs-for-different-inputs
  (is (not= (ev/stable-hash {:a 1}) (ev/stable-hash {:a 2}))))

(deftest stable-hash-handles-keyword-values
  (let [h (ev/stable-hash {:status :active :tags #{:a :b}})]
    (is (string? h))
    (is (= 64 (count h)))))

(deftest stable-hash-handles-empty-map
  (let [h (ev/stable-hash {})]
    (is (string? h))
    (is (= 64 (count h)))))

;; ── make-evidence-record ─────────────────────────────────────────────────────

(deftest make-evidence-record-includes-attribution
  (let [record (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})]
    (is (= "evidence-record.v1" (:schema-version record)))
    (is (= :transition (:artifact-kind record)))
    (is (= sample-attribution (:attribution record)))
    (is (string? (:evidence-hash record)))))

(deftest make-evidence-record-includes-hashes
  (let [record (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})]
    (is (string? (:context-hash record)))
    (is (string? (:before-hash record)))
    (is (string? (:after-hash record)))
    (is (string? (:action-hash record)))
    (is (string? (:result-hash record)))
    (is (string? (:evidence-hash record)))))

(deftest make-evidence-record-hash-changes-when-attribution-changes
  (let [record1 (ev/make-evidence-record
                  {:artifact-kind :transition
                   :before sample-world
                   :after sample-world
                   :action sample-action
                   :result sample-result
                   :attribution sample-attribution})
        record2 (ev/make-evidence-record
                  {:artifact-kind :transition
                   :before sample-world
                   :after sample-world
                   :action sample-action
                   :result sample-result
                   :attribution (assoc sample-attribution :ctx/step 99)})]
    (is (not= (:evidence-hash record1) (:evidence-hash record2)))))

(deftest make-evidence-record-hash-changes-when-result-changes
  (let [record1 (ev/make-evidence-record
                  {:artifact-kind :transition
                   :before sample-world
                   :after sample-world
                   :action sample-action
                   :result sample-result
                   :attribution sample-attribution})
        record2 (ev/make-evidence-record
                  {:artifact-kind :transition
                   :before sample-world
                   :after sample-world
                   :action sample-action
                   :result (assoc sample-result :success? false)
                   :attribution sample-attribution})]
    (is (not= (:evidence-hash record1) (:evidence-hash record2)))))

(deftest make-evidence-record-hash-changes-when-before-changes
  (let [record1 (ev/make-evidence-record
                  {:artifact-kind :transition
                   :before sample-world
                   :after sample-world
                   :action sample-action
                   :result sample-result
                   :attribution sample-attribution})
        record2 (ev/make-evidence-record
                  {:artifact-kind :transition
                   :before (assoc-in sample-world [:escrows "0xabc" :balance] 2000)
                   :after sample-world
                   :action sample-action
                   :result sample-result
                   :attribution sample-attribution})]
    (is (not= (:evidence-hash record1) (:evidence-hash record2)))))

(deftest make-evidence-record-consistent-for-same-inputs
  (let [spec {:artifact-kind :transition
              :before sample-world
              :after sample-world
              :action sample-action
              :result sample-result
              :attribution sample-attribution}
        record1 (ev/make-evidence-record spec)
        record2 (ev/make-evidence-record spec)]
    (is (= (:evidence-hash record1) (:evidence-hash record2)))
    (is (= record1 record2))))

(deftest make-evidence-record-with-different-kinds
  (let [invariant-record (ev/make-evidence-record
                           {:artifact-kind :invariant
                            :before sample-world
                            :after sample-world
                            :action sample-action
                            :result {:invariant :solvency :passed? true}
                            :attribution sample-attribution})]
    (is (= :invariant (:artifact-kind invariant-record)))
    (is (string? (:evidence-hash invariant-record)))))

;; ── emit-evidence! ───────────────────────────────────────────────────────────

(deftest emit-evidence-produces-valid-record
  (binding [attr/*attribution* sample-attribution]
    (let [record (ev/emit-evidence!
                   {:artifact-kind :transition
                    :block-time 1000
                    :step 1
                    :before sample-world
                    :after sample-world
                    :action sample-action
                    :result sample-result})]
      (is (= "evidence-record.v1" (:schema-version record)))
      (is (= :transition (:artifact-kind record)))
      (is (contains? record :attribution))
      (is (string? (:evidence-hash record))))))

(deftest emit-evidence-throws-on-missing-attribution
  (binding [attr/*attribution* {}]
    (try
      (ev/emit-evidence!
        {:artifact-kind :transition
         :block-time 1000
         :step 1
         :before {}
         :after {}
         :action {}
         :result {}})
      (is false "Expected exception was not thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :transition (:evidence-kind data)))
          (is (some? (:missing data))))))))

(deftest emit-evidence-throws-on-missing-temporal-context
  (binding [attr/*attribution* sample-attribution]
    (try
      (ev/emit-evidence!
        {:artifact-kind :transition
         :before {}
         :after {}
         :action {}
         :result {}})
      (is false "Expected exception was not thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (nil? (:block-time data)))
          (is (nil? (:step data))))))))

(deftest emit-evidence-works-with-partial-attribution-for-scenario
  (binding [attr/*attribution* {:ctx/run-id "run-1"
                                :ctx/scenario-id "scenario-1"}]
    (let [record (ev/emit-evidence!
                   {:artifact-kind :scenario
                    :block-time 1000
                    :step 1
                    :before {}
                    :after {}
                    :action {:type :start}
                    :result {:status :ok}})]
      (is (= "evidence-record.v1" (:schema-version record)))
      (is (string? (:evidence-hash record))))))

(deftest emit-evidence-works-with-explicit-attribution
  (let [explicit-attr {:ctx/run-id "run-1"
                       :ctx/scenario-id "scen-1"
                       :ctx/step 1
                       :ctx/event-id "evt-1"}]
    (binding [attr/*attribution* {}] ;; Ensure dynamic is empty
      (let [record (ev/emit-evidence!
                     {:artifact-kind :transition
                      :block-time 1000
                      :step 1
                      :before {}
                      :after {}
                      :action {}
                      :result {}
                      :attribution-context explicit-attr})]
        (is (= "run-1" (get-in record [:attribution :ctx/run-id])))))))

(deftest emit-evidence-works-with-attributed-state
  (let [explicit-attr {:ctx/run-id "run-1"
                       :ctx/scenario-id "scen-1"
                       :ctx/step 1
                       :ctx/event-id "evt-1"}
        attributed (attr/wrap-state {} explicit-attr)]
    (binding [attr/*attribution* {}] ;; Ensure dynamic is empty
      (let [record (ev/emit-evidence!
                     {:artifact-kind :transition
                      :block-time 1000
                      :step 1
                      :before {}
                      :after {}
                      :action {}
                      :result {}
                      :attribution-context attributed})]
        (is (= "run-1" (get-in record [:attribution :ctx/run-id])))))))

;; ── wrap-attribution ─────────────────────────────────────────────────────────

(deftest wrap-attribution-preserves-context
  (binding [attr/*attribution* sample-attribution]
    (let [wrapped (ev/wrap-attribution (fn [] (attr/current-attribution)))]
      (binding [attr/*attribution* {}]
        (let [preserved (wrapped)]
          (is (= (:ctx/run-id sample-attribution) (:ctx/run-id preserved)))
          (is (= (:ctx/scenario-id sample-attribution) (:ctx/scenario-id preserved)))
          (is (= (:ctx/step sample-attribution) (:ctx/step preserved))))))))

(deftest wrap-attribution-passes-arguments
  (binding [attr/*attribution* sample-attribution]
    (let [wrapped (ev/wrap-attribution (fn [a b] (+ a b)))]
      (is (= 7 (wrapped 3 4)))
      (let [wrapped2 (ev/wrap-attribution (fn [x] (assoc (attr/current-attribution) :arg x)))]
        (binding [attr/*attribution* {}]
          (let [result (wrapped2 "test-arg")]
            (is (= "test-arg" (:arg result)))
            (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result)))))))))

;; ── contextual-future ────────────────────────────────────────────────────────

(deftest contextual-future-preserves-attribution
  (binding [attr/*attribution* sample-attribution]
    (let [f (ev/contextual-future (attr/current-attribution))
          result @f]
      (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result)))
      (is (= (:ctx/scenario-id sample-attribution) (:ctx/scenario-id result)))
      (is (= (:ctx/step sample-attribution) (:ctx/step result))))))

(deftest contextual-future-isolated-from-outer-changes
  (binding [attr/*attribution* sample-attribution]
    (let [f (ev/contextual-future (do (Thread/sleep 50) (attr/current-attribution)))]
      (binding [attr/*attribution* {:ctx/run-id "overwritten"}]
        (let [result @f]
          (is (= (:ctx/run-id sample-attribution) (:ctx/run-id result))))))))

;; ── contextual-pmap ──────────────────────────────────────────────────────────

(deftest contextual-pmap-preserves-attribution
  (binding [attr/*attribution* sample-attribution]
    (let [items [1 2 3]
          results (doall (ev/contextual-pmap
                          (fn [x] [x (attr/current-attribution)])
                          items))]
      (is (= 3 (count results)))
      (doseq [[_ attr-result] results]
        (is (= (:ctx/run-id sample-attribution) (:ctx/run-id attr-result)))))))

;; ── Edge Cases ───────────────────────────────────────────────────────────────

(deftest make-evidence-record-with-nil-values
  (let [record (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before nil
                  :after nil
                  :action nil
                  :result nil
                  :attribution {:ctx/run-id "run-1"}})]
    (is (string? (:evidence-hash record)))
    (is (string? (:before-hash record)))
    (is (string? (:after-hash record)))
    (is (= "evidence-record.v1" (:schema-version record)))))

(deftest evidence-record-round-trip-json
  (let [record (ev/make-evidence-record
                 {:artifact-kind :transition
                  :before sample-world
                  :after sample-world
                  :action sample-action
                  :result sample-result
                  :attribution sample-attribution})
        json-str (json/write-str record)
        re-read (json/read-str json-str)]
    (is (string? json-str))
    (is (= (:schema-version record) (get re-read "schema-version")))))
