(ns resolver-sim.economics.payoffs
  "[REFERENCE-IMPLEMENTATION ALIGNED] Economic logic for dispute-protocol simulations.
   Centralizes payoff, fee, and bounty calculations used by simulation and
   contract-model flows.

   Boundary note:
   - This namespace is currently aligned with the active Sew accounting path.
   - Treat constants and payout formulas here as reference defaults, not
     universal cross-protocol economics."
  (:require [resolver-sim.yield.exact-math :as m]))

;; Fee denominator constant
(def ^:private fee-denominator 10000)

(defn- compute-fee
  "Compute fee from amount and fee bps using integer division (uint256 semantics)."
  [amount fee-bps]
  (quot (* amount fee-bps) fee-denominator))

;; ---------------------------------------------------------------------------
;; Escrow Fees
;; ---------------------------------------------------------------------------

(defn calculate-escrow-fee
  "Calculate the fee for a new escrow.
   Mirrors EscrowVault fee logic."
  [amount fee-bps]
  (compute-fee amount fee-bps))

;; ---------------------------------------------------------------------------
;; Bonds & Appeal Fees
;; ---------------------------------------------------------------------------

(defn calculate-appeal-bond-fee
  "Calculate the protocol fee deducted from an appeal bond.
   Returns {:fee amount :net amount}"
  [amount fee-bps]
  (let [fee (compute-fee amount fee-bps)]
    {:fee fee
     :net (- amount fee)}))

(defn calculate-challenge-bond-amount
  "Calculate the required challenge bond amount (Phase L).

   Returns 0 (no bond required) when neither challenge-bond-bps nor
   appeal-bond-amount is configured, matching the Solidity contract
   behavior where escalation cost is governance-configurable and
   starts at 0 by default.

   Priority:
     1. challenge-bond-bps > 0  → bps of AFA
     2. appeal-bond-amount > 0  → absolute value
     3. fallback                → 0 (no bond required)

   NOTE: ModuleSnapshot (make-escrow-snapshot) defaults appeal-bond-amount to 0 (an integer).
   In Clojure (or 0 100) = 0, so the fallback must use pos? not or."
  [afa snap]
  (cond
    (pos? (:challenge-bond-bps snap 0))  (compute-fee afa (:challenge-bond-bps snap))
    (pos? (:appeal-bond-amount snap 0))  (:appeal-bond-amount snap)
    :else                                0))

(defn calculate-appeal-bond-amount
  "Calculate the required appeal bond amount for escalation.

   Priority:
     1. appeal-bond-amount > 0  → absolute value (fixed flat fee)
     2. appeal-bond-bps         → bps of AFA (proportional fee)
     3. neither configured      → 0 (no bond required)"
  [afa snap]
  (cond
    (pos? (:appeal-bond-amount snap 0)) (:appeal-bond-amount snap)
    (and (number? afa) (pos? afa) (pos? (:appeal-bond-bps snap 0)))
    (compute-fee afa (:appeal-bond-bps snap 0))
    :else 0))

;; ---------------------------------------------------------------------------
;; Slashing & Bounties
;; ---------------------------------------------------------------------------

(defn calculate-slashing-distribution
  "Calculate distribution for slashed funds with optional governance overrides.
   
   Default split: 50% insurance, 30% protocol, 20% retained reserves.
   Overridable via :insurance-cut-bps and :protocol-retained-bps (in basis points).
   Retained is always the remainder (10000 - insurance-cut-bps - protocol-retained-bps).
   
   If bounty is provided, it is deducted equally from insurance and protocol portions.
   Returns {:insurance amount :protocol amount :retained amount}"
  ([amount bounty]
   (calculate-slashing-distribution amount bounty nil))
  ([amount bounty {:keys [insurance-cut-bps protocol-retained-bps]
                   :or   {insurance-cut-bps 5000
                          protocol-retained-bps 3000}}]
   (let [remainder (- 10000 insurance-cut-bps protocol-retained-bps)
         insurance (quot (* amount insurance-cut-bps) 10000)
         protocol  (quot (* amount protocol-retained-bps) 10000)
         retained  (quot (* amount remainder) 10000)
         half-bounty (quot bounty 2)]
     {:insurance (- insurance half-bounty)
      :protocol  (- protocol half-bounty)
      :retained  retained})))

(defn calculate-bounty
  "Calculate the bounty amount for a successful challenge (Phase L)."
  [slash-amount bounty-bps]
  (if (pos? bounty-bps)
    (compute-fee slash-amount bounty-bps)
    0))

