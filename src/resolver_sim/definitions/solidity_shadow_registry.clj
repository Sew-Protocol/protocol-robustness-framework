(ns resolver-sim.definitions.solidity-shadow-registry
  "Machine-readable registry tracking which simulation code shadows which
   Solidity contract, with documented known differences.

   Each entry maps a simulation module/function to its Solidity counterpart
   and records intentional differences.  The registry is informational —
   it does not block startup.

   Query API:
   - lookup-by-simulation  — find entries by simulation namespace
   - lookup-by-solidity    — find entries by Solidity contract file
   - all-differences       — return all recorded differences
   - check-shadow-coverage — detect simulation namespaces without entries"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.logging :as log]))

;; ──────────────────────────────────────────────────────────────────────────
;; Schema
;; ──────────────────────────────────────────────────────────────────────────

(def schema-version
  "Current schema version for solidity-shadow entries."
  "1.0")

(def shadow-entry-keys
  "Required keys for every shadow entry."
  #{:shadow/id :simulation/ns :simulation/role
    :solidity/contract :solidity/function
    :solidity/status :protocol/status
    :description})

;; ──────────────────────────────────────────────────────────────────────────
;; Registry entries
;; ──────────────────────────────────────────────────────────────────────────

(def solidity-shadow-entries
  "SOLIDITY_SHADOW_REGISTRY_SPEC_V1 entries."
  [;; ══════════════════════════════════════════════════════════════════
         ;; Escrow lifecycle — BaseEscrow.sol
         ;; ══════════════════════════════════════════════════════════════════
         {:shadow/id           :escrow-create
          :simulation/ns       "resolver-sim.sim.adversarial.reorg-check"
          :simulation/role     :action
          :solidity/contract   "BaseEscrow.sol"
          :solidity/function   "createEscrow"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Escrow creation with deposit and participant assignment"
          :differences         ["Simulation combines deposit + escrow creation in a single step"
                                "Simulation does not enforce Nonce-based ID derivation (keccak256)"]
          :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :escrow-sender-cancel
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "BaseEscrow.sol"
          :solidity/function   "senderCancel"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Sender-initiated escrow cancellation"
          :differences         ["Simulation assumes mutual consent — no unilateral sender cancel pathway"]
          :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :escrow-recipient-cancel
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "BaseEscrow.sol"
          :solidity/function   "recipientCancel"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Recipient-initiated escrow cancellation"
          :differences         ["Simulation cancels are symmetric — Solidity distinguishes sender vs recipient pathways"]
          :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :escrow-auto-cancel
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "BaseEscrow.sol"
          :solidity/function   "autoCancelDisputedEscrow"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Automatic cancellation of disputed escrows by timeout"
          :differences         []
          :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Dispute lifecycle — EscrowStateMachine, resolver contracts
         ;; ══════════════════════════════════════════════════════════════════
         {:shadow/id           :dispute-raise
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "EscrowStateMachine.sol"
          :solidity/function   "raiseDispute"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Raising a dispute on an escrow, transitioning to DISPUTED state"
          :differences         []
          :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :dispute-resolve
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "ResolverSlashingModuleV1.sol"
          :solidity/function   "resolveDispute"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Resolution of a dispute by a resolver, with optional slashing"
          :differences         ["Simulation models dispute resolution at higher granularity — does not replicate Solidity call sequencing"]
          :test/link           "test/foundry/invariants/ResolverInvariants.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :settlement-execute
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "SettlementOps.sol"
          :solidity/function   "executePendingSettlement"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Execution of a pending settlement after the appeal window closes"
          :differences         []
          :test/link           "test/foundry/core/AppealWindowEnforcement.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :appeal-raise
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "ResolverSlashingModuleV1.sol"
          :solidity/function   "appeal"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Appeal of a dispute resolution to the next escalation level"
          :differences         []
          :test/link           "test/foundry/core/AppealWindowEnforcement.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Cancellation strategies — ICancellationStrategy implementations
         ;; ══════════════════════════════════════════════════════════════════
         {:shadow/id           :cancellation-strategy-default
          :simulation/ns       "resolver-sim.contract-model.replay.execution"
          :simulation/role     :action
          :solidity/contract   "DefaultCancellationStrategy.sol"
          :solidity/function   "canCancel, onCancelAttempt"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Mutual-consent cancellation strategy"
          :differences         ["Simulation does not model separate strategy contracts — cancellation is a single action"]
          :test/link           "test/foundry/modules/DefaultCancellationStrategy.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Invariants — Foundry forge invariants
         ;; ══════════════════════════════════════════════════════════════════
         {:shadow/id           :invariant-solvency
          :simulation/ns       "resolver-sim.protocols.sew.invariants"
          :simulation/role     :invariant
          :solidity/contract   "StateInvariants.t.sol"
          :solidity/function   "invariant_solvency"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Vault balance must cover principal + fees"
          :differences         []
          :test/link           "test/foundry/invariants/StateInvariants.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :invariant-terminal-states
          :simulation/ns       "resolver-sim.protocols.sew.invariants"
          :simulation/role     :invariant
          :solidity/contract   "StateInvariants.t.sol"
          :solidity/function   "invariant_terminal_states_absorbing"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Released/refunded/resolved escrows are absorbing"
          :differences         []
          :test/link           "test/foundry/invariants/StateInvariants.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :invariant-fees-monotone
          :simulation/ns       "resolver-sim.protocols.sew.invariants"
          :simulation/role     :invariant
          :solidity/contract   "StateInvariants.t.sol"
          :solidity/function   "invariant_fees_monotone"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "Fees do not decrease between non-withdraw actions"
          :differences         []
          :test/link           "test/foundry/invariants/StateInvariants.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :invariant-appeal-window
          :simulation/ns       "resolver-sim.protocols.sew.invariants"
          :simulation/role     :invariant
          :solidity/contract   "ResolverInvariants.t.sol"
          :solidity/function   "invariant_appeal_window_enforced"
          :solidity/status     :solidity/implemented
          :protocol/status     :protocol/current
          :description         "executePendingSettlement must fail pre-deadline"
          :differences         []
          :test/link           "test/foundry/invariants/ResolverInvariants.t.sol"
          :trace-equivalence   "cdrs-v0.2"
          :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Proposed features — not yet in Solidity
         ;; ══════════════════════════════════════════════════════════════════
         {:shadow/id           :identity-guard
          :simulation/ns       "resolver-sim.protocols.sew.lifecycle"
          :simulation/role     :action
          :solidity/contract   "IdentityGuard.sol"
          :solidity/function   "N/A — proposed"
          :solidity/status     :solidity/not-implemented
          :protocol/status     :protocol/proposed
          :description         "Identity confusion fix (S55): require(escrows[transferId].state == None)"
          :differences         ["Entirely simulation-only — Solidity contract does not exist yet"
                                "Keccak256 ID derivation modeled in Clojure via hash/canonical"]
          :test/link           "N/A"
          :trace-equivalence   "N/A"
          :last-reviewed       "2026-06-27"}

         {:shadow/id           :storage-migration-v2
          :simulation/ns       "resolver-sim.contract-model.idempotency"
          :simulation/role     :projection
          :solidity/contract   "StorageMigration.sol"
          :solidity/function   "N/A — proposed"
          :solidity/status     :solidity/not-implemented
          :protocol/status     :protocol/proposed
          :description         "V1→V2 storage layout migration from uint256 monotonic to bytes32 semantic IDs"
          :differences         ["Entirely simulation-only — Solidity contract does not exist yet"
                                "Simulation models the migration algebra without actual storage layout"]
          :test/link           "N/A"
          :trace-equivalence   "N/A"
           :last-reviewed       "2026-06-27"}])

;; ──────────────────────────────────────────────────────────────────────────
;; Index
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private entries-by-simulation-ns
  "Index: simulation namespace → list of entries."
  (delay
    (group-by :simulation/ns solidity-shadow-entries)))

(def ^:private entries-by-solidity-contract
  "Index: solidity contract name → list of entries."
  (delay
    (group-by (comp str/trim :solidity/contract) solidity-shadow-entries)))

;; ──────────────────────────────────────────────────────────────────────────
;; Query API
;; ──────────────────────────────────────────────────────────────────────────

(defn lookup-by-simulation
  "Return all shadow entries whose :simulation/ns matches ns-str.
   ns-str may be a fully qualified namespace string.
   Matches by prefix if ns-str ends with '*'."
  [ns-str]
  (let [entries @entries-by-simulation-ns]
    (if (str/ends-with? ns-str "*")
      (let [prefix (str/replace ns-str #"\*$" "")]
        (filter #(str/starts-with? (:simulation/ns %) prefix)
                (mapcat val entries)))
      (get entries ns-str []))))

(defn lookup-by-solidity
  "Return all shadow entries that shadow contract-name."
  [contract-name]
  (get @entries-by-solidity-contract (str/trim contract-name) []))

(defn all-differences
  "Return a seq of {:shadow/id :differences [...]} for entries with non-empty differences."
  []
  (keep (fn [e]
          (let [diffs (:differences e)]
            (when (seq diffs)
              {:shadow/id (:shadow/id e)
               :simulation/ns (:simulation/ns e)
               :solidity/contract (:solidity/contract e)
               :differences diffs})))
        solidity-shadow-entries))

(defn all-entries
  "Return all registry entries."
  []
  solidity-shadow-entries)

;; ──────────────────────────────────────────────────────────────────────────
;; Coverage check
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private known-simulation-paths
  "Known simulation namespaces that have shadow entries.
   Used by check-shadow-coverage to detect unregistered code."
  (delay
    (set (map :simulation/ns solidity-shadow-entries))))

(defn- collect-simulation-namespaces
  "Scan src/ for .clj files and extract their namespace declarations."
  []
  (let [files (file-seq (io/file "src"))]
    (into #{}
          (comp
           (filter #(and (.isFile %)
                         (.endsWith (.getName %) ".clj")))
           (map (fn [f]
                  (with-open [r (clojure.java.io/reader f)]
                    (some (fn [line]
                            (when-let [m (re-find #"^\(ns\s+([^\s\)]+)" line)]
                              (second m)))
                          (line-seq r))))))
          files)))

(defn check-shadow-coverage
  "Identify simulation namespaces in src/ that lack a shadow entry.
   Returns a seq of {:namespace <name> :path <file-path>}.
   Ignores test and protocols_src namespaces by default."
  ([]
   (check-shadow-coverage {:include-protocols? false
                           :ignore-prefixes #{"resolver-sim.definitions"
                                              "resolver-sim.hash"
                                              "resolver-sim.logging"
                                              "resolver-sim.server"
                                              "resolver-sim.db"
                                              "resolver-sim.io"
                                              "resolver-sim.notebook-support"
                                              "resolver-sim.benchmark"
                                              "resolver-sim.scenario"
                                              "resolver-sim.generators"
                                              "resolver-sim.validation"
                                              "resolver-sim.cartesi"
                                              "resolver-sim.stochastic"
                                              "resolver-sim.economics"
                                              "resolver-sim.time"
                                              "resolver-sim.fixtures"}}))
  ([{:keys [include-protocols? ignore-prefixes]}]
   (let [registered @known-simulation-paths
         all-nses (collect-simulation-namespaces)
         ignored? (fn [ns]
                    (some #(str/starts-with? % (str/replace % #"\*" ""))
                          (keep (fn [p] (when (str/starts-with? ns p) p)) ignore-prefixes)))
         unregistered (remove (fn [ns]
                                (or (contains? registered ns)
                                    (ignored? ns)))
                              all-nses)]
     (mapv (fn [ns]
             (let [path (str/replace ns "." "/")
                   f (io/file "src" (str path ".clj"))]
               {:namespace ns
                :path (str "src/" path ".clj")
                :exists? (.exists f)}))
           (sort unregistered)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Report
;; ──────────────────────────────────────────────────────────────────────────

(defn format-shadow-report
  "Return a human-readable string summarizing the shadow registry."
  []
  (let [entries solidity-shadow-entries
        total (count entries)
        implemented (count (filter #(= :solidity/implemented (:solidity/status %)) entries))
        not-implemented (count (filter #(= :solidity/not-implemented (:solidity/status %)) entries))
        with-diffs (count (all-differences))
        contracts (distinct (map :solidity/contract entries))
        contract-block (fn [c]
                         (let [ce (sort-by :shadow/id (filter #(= c (:solidity/contract %)) entries))]
                           (str "  " c "\n"
                                (str/join "\n" (for [e ce]
                                                 (let [diffs (:differences e)]
                                                   (str "    " (name (:shadow/id e))
                                                        (when (seq diffs) (str " (" (count diffs) " diff(s))"))
                                                        (when (= :solidity/not-implemented (:solidity/status e)) " [PROPOSED]"))))))))
        ns-list (str/join "\n" (map (fn [ns] (str "  " ns)) (sort (distinct (map :simulation/ns entries)))))]
    (str (format "Solidity Shadow Registry — %d entries, %d contracts" total (count contracts))
         "\n"
         (format "  Implemented: %d" implemented)
         "\n"
         (format "  Not impl.:   %d" not-implemented)
         "\n"
         (format "  Differences: %d" with-diffs)
         "\n"
         "\n"
         (str/join "\n" (map contract-block (sort contracts)))
         "\n"
         "\n"
         "Registered namespaces:"
         "\n"
          ns-list)))
