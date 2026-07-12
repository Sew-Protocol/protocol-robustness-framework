(ns resolver-sim.validation.deviation-contract
  "Deviation-contract abstraction for strategic validation.
   A deviation contract declares which actor, prescribed action, deviation set,
   utility model, and parameter domain a strategic claim covers.  Every
   `:validation.class/deviation-resistance` result should reference a contract.
   Contracts are stored in `registered-contracts` and referenced by claims
   in `strategic-claim-catalog` via `:deviation-set-ids`."
  (:require [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; Contract schema
;; ---------------------------------------------------------------------------

(def deviation-contract-keys
  "Complete set of keys a deviation contract may include."
  #{:contract/id
    :contract/version
    :mechanism
    :actor/type
    :reference-action
    :deviation-generators
    :utility-model
    :parameter-scope
    :epsilon
    :exclusions
    :description})

(defn validate-contract
  "Validate a deviation contract map.  Returns nil if valid, or
   a string describing the first missing required key."
  [contract]
  (cond
    (not (:contract/id contract))
    "missing :contract/id"
    (not (:deviation-generators contract))
    (str "contract " (:contract/id contract) " missing :deviation-generators")
    (not (sequential? (:deviation-generators contract)))
    (str "contract " (:contract/id contract) " :deviation-generators must be sequential")
    :else nil))

;; ---------------------------------------------------------------------------
;; Registered contracts
;; ---------------------------------------------------------------------------

(def partial-fill-split-merge-sybil
  "Covers claim splitting, merging, permutation, and sybil identity
   deviations under pro-rata partial-fill allocation with token-linear
   utility."
  {:contract/id :partial-fill/claimant-split-merge-sybil
   :contract/version 1
   :mechanism :yield/partial-fill
   :actor/type :claimant
   :reference-action :submit-single-claim
   :deviation-generators [:split :merge :permute :sybil :inflate]
   :utility-model :utility/token-linear-v1
   :parameter-scope {:claim-count-max 5 :request-max 20 :liquidity-max 20}
   :epsilon 0
   :exclusions [:cross-workflow-coordination :timing-manipulation]
   :description "Claimant cannot improve allocation by splitting, merging, reordering, or sybilling claims under pro-rata fill."})

(def partial-fill-monotonicity
  "Covers request-monotonicity: increasing a valid claim request does not
   decrease the claimant's own allocation."
  {:contract/id :partial-fill/claimant-monotonicity
   :contract/version 1
   :mechanism :yield/partial-fill
   :actor/type :claimant
   :reference-action :submit-claim-amount
   :deviation-generators [:inflate]
   :utility-model :utility/token-linear-v1
   :parameter-scope {:claim-count-max 5 :request-max 20 :liquidity-max 20}
   :epsilon 0
   :exclusions [:deflation :priority-reclassification]
   :description "Increasing a valid claim request does not decrease the claimant's fill allocation."})

(def registered-contracts
  "Map of contract-id -> contract definition."
  {:partial-fill/claimant-split-merge-sybil partial-fill-split-merge-sybil
   :partial-fill/claimant-monotonicity partial-fill-monotonicity})

;; ---------------------------------------------------------------------------
;; Contract lookup
;; ---------------------------------------------------------------------------

(defn get-contract
  "Look up a deviation contract by id.  Returns nil if not found."
  [contract-id]
  (get registered-contracts contract-id))

(defn contracts-for-deviations
  "Return all contracts that cover at least one of the given deviations."
  [deviations]
  (let [dev-set (set deviations)]
    (filter (fn [c] (seq (clojure.set/intersection dev-set
                                                    (set (:deviation-generators c)))))
            (vals registered-contracts))))

(defn deviations-in-contract
  "Return the set of deviation generators covered by a contract."
  [contract-id]
  (set (:deviation-generators (get-contract contract-id))))

(defn contract-generates-deviation?
  "True if the named contract covers `deviation`."
  [contract-id deviation]
  (contains? (deviations-in-contract contract-id) deviation))
