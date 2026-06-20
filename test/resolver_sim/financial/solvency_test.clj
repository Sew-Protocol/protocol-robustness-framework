(ns resolver-sim.financial.solvency-test
  "Tests for cryptographic solvency classification.
   Covers all five solvency statuses: :solvent, :insolvent, :unproven,
   :proof-invalid, :proof-state-mismatch.

   Also covers the live SHA-256 commitment layer (compute-state-commitment,
   with-commitment, and the :valid proof-status path that verifies the
   stored commitment against a re-computed hash)."
  (:require [clojure.test :refer :all]
            [resolver-sim.financial.solvency :as solv]
            [resolver-sim.protocols.sew.types :as t]))

;; ── Classification defaults ──────────────────────────────────────────────────

(deftest default-is-unproven
  (let [result (solv/classify-solvency (t/empty-world 1000))]
    (is (= :unproven (:solvency/status result)))
    (is (nil? (:solvency/proof-valid? result)))))

(deftest accounting-insolvent-detected
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:total-held :USDC] 500)
                  (assoc-in [:claimable :USDC] 200)
                  (assoc-in [:bond-balances 0 "0xRes0"] 10000))
        result (solv/classify-solvency world)]
    (is (= :insolvent (:solvency/status result)))))

;; ── Proof-status mapping ─────────────────────────────────────────────────────

(deftest proof-status-maps-correctly
  (let [cases [[nil       :unproven]
               [:unproven :unproven]
               [:valid    :proof-invalid]   ;; no stored commitment → invalid
               [:invalid  :proof-invalid]
               [:mismatch :proof-state-mismatch]]]
    (doseq [[proof-status expected] cases]
      (let [result (solv/classify-solvency (t/empty-world 1000) nil
                                           {:proof-status proof-status})]
        (is (= expected (:solvency/status result))
            (str "proof-status " proof-status " → " expected))))))

;; ── SHA-256 commitment layer ─────────────────────────────────────────────────

(deftest commitment-deterministic
  (testing "Same world + same prev-commitment produces identical hash"
    (let [world (t/empty-world 1000)
          h1    (solv/compute-state-commitment world nil)
          h2    (solv/compute-state-commitment world nil)]
      (is (string? h1))
      (is (= 64 (count h1)) "SHA-256 hex = 64 chars")
      (is (= h1 h2) "deterministic — same inputs → same hash"))))

(deftest commitment-changes-with-state
  (testing "Different world states produce different commitments"
    (let [w1  (t/empty-world 1000)
          w2  (assoc-in (t/empty-world 1000) [:escrow-transfers 0 :escrow-state] :pending)
          h1  (solv/compute-state-commitment w1 nil)
          h2  (solv/compute-state-commitment w2 nil)]
      (is (not= h1 h2) "different escrow state → different hash"))))

(deftest commitment-chains
  (testing "Previous commitment is included in the preimage"
    (let [world (t/empty-world 1000)
          h1    (solv/compute-state-commitment world nil)
          h2    (solv/compute-state-commitment world h1)]
      (is (not= h1 h2) "prev-commitment changes the hash"))))

(deftest with-commitment-stores-hash
  (testing "with-commitment stores commitment-root in world"
    (let [world (solv/with-commitment (t/empty-world 1000))
          sol   (:solvency world)]
      (is (map? sol))
      (is (string? (:commitment-root sol)))
      (is (= 64 (count (:commitment-root sol))))
      (is (nil? (:prev-commitment sol)) "first commitment has no prev"))))

(deftest with-commitment-chains-properly
  (testing "Second call uses first commitment as prev"
    (let [w1 (solv/with-commitment (t/empty-world 1000))
          w2 (solv/with-commitment w1)
          c1 (get-in w1 [:solvency :commitment-root])
          c2 (get-in w2 [:solvency :commitment-root])
          p2 (get-in w2 [:solvency :prev-commitment])]
      (is (= c1 p2) "second commitment's prev = first commitment's root")
      (is (not= c1 c2) "second hash differs from first"))))

(deftest with-commitment-valid-proof
  (testing "After with-commitment, :proof-status :valid produces :solvent"
    (let [world (solv/with-commitment (t/empty-world 1000))
          result (solv/classify-solvency world nil {:proof-status :valid})]
      (is (= :solvent (:solvency/status result))
          "with-commitment + valid request → solvent")
      (is (true? (:solvency/proof-valid? result)))
      (is (string? (:solvency/commitment result))))))

(deftest tampered-world-commitment-mismatch
  (testing "Tampering state after with-commitment produces different hash"
    (let [base    (t/empty-world 1000)
          world   (solv/with-commitment base)
          stored  (get-in world [:solvency :commitment-root])
          tampered (assoc-in world [:escrow-transfers 0 :escrow-state] :disputed)
          computed (solv/compute-state-commitment tampered
                                                  (get-in tampered [:solvency :prev-commitment]))
          result  (solv/classify-solvency tampered nil {:proof-status :valid})]
      (is (string? stored))
      (is (string? computed))
      (is (not= stored computed) "tampered state → different commitment hash")
      ;; Final status may be :insolvent if accounting fails, but the key
      ;; assertion is that the proof layer recognizes the mismatch:
      (is (not= :solvent (:solvency/status result))
          "tampered state → cannot be :solvent"))))
