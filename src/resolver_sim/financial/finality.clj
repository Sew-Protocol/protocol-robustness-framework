(ns resolver-sim.financial.finality
  "Pure classification of chain finality and financial finality.

   Chain finality = state permanence (blockchain consensus).
   Financial finality = obligation permanence (economic outcome stability).

   They are explicitly NOT the same. A transaction can be chain-final
   while the financial outcome is still challengeable, recoverable, or
   awaiting solvency proof.

   The classifier reads existing world/protocol-params/result data —
   it never modifies state. All functions are pure and side-effect-free."
  (:require [clojure.string :as str]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.resolution :as res]))

;; ── Chain finality ───────────────────────────────────────────────────────────

(def chain-phases
  "Ordered phases of blockchain consensus finality."
  [:pending :confirmed :safe :final])

(defn classify-chain-finality
  "Classify chain finality from world state.

   In the replay engine, we do not model chain reorganisations.
   All states are assumed chain-final by the time they appear in the
   world. This is explicit rather than implicit so that projections
   never confuse chain finality with financial finality.

   Returns:
     {:chain/phase     :final
      :chain/source    :assumed-by-replay
      :chain/block     nil
      :chain-final?    true}"
  [world]
  {:chain/phase  :final
   :chain/source :assumed-by-replay
   :chain/block  (:block-time world)
   :chain-final? true})

;; ── Financial finality ───────────────────────────────────────────────────────

(def financial-phases
  "Ordered phases of financial outcome stability.

   :provisional        — no resolution or settlement yet; outcome unknown
   :challengeable      — resolution recorded but appeal/challenge window open
   :recoverable        — settlement executed but positions still recoverable
                         (yield shortfall recovery, slashing appeal)
   :finalizing         — all gates closing, last claimable amounts settling
   :financially-final  — all economic outcome gates closed

   Note: :provisional precedes :challengeable — they are the same ordering
   when no resolution has been recorded (e.g., an escrow in :pending state)."
  [:provisional :challengeable :recoverable :finalizing :financially-final])

(defn- phase-ordinal
  "Numeric ordinal for comparing financial phases."
  [phase]
  (case phase
    :provisional 0
    :challengeable 1
    :recoverable 2
    :finalizing 3
    :financially-final 4))

(defn- open-gates
  "Determine which financial-finality gates are still open for a workflow in the given world."
  [world workflow-id]
  (let [state     (t/escrow-state world workflow-id)
        pending   (t/get-pending world workflow-id)
        positions (get-in world [:yield/positions] {})
        has-pos?  (fn [wf] (some #(when (= wf (get-in % [:workflow-id])) true) (vals positions)))
        has-unwinding? (fn [wf]
                         (some #(and (= wf (get-in % [:workflow-id]))
                                    (= :unwinding (:status %)))
                               (vals positions)))
        has-appeal-pending? (fn [wf]
                              (some #(and (= wf (:workflow-id %))
                                         (= :pending (:status %)))
                                    (vals (get world :pending-fraud-slashes {}))))]
    (cond-> []

      ;; Gate: non-terminal state means outcome not yet determined
      (not (contains? t/terminal-states state))
      (conj :escrow-state)

      ;; Gate: unresolved pending settlement
      (and (= state :disputed) (:exists pending) true)
      (conj :pending-settlement)

      ;; Gate: appeal/challenge window still open
      (and (= state :disputed) (:exists pending) true
           (< (:block-time world) (:appeal-deadline pending)))
      (conj :appeal-window)

      ;; Gate: yield position still unwinding (shortfall recovery)
      (and has-pos? (has-unwinding? workflow-id))
      (conj :yield-recovery)

      ;; Gate: slashing appeal still pending
      (and has-appeal-pending? (has-appeal-pending? workflow-id))
      (conj :slash-appeal))))

(defn classify-financial-finality
  "Classify financial finality for a specific workflow.

   Returns:
     {:financial/phase              keyword
      :financially-final?           boolean
      :can-change?                  boolean
      :open-gates                   [:appeal-window :yield-recovery ...]
      :reason                       string}"
  [world workflow-id]
  (let [state (t/escrow-state world workflow-id)
        gates (open-gates world workflow-id)]
    (cond
      ;; No escrow at this workflow-id — trivially provisional
      (nil? state)
      {:financial/phase       :provisional
       :financially-final?    false
       :can-change?           false
       :open-gates            []
       :reason                "no escrow at this workflow-id"}

      ;; Fully terminal with no open gates
      (and (contains? t/terminal-states state)
           (empty? gates))
      {:financial/phase       :financially-final
       :financially-final?    true
       :can-change?           false
       :open-gates            []
       :reason                "all gates closed; escrow terminal and no pending recoveries"}

      ;; Terminal state but still recoverable (yield/slashing not yet final)
      (and (contains? t/terminal-states state)
           (seq gates))
      {:financial/phase       (if (some #{:yield-recovery :slash-appeal} gates)
                               :recoverable
                               :finalizing)
       :financially-final?    false
       :can-change?           true
       :open-gates            gates
       :reason                (str "terminal escrow but gates still open: "
                                  (str/join ", " (map name gates)))}

      ;; Disputed with challenge/appeal windows open
      (= state :disputed)
      {:financial/phase       :challengeable
       :financially-final?    false
       :can-change?           true
       :open-gates            gates
       :reason                (if (seq gates)
                               (str "gates open: " (str/join ", " (map name gates)))
                               "disputed with no pending settlement")}

      ;; No resolution yet (pending escrow, or not yet disputed)
      :else
      {:financial/phase       :provisional
       :financially-final?    false
       :can-change?           true
       :open-gates            (vec gates)
       :reason                (str "escrow in " (name state) " state")})))

(defn combine-finality
  "Produce a single, plain classifier map for a workflow.

   This is the primary entry point for consumers. It returns both
   chain and financial finality in one map, with no ambiguous
   :final? or :settled? keys.

   Returns:
     {:chain {:phase :final :source :assumed-by-replay :chain-final? true}
      :financial {:phase :challengeable :financially-final? false
                  :can-change? true :open-gates [...] :reason \"...\"}}"
  [world workflow-id]
  {:chain     (classify-chain-finality world)
   :financial (classify-financial-finality world workflow-id)})
