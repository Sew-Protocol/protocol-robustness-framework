(ns resolver-sim.financial.solvency
  "Cryptographic solvency classification.

   Cryptographic solvency is stronger than accounting solvency. It asks:
   can the protocol prove, from verifiable state commitments, that
   assets are sufficient to meet obligations?

   Tiers (low to high assurance):

     :solvent            — formal solvency holds from state alone
     :insolvent          — formal solvency fails (liabilities > assets)
     :unproven           — accounting says solvent, no cryptographic proof
     :proof-invalid      — cryptographic proof exists but fails validation
     :proof-state-mismatch  — proof exists but references different state

   The current implementation wraps the existing solvency-holds?
   invariant and adds a mock proof-status layer. Full cryptographic
   proof generation (Merkle roots, commitment hashes) is deferred to
   a future phase."
  (:require [resolver-sim.protocols.sew.invariants.solvency :as solvency-inv]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.financial.finality :as finality]))

;; ── Core solvency classifier ─────────────────────────────────────────────────

(defn classify-solvency
  "Classify cryptographic solvency for the current world.

   Parameters:
     world              — current world state
     token-balances     — optional pre-computed token balance map
     opts               — optional map:
       :proof-status    — one of nil, :unproven, :invalid, :mismatch
                          (mock — real proofs not yet implemented)

   Returns:
     {:solvency/status       :solvent | :insolvent | :unproven | :proof-invalid
                                                    | :proof-state-mismatch
      :solvency/proof-required?  false
      :solvency/proof-valid?     nil
      :solvency/ratio            numeric
      :solvency/reason           string}"
  ([world] (classify-solvency world nil {}))
  ([world token-balances]
   (classify-solvency world token-balances {}))
  ([world token-balances {:keys [proof-status] :or {proof-status nil}}]
   (let [merged-balances  (or token-balances
                              {:total-held (get world :total-held {})
                               :claimable  (get world :claimable {})
                               :fees       (get world :fees {})
                               :withdrawn  (get world :withdrawn {})
                               :bond-balances (get world :bond-balances {})
                               :bond-fees     (get world :bond-fees {})
                               :bond-dist     (get world :bond-distributed {})
                               :retained      (get world :retained-slash-reserves 0)})

         solvency-result  (try (solvency-inv/solvency-holds? world merged-balances)
                               (catch Exception _
                                 {:holds? false :violations [{:reason :invariant-error}]}))

         balance-result   (try (let [ratio (invariants/calculate-solvency-ratio world)]
                                 {:holds? (>= ratio 0.999) :ratio ratio})
                               (catch Exception _
                                 {:holds? false :ratio 1.0}))

         accounting-solvent? (and (:holds? solvency-result true)
                                 (:holds? balance-result true))

         ;; Map proof status to solvency status
         proof-status*      (if proof-status proof-status :unproven)
         proof-valid?       (case proof-status*
                              nil       nil
                              :unproven nil
                              :invalid  false
                              :mismatch false
                              :valid    true
                              nil)]

     {:solvency/status
      (cond
        (not accounting-solvent?) :insolvent
        (= proof-status* :invalid) :proof-invalid
        (= proof-status* :mismatch) :proof-state-mismatch
        (= proof-status* :valid) :solvent
        :else :unproven)

      :solvency/proof-required? (= :invalid proof-status*)
      :solvency/proof-valid?    proof-valid?
      :solvency/ratio           (:ratio balance-result 1.0)
      :solvency/reason
      (cond
        (not accounting-solvent?)
        (str "accounting insolvent: "
             (count (:violations solvency-result 0)) " violations")
        (= proof-status* :invalid)  "cryptographic proof fails validation"
        (= proof-status* :mismatch) "proof references different state"
        (= proof-status* :valid)    "proof valid, cryptographically solvent"
        :else                       "accounting solvent but no cryptographic proof")})))
