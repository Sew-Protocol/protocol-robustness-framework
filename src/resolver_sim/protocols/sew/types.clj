(ns resolver-sim.protocols.sew.types
  "Clojure mirror of EscrowTypes.sol and BaseEscrow storage structs.

   All enum values are keywords. Struct fields use kebab-case.
   Arithmetic follows uint256 truncation (integer division, no rounding).

   World-state shape:
     {:escrow-transfers    {workflow-id EscrowTransfer-map}
      :escrow-settings     {workflow-id EscrowSettings-map}
      :total-held          {token-addr   nat-int}
      :total-fees          {token-addr   nat-int}
      :pending-settlements {workflow-id  PendingSettlement-map}
      :module-snapshots    {workflow-id  ModuleSnapshot-map}
      :dispute-timestamps  {workflow-id  nat-int}   ; block.timestamp of raiseDispute
      :claimable           {workflow-id {addr nat-int}}
      :resolver-bonds      {addr {:stable nat-int :sew nat-int}} ; DR3 80/20 mix
      :senior-bonds        {addr {:coverage-max nat-int :reserved-coverage nat-int}}
      :resolver-frozen-until {addr nat-int}          ; freeze expiry (0 = not frozen)
      :resolver-epoch-slashed {addr {:epoch-start nat-int :amount nat-int}}
      :paused?             boolean                   ; protocol pause state
      :block-time          nat-int}                  ; injected clock

   Every operation function signature:
     (fn [world workflow-id ...args] -> {:ok bool :world world' :error keyword})"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Enum sets (canonical values)
;; ---------------------------------------------------------------------------

(def escrow-states
  "EscrowState enum values."
  #{:none :pending :released :refunded :disputed :resolved})

(def sender-statuses
  "SenderStatus enum values."
  #{:none :agree-to-cancel :raise-dispute})

(def recipient-statuses
  "RecipientStatus enum values."
  #{:none :agree-to-cancel :raise-dispute})

(def resolution-outcomes
  "ResolutionOutcome enum values."
  #{:none :release :cancel})

(def execution-sources
  "ExecutionSource enum values."
  #{:user :keeper :governance})

;; ---------------------------------------------------------------------------
;; Constructor helpers — build correctly-typed maps
;; ---------------------------------------------------------------------------

(defn make-escrow-transfer
  "Construct an EscrowTransfer map (mirrors the Solidity struct).

   Required keys:
     :token       — token address string
     :to          — recipient address string
     :from        — sender address string
     :amount-after-fee — uint256, amount held after fee deduction

   Optional keys (default nil/0):
     :dispute-resolver  — address string, or nil
     :auto-release-time — uint64, epoch seconds, 0 = disabled
     :auto-cancel-time  — uint64, epoch seconds, 0 = disabled
     :escrow-state      — keyword, default :pending
     :sender-status     — keyword, default :none
     :recipient-status  — keyword, default :none"
  [{:keys [token to from amount-after-fee dispute-resolver
           auto-release-time auto-cancel-time
           escrow-state sender-status recipient-status initial-fee]}]
  {:token             token
   :to                to
   :from              from
   :amount-after-fee  (long amount-after-fee)
   :initial-fee       (or initial-fee 0)
   :dispute-resolver  dispute-resolver
   :auto-release-time (or auto-release-time 0)
   :auto-cancel-time  (or auto-cancel-time 0)
   :escrow-state      (or escrow-state :pending)
   :sender-status     (or sender-status :none)
   :recipient-status  (or recipient-status :none)})

(defn make-escrow-settings
  "Construct an EscrowSettings map (per-escrow settings, frozen at creation).

   :custom-resolver   — address or nil (overrides module resolver)
   :release-address   — address or nil (authorised to release; nil = sender only)
   :yield-preset      — keyword (:off :to-sender etc.) default :off
   :auto-release-time — 0 = use default
   :auto-cancel-time  — 0 = use default"
  [{:keys [custom-resolver release-address yield-preset
           auto-release-time auto-cancel-time]}]
  {:custom-resolver   custom-resolver
   :release-address   release-address
   :yield-preset      (or yield-preset :off)
   :auto-release-time (or auto-release-time 0)
   :auto-cancel-time  (or auto-cancel-time 0)})

(defn make-module-snapshot
  "Construct a ModuleSnapshot map.
   All fields are snapshotted at createEscrow time and NEVER change thereafter —
   this is the key correctness property: governance config changes cannot affect
   in-flight escrows.

   Numeric fee/timeout fields use integer (uint256 semantics)."
  [{:keys [resolution-module release-strategy cancellation-strategy
           yield-generation-module yield-distribution-module incentive-module
           yield-protocol-fee-bps appeal-bond-protocol-fee-bps escrow-fee-bps
           default-auto-release-delay default-auto-cancel-delay
           max-dispute-duration appeal-window-duration dispute-resolver
           appeal-bond-bps resolver-bond-bps appeal-bond-amount
           reversal-slash-bps fraud-slash-bps
           challenge-window-duration challenge-bond-bps challenge-bounty-bps]}]
  {:resolution-module           resolution-module
   :release-strategy            release-strategy
   :cancellation-strategy       cancellation-strategy
   :yield-generation-module     yield-generation-module
   :yield-distribution-module   yield-distribution-module
   :incentive-module            incentive-module
   :yield-protocol-fee-bps      (or yield-protocol-fee-bps 0)
   :appeal-bond-protocol-fee-bps (or appeal-bond-protocol-fee-bps 0)
   :escrow-fee-bps              (or escrow-fee-bps 0)
   :default-auto-release-delay  (or default-auto-release-delay 0)
   :default-auto-cancel-delay   (or default-auto-cancel-delay 0)
   :max-dispute-duration        (or max-dispute-duration 0)
   :appeal-window-duration      (or appeal-window-duration 0)
   :dispute-resolver            dispute-resolver
   :appeal-bond-bps             (or appeal-bond-bps 0)
   :resolver-bond-bps           (or resolver-bond-bps 0)
   :appeal-bond-amount          (or appeal-bond-amount 0)
   :reversal-slash-bps          (or reversal-slash-bps 0)
   :fraud-slash-bps             (or fraud-slash-bps 0)
   :challenge-window-duration   (or challenge-window-duration 0)
   :challenge-bond-bps          (or challenge-bond-bps 0)
   :challenge-bounty-bps        (or challenge-bounty-bps 0)})

(defn make-pending-settlement
  "Construct a PendingSettlement map.

   :exists         — boolean (mirrors the Solidity bool)
   :is-release     — true = release to recipient, false = refund to sender
   :appeal-deadline — block timestamp after which settlement may execute
   :resolution-hash — bytes32 hex string (opaque in the model)"
  [{:keys [exists is-release appeal-deadline resolution-hash]}]
  {:exists          (boolean exists)
   :is-release      (boolean is-release)
   :appeal-deadline (or appeal-deadline 0)
   :resolution-hash resolution-hash})

(def empty-pending-settlement
  "Zero value for PendingSettlement (mirrors default mapping value)."
  {:exists false :is-release false :appeal-deadline 0 :resolution-hash nil})

(defn make-timeout-config
  "Construct a TimeoutConfig map (protocol-level defaults, NOT per-escrow).
   Per-escrow values come from ModuleSnapshot."
  [{:keys [default-auto-release-delay default-auto-cancel-delay
           max-dispute-duration appeal-window-duration]}]
  {:default-auto-release-delay (or default-auto-release-delay 0)
   :default-auto-cancel-delay  (or default-auto-cancel-delay 0)
   :max-dispute-duration       (or max-dispute-duration 0)
   :appeal-window-duration     (or appeal-window-duration 0)})

;; ---------------------------------------------------------------------------
;; World state constructor
;; ---------------------------------------------------------------------------

;; Maximum escalation round — mirrors DecentralizedResolutionModule.MAX_ROUND.
;; Round 0 = initial resolver, 1 = senior resolver, 2 = external (Kleros).
(def ^:const max-dispute-level 2)

(defn empty-world
  "Create an empty world-state map at a given block time."
  ([] (empty-world 0))
  ([block-time]
   {:escrow-transfers    {}
    :escrow-settings     {}
    :total-held          {}
    :total-fees          {}
    :total-released      {}   ; {token-addr nat-int} — cumulative AFAs finalized via release
    :total-refunded      {}   ; {token-addr nat-int} — cumulative AFAs finalized via refund
    :pending-settlements {}
    :module-snapshots    {}
    :dispute-timestamps  {}
    :dispute-levels      {}   ; {workflow-id nat-int} — current escalation round (0–2)
    :claimable           {}
    :resolver-stakes     {}   ; {addr nat-int} — for Tiered Authority (Phase K)
    :resolver-slash-total {}  ; {addr nat-int} — cumulative stake slashed (distinguishes slash from withdrawal)
    :pending-fraud-slashes {} ; {slash-id {:resolver :amount :status :appeal-deadline
                              ;            :appeal-bond-held :contest-deadline :proposed-at}}
    :previous-decisions  {}   ; {wf-id {level {:resolver :is-release}}}
    :challengers         {}   ; {wf-id {level challenger-addr}} — for Phase L Bounties
    :bond-balances       {}   ; {workflow-id {addr amount}}
    :bond-fees           {}   ; {token amount}
    :bond-slashed        {}   ; {workflow-id amount}
    :bond-distribution   {:insurance 0 :protocol 0 :burned 0} ; 50/30/20 split
    :resolver-bonds      {}   ; {addr {:stable nat-int :sew nat-int}} — DR3 80/20 mix invariant
    :senior-bonds        {}   ; {addr {:coverage-max nat-int :reserved-coverage nat-int}}
    :resolver-frozen-until {} ; {addr nat-int} — resolver freeze expiry (0 = not frozen)
    :resolver-epoch-slashed {} ; {addr {:epoch-start nat-int :amount nat-int}} — per-epoch slash cap
    :token-fot-bps          {} ; {token-addr nat-int} — Fee-on-Transfer BPS per token (0 = normal ERC20)
    :paused?                false
    :block-time          block-time}))

;; ---------------------------------------------------------------------------
;; Result constructors
;; ---------------------------------------------------------------------------

(defn ok
  "Successful transition result."
  [world]
  {:ok true :world world})

(defn fail
  "Failed transition result — mirrors a Solidity revert."
  [error-kw]
  {:ok false :error error-kw})

;; ---------------------------------------------------------------------------
;; Fee arithmetic (uint256 truncating integer division)
;; ---------------------------------------------------------------------------

(def ^:const fee-denominator
  "ESCROW_FEE_DENOMINATOR = 10000 bps."
  10000)

(defn compute-fee
  "Compute fee from amount and fee bps using integer division (uint256 semantics)."
  [amount fee-bps]
  (quot (* amount fee-bps) fee-denominator))

(defn compute-amount-after-fee
  "Amount held in escrow = amount - fee."
  [amount fee-bps]
  (- amount (compute-fee amount fee-bps)))

(defn normalize-workflow-id
  "Normalize workflow IDs across call-sites.

   Supports integer IDs (canonical), numeric strings (e.g. \"0\"), and
   keyword-like values with a leading colon (e.g. \":0\").
   Returns the normalized integer ID when parseable, else returns the original
   value so callers can still fail cleanly via map lookup/guards."
  [workflow-id]
  (cond
    (integer? workflow-id)
    workflow-id

    (string? workflow-id)
    (let [s (str/trim workflow-id)
          s (if (.startsWith s ":") (subs s 1) s)]
      (if (re-matches #"\d+" s)
        (Long/parseLong s)
        workflow-id))

    (keyword? workflow-id)
    (let [s (name workflow-id)]
      (if (re-matches #"\d+" s)
        (Long/parseLong s)
        workflow-id))

    :else
    workflow-id))

;; ---------------------------------------------------------------------------
;; World-state accessors
;; ---------------------------------------------------------------------------

(defn get-transfer
  "Retrieve EscrowTransfer map for workflow-id, or nil."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:escrow-transfers wf-id])))

(defn get-settings
  "Retrieve EscrowSettings map for workflow-id, or nil."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:escrow-settings wf-id])))

(defn get-snapshot
  "Retrieve ModuleSnapshot for workflow-id, or nil."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:module-snapshots wf-id])))

(defn get-pending
  "Retrieve PendingSettlement for workflow-id (defaults to empty)."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:pending-settlements wf-id] empty-pending-settlement)))

(defn escrow-state
  "Current EscrowState keyword for workflow-id."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:escrow-transfers wf-id :escrow-state])))

(defn terminal-state?
  "True if the escrow is in an absorbing (terminal) state."
  [world workflow-id]
  (contains? #{:released :refunded :resolved} (escrow-state world workflow-id)))

(defn valid-workflow-id?
  "True if workflow-id exists in escrow-transfers."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (contains? (:escrow-transfers world) wf-id)))

(defn dispute-level
  "Current escalation round for workflow-id (0 = initial, 1 = senior, 2 = external).
   Defaults to 0 when no escalation has occurred."
  [world workflow-id]
  (let [wf-id (normalize-workflow-id workflow-id)]
    (get-in world [:dispute-levels wf-id] 0)))

(defn final-round?
  "True when the escrow is at the maximum escalation round (no further appeals)."
  [world workflow-id]
  (>= (dispute-level world workflow-id) max-dispute-level))
