(ns resolver-sim.economics.payoffs
  "Economic logic for dispute-protocol simulations.
   Centralizes payoff, fee, and bounty calculations used by simulation and
   contract-model flows. Fee semantics are currently aligned with the active
   SEW accounting path." )

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
   If challenge-bond-bps is 0, falls back to appeal-bond-amount or default."
  [afa snap]
  (if (pos? (:challenge-bond-bps snap 0))
    (compute-fee afa (:challenge-bond-bps snap))
    (or (:appeal-bond-amount snap) 100)))

(defn calculate-appeal-bond-amount
  "Calculate the required appeal bond amount for escalation."
  [afa snap]
  (if (pos? (:appeal-bond-amount snap 0))
    (:appeal-bond-amount snap)
    (compute-fee afa (:appeal-bond-bps snap 0))))

;; ---------------------------------------------------------------------------
;; Slashing & Bounties
;; ---------------------------------------------------------------------------

(defn calculate-slashing-distribution
  "Calculate the 50/30/20 distribution for slashed funds.
   If bounty is provided, it is deducted from insurance and protocol portions.
   Returns {:insurance amount :protocol amount :retained amount}"
  [amount bounty]
  (let [insurance (quot (* amount 50) 100)
        protocol  (quot (* amount 30) 100)
        retained  (- amount insurance protocol)
        half-bounty (quot bounty 2)]
    {:insurance (- insurance half-bounty)
     :protocol  (- protocol half-bounty)
     :retained  retained}))

(defn calculate-bounty
  "Calculate the bounty amount for a successful challenge (Phase L)."
  [slash-amount bounty-bps]
  (if (pos? bounty-bps)
    (compute-fee slash-amount bounty-bps)
    0))

(defn calculate-reversal-slash
  "Calculate the stake amount to be slashed on a decision reversal."
  [afa reversal-slash-bps]
  (compute-fee afa reversal-slash-bps))

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
