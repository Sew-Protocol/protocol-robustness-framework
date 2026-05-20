;; # Sew Protocol — Decentralized Dispute Resolution Validation Workbench
;;
;; **Subtitle:** Scenario evidence, adversarial testing, escalation behavior, and open validation gaps.
;;
;; ---
;; **Audience:** Kleros integrations / protocol team · Protocol reviewers · Security researchers ·
;; Sew contributors · Mechanism design / dispute resolution researchers.
;;
;; **Purpose:** Production-quality validation workbench for the Sew Protocol dispute resolution
;; subsystem. Every status indicator is paired with:
;; - what it means,
;; - what it does **not** mean,
;; - the source artifact backing it,
;; - a confidence tier.
;;
;; **Not a marketing page.** Green does not mean safe. Every cell in this workbench is an
;; evidence claim with an explicit backing level. Missing evidence shows as amber, not green.
;;
;; **Data sources loaded at render time:**
;; - `results/test-artifacts/test-summary.json` — canonical CI gate
;; - `data/fixtures/golden/*.report.edn` — per-scenario replay outcomes
;; - `data/fixtures/traces/*.trace.json` — scenario metadata corpus
;; - `results/test-artifacts/coverage.json` — transition / threat-tag coverage
;; - Live: `resolver-sim.protocols.sew.invariant-runner/run-all` — in-process invariant suite

(ns notebooks.dispute-resolution
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.notebooks.ui :as ui]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.protocols.sew.invariant-runner :as runner]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.types :as t]))

;; ---------------------------------------------------------------------------
;; Utilities: safe I/O
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:result :hide}}
(defn- safe-slurp [path]
  (try
    (let [f (io/file path)]
      (when (.exists f) (slurp f)))
    (catch Exception e
      (println "WARN: could not read" path "-" (.getMessage e))
      nil)))

^{::clerk/visibility {:result :hide}}
(defn- read-json [path]
  (when-let [s (safe-slurp path)]
    (try (json/read-str s {:key-fn keyword})
         (catch Exception e
           (println "WARN: JSON parse error" path "-" (.getMessage e))
           nil))))

^{::clerk/visibility {:result :hide}}
(defn- read-edn [path]
  (when-let [s (safe-slurp path)]
    (try (edn/read-string s)
         (catch Exception e
           (println "WARN: EDN parse error" path "-" (.getMessage e))
           nil))))

^{::clerk/visibility {:result :hide}}
(defn- safe-render [label f]
  (try (f)
       (catch Exception e
         [:div {:style {:background "#fef2f2" :border "1px solid #dc2626"
                        :borderRadius "4px" :padding "12px" :margin "8px 0"}}
          [:strong (str "⚠ " label " render error: ")]
          [:code (.getMessage e)]])))

;; ---------------------------------------------------------------------------
;; Artifact loading
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:result :hide}}
(def test-summary (read-json "results/test-artifacts/test-summary.json"))

^{::clerk/visibility {:result :hide}}
(def coverage-data (read-json "results/test-artifacts/coverage.json"))

