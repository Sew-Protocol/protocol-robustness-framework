(ns resolver-sim.protocols.sew.diff
  "Canonical world-state hashing and structural diff.

   Used for differential testing: compare Clojure model state step-by-step
   against EVM execution on Anvil.

   Core workflow:
     1. After each replay step, call (world-hash world') to get a SHA-256 digest.
     2. Extract the equivalent minimal state from Anvil via RPC (escrow states,
        dispute levels, balances) and convert to the same canonical structure.
     3. Compare hashes; on mismatch use (diff-worlds sim-world evm-world) to
        locate the first point of divergence.

   Hashing uses resolver-sim.hash.canonical with :world-structure intent.
   Structural diff uses clojure.data/diff on sorted-map representations."
  (:require [clojure.data :as data]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.invariants.accounting :as acct-inv]))

;; ---------------------------------------------------------------------------
;; Canonical form
;; ---------------------------------------------------------------------------

(defn- ->sorted-deep
  "Recursively convert all maps to sorted-map.
   Vectors and lists are walked element-by-element; all other values pass through."
  [x]
  (cond
    (map? x)        (into (sorted-map) (map (fn [[k v]] [k (->sorted-deep v)]) x))
    (sequential? x) (mapv ->sorted-deep x)
    :else           x))

(defn canonical-world
  "Return a canonically-ordered copy of world (all maps → sorted-map).
   Used by diff-worlds for structural comparison with clojure.data/diff."
  [world]
  (->sorted-deep world))

;; ---------------------------------------------------------------------------
;; Structural diff
;; ---------------------------------------------------------------------------

(defn diff-worlds
  "Deep structural diff between two world states.

   Returns nil when worlds are logically identical.
   Otherwise returns:
     {:only-in-a  — keys/values present in world-a but absent or different in world-b
      :only-in-b  — keys/values present in world-b but absent or different in world-a
      :hash-a     — SHA-256 of world-a
      :hash-b     — SHA-256 of world-b}

   Typical usage: diff the Clojure model world against an EVM-reconstructed world
   to find the first divergent field after a state mismatch is detected."
  [world-a world-b]
  (let [[only-a only-b _same] (data/diff (canonical-world world-a)
                                         (canonical-world world-b))]
    (when (or only-a only-b)
      {:only-in-a only-a
       :only-in-b only-b
       :hash-a    (hc/hash-with-intent {:hash/intent :world-structure} world-a)
       :hash-b    (hc/hash-with-intent {:hash/intent :world-structure} world-b)})))

;; ---------------------------------------------------------------------------
;; EVM state adapter helpers
;; ---------------------------------------------------------------------------

(defn evm-world-skeleton
  "Return the keys that an EVM state adapter must populate to produce a world
   map comparable by (world-hash).

   The Anvil adapter (to be built) must read these fields from contract storage:
     :escrow-transfers    — {wf-id {:escrow-state :amount-after-fee :token ...}}
     :total-held          — {token-addr nat-int}  from EscrowVault.totalHeldPerToken
     :total-fees          — {token-addr nat-int}  from EscrowVault.totalFeesPerToken
     :pending-settlements — {wf-id {:exists :is-release :appeal-deadline}}
     :dispute-levels      — {wf-id nat-int}        from DR module dm.currentRound
     :block-time          — nat-int               from block.timestamp

   Fields that exist in the sim world but have no direct EVM equivalent
   (:escrow-settings, :module-snapshots, :claimable, :dispute-timestamps)
   should be omitted from both sides before comparison by projecting to a
   common subset with (select-keys world comparable-keys).

   See docs/differential-testing.md (to be created) for the full mapping."
  []
  {:escrow-transfers    {}
   :total-held          {}
   :total-fees          {}
   :pending-settlements {}
   :dispute-levels      {}
   :block-time          0})

(defn comparable-keys
  "The world-state keys that have a direct EVM equivalent and should be used
   when comparing model state against Anvil state.

   Use (select-keys world (comparable-keys)) on BOTH sides before hashing to
   avoid false positives from fields that don't exist on-chain."
  []
  #{:escrow-transfers :total-held :total-fees :pending-settlements
    :dispute-levels :block-time})

(defn projection
  "Project world to only the fields that can be compared against EVM state."
  [world]
  (select-keys world (comparable-keys)))

(defn projection-hash
  "Hash of the EVM-comparable projection of world with :evm-projection intent.
   Uses :evm-projection domain tag for cross-domain isolation."
  [world]
  (let [proj (-> (projection world)
                 (assoc :accounting-consistent? (acct-inv/accounting-consistent? world)))]
    (hc/hash-with-intent {:hash/intent :evm-projection} proj)))