(defn calculate-slash-amount-from-basis
  "Calculate the stake amount to be slashed on a decision reversal or fraud event.
   
   Design Note: Currently slash amount is proportional to the resolver's total stake.
   Principal-capped or hybrid slashing based on escrow exposure may be considered
   as a future protocol-design change."
  [slashable-stake slash-bps]
  (compute-fee slashable-stake slash-bps))

(defn calculate-reversal-slash
  "Stake-basis reversal slash (replay engine). Prefer this name in calibration docs.
   Monte Carlo dispute.clj may still use escrow AFA × bps — see calibration_test."
  [slashable-stake slash-bps]
  (calculate-slash-amount-from-basis slashable-stake slash-bps))

;; ---------------------------------------------------------------------------
;; Pro-Rata Slash Allocation
;; ---------------------------------------------------------------------------

(defn calculate-prorata-slash-allocation
  "Allocate a slash obligation pro-rata across liable parties.

   Each party's owed amount is proportional to their share of total basis
   (default: :slashable-stake). The actual paid amount is capped by their
   available-slashable amount. Any unpaid portion is recorded as unmet.

   Rounding delegates to resolver-sim.yield.exact-math/largest-remainder-alloc
   for exact-ratio Hare-quota allocation with deterministic tie-breaking.

   Policy keys (on input map):
     :basis       - key for pro-rata weighting (default :slashable-stake)
     :cap-field   - key capping paid amount  (default :available-slashable)
     :unmet-policy - :record-only (default), no redistribution of unmet

   Returns map with :allocations, :recovered-total, :unmet-total, and metadata.
   When total-basis is zero, returns {:status :no-liable-basis :recovered-total 0}."
  [{:keys [slash-obligation liable-parties basis cap-field unmet-policy]
    :or   {basis        :slashable-stake
           cap-field    :available-slashable
           unmet-policy :record-only}}]
  (let [total-basis (reduce + 0 (map basis liable-parties))]
    (if (zero? total-basis)
      {:status :no-liable-basis
       :basis basis
       :cap-field cap-field
       :unmet-policy unmet-policy
       :slash-obligation slash-obligation
       :total-basis 0
       :recovered-total 0
       :unmet-total slash-obligation
       :allocations []}
      (let [claims     (mapv (fn [p] (assoc p :amount (basis p))) liable-parties)
            alloc-result (m/largest-remainder-alloc slash-obligation claims)
            allocations (mapv (fn [claim {:keys [filled]}]
                                (let [available (or (cap-field claim) 0)
                                      paid      (min filled available)
                                      unmet     (- filled paid)]
                                  {:id           (:id claim)
                                   :basis-amount (:amount claim)
                                   :share        (if (pos? total-basis)
                                                   (/ (:amount claim) total-basis)
                                                   0)
                                   :owed         filled
                                   :paid         paid
                                   :unmet        (max unmet 0)}))
                              claims (:allocations alloc-result))
            recovered-total (reduce + 0 (map :paid allocations))
            unmet-total     (reduce + 0 (map :unmet allocations))]
        {:basis basis
         :cap-field cap-field
         :unmet-policy unmet-policy
         :slash-obligation slash-obligation
         :total-basis total-basis
         :recovered-total recovered-total
         :unmet-total unmet-total
         :allocations allocations}))))

;; ---------------------------------------------------------------------------
;; Economic Policies (Bands)
;; ---------------------------------------------------------------------------

(def ECONOMIC_POLICIES
  "Recommended parameter bands for governance.
   Conservative: launch-ready, fully/mostly bond-backed.
   Balanced: growth phase, partially bond-backed.
   Aggressive: research/testing."
  {:conservative {:capacity-multiplier 1.0
                  :insurance-cut-bps  8000
                  :alpha-bps           500}
   :balanced     {:capacity-multiplier 1.5
                  :insurance-cut-bps  5000
                  :alpha-bps          1000}
   :aggressive   {:capacity-multiplier 4.0
                  :insurance-cut-bps  2000
                  :alpha-bps          3000}})

(defn calculate-escrow-cap
  "Compute the maximum escrow amount a resolver can handle based on their stake.
   For Phase K (Tiered Authority): Cap = Stake * Multiplier.
   Defaults to Conservative (1.0x)."
  ([stake] (calculate-escrow-cap stake 1.0))
  ([stake multiplier]
   (* stake (or multiplier 1.0))))
