(ns resolver-sim.stochastic.detection-test
  "Tests for probabilistic detection helpers, including the new-evidence-probability
   gate that controls Track 2 pending-evidence reversal slashing."
  (:require [clojure.test :refer :all]
            [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.types :as st-types]
            [resolver-sim.io.params :as io-params]))

(deftest reversal-pending-live?-zero-threshold-disabled
  (is (false? (detection/reversal-pending-live?
                {:rng (rng/make-rng 42)
                 :new-evidence-probability 0.0}
                {:reversal-slashed? true}))
      "reversal-pending-live? should return false when threshold is 0.0"))

(deftest reversal-pending-live?-not-slashed-disabled
  (is (false? (detection/reversal-pending-live?
                {:rng (rng/make-rng 42)
                 :new-evidence-probability 0.05}
                {:reversal-slashed? false}))
      "reversal-pending-live? should return false when not reversal-slashed"))

(deftest reversal-pending-live?-positive-threshold-active
  (let [params  {:rng (rng/make-rng 42)
                 :new-evidence-probability 1.0}
        result  (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (true? result)
        "reversal-pending-live? should return true when threshold=1.0 (always fires)")))

(deftest reversal-pending-live?-probabilistic-roll
  (let [n-trials 1000
        params-fn (fn [seed]
                    {:rng (rng/make-rng seed)
                     :new-evidence-probability 0.5})
        results (for [s (range n-trials)]
                  (detection/reversal-pending-live? (params-fn s) {:reversal-slashed? true}))
        rate (/ (count (filter true? results)) (double n-trials))]
    (is (< 0.40 rate 0.60)
        (str "reversal-pending-live? with 0.5 threshold should fire ~50% over " n-trials " trials; got " rate))))

(deftest reversal-pending-live?-trace-decision-marker
  (let [params {:rng (rng/make-rng 42)
                :oracle-roll-trace-enabled? true
                :new-evidence-probability 1.0
                :scenario-id "detection-test"}
        result (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (true? result)
        "oracle-roll-trace-enabled? should not affect reversal-pending-live? outcome")))

(deftest new-evidence-probability-integration
  (testing "schema validator rejects out-of-range values"
    (let [schema-fn (get st-types/scenario-schema :new-evidence-probability)]
      (is (schema-fn 0.0) "0.0 is valid")
      (is (schema-fn 0.05) "0.05 is valid")
      (is (schema-fn 1.0) "1.0 is valid")
      (is (not (schema-fn -0.1)) "-0.1 is invalid")
      (is (not (schema-fn 1.5)) "1.5 is invalid")
      (is (not (schema-fn "0.5")) "string is invalid")))

  (testing "params EDN files have differentiated values"
    (let [baseline   (io-params/load-edn "data/params/phase-n-baseline.edn")
          high-fraud (io-params/load-edn "data/params/phase-n-high-fraud.edn")]
      (is (< (get baseline :new-evidence-probability 0)
             (get high-fraud :new-evidence-probability 0))
          "high-fraud new-evidence-probability should exceed baseline"))))

(deftest fixed-or-per-kind-map-pipeline
  (testing ":fixed-or with per-kind map propagates through to reversal-pending-live?"
    (let [params  {:fixed-or {:rolls {:pending-evidence [0.9 0.9 0.9 0.9 0.9 0.1]}
                               :scope #{:detection}}
                   :new-evidence-probability 0.5}
          ;; The first 5 rolls (0.9) are NOT < 0.5 → no detect.
          ;; The 6th roll (0.1) IS < 0.5 → detect.
          prepared (detection/prepare-oracle-params params)
          oracle-effective (:oracle-effective prepared)]
      (is (= :fixed-roll-sequence (:mode oracle-effective))
          ":fixed-or shorthand must normalize to :fixed-roll-sequence")
      (is (contains? oracle-effective :rolls)
          "oracle fixture must have :rolls")
      ;; The fixture has 5 zeros then a 1.0 — with threshold 1.0 and
      ;; reversal-slashed? true, the 6th roll (1.0) should detect.
      (let [_ (dotimes [_ 5]
                 (detection/oracle-roll-event prepared :pending-evidence))
            result (detection/reversal-pending-live?
                    prepared {:reversal-slashed? true})]
        (is result "reversal-pending-live? should detect on 6th roll (0.1 < 0.5 threshold)")))))


(deftest fixed-or-simple-vector-fallback
  (testing ":fixed-or simple vector feeds shared roll sequence across kinds"
    (let [params  {:fixed-or [0.9 0.9 0.9 0.9 0.9 0.1]
                   :oracle-mode :fixed-or
                   :new-evidence-probability 0.5}
          prepared (detection/prepare-oracle-params params)
          oracle-effective (:oracle-effective prepared)]
      (is (= :fixed-roll-sequence (:mode oracle-effective))
          ":fixed-or shorthand must normalize to :fixed-roll-sequence")
      (is (vector? (:rolls oracle-effective))
          "simple vector must remain a vector, not become a per-kind map")
      ;; Consume 5 rolls via :fraud-detection (valid roll-kind, shared cursor)
      (dotimes [_ 5]
        (detection/oracle-roll-event prepared :fraud-detection))
      ;; The 6th roll (0.1) is now at cursor position 5 (0-indexed).
      ;; :pending-evidence falls back to the same shared sequence.
      (let [result (detection/reversal-pending-live?
                    prepared {:reversal-slashed? true})]
        (is result "simple vector: 6th roll 0.1 < 0.5 threshold should detect on :pending-evidence")))))