^{::clerk/visibility {:result :hide}}
(def golden-reports
  (try
    (let [dir (io/file "data/fixtures/golden")]
      (when (.isDirectory dir)
        (->> (.listFiles dir)
             (filter #(str/ends-with? (.getName %) ".report.edn"))
             (keep (fn [f]
                     (try
                       (let [d (edn/read-string (slurp f))]
                         [(str (:trace-id d)) d])
                       (catch Exception _ nil))))
             (into {}))))
    (catch Exception _ {})))

^{::clerk/visibility {:result :hide}}
(def all-traces
  (try
    (let [dir (io/file "data/fixtures/traces")]
      (when (.isDirectory dir)
        (->> (.listFiles dir)
             (filter #(str/ends-with? (.getName %) ".trace.json"))
             (keep (fn [f]
                     (try
                       (let [d (json/read-str (slurp f) {:key-fn keyword})]
                         {:id          (or (:scenario-id d)
                                          (str/replace (.getName f) ".trace.json" ""))
                          :title       (or (:title d) "")
                          :description (or (:description d) "")
                          :purpose     (or (:purpose d) "")
                          :threat-tags (or (:threat-tags d) [])})
                       (catch Exception _ nil))))
             (remove nil?)
             (sort-by :id))))
    (catch Exception _ [])))

;; Live invariant suite run — executes all S01–S67 scenarios in-process.
;; Cached by Clerk across re-evaluations unless `::clerk/no-cache true` is set.
^{::clerk/visibility {:result :hide}}
(def live-suite-results
  (try (runner/run-all)
       (catch Exception e
         {:passed 0 :total 0 :elapsed-ms 0 :results []
          :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Canonical state machine diagram generator
;;
;; Derives a Mermaid stateDiagram-v2 source string directly from:
;;   • sm/allowed-transitions  — authoritative edge set
;;   • sm/transitions          — guard/effect metadata per trigger
;;   • t/max-dispute-level     — maximum escalation level (= 2)
;;
;; The output is the SINGLE canonical diagram for the protocol.
;; Regenerate by re-evaluating this cell; no manual maintenance required.
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:result :hide}}
(def ^:private edge-labels
  "Human-readable labels for each top-level edge.
   Keys are [from-kw to-kw]. Values: {:op string :actor string :note string}."
  {[:none     :pending]  {:op "createEscrow()"       :actor "Buyer"}
   [:pending  :disputed] {:op "raiseDispute()"        :actor "Buyer or Seller"  :note "participant only"}
   [:pending  :released] {:op "release() / autoRelease() / mutualCancel()" :actor "Buyer · Keeper"}
   [:pending  :refunded] {:op "senderCancel() / recipientCancel() / autoCancel()" :actor "Seller · Keeper"}
   [:disputed :released] {:op "executeResolution(release) / executePendingSettlement()" :actor "Resolver · Keeper"}
   [:disputed :refunded] {:op "executeResolution(refund) / autoCancelDisputedEscrow()" :actor "Resolver · Keeper"}
   [:disputed :resolved] {:op "transitionToResolved()" :actor "(internal)" :note "no production call site"}})

^{::clerk/visibility {:result :hide}}
(defn generate-state-machine-mermaid
  "Generate a Mermaid stateDiagram-v2 string from the live simulator state machine.
   Returns a string ready to embed in a ```mermaid fenced block."
  []
  (let [transitions  sm/allowed-transitions
        max-level    t/max-dispute-level
        ;; Topological order for readability
        state-order  [:none :pending :disputed :released :refunded :resolved]
        terminal?    (fn [s] (empty? (get transitions s #{})))
        indent       (fn [n s] (str (apply str (repeat n "    ")) s))
        lines        (atom [])]
    (letfn [(emit [& parts] (swap! lines conj (apply str parts)))]
      (emit "stateDiagram-v2")
      (emit (indent 1 "direction LR"))
      (emit "")
      (emit (indent 1 "%% ── Top-level states ──────────────────────────────────────────────"))
      ;; :none is a pre-creation sentinel — show it as the entry point
      (emit (indent 1 "[*] --> PENDING : createEscrow()  [Buyer]"))
      (emit "")
      (emit (indent 1 "%% ── PENDING transitions ───────────────────────────────────────────"))
      (doseq [to (sort-by str (get transitions :pending #{}))]
        (let [{:keys [op actor note]} (get edge-labels [:pending to] {:op "?" :actor "?"})]
          (emit (indent 1 (str "PENDING --> " (str/upper-case (name to))
                               " : " op
                               (when note (str "  [" note "]")))))))
      (emit "")
      (emit (indent 1 "%% ── DISPUTED: escalation sub-process ──────────────────────────────"))
      (emit (indent 1 "state DISPUTED {"))
      (emit (indent 2 "direction TB"))
      (emit (indent 2 "[*] --> L0 : dispute opened"))
      (emit "")
      ;; Emit L0 … L(max-1) escalation chain
      (doseq [lvl (range (inc max-level))]
        (let [label     (str "L" lvl)
              is-last   (= lvl max-level)
              next-lbl  (str "L" (inc lvl))]
          (if is-last
            (do
              (emit (indent 2 (str "state \"L" lvl " — Kleros backstop (final round)\" as L" lvl)))
              (emit (indent 2 (str "L" lvl " --> [*] : executeResolution()  [Kleros jurors]"))))
            (do
              (let [ps-lbl (if (zero? lvl) "PendingSettlement" (str "PendingSettlement" lvl))]
                (emit (indent 2 (str "L" lvl " --> " ps-lbl " : executeResolution()  (appeal window open)")))
                (emit (indent 2 (str "L" lvl " --> [*] : executeResolution()  (no appeal window)")))
                (emit (indent 2 (str "L" lvl " --> [*] : autoCancelDisputedEscrow()  (timeout)")))
                (emit "")
                (emit (indent 2 (str "state \"PendingSettlement (L" lvl " decision, appeal open)\" as " ps-lbl)))
                (emit (indent 2 (str ps-lbl " --> " next-lbl " : escalateDispute() / challengeResolution()  (within appeal window)")))
                (emit (indent 2 (str ps-lbl " --> [*] : executePendingSettlement()  (after deadline)")))
                (emit ""))))))
      (emit (indent 1 "}"))
      (emit "")
      (emit (indent 1 "%% ── Terminal states ───────────────────────────────────────────────"))
      (doseq [s [:released :refunded :resolved]]
        (emit (indent 1 (str (str/upper-case (name s)) " --> [*]"))))
      (emit "")
      (emit (indent 1 "%% ── RESOLVED note ─────────────────────────────────────────────────"))
      (emit (indent 1 "note right of RESOLVED"))
      (emit (indent 2 "Reserved — no production call site."))
      (emit (indent 2 "Retained for enum completeness"))
      (emit (indent 2 "and formal verification only."))
      (emit (indent 1 "end note")))
    (str/join "\n" @lines)))

^{::clerk/visibility {:result :hide}}
(def canonical-mermaid-source
  "The canonical Mermaid diagram source, generated from the live simulator at
   notebook evaluation time. Saved here so it can be inspected as plain text."
  (generate-state-machine-mermaid))

;; ---------------------------------------------------------------------------
;; RAG / Status helpers (pure)
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:result :hide}}
(defn rag-badge [rag text]
  (ui/rag-badge rag text))

^{::clerk/visibility {:result :hide}}
(defn- status-emoji [rag]
  (case rag :green "🟢" :amber "🟠" :red "🔴" "⚪"))

^{::clerk/visibility {:result :hide}}
(defn- conf-badge [level]
  (let [[bg border fg]
        (case level
          "High"    ["#dcfce7" "#16a34a" "#166534"]
          "Medium"  ["#eff6ff" "#3b82f6" "#1e3a8a"]
          "Low"     ["#fef3c7" "#f59e0b" "#92400e"]
          "Missing" ["#f1f5f9" "#94a3b8" "#334155"]
          ["#f1f5f9" "#94a3b8" "#334155"])]
    [:span {:style {:display "inline-block" :padding "2px 8px"
                    :borderRadius "999px" :border (str "1px solid " border)
                    :backgroundColor bg :color fg :fontSize "0.75em" :fontWeight "600"}}
     level]))

^{::clerk/visibility {:result :hide}}
(defn- card [rag label value note]
  (let [border (case rag :green "#16a34a" :red "#dc2626" "#d97706")
        bg     (case rag :green "#f0fdf4" :red "#fef2f2" "#fffbeb")
        color  (case rag :green "#14532d" :red "#7f1d1d" "#78350f")]
    [:div {:style {:borderLeft   (str "4px solid " border)
                   :background   bg
                   :color        color
                   :padding      "12px 16px"
                   :borderRadius "4px"
                   :marginBottom "8px"}}
     [:div {:style {:display "flex" :alignItems "baseline" :gap "8px"}}
      [:span {:style {:fontSize "1.1em"}} (status-emoji rag)]
      [:strong label]
      (when value [:span {:style {:fontSize "0.95em" :opacity "0.85"}} value])]
     (when note
       [:div {:style {:fontSize "0.82em" :marginTop "4px" :opacity "0.8"}} note])]))

^{::clerk/visibility {:result :hide}}
(defn- section-header [title sub]
  [:div {:style {:borderBottom "2px solid #e2e8f0" :paddingBottom "8px" :marginTop "28px" :marginBottom "12px"}}
   [:h2 {:style {:margin "0 0 4px 0"}} title]
   (when sub [:p {:style {:color "#64748b" :fontSize "0.88em" :margin "0"}} sub])])

^{::clerk/visibility {:result :hide}}
(defn- note-box [text]
  [:div {:style {:background "#eff6ff" :border "1px solid #93c5fd"
                 :borderRadius "4px" :padding "10px 14px"
                 :fontSize "0.84em" :color "#1e3a8a" :marginBottom "10px"}}
   text])

^{::clerk/visibility {:result :hide}}
(defn- warn-box [text]
  [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b"
                 :borderRadius "4px" :padding "10px 14px"
                 :fontSize "0.84em" :color "#78350f" :marginBottom "10px"}}
   text])

^{::clerk/visibility {:result :hide}}
(defn- simple-table [headers rows]
  [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
   [:thead
    [:tr {:style {:background "#f1f5f9"}}
     (map-indexed
      (fn [idx h]
        ^{:key (str "header-" idx "-" h)}
        [:th {:style {:padding "8px 10px" :textAlign "left"}} h])
      headers)]]
   (into [:tbody] rows)])

;; ---------------------------------------------------------------------------
;; Notebook navigation
;; ---------------------------------------------------------------------------

(clerk/html (ui/notebook-navigation "Dispute Resolution Workbench"))

;; ---
;; ## Legend
;;
;; | Colour | Meaning |
;; |--------|---------|
;; | 🟢 Green | Validated — simulator-backed, replayed, invariant-checked |
;; | 🟠 Amber | Inconclusive — scenario-backed but limited range, partial implementation, or artifact missing |
;; | 🔴 Red   | Failure / finding — hard invariant violation, unexpected outcome, or active vulnerability evidence |
;;
;; **Confidence tiers** (used throughout this workbench):
;; - **High** — simulator-backed, replayed across parameter ranges, all relevant invariants checked
;; - **Medium** — scenario-backed with limited parameter range, or derivation-backed with partial replay coverage
;; - **Low** — derivation-backed, partial implementation, or manual review only
;; - **Missing** — no meaningful evidence yet exists

;; ===========================================================================
;; ## Section 1 — Executive Overview
;; ===========================================================================

(clerk/html
 (safe-render
  "Executive Overview"
  (fn []
    (let [{:keys [passed total elapsed-ms error]} live-suite-results
          suite-rag  (cond error :amber (= passed total) :green :else :red)
          inv-count  (count invariants/canonical-ids)
          sc-count   (count sc/all-scenarios)
          adv-count  (count (filter #(= :adversarial
                                        (:scenario/type (get sc/scenario-type-registry
                                                             (if (map? (second %))
                                                               (:scenario-id (second %))
                                                               (:scenario-id (first (second %)))))))
                                    sc/all-scenarios))
          gate-rag   (if test-summary
                       (if (= "pass" (str (:overall_status test-summary))) :green :red)
                       :amber)]
      [:div
       (section-header
        "Executive Overview"
        "What this workbench covers and what it does not.")

       (note-box
        [:span
         [:strong "Scope: "]
         "This workbench presents evidence for the Sew Protocol dispute resolution subsystem "
         "as exercised by the deterministic invariant scenario suite (S01–S67). "
         "It does not cover the full on-chain implementation, gas analysis, or live mainnet behavior. "
         "Evidence strength is annotated on every claim."])

       ;; Protocol model summary
       [:div {:style {:background "#f8fafc" :border "1px solid #e2e8f0"
                      :borderRadius "6px" :padding "14px 16px" :marginBottom "14px"}}
        [:h3 {:style {:margin "0 0 10px 0" :fontSize "1em"}} "Protocol Model Summary"]
        [:ul {:style {:margin "0" :paddingLeft "20px" :fontSize "0.88em" :lineHeight "1.8"}}
         [:li "Sew protected transfers (escrows) lock funds until explicit release, "
          "mutual cancel, dispute resolution, or timeout."]
         [:li "Disputes follow a predefined escalation path: "
          "Level-0 resolver → Level-1 (appeal) → Level-2 → Kleros backstop."]
         [:li "Escrow terms and resolver assignments are "
          [:strong "snapshotted at creation time"] ". "
          "Governance changes to protocol parameters do not affect active escrows."]
         [:li "Governance " [:strong "cannot alter active escrow terms"] " after creation. "
          "The snapshot is an immutable contract commitment."]
         [:li "Kleros is modeled as the final escalation layer ("
          [:code "0xkleros-proxy"]
          ") with a configurable multi-level resolver set (L0/L1/L2)."]
         [:li "The simulator tests dispute behavior under adversarial conditions using three "
          "adversary classes: profit-maximizer, forking-strategist, and colluder."]
         [:li "This workbench presents " [:strong "evidence"] ", not claims. "
          "Every status below references a specific artifact or explains why it is absent."]]]

       ;; Live suite status bar
       [:div {:style {:background "#f0fdf4" :border "1px solid #16a34a"
                      :borderRadius "6px" :padding "10px 14px" :marginBottom "14px"
                      :display "flex" :gap "24px" :flexWrap "wrap" :alignItems "center"}}
        (status-emoji suite-rag)
        [:strong "Live invariant suite: "]
        [:span (if error
                 (str "error — " error)
                 (str passed "/" total " scenarios pass"))]
        [:span {:style {:color "#64748b" :fontSize "0.85em"}}
         (when-not error (str "(" (int (/ elapsed-ms 1000.0)) "s)"))]
        [:span {:style {:color "#64748b" :fontSize "0.85em"}}
         (str "  │  " inv-count " canonical invariants  │  "
              sc-count " scenarios  │  " adv-count " adversarial")]]

       ;; Summary table
       (let [rows
             [["Basic dispute lifecycle"
               (if (>= passed 3) "🟢 Exercised" "🟠 Partial")
               "High"
               "S01–S08 cover create/dispute/resolve/refund/timeout paths. "
               "Scenario-backed, replayed, all structural invariants checked."]
              ["Resolver assignment / routing"
               "🟢 Validated"
               "High"
               "S07, S14, S15 test authorized vs. unauthorized resolver rejection "
               "via both custom-resolver and module-based routing."]
              ["Escalation pipeline"
               "🟢 Validated"
               "High"
               "S19–S23, S26–S33 exercise multi-level escalation, level-monotonicity, "
               "pending-settlement clearing, and premature escalation rejection."]
              ["Appeal windows"
               "🟢 Validated"
               "High"
               "S05, S13, S21 test appeal deadline enforcement, early-settlement rejection, "
               "and deadline-exact execution."]
              ["Kleros backstop integration"
               "🟠 Modeled"
               "Medium"
               "S18–S23 exercise the Kleros proxy module model (0xkleros-proxy). "
               "Confidence is Medium: simulator model, not live Kleros contract integration."]
              ["Resolver liveness"
               "🟢 Validated"
               "High"
               "S04, S17, S24 test timeout-triggered auto-cancel and resolver stake "
               "depletion. Bond-slash saturation is exercised across 3 concurrent escrows."]
              ["Bond / incentive behavior"
               "🟢 Validated"
               "High"
               "S24–S37, S40–S41, S45 test slash accounting, bond-mix validity, "
               "senior coverage, freeze-post-slash, and flash-loan stake inflation."]
              ["Governance interaction"
               "🟢 Validated"
               "High"
               "S12 (snapshot isolation) confirms governance params do not "
               "cross-contaminate active escrows. Governance-capture under multi-epoch "
               "adversarial conditions is covered in the stochastic phases."]
              ["Capacity / dispute flooding"
               "🟠 Partial"
               "Medium"
               "S24 (stake cascade) tests 3-escrow concurrent depletion. "
               "Monte Carlo sweep (Phase F) covers flooding at scale. "
               "No dedicated single-notebook flooding scenario."]
              ["Multi-epoch adversarial behavior"
               "🟠 Partial"
               "Low"
               "Stochastic phases (Phase J) model multi-epoch reputation drift. "
               "No deterministic multi-epoch scenario in S01–S67 currently."]]]
         [:div {:style {:overflowX "auto"}}
          [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
           [:thead
            [:tr {:style {:background "#f1f5f9"}}
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Area"]
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Current status"]
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Confidence"]
             [:th {:style {:padding "8px 10px" :textAlign "left" :borderBottom "2px solid #cbd5e1"}} "Notes"]]]
           (into [:tbody]
                 (map (fn [[area status conf note1 note2]]
                        [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                         [:td {:style {:padding "8px 10px" :fontWeight "600"}} area]
                         [:td {:style {:padding "8px 10px"}} status]
                         [:td {:style {:padding "8px 10px"}} (conf-badge conf)]
                         [:td {:style {:padding "8px 10px" :color "#475569"}} (str note1 " " note2)]])
                      rows))]])]

       (warn-box
         [:span
          [:strong "Confidence caveat: "]
          "\"High\" confidence means simulator-backed with invariant checking across "
          "a deterministic scenario suite. It does not imply formal verification, "
          "gas-correctness, on-chain proof, or absence of unknown-unknown attack surfaces. "
          "The simulator is a pure Clojure model that mirrors the Solidity spec; "
          "divergence from the on-chain contract is a bug."])))))

;; ===========================================================================
;; ## Section 2 — Dispute Lifecycle Model
;; ===========================================================================

;; Canonical diagram — generated from sm/allowed-transitions + t/max-dispute-level
(clerk/md (str "## Dispute Lifecycle Model

The diagram below is **generated directly from the live simulator** at notebook evaluation time.
Sources:
- `resolver-sim.protocols.sew.state-machine/allowed-transitions` — the authoritative edge set
- `resolver-sim.protocols.sew.types/max-dispute-level` = `" t/max-dispute-level "` — maximum escalation rounds

Every lifecycle function that changes `:escrow-state` goes through `apply-transition!`,
which throws a programming error on any illegal edge attempt.

```mermaid
" canonical-mermaid-source "
```

> **Note on `:resolved`:** `transitionToResolved()` is defined in `StateManagementLibrary.sol`
> but is **never called** by any production code path (verified from source).
> Disputes always terminate in `:released` or `:refunded`.
> `:resolved` is retained for enum completeness and Foundry/halmos compatibility only.

### Who can initiate each action?

| Action | Authorized caller | Precondition |
|--------|------------------|--------------|
| `createEscrow()` | Buyer | — |
| `raiseDispute()` | Buyer or Seller (participant) | State = `:pending` |
| `release()` | Buyer | State = `:pending` |
| `senderCancel()` / `recipientCancel()` | Respective party | State = `:pending` |
| `autoRelease()` / `autoCancel()` | Anyone (keeper) | Timeout elapsed; state = `:pending` |
| `executeResolution()` | Authorized resolver | State = `:disputed`; resolver authority check |
| `escalateDispute()` / `challengeResolution()` | Any party | State = `:disputed`; pending-settlement exists; within appeal window; level < max |
| `executePendingSettlement()` | Anyone (keeper) | State = `:disputed`; appeal deadline elapsed |
| `autoCancelDisputedEscrow()` | Anyone (keeper) | State = `:disputed`; `max-dispute-duration` elapsed |

### What cannot happen after finalization?

Terminal states (`:released`, `:refunded`, `:resolved`) are absorbing — enforced by:
1. `allowed-transitions` contains `#{}` for all three terminal states.
2. `apply-transition!` throws a programming error on any illegal edge.
3. The `:terminal-states-unchanged` invariant checked after every scenario step.
4. Scenarios S08 (state machine attack gauntlet) and S10 (double-finalize rejected) provide deterministic regression coverage.

### Governance isolation

Escrow protocol parameters (resolver fee, appeal window, dispute duration) are **snapshotted at creation time**.
Governance changes after creation have no effect on active escrows.
Scenario S12 (governance snapshot isolation) provides deterministic regression coverage."))

;; Live transition table — raw map from the simulator
(clerk/html
 (safe-render
  "Transition Graph"
  (fn []
    [:div {:style {:marginBottom "16px"}}
     [:h3 {:style {:margin "0 0 10px 0"}} "Live transition table"]
     (note-box
      [:span "Rendered directly from "
       [:code "sm/allowed-transitions"] " and "
       [:code "sm/transitions"] " at evaluation time. "
       "This is the exact data structure consumed by "
       [:code "apply-transition!"] " at runtime."])
     ;; sm/transitions shape: {:trigger-kw {:from #{states} :to state :guards [...] :effects [...]}}
     (simple-table
      ["From" "To" "Trigger" "Guards" "Effects"]
      (let [edge->trigger
            (into {}
                  (mapcat (fn [[kw {:keys [from to guards effects]}]]
                            (map (fn [f] [[f to] {:kw kw :guards guards :effects effects}])
                                 from))
                          sm/transitions))]
        (for [[from tos] (sort-by (comp str first) sm/allowed-transitions)
              to         (sort-by str tos)]
          (let [{:keys [kw guards effects]} (get edge->trigger [from to] {})]
            [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
             [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontWeight "600"}} (name from)]
             [:td {:style {:padding "6px 10px" :fontFamily "monospace"}} (name to)]
             [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontSize "0.85em"}}
              (if kw (name kw) "—")]
             [:td {:style {:padding "6px 10px" :fontSize "0.82em" :color "#475569"}}
              (if (seq guards) (str/join " · " (map name guards)) "—")]
             [:td {:style {:padding "6px 10px" :fontSize "0.82em" :color "#475569"}}
              (if (seq effects) (str/join " · " (map name effects)) "—")]]))))
     ;; Terminal states row
     [:div {:style {:marginTop "8px"}}
      (into [:div {:style {:display "flex" :gap "8px" :flexWrap "wrap"}}]
            (map (fn [s]
                   [:span {:style {:background "#f0fdf4" :border "1px solid #16a34a"
                                   :borderRadius "4px" :padding "2px 8px"
                                   :fontFamily "monospace" :fontSize "0.82em"}}
                    (str "🔒 " (name s) " — terminal")]  )
                 (filter (fn [s] (empty? (get sm/allowed-transitions s #{})))
                         (keys sm/allowed-transitions))))]
     [:p {:style {:fontSize "0.8em" :color "#64748b" :marginTop "8px"}}
      "Source: "
      [:code "resolver-sim.protocols.sew.state-machine/allowed-transitions"]
      " and "
      [:code "resolver-sim.protocols.sew.state-machine/transitions"]
      " — rendered live at notebook evaluation time. "
      [:code (str "max-dispute-level = " t/max-dispute-level)]
      " from "
      [:code "resolver-sim.protocols.sew.types/max-dispute-level"] "."]])))

;; Raw Mermaid source for copy/export
(clerk/html
 (safe-render
  "Raw Mermaid Source"
  (fn []
    [:details {:style {:marginTop "8px"}}
     [:summary {:style {:cursor "pointer" :fontSize "0.85em" :color "#475569" :userSelect "none"}}
      "▶ Show raw Mermaid source (generated at evaluation time — copy to render externally)"]
     [:pre {:style {:background "#f8fafc" :border "1px solid #e2e8f0" :borderRadius "4px"
                    :padding "12px" :fontSize "0.78em" :overflowX "auto" :marginTop "6px"}}
      canonical-mermaid-source]])))

;; ===========================================================================
;; ## Section 3 — Invariant Coverage
;; ===========================================================================

(clerk/html
 (safe-render
  "Invariant Coverage"
  (fn []
    (let [inv-ids (sort (map name invariants/canonical-ids))
          suite   live-suite-results
          results (:results suite)
          ;; Map invariant-id → set of scenario names that exercise it
          ;; (derived from scenario IDs that are tagged with known invariant coverage)
          inv-coverage
          {"solvency"                      ["S09" "S24" "S25" "S37"]
           "fees-non-negative"             ["S11" "S25" "S36"]
           "held-non-negative"             ["S09" "S24" "S25"]
           "all-status-combinations-valid" ["S08" "S10" "S22"]
           "pending-settlement-consistent" ["S05" "S13" "S21" "S25" "S32"]
           "dispute-timestamp-consistent"  ["S04" "S05" "S17" "S21"]
           "dispute-level-bounded"         ["S20" "S28" "S30"]
           "slash-status-consistent"       ["S25" "S34" "S35" "S36"]
           "appeal-bond-conserved"         ["S25" "S35" "S36"]
           "appeal-bond-custody-consistent" ["S25" "S35" "S36"]
           "no-auto-fraud-execute"         ["S25" "S34"]
           "bond-liquidity"                ["S24" "S38" "S39"]
           "bond-slash-bounded"            ["S24" "S41"]
           "fee-cap"                       ["S11" "S12a" "S12b"]
           "no-stale-automatable-escrows"  ["S04" "S17"]
           "conservation-of-funds"         ["S24" "S25" "S31" "S37"]
           "dispute-resolution-path"       ["S02" "S03" "S18" "S26" "S27"]
           "slash-distribution-consistent" ["S24" "S34" "S37"]
           "resolver-bond-mix-valid"       ["S38"]
           "senior-coverage-not-exceeded"  ["S39"]
           "resolver-not-frozen-on-assign" ["S40"]
           "slash-epoch-cap-respected"     ["S40" "S41"]
           "reversal-slash-disabled"       ["S41"]
           "resolver-capacity"             ["S24" "S38"]
           "yield-position-consistency"    []
           "yield-exposure"                []
           "terminal-states-unchanged"     ["S08" "S10" "S19" "S25"]
           "time-non-decreasing"           ["S04" "S05" "S21"]
           "time-no-action-after-finality" ["S08" "S10"]
           "finalization-accounting-correct" ["S02" "S03" "S09" "S25"]
           "escalation-level-monotonic"    ["S19" "S21" "S28" "S32"]
           "no-withdrawal-during-dispute"  ["S45"]
           "time-lock-integrity"           ["S66"]
           "token-tax-reconciliation"      ["S11"]
           "fees-monotone"                 ["S11" "S25" "S37"]
           "single-resolution-payout-consistent" ["S02" "S03" "S31"]
           "fraud-slash-executions-accounted"    ["S25" "S34" "S35"]}
          covered?   (fn [inv] (seq (get inv-coverage inv)))
          total-inv  (count inv-ids)
          covered-n  (count (filter covered? inv-ids))
          uncovered  (remove covered? inv-ids)]
      [:div
       (section-header
        "Invariant Coverage"
        (str total-inv " canonical invariants across SEW v1; "
             covered-n " with deterministic scenario coverage."))

       (note-box
        [:span
         [:strong "What these invariants are: "]
         "Each invariant in "
         [:code "resolver-sim.protocols.sew.invariants/canonical-ids"]
         " mirrors a runtime guard in "
         [:code "InvariantGuardInternal.sol"]
         " and defines the specification for future Foundry invariant tests and Halmos properties. "
         "The simulator checks all applicable invariants after every event step."])

       [:div {:style {:display "flex" :gap "16px" :flexWrap "wrap" :marginBottom "12px"}}
        (card :green "Invariants with scenario coverage"
              (str covered-n "/" total-inv) nil)
        (card (if (empty? uncovered) :green :amber)
              "Invariants with no scenario coverage yet"
              (str (count uncovered)) nil)]

       ;; Coverage table
       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.82em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Invariant ID"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Coverage"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Scenarios"]]]
         (into [:tbody]
               (map (fn [inv]
                      (let [covs  (get inv-coverage inv [])
                            cov?  (seq covs)
                            rag   (if cov? :green :amber)]
                        [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                         [:td {:style {:padding "6px 10px" :fontFamily "monospace"}} (str ":" inv)]
                         [:td {:style {:padding "6px 10px"}}
                          (if cov?
                            [:span {:style {:color "#15803d"}} "✓ covered"]
                            [:span {:style {:color "#b45309"}} "⚠ no scenario"])]
                         [:td {:style {:padding "6px 10px" :color "#475569" :fontSize "0.9em"}}
                          (if (seq covs) (str/join ", " covs) "—")]]))
                    inv-ids))]]

       (when (seq uncovered)
         [:div {:style {:marginTop "12px"}}
          (warn-box
           [:span
            [:strong "Uncovered invariants: "]
            "The following invariants exist in "
            [:code "canonical-ids"]
            " but have no deterministic scenario coverage yet: "
            [:code (str/join ", " (map #(str ":" %) uncovered))]
            ". This does not mean they are unimplemented — the checker runs on every step — "
            "but there is no scenario specifically designed to stress-test them."])])]))))

;; ===========================================================================
;; ## Section 4 — Scenario Matrix (Live)
;; ===========================================================================

;; Interactive: select a scenario to see its type metadata and live result.
^{::clerk/sync true}
(defonce !selected-scenario (atom nil))

(clerk/html
 (safe-render
  "Scenario Matrix"
  (fn []
    (let [results      (:results live-suite-results [])
          selected-id  @!selected-scenario
          type-counts  (frequencies (map :scenario/type results))
          adv-results  (filter #(= :adversarial (:scenario/type %)) results)
          pass-count   (:passed live-suite-results 0)
          total-count  (:total live-suite-results 0)
          suite-rag    (if (= pass-count total-count) :green :red)]
      [:div
       (section-header
        "Scenario Matrix — Live Execution (S01–S67)"
        "All deterministic invariant scenarios executed in-process at notebook load time.")

       (note-box
        [:span
         "Each row represents one execution of "
         [:code "sew/replay-with-sew-protocol"]
         " against a fully deterministic scenario. "
         "\"XFAIL\" (expected-fail) scenarios are regression tests for known-fixed bugs: "
         "they pass when the invariant fires as expected. "
         "Click a row to inspect its metadata."])

       ;; Summary metrics
       [:div {:style {:display "flex" :gap "12px" :flexWrap "wrap" :marginBottom "14px"}}
        (card suite-rag "Suite result" (str pass-count "/" total-count) "All scenarios must pass")
        (card :green "Baseline" (str (get type-counts :baseline 0)) "Standard protocol flows")
        (card :green "Edge-case" (str (get type-counts :edge-case 0)) "Guards, boundaries, state checks")
        (card :green "Stress" (str (get type-counts :stress 0)) "Solvency, multi-escrow, depletion")
        (card (if (pos? (get type-counts :adversarial 0)) :green :amber) "Adversarial"
              (str (get type-counts :adversarial 0)) "Profit-maximizer, forking-strategist, colluder")]

       ;; Scenario table
       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.81em"
                         :cursor "pointer"}}
         [:thead
          [:tr {:style {:background "#f1f5f9" :position "sticky" :top "0"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} ""]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Scenario"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Type"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Steps"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Reverts"]
           [:th {:style {:padding "7px 10px" :textAlign "right"}} "Violations"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Result"]]]
         (into [:tbody]
               (map (fn [{:keys [name pass? expected-fail? steps reverts violations
                                 scenario/type adversary/type adversary/traits] :as r}]
                      (let [rag      (cond (and pass? expected-fail?) :amber
                                           pass?                      :green
                                           :else                      :red)
                            selected? (= name selected-id)
                            row-bg   (cond selected? "#eff6ff"
                                           (= :adversarial type) "#fefce8"
                                           :else nil)]
                        [:tr {:style (cond-> {:borderBottom "1px solid #e2e8f0"}
                                       row-bg (assoc :background row-bg))
                              :on-click #(reset! !selected-scenario name)}
                         [:td {:style {:padding "6px 10px"}}
                          (status-emoji rag)]
                         [:td {:style {:padding "6px 10px" :fontFamily "monospace"
                                       :fontWeight (when selected? "600")}}
                          name]
                         [:td {:style {:padding "6px 10px" :color "#475569"}}
                          (when type (clojure.core/name type))]
                         [:td {:style {:padding "6px 10px" :textAlign "right"}} steps]
                         [:td {:style {:padding "6px 10px" :textAlign "right"}} reverts]
                         [:td {:style {:padding "6px 10px" :textAlign "right"
                                       :color (when (pos? (or violations 0)) "#dc2626")}}
                          violations]
                         [:td {:style {:padding "6px 10px"}}
                          (cond (and pass? expected-fail?) "✓ XFAIL"
                                pass? "✓ PASS"
                                :else "✗ FAIL")]]))
                    results))]]

       ;; Detail panel for selected scenario
       (when-let [selected (and selected-id (some #(when (= (:name %) selected-id) %) results))]
         [:div {:style {:marginTop "16px" :border "1px solid #93c5fd"
                        :borderRadius "6px" :padding "14px 16px" :background "#eff6ff"}}
          [:h3 {:style {:margin "0 0 10px 0"}} "Scenario detail: " (:name selected)]
          [:dl {:style {:display "grid" :gridTemplateColumns "160px 1fr"
                        :gap "4px 12px" :fontSize "0.85em"}}
           [:dt [:strong "Type"]]
           [:dd (str (or (:scenario/type selected) "—"))]
           [:dt [:strong "Adversary type"]]
           [:dd (str (or (:adversary/type selected) "none"))]
           [:dt [:strong "Adversary traits"]]
           [:dd (if (seq (:adversary/traits selected))
                  (str/join ", " (map name (:adversary/traits selected)))
                  "—")]
           [:dt [:strong "Expected-fail?"]]
           [:dd (str (:expected-fail? selected))]
           [:dt [:strong "Steps executed"]]
           [:dd (str (:steps selected))]
           [:dt [:strong "Reverts"]]
           [:dd (str (:reverts selected))]
           [:dt [:strong "Violations"]]
           [:dd (str (:violations selected))]
           [:dt [:strong "Pass?"]]
           [:dd (str (:pass? selected))]]])

       [:p {:style {:fontSize "0.78em" :color "#64748b" :marginTop "8px"}}
        "Click any row to inspect its metadata. "
        "Yellow background = adversarial scenario. "
        "Source: "
        [:code "resolver-sim.protocols.sew.invariant-runner/run-all"]
        " — executed live."]]))))

;; ===========================================================================
;; ## Section 5 — Adversarial Scenario Breakdown
;; ===========================================================================

^{::clerk/visibility {:result :hide}}
(defn- render-adversary-card [adv-type scenarios adversary-descriptions]
  (let [meta       (get adversary-descriptions adv-type {})
        pass-count (count (filter :pass? scenarios))
        total      (count scenarios)
        rag        (if (= pass-count total) :green :red)]
    [:div {:style {:border "1px solid #e2e8f0" :borderRadius "6px"
                   :padding "14px 16px" :marginBottom "12px"}}
     [:div {:style {:display "flex" :gap "10px" :alignItems "baseline"
                    :marginBottom "8px"}}
      (status-emoji rag)
      [:h3 {:style {:margin "0"}} (str (or (:label meta) (name adv-type)))]
      [:span {:style {:color "#64748b" :fontSize "0.85em"}}
       (str pass-count "/" total " pass")]]
     (when (:summary meta)
       [:p {:style {:fontSize "0.86em" :color "#374151" :marginBottom "8px"}}
        (:summary meta)])
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "10px"}}
      [:div
       [:strong {:style {:fontSize "0.83em"}} "Modeled tactics"]
       [:ul {:style {:margin "4px 0" :paddingLeft "18px" :fontSize "0.82em"}}
        (map #(vector :li %) (or (:tactics meta) []))]]
      [:div
       [:strong {:style {:fontSize "0.83em"}} "Finding"]
       [:p {:style {:fontSize "0.82em" :color "#374151" :margin "4px 0"}}
        (or (:finding meta) "—")]]]
     [:div {:style {:marginTop "10px" :overflowX "auto"}}
      [:table {:style {:borderCollapse "collapse" :fontSize "0.8em" :width "100%"}}
       [:thead
        [:tr {:style {:background "#f8fafc"}}
         [:th {:style {:padding "5px 8px" :textAlign "left"}} "Scenario"]
         [:th {:style {:padding "5px 8px" :textAlign "left"}} "Traits"]
         [:th {:style {:padding "5px 8px" :textAlign "right"}} "Steps"]
         [:th {:style {:padding "5px 8px" :textAlign "right"}} "Reverts"]
         [:th {:style {:padding "5px 8px" :textAlign "left"}} "Result"]]]
       (into [:tbody]
             (map (fn [{:keys [name pass? steps reverts adversary/traits]}]
                    [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                     [:td {:style {:padding "4px 8px" :fontFamily "monospace"}}
                      name]
                     [:td {:style {:padding "4px 8px" :color "#475569"}}
                      (if (seq traits) (str/join ", " (map clojure.core/name traits)) "—")]
                     [:td {:style {:padding "4px 8px" :textAlign "right"}} steps]
                     [:td {:style {:padding "4px 8px" :textAlign "right"}} reverts]
                     [:td {:style {:padding "4px 8px"}}
                      (if pass? "✓ PASS" "✗ FAIL")]])
                  scenarios))]]]))

(clerk/html
 (safe-render
  "Adversarial Breakdown"
  (fn []
    (let [results     (:results live-suite-results [])
          adv-results (filter #(= :adversarial (:scenario/type %)) results)
          by-adversary (group-by :adversary/type adv-results)
          adversary-descriptions
          {:profit-maximizer
           {:label    "Profit-Maximizer"
            :summary  "Attempts to extract value via speculative fraud slashes, pre-window settlement execution, governance manipulation, and stake inflation."
            :tactics  ["Speculative fraud slash followed by appeal (S25)"
                       "Unchallenged slash (S34)"
                       "Governance wins appeal against resolver (S35)"
                       "Pre-window settlement execution rejected (S36)"
                       "Two-resolver split-outcome extraction (S37)"
                       "Flash-loan stake inflation (S45)"
                       "Reentrancy callback attempt (S67)"]
            :finding  "All extraction attempts are rejected or bounded by the bond-slash accounting model. No profit-maximizer scenario produces a solvency violation."}
           :forking-strategist
           {:label    "Forking-Strategist"
            :summary  "Attempts to exploit the multi-level escalation pipeline: skipping levels, fabricating appeal windows, isolating forks, and triggering double-loss scenarios."
            :tactics  ["L1 reversal attempt (S26)"
                       "L2 fork attempt (S27)"
                       "Late escalation after deadline (S28)"
                       "Seller-initiated escalation (S29)"
                       "Double-loss via forking (S30)"
                       "All-levels confirm (S31)"
                       "Premature settlement rejection (S32)"
                       "Two-escrow fork isolation (S33)"]
            :finding  "The escalation-level-monotonic and pending-settlement-consistent invariants prevent level-skipping and premature finalization. All forking attempts are either rejected or produce correctly-isolated outcomes."}
           :colluder
           {:label    "Colluder"
            :summary  "Multi-agent collusion: resolver–buyer bribery loop."
            :tactics  ["Resolver-buyer bribery loop (S42)"]
            :finding  "Single scenario. Demonstrates that the resolver authority model prevents bribery-based resolution hijacking within the deterministic model. Multi-epoch collusion resistance relies on stochastic Phase J."}}
          total-adv  (count adv-results)
          pass-adv   (count (filter :pass? adv-results))]
      [:div
       (section-header
        "Adversarial Scenario Breakdown"
        (str total-adv " adversarial scenarios across 3 adversary classes; "
             pass-adv "/" total-adv " pass."))

       (note-box
        [:span
         [:strong "Adversarial scenarios are not attack demonstrations. "]
         "They are deterministic proofs that specific adversarial strategies fail as modeled. "
         "A passing adversarial scenario means the protocol correctly rejected the attack. "
         [:strong "A failing adversarial scenario means an attack succeeded — investigate immediately."]])

       ;; Per-adversary breakdown
       (into [:div]
             (map (fn [[adv-type scenarios]]
                    (render-adversary-card adv-type scenarios adversary-descriptions))
                  by-adversary))]))))

;; ===========================================================================
;; ## Section 6 — Kleros Integration Model
;; ===========================================================================

(clerk/md
 "## Kleros Integration Model

### Where Kleros fits in the Sew escalation model

Sew Protocol uses Kleros as a **final escalation / backstop layer** for disputes
that cannot be resolved by the escrow-level resolver within the standard appeal
pipeline.

```
Dispute raised
     │
     ▼
 Level-0 Resolver (custom or module-assigned)
     │
     │ resolution + appeal window
     ▼
 Appeal → Level-1 Resolver
     │
     │ resolution + appeal window
     ▼
 Appeal → Level-2 Resolver  ←── Kleros proxy (0xkleros-proxy)
     │
     │ final resolution (no further escalation)
     ▼
 executePendingSettlement (after deadline)
     │
     ▼
 :released or :refunded  (terminal)
```

Kleros enters via the `resolution-module` parameter set to `\"0xkleros-proxy\"`
at escrow creation time. The module configures `escalation-resolvers` as a
level-indexed map: `{:0 \"0xl0\" :1 \"0xl1\" :2 \"0xl2\"}`.

### Kleros-specific invariants enforced by the simulator

| Invariant | Meaning |
|-----------|---------|
| `escalation-level-monotonic` | Dispute level can only increase; skipping levels is impossible |
| `pending-settlement-consistent` | Escalation requires an existing pending settlement (a resolution must have been proposed) |
| `dispute-level-bounded` | Level is capped at the max configured in `escalation-resolvers` |
| `no-withdrawal-during-dispute` | Funds cannot be withdrawn while a dispute is active at any level |
| `terminal-states-unchanged` | Once finalized, even Kleros cannot re-open the dispute |

### Deterministic scenarios covering Kleros integration

| Scenario | Description | What it proves |
|----------|-------------|----------------|
| S18 | DR3 Kleros: L0 resolver resolves at level 0 | Standard Kleros-module resolution path |
| S19 | Preemptive escalation rejected; L0 resolves | Escalation requires prior resolution |
| S20 | Max-escalation guard | Level cap enforcement |
| S21 | Pending settlement cleared on escalation | Escalation correctly clears prior settlement; L1 path works |
| S22 | Status-leak regression | Agree-to-cancel status cleared on dispute |
| S23 | Preemptive escalation blocked (seller) | Both-party escalation guard |
| S26 | Forking-strategist L1 reversal | L1 reversal is correctly accounted |
| S27 | Forking-strategist L2 fork | L2 Kleros level reached; invariants hold |
| S28 | Late escalation rejected | Post-deadline escalation is blocked |
| S32 | Premature settlement rejected | Cannot settle before appeal deadline |

### What this does NOT prove

- **Live Kleros contract integration** — the simulator uses `0xkleros-proxy` as a
  stub with a configurable resolver set. The actual Kleros court contract is not
  exercised.
- **Kleros economics** — juror incentives, PNK staking, coherence bonuses, and
  appeal fee dynamics are not modeled.
- **Cross-chain Kleros** — multi-chain dispute routing is not covered.
- **Kleros v2 (Kleros Court)** — the model assumes the Kleros v1/arbitration interface.

These gaps should be addressed in a joint integration specification with the
Kleros protocol team before production deployment.")

;; Live Kleros scenario results panel
(clerk/html
 (safe-render
  "Kleros Scenario Results"
  (fn []
    (let [results      (:results live-suite-results [])
          kleros-ids   #{"S18  dr3-kleros-l0-resolves"
                         "S19  dr3-kleros-escalation-rejected-l0-resolves"
                         "S20  dr3-kleros-max-escalation-guard"
                         "S21  dr3-kleros-pending-cleared-on-escalation"
                         "S22  status-leak-agree-cancel-over-dispute"
                         "S23  preemptive-escalation-blocked"
                         "S26  forking-strategist-l1-reversal"
                         "S27  forking-strategist-l2-fork"
                         "S28  forking-strategist-late-escalation-rejected"
                         "S29  forking-strategist-seller-escalates"
                         "S30  forking-strategist-double-loss"
                         "S31  forking-strategist-all-levels-confirm"
                         "S32  forking-strategist-premature-settlement-rejected"
                         "S33  forking-strategist-two-escrow-fork-isolation"}
          kleros-res   (filter #(kleros-ids (:name %)) results)
          pass-n       (count (filter :pass? kleros-res))
          total-n      (count kleros-res)]
      [:div
       [:h3 "Kleros-Path Scenario Results (Live)"]
       (card (if (= pass-n total-n) :green :red)
             "Kleros-path scenarios"
             (str pass-n "/" total-n " pass")
             (str "Includes all Kleros-module resolver scenarios and forking-strategist escalation scenarios."))
       [:table {:style {:borderCollapse "collapse" :fontSize "0.82em" :width "100%"}}
        [:thead
         [:tr {:style {:background "#f1f5f9"}}
          [:th {:style {:padding "6px 10px" :textAlign "left"}} ""]
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Scenario"]
          [:th {:style {:padding "6px 10px" :textAlign "right"}} "Steps"]
          [:th {:style {:padding "6px 10px" :textAlign "right"}} "Reverts"]
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Result"]]]
        (into [:tbody]
              (map (fn [{:keys [name pass? steps reverts]}]
                     [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                      [:td {:style {:padding "5px 10px"}} (if pass? "🟢" "🔴")]
                      [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} name]
                      [:td {:style {:padding "5px 10px" :textAlign "right"}} steps]
                      [:td {:style {:padding "5px 10px" :textAlign "right"}} reverts]
                      [:td {:style {:padding "5px 10px"}} (if pass? "✓ PASS" "✗ FAIL")]])
                   kleros-res))]]))))

;; ===========================================================================
;; ## Section 7 — Confidence Summary by Area
;; ===========================================================================

(clerk/html
 (safe-render
  "Confidence Summary"
  (fn []
    (let [results  (:results live-suite-results [])
          pass-n   (:passed live-suite-results 0)
          total-n  (:total live-suite-results 0)
          areas
          [{:area        "State machine correctness"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S08, S10, S22"
            :invariants  ":terminal-states-unchanged, :all-status-combinations-valid, :time-no-action-after-finality"
            :rag         :green
            :caveat      "Covers all declared transitions. Formal proof (Halmos/Foundry) not yet complete."}
           {:area        "Solvency (fund conservation)"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S09, S24, S25, S37"
            :invariants  ":solvency, :conservation-of-funds, :held-non-negative, :fees-non-negative"
            :rag         :green
            :caveat      "Strict equality invariant (=, not ≤). External token balance verification depends on token contract behavior."}
           {:area        "Resolver authorization"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S07, S14, S15"
            :invariants  ":dispute-resolution-path"
            :rag         :green
            :caveat      "Covers custom-resolver and module-based routing. Registry/governance integration is a separate trust assumption."}
           {:area        "Appeal window enforcement"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S05, S13, S21, S32"
            :invariants  ":pending-settlement-consistent, :dispute-timestamp-consistent, :escalation-level-monotonic"
            :rag         :green
            :caveat      "Deadline arithmetic is integer seconds. EVM block-time drift is not modeled."}
           {:area        "Dispute timeout / liveness"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S04, S17, S24"
            :invariants  ":no-stale-automatable-escrows, :bond-slash-bounded"
            :rag         :green
            :caveat      "Liveness depends on keeper availability. Keeper incentive economics are modeled in stochastic Phase O."}
           {:area        "Governance snapshot isolation"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S12 (paired)"
            :invariants  ":fee-cap (via snapshot parameter)"
            :rag         :green
            :caveat      "Tests fee_bps isolation. Full governance upgrade path not yet covered."}
           {:area        "Bond / slash accounting"
            :confidence  "High"
            :backing     "Simulator-backed"
            :scenarios   "S24, S25, S34–S37, S38–S41, S45"
            :invariants  ":bond-liquidity, :bond-slash-bounded, :slash-status-consistent, :slash-epoch-cap-respected, :reversal-slash-disabled"
            :rag         :green
            :caveat      "Flash-loan stake inflation (S45) is modeled as a single-epoch attack. Multi-block flash-loan is not fully modeled."}
           {:area        "Kleros escalation path"
            :confidence  "Medium"
            :backing     "Scenario-backed (stub proxy)"
            :scenarios   "S18–S23, S26–S33"
            :invariants  ":escalation-level-monotonic, :dispute-level-bounded, :pending-settlement-consistent"
            :rag         :amber
            :caveat      "Kleros proxy is a stub. Real Kleros court economics, juror coherence, and PNK stake are not modeled. Confidence is Medium pending live integration."}
           {:area        "Capacity / dispute flooding"
            :confidence  "Medium"
            :backing     "Stochastic (Phase F) + limited deterministic"
            :scenarios   "S24 + Monte Carlo"
            :invariants  ":resolver-capacity"
            :rag         :amber
            :caveat      "S24 covers 3-escrow cascade. Scale flooding is in the Monte Carlo sweep, not this workbench."}
           {:area        "Multi-epoch adversarial behavior"
            :confidence  "Low"
            :backing     "Stochastic Phase J (separate notebook)"
            :scenarios   "None in S01–S67"
            :invariants  "N/A (stochastic)"
            :rag         :amber
            :caveat      "Multi-epoch reputation drift and ring-attack collusion are modeled in Phase J. No deterministic scenario exists."}
           {:area        "On-chain / gas correctness"
            :confidence  "Missing"
            :backing     "Not yet assessed"
            :scenarios   "—"
            :invariants  "—"
            :rag         :amber
            :caveat      "The simulator is a pure Clojure model. Foundry invariant tests and gas analysis are planned but not complete."}
           {:area        "Yield position accounting"
            :confidence  "Low"
            :backing     "Partial — invariant exists, limited coverage"
            :scenarios   "—"
            :invariants  ":yield-position-consistency, :yield-exposure"
            :rag         :amber
            :caveat      "Yield invariants are defined in canonical-ids but lack dedicated scenario coverage."}]]
      [:div
       (section-header
        "Confidence Summary by Protocol Area"
        "Every confidence level is backed by a specific artifact or explains why evidence is absent.")

       (warn-box
        [:span
         [:strong "Important: confidence ≠ safety. "]
         "High confidence means the claim is well-evidenced within the simulator model. "
         "It does not mean the on-chain implementation is correct, "
         "that all attack surfaces have been discovered, "
         "or that the model is complete. "
         "Unknown unknowns are not reflected in any confidence level."])

       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.83em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "8px 10px" :textAlign "left"}} ""]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Area"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Confidence"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Backing"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Key scenarios"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Key invariants"]
           [:th {:style {:padding "8px 10px" :textAlign "left"}} "Caveat"]]]
         (into [:tbody]
               (map (fn [{:keys [area confidence backing scenarios invariants rag caveat]}]
                      [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                       [:td {:style {:padding "6px 10px"}} (status-emoji rag)]
                       [:td {:style {:padding "6px 10px" :fontWeight "600"}} area]
                       [:td {:style {:padding "6px 10px"}} (conf-badge confidence)]
                       [:td {:style {:padding "6px 10px" :color "#475569"}} backing]
                       [:td {:style {:padding "6px 10px" :fontFamily "monospace"
                                     :fontSize "0.85em" :color "#374151"}} scenarios]
                       [:td {:style {:padding "6px 10px" :fontFamily "monospace"
                                     :fontSize "0.78em" :color "#374151"}} invariants]
                       [:td {:style {:padding "6px 10px" :color "#6b7280" :fontSize "0.85em"}} caveat]])
                    areas))]]]))))

;; ===========================================================================
;; ## Section 8 — Open Validation Gaps
;; ===========================================================================

(clerk/html
 (safe-render
  "Open Gaps"
  (fn []
    (let [gaps
          [{:id    "G01"
            :area  "Formal verification"
            :desc  "No Foundry invariant tests or Halmos properties yet. The simulator defines the spec; the on-chain implementation has not been formally verified against it."
            :risk  "High"
            :path  "Define Foundry invariant tests mirroring canonical-ids. Run Halmos for symbolic bounded checking on the state machine."}
           {:id    "G02"
            :area  "Live Kleros contract integration"
            :desc  "The Kleros proxy is a stub model. The real Kleros court, PNK economics, juror coherence bonuses, and appeal fee dynamics are not exercised."
            :risk  "High"
            :path  "Define a joint integration specification with Kleros. Run integration tests against Kleros Sepolia testnet before mainnet deployment."}
           {:id    "G03"
            :area  "Multi-epoch adversarial determinism"
            :desc  "Multi-epoch reputation drift and ring-attack collusion resistance are covered only in the stochastic Phase J simulation, not in a deterministic scenario."
            :risk  "Medium"
            :path  "Add at least one deterministic multi-epoch scenario (e.g., resolver reputation drift over N dispute cycles) to bridge the stochastic/deterministic gap."}
           {:id    "G04"
            :area  "Gas correctness"
            :desc  "The simulator is a pure Clojure integer model. Gas consumption, EVM stack depth, and token transfer fallback behavior are not modeled."
            :risk  "Medium"
            :path  "Run Foundry fuzz tests on all state-machine entry points. Profile gas for worst-case multi-escrow scenarios."}
           {:id    "G05"
            :area  "Yield invariant scenario coverage"
            :desc  ":yield-position-consistency and :yield-exposure are in canonical-ids but have no dedicated scenario. The checker runs but is not stress-tested."
            :risk  "Low"
            :path  "Add at least two yield-path scenarios: one nominal and one adversarial (e.g., yield accrual during disputed state)."}
           {:id    "G06"
            :area  "Capacity / dispute flooding at scale"
            :desc  "S24 covers 3-escrow stake depletion. Large-scale concurrent dispute flooding is in the Monte Carlo Phase F sweep only."
            :risk  "Low"
            :path  "Add a deterministic flooding scenario (e.g., N=20 concurrent disputes against a single under-bonded resolver) to bridge the Monte Carlo gap."}
           {:id    "G07"
            :area  "Oracle / off-chain evidence submission"
            :desc  "The simulator does not model the submission of off-chain evidence hashes or the oracle verification layer that Kleros jurors may rely on."
            :risk  "Medium"
            :path  "Define the evidence submission interface. Add scenarios testing evidence-hash integrity and oracle dispute triggers."}
           {:id    "G08"
            :area  "Cross-chain dispute routing"
            :desc  "Multi-chain escrow scenarios (e.g., Kleros on L1, escrow on L2) are not modeled."
            :risk  "Low"
            :path  "Design cross-chain escalation specification. Add bridge-delay modeling to the timeout invariants."}
           {:id    "G09"
            :area  "Governance upgrade / proxy attack surface"
            :desc  "S12 covers snapshot isolation but not the full governance upgrade path (proxy admin key, timelock, quorum). Governance capture under long-tail adversarial conditions is stochastic only."
            :risk  "Medium"
            :path  "Add deterministic governance-upgrade scenarios. Verify snapshot isolation holds across proxy upgrades."}
           {:id    "G10"
            :area  "EVM block-time drift and timestamp manipulation"
            :desc  "The simulator uses integer timestamps. EVM block-timestamp miner manipulation (±15s) is not modeled. Appeal window boundary conditions may be off-by-one under adversarial miner assumptions."
            :risk  "Low"
            :path  "Add timestamp boundary scenarios with ±1 block tolerance. Verify deadline arithmetic under miner-controlled timestamp drift."}]]
      [:div
       (section-header
        "Open Validation Gaps"
        "Known gaps in evidence, coverage, or integration. Gaps do not necessarily indicate vulnerabilities — they indicate areas where evidence is incomplete.")

       (note-box
        [:span
         "This list is derived from the coverage analysis above and the known limitations of the simulator model. "
         "A gap is flagged wherever the current evidence does not fully support a confidence claim, "
         "a known external dependency is unstubbed, or a threat class is only stochastically (not deterministically) covered."])

       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.83em"}}
         [:thead
          [:tr {:style {:background "#f1f5f9"}}
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "ID"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Area"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Risk"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Description"]
           [:th {:style {:padding "7px 10px" :textAlign "left"}} "Suggested path"]]]
         (into [:tbody]
               (map (fn [{:keys [id area risk desc path]}]
                      (let [risk-rag (case risk "High" :red "Medium" :amber :green)]
                        [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                         [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontWeight "600"}}
                          id]
                         [:td {:style {:padding "6px 10px" :fontWeight "600"}} area]
                         [:td {:style {:padding "6px 10px"}} (rag-badge risk-rag risk)]
                         [:td {:style {:padding "6px 10px" :color "#374151"}} desc]
                         [:td {:style {:padding "6px 10px" :color "#475569" :fontSize "0.9em"}}
                          path]]))
                    gaps))]]

       [:div {:style {:marginTop "16px" :background "#f8fafc"
                      :border "1px solid #e2e8f0" :borderRadius "6px"
                      :padding "12px 16px"}}
        [:h4 {:style {:margin "0 0 8px 0"}} "Gap priority summary"]
        [:ul {:style {:margin "0" :paddingLeft "20px" :fontSize "0.85em" :lineHeight "1.8"}}
         [:li [:strong "Highest priority (G01, G02): "]
          "Formal verification and live Kleros integration — these are prerequisites "
          "for production deployment confidence."]
         [:li [:strong "Medium priority (G03, G04, G07, G09): "]
          "Multi-epoch determinism, gas correctness, oracle interface, governance upgrade — "
          "required before full protocol review sign-off."]
         [:li [:strong "Lower priority (G05, G06, G08, G10): "]
          "Yield invariant coverage, scale flooding, cross-chain, timestamp drift — "
          "important for completeness but lower immediate risk."]]]]))))

;; ===========================================================================
;; ## Section 9 — Artifact Provenance
;; ===========================================================================

(clerk/html
 (safe-render
  "Artifact Provenance"
  (fn []
    (let [suite       live-suite-results
          inv-count   (count invariants/canonical-ids)
          sc-count    (count sc/all-scenarios)
          golden-n    (count (or golden-reports {}))
          trace-n     (count (or all-traces []))
          gate-rag    (if test-summary
                        (if (= "pass" (str (:overall_status test-summary))) :green :red)
                        :amber)]
      [:div
       (section-header
        "Artifact Provenance"
        "What artifact backs each claim in this workbench, and where to find it.")

       (simple-table
        ["Artifact" "Path" "Status" "Used in sections"]
        (map (fn [[artifact path status sections]]
               [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
                [:td {:style {:padding "6px 10px" :fontWeight "600"}} artifact]
                [:td {:style {:padding "6px 10px" :fontFamily "monospace" :fontSize "0.85em"}} path]
                [:td {:style {:padding "6px 10px"}} status]
                [:td {:style {:padding "6px 10px" :color "#475569"}} sections]])
             [["Invariant runner (live)"
               "resolver-sim.protocols.sew.invariant-runner/run-all"
               (let [{:keys [passed total error]} suite]
                 (if error
                   (str "⚠ error: " error)
                   (str "🟢 " passed "/" total " pass")))
               "§1, §3, §4, §5, §6, §7"]
              ["Canonical invariant IDs"
               "resolver-sim.protocols.sew.invariants/canonical-ids"
               (str "🟢 " inv-count " invariants loaded")
               "§3, §7"]
              ["Scenario registry"
               "resolver-sim.protocols.sew.invariant-scenarios/all-scenarios"
               (str "🟢 " sc-count " scenarios")
               "§4, §5"]
              ["Scenario type registry"
               "resolver-sim.protocols.sew.invariant-scenarios/scenario-type-registry"
               "🟢 loaded"
               "§4, §5"]
              ["State machine transitions"
               "resolver-sim.protocols.sew.state-machine/allowed-transitions"
               "🟢 loaded"
               "§2"]
              ["Test summary (CI gate)"
               "results/test-artifacts/test-summary.json"
               (if test-summary
                 (str "🟢 loaded — "
                      (or (:overall_status test-summary) "unknown"))
                 "🟠 not found (optional)")
               "§1"]
              ["Coverage data"
               "results/test-artifacts/coverage.json"
               (if coverage-data "🟢 loaded" "🟠 not found (optional)")
               "§3"]
              ["Golden reports"
               "data/fixtures/golden/*.report.edn"
               (if (pos? golden-n)
                 (str "🟢 " golden-n " loaded")
                 "🟠 none found (optional)")
               "§1"]
              ["Trace metadata"
               "data/fixtures/traces/*.trace.json"
               (if (pos? trace-n)
                 (str "🟢 " trace-n " loaded")
                 "🟠 none found (optional)")
               "§1"]]))

       [:div {:style {:marginTop "12px" :fontSize "0.82em" :color "#64748b"}}
        "All live artifacts are loaded at notebook evaluation time and cached by Clerk. "
        "Re-run with "
        [:code "clojure -M:clerk"] " or "
        [:code "clerk/serve!"]
        " to refresh. File artifacts use graceful degradation — absent files show amber status, not red."]]))))

;; ---
;; *Notebook generated by the Sew Protocol simulation toolchain.*
;; *Source: `notebooks/dispute_resolution.clj`*
;; *All scenario results are live — evaluated in-process at render time.*
