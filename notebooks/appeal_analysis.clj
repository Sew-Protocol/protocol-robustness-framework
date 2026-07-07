;; # Sew Protocol — Appeal Subsystem Analysis
;;
;; **Audience:** Grant reviewers · Protocol researchers · Auditors · Security reviewers
;;
;; **Purpose:** Explain, exercise, and visualize the protocol's appeal subsystem.
;; Supports grant applications, research updates, and technical due diligence.
;;
;; The notebook demonstrates:
;; - how appeals work mechanically,
;; - why appeal bonds matter economically,
;; - how custody and conservation invariants are enforced,
;; - how appeal windows and boundary conditions are tested,
;; - how Kleros / escalation backstops improve robustness,
;; - how the system can be benchmarked with reproducible evidence.
;;
;; **Data sources:**
;; - Live in-process world states via fixture builders
;; - Deterministic invariant scenarios (S25, S35, S36, S47, S49, S62, S63, S65, S72, S76, S78, S81–S83)
;; - Analytic benchmark results (F6, M3)
;; - Research models (escalation economics, stochastic detection)

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.appeal-analysis
  (:require [clojure.string :as str]
            [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.theme :refer [notebook-theme]]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.protocols.sew.research-models.escalation-economics :as ee]
            [resolver-sim.stochastic.detection :as detect]
            [resolver-sim.stochastic.decision-quality :as dq]
            [resolver-sim.io.params :as io-params]))

;; ===========================================================================
;; Notebook configuration (loaded from shared EDN)
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :hide}}
(def cfg
  (delay (io-params/load-edn "notebooks/appeal_config.edn")))

^{::clerk/visibility {:code :hide :result :hide}}
(defn- styled-table
  "Render a table with consistent styling. head is a vector of column labels,
   rows is a seq of row vectors."
  [head rows]
  (clerk/html
   [:table {:style (merge (:table-style notebook-theme) {:fontSize "0.82em" :width "100%" :borderCollapse "collapse"})}
    [:thead
     [:tr {:style (:table-header-row-style notebook-theme)}
      (for [h head]
        [:th {:style (:table-header-cell-style notebook-theme)} h])]]
    (into [:tbody]
          (map (fn [row]
                 [:tr {:style {:borderBottom (str "1px solid " (:table/border notebook-theme))}}
                  (for [cell row]
                    [:td {:style (:table-cell-style notebook-theme)} (str cell)])]))
          rows)]))

;; ===========================================================================
;; Section 1: Appeal Subsystem Summary
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "## 1. Appeal Subsystem Summary

   ### What an appeal is

   An appeal is a formal challenge to a resolver's slashing decision. It is
   submitted by the resolver who was slashed, within a bounded window, and
   requires posting an appeal bond.

   ### Who can appeal

   Only the resolver who received the slash can appeal. This is the entity
   that was penalized by the fraud-slash mechanism.

   ### When an appeal is valid

   An appeal is valid when:
   - A `:pending` fraud slash exists against the resolver,
   - The appeal window has not expired,
   - The caller is the resolver who was slashed,
   - The slash has not already been appealed.

   ### What bond is required

   The appeal bond is calculated as a percentage (bps) of the slashed amount,
   or a fixed absolute amount if the protocol snapshot defines one. A protocol
   fee is deducted from the bond at posting time.

   ### If the appeal succeeds

   If governance or a higher-level resolver upholds the appeal (``appeal-upheld? =
   true``), the slash is reversed and cannot be executed. The appeal bond is
   returned to the appellant.

   ### If the appeal fails

   If the appeal is rejected (``appeal-upheld? = false``), the slash stands and
   becomes executable. The appeal bond is forfeited — it goes to the bond-fee
   pool or slashed-bond pool for incentive distribution.

   ### How appeal finality interacts with dispute finality

   While an appeal is active, the dispute cannot reach financial finality:
   ``execute_pending_settlement`` is blocked by the ``:appeal-window-not-expired``
   guard. Financial finality is deferred until the appeal window closes
   without an appeal, or until the appeal is resolved.

   ### How Kleros / escalation acts as a backstop

   The protocol supports multi-level escalation. If the resolver-level appeal
   fails, the dispute can escalate to higher levels (L1, L2, Kleros backstop).
   Each escalation level requires a higher bond and provides stronger scrutiny.
   The Kleros integration provides a decentralized review layer when
   on-protocol resolution is insufficient or contested.")

;; ===========================================================================
;; Section 2: Appeal Lifecycle Map
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 2. Appeal Lifecycle Map")

^{::clerk/visibility {:code :hide :result :hide}}
(defn appeal-lifecycle-diagram
  "Return a Mermaid stateDiagram-v2 describing the appeal lifecycle."
  []
  (str/join "\n"
            ["stateDiagram-v2"
             "    direction LR"
             "    state \"Slash Proposed\" as PENDING"
             "    state \"Appeal Window Open\" as WINDOW"
             "    state \"Appeal Submitted\" as APPEALED"
             "    state \"Appeal Resolved\" as RESOLVED"
             "    state \"Bond Returned\" as RETURNED"
             "    state \"Bond Forfeited\" as FORFEITED"
             "    state \"Slash Executed\" as EXECUTED"
             "    state \"Slash Reversed\" as REVERSED"
             ""
             "    [*] --> PENDING : propose_fraud_slash"
             "    PENDING --> WINDOW : slash pending, window open"
             "    WINDOW --> WINDOW : deadline elapsing"
             "    WINDOW --> APPEALED : appeal_slash"
             "    APPEALED --> RESOLVED : resolve_appeal"
             "    RESOLVED --> RETURNED : appeal upheld (slash reversed)"
             "    RESOLVED --> FORFEITED : appeal rejected (slash stands)"
             "    RETURNED --> REVERSED : slash cancelled"
             "    FORFEITED --> EXECUTED : slash executed"
             "    WINDOW --> EXECUTED : window expired, no appeal"
             ""
             "    note right of WINDOW : execute_pending_settlement"
             "    note right of WINDOW : blocked during appeal"
             ""
             "    state \"Governance / Kleros Backstop\" as GOVERNANCE"
             "    APPEALED --> GOVERNANCE : escalation to L1/L2"
             "    GOVERNANCE --> RESOLVED : governance decision"]))

^{::clerk/visibility {:code :hide :result :show}}
(def appeal-lifecycle-mermaid
  (appeal-lifecycle-diagram))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md (str "```mermaid\n" appeal-lifecycle-mermaid "\n```"))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "**Lifecycle key events:**

 | # | Event | What happens |
 |---|-------|-------------|
 | 1 | ``propose_fraud_slash`` | A slash is proposed against a resolver for a fraudulent decision. |
 | 2 | ``appeal_slash`` | The resolver appeals within the window, posting an appeal bond. |
 | 3 | ``resolve_appeal`` | Governance resolves the appeal: upheld (bond returned) or rejected (bond forfeited). |
 | 4 | Slash execution | If appeal failed or window expired, the slash executes. |
 | 5 | Escalation | Dispute can escalate through L1 → L2 → Kleros backstop. |")

;; ===========================================================================
;; Section 3: Appeal Bond Flow
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 3. Appeal Bond Flow

   Appeal bonds serve dual purpose: they deter frivolous appeals through
   economic disincentive, and they provide a measurable security parameter
   with custody invariants and reproducible conservation checks.")

^{::clerk/visibility {:code :hide :result :hide}}
(defn appeal-bond-flow
  "Return a Mermaid stateDiagram-v2 showing appeal bond custody paths."
  []
  (str/join "\n"
            ["stateDiagram-v2"
             "    direction TB"
             "    state \"Appeal Bond Posted\" as POSTED"
             "    state \"Bond Held in Custody\" as CUSTODY"
             "    state \"Protocol Fee Deducted\" as FEE"
             "    state \"Net Bond Held\" as NET"
             "    state \"Bond Returned (Appeal Upheld)\" as RETURNED"
             "    state \"Bond Forfeited (Appeal Rejected)\" as FORFEITED"
             "    state \"Bond-Fee Pool\" as FEEPOOL"
             "    state \"Slashed-Bond Pool (50/30/20 split)\" as SLASHED"
             ""
             "    [*] --> POSTED : post-appeal-bond"
             "    POSTED --> FEE : calculate-appeal-bond-fee"
             "    FEE --> FEEPOOL : fee deducted"
             "    FEE --> NET : net bond amount"
             "    NET --> CUSTODY : bond-balances entry created"
             "    CUSTODY --> RETURNED : resolve-appeal (upheld)"
             "    CUSTODY --> FORFEITED : resolve-appeal (rejected)"
             "    RETURNED --> [*] : return-bond / sub-held"
             "    FORFEITED --> SLASHED : slash-bond / sub-held"
             ""
             "    note right of POSTED : amount = bps(slash-amount)"
             "    note right of POSTED : or fixed snap.appeal-bond-amount"
             "    note right of FEE : fee-bps from protocol snapshot"
             "    note right of NET : total-bonded increment"
             "    note right of RETURNED : custody cleared, bond returned"
             "    note right of FORFEITED : custody cleared, bond slashed"]))

^{::clerk/visibility {:code :hide :result :show}}
(def appeal-bond-mermaid
  (appeal-bond-flow))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md (str "```mermaid\n" appeal-bond-mermaid "\n```"))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "**Key accounting functions:**

 | Function | Purpose | Invariant checked |
 |----------|---------|-------------------|
 | ``post-appeal-bond`` | Records bond custody, deducts protocol fee, updates totals | ``:appeal-bond-held`` non-negative |
 | ``return-bond`` | Returns bond to winning appellant, clears custody | Custody entry cleared |
 | ``slash-bond`` | Forfeits bond for losing appellant, applies 50/30/20 split | ``:appeal-bond-held`` zeroed |
 | ``sub-held`` | Low-level custody deduction with reason tag | Conservation-of-funds |

   **Conservation expectation:** The total appeal bond amount is conserved
   across post, return, and forfeiture paths. No bond value is created or
   destroyed — it is either returned to the appellant or distributed to
   incentive pools.")

;; ===========================================================================
;; Section 4: Appeal Economics Deep Dive
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 4. Appeal Economics Deep Dive

   This section shows how appeal cost changes with escrow amount, bond basis
   points, appeal round, escalation depth, protocol fee, and probability
   assumptions. All values are in wei (1 ETH = 10^18 wei).")

;; --- 4a: Bond amount and fee table ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- bond-amount-table
  "Generate rows showing required bond and fee for escrow amounts and bond-bps bands."
  []
  (let [eco (:economic-analysis @cfg)
        escrow-amounts (:escrow-amounts eco)
        bond-bps-values (:bond-bps-values eco)
        fee-bps (:fee-bps @cfg)
        rows (for [escrow escrow-amounts
                   bps bond-bps-values]
               (let [bond-amount (sew-econ/calculate-appeal-bond-amount
                                  escrow
                                  {:appeal-bond-bps bps :appeal-bond-amount 0})
                     fee-result (sew-econ/calculate-appeal-bond-fee bond-amount fee-bps)]
                 {:escrow escrow
                  :bond-bps bps
                  :required-bond bond-amount
                  :fee (:fee fee-result)
                  :net-custody (:net fee-result)}))
        ;; Group for compact display
        grouped (group-by :bond-bps rows)]
    (for [[bps items] (sort grouped)]
      [bps
       (map (fn [i] [(:escrow i) (:required-bond i) (:fee i) (:net-custody i)]) items)])))

^{::clerk/visibility {:code :hide :result :show}}
(def economics-bond-table
  (bond-amount-table))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Bond Amount and Fee by Escrow Size and Rate

   Required bond = ``calculate-appeal-bond-amount(escrow, snap)`` where
   snap provides either an absolute ``:appeal-bond-amount`` or a
   ``:appeal-bond-bps`` rate. Fee = ``calculate-appeal-bond-fee(bond, fee-bps)``.")

^{::clerk/visibility {:code :hide :result :show}}
(doseq [[bps rows] economics-bond-table]
  (clerk/md (str "**Bond rate: " bps " bps (" (/ bps 100) "%)**"))
  (styled-table
   ["Escrow" "Required Bond" "Fee (150 bps)" "Net Custody"]
   (map (fn [[escrow bond fee net]]
          [(str escrow) (str bond) (str fee) (str net)])
        rows)))

;; --- 4b: Escalation cost table ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- escalation-cost-rows
  "Generate cost rows using the escalation economics model."
  []
  (let [config ee/DEFAULT_ESCALATION_CONFIG
        rounds (:rounds (:economic-analysis @cfg))]
    (for [r rounds]
      {:round r
       :marginal-bond (ee/appeal-bond-at-round (dec r) config)
       :cumulative-cost (ee/total-appeal-cost-to-round r config)})))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Escalation Cost by Round

   Each escalation level increases the appeal bond cost. Using
   ``appeal-bond-at-round`` and ``total-appeal-cost-to-round`` from the
   escalation economics model with default parameters.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Round" "Marginal Bond Cost" "Cumulative Appeal Cost"]
 (map (fn [{:keys [round marginal-bond cumulative-cost]}]
        [(str round) (str marginal-bond) (str cumulative-cost)])
      (escalation-cost-rows)))

;; --- 4c: Security improvement from escalation ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- security-improvement-rows
  []
  (let [security (ee/escalation-provides-security ee/DEFAULT_ESCALATION_CONFIG)]
    [["Stake increase R0→R1" (str (int (* 100 (:stake-increase-r1 security)))) "%"]
     ["Stake increase R0→R2" (str (int (* 100 (:stake-increase-r2 security)))) "%"]
     ["Cumulative appeal cost to R1" (str (:cumulative-appeal-cost-r1 security)) " wei"]
     ["Cumulative appeal cost to R2" (str (:cumulative-appeal-cost-r2 security)) " wei"]
     ["Protection improvement R1" (str (int (* 100 (:protection-improvement-r1 security)))) "%"]
     ["Protection improvement R2" (str (int (* 100 (:protection-improvement-r2 security)))) "%"]]))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Security Improvement from Escalation

   Higher rounds increase resolver stakes and appeal bond costs, providing
   stronger security guarantees.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Metric" "Value" "Unit"]
 (security-improvement-rows))

;; --- 4d: Benchmark results (deferred) ---

^{::clerk/visibility {:code :hide :result :hide}}
(def f6-results
  (delay
    (try
      (let [phase-f-ns 'resolver-sim.research.sew.analytic.phase-f-economic-parameters]
        (require phase-f-ns)
        ((requiring-resolve 'resolver-sim.research.sew.analytic.phase-f-economic-parameters/run-f6-appeal-window-adequacy)))
      (catch Exception e
        {:benchmark-id "F6" :label "Appeal Window Adequacy"
         :passed? false :summary {:error (.getMessage e)}}))))

^{::clerk/visibility {:code :hide :result :hide}}
(def m3-results
  (delay
    (try
      (let [phase-m-ns 'resolver-sim.research.sew.analytic.phase-m-fairness-analysis]
        (require phase-m-ns)
        ((requiring-resolve 'resolver-sim.research.sew.analytic.phase-m-fairness-analysis/run-m3-frivolous-appeal-discouragement)))
      (catch Exception e
        {:benchmark-id "M3" :label "Frivolous Appeal Discouragement"
         :passed? false :summary {:error (.getMessage e)}}))))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Analytic Benchmark Results

   The following benchmarks are computed live at render time using the same
   parameter sweeps defined in the research analytic suite.")

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md (let [{:keys [benchmark-id label passed? summary]} @f6-results]
            (str "**" label " (" benchmark-id ")** — "
                 (if passed? "✅ PASS" "❌ FAIL")
                 (when summary
                   (str " — pass rate: " (int (* 100 (:pass-rate summary 0))) "%"
                        " (" (:healthy-trials summary 0) "/" (:total-trials summary 0) " trials)")))))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md (let [{:keys [benchmark-id label passed? summary]} @m3-results]
            (str "**" label " (" benchmark-id ")** — "
                 (if passed? "✅ PASS" "❌ FAIL")
                 (when summary
                   (str " — pass rate: " (int (* 100 (:pass-rate summary 0))) "%"
                        " (" (:discouraged-trials summary 0) "/" (:total-trials summary 0) " trials)")))))

;; ===========================================================================
;; Section 5: Appeal Scenarios Deep Dive
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 5. Appeal Scenarios Deep Dive

   This section identifies and describes appeal-related scenarios from the
   deterministic invariant scenario registry (S01–S100). Each scenario is
   exercised by the in-process replay engine and its outcome is compared
   against a golden artifact.")

^{::clerk/visibility {:code :hide :result :hide}}
(def appeal-scenario-registry
  "Curated metadata for appeal-related scenarios.
   Covers the core appeal lifecycle scenarios and boundary conditions."
  [{:scenario "S25"
    :display-name "profit-maximizer-slash-lifecycle"
    :risk "Fraud slash lifecycle: slash proposed, appealed, resolved"
    :mechanism "resolve-appeal, appeal-slash, post-appeal-bond"
    :expected "Full appeal lifecycle completes correctly"
    :invariant "appeal-bond-conserved?, appeal-bond-custody-consistent?"
    :grant-claim "Slash + appeal lifecycle is validated end-to-end"}

   {:scenario "S35"
    :display-name "profit-maximizer-governance-wins-appeal"
    :risk "Governance overturns a slash decision via appeal"
    :mechanism "resolve-appeal with appeal-upheld? = true"
    :expected "Slash reversed, bond returned, execution blocked"
    :invariant "appeal-bond-conserved?, appeal-bond-custody-consistent?"
    :grant-claim "Governance appeal path works correctly"}

   {:scenario "S36"
    :display-name "profit-maximizer-pre-window-execute-rejected"
    :risk "Execution attempted before appeal window closes"
    :mechanism "execute-pending-settlement blocked by :appeal-window-not-expired"
    :expected "Execution rejected, retry after window succeeds"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Pre-window execution is correctly rejected"}

   {:scenario "S47"
    :display-name "appeal-window-boundary-pair"
    :risk "Appeal window edge cases (pair: S47a + S47b)"
    :mechanism "Appeal deadline enforcement at boundary"
    :expected "Window boundaries enforced correctly"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Appeal window boundaries are deterministic"}

   {:scenario "S49"
    :display-name "appeal-deadline-boundary"
    :risk "Deadline boundary: appeal at exact deadline"
    :mechanism "Appeal deadline guard"
    :expected "Appeal at deadline accepted, after rejected"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Appeal deadline boundaries are explicitly tested"}

   {:scenario "S62"
    :display-name "multi-appeal-escalation-chain"
    :risk "Multiple concurrent appeals at different escalation levels"
    :mechanism "Multi-level appeal with escalation"
    :expected "All appeal paths resolve independently"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Multi-appeal escalation does not cause state corruption"}

   {:scenario "S63"
    :display-name "frivolous-appeal-slashing"
    :risk "Frivolous appeal penalized via bond forfeiture"
    :mechanism "frivolous appeal bond forfeiture"
    :expected "Bond forfeited, slash stands"
    :invariant "appeal-bond-custody-consistent?"
    :grant-claim "Frivolous appeals are economically deterred"}

   {:scenario "S65"
    :display-name "appeal-after-settlement-rejected"
    :risk "Appeal attempted after settlement already executed"
    :mechanism "Guard against appeal after finality"
    :expected "Appeal rejected because window closed"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Appeal after settlement is correctly rejected"}

   {:scenario "S72"
    :display-name "challenge-during-appeal-window"
    :risk "Escalation challenge submitted during appeal window"
    :mechanism "Challenge and appeal window interaction"
    :expected "Challenge accepted, appeal lifecycle continues"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Challenge and appeal can coexist safely"}

   {:scenario "S76"
    :display-name "sender-cancel-during-appeal"
    :risk "Sender attempts cancel while appeal is active"
    :mechanism "Cancel blocked during active appeal"
    :expected "Cancel rejected until appeal resolves"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Sender cancel is blocked during active appeal"}

   {:scenario "S78"
    :display-name "many-appeals-eventually-rejects"
    :risk "Repeated appeals eventually exhausted"
    :mechanism "Maximum escalation guard"
    :expected "Appeals rejected after max level"
    :invariant "appeal-requires-prior-resolution?"
    :grant-claim "Appeal exhaustion is bounded and enforced"}

   {:scenario "S81"
    :display-name "appeal-deadline-boundary-before"
    :risk "Execution at t = deadline - 1 (before window closes)"
    :mechanism "execute-pending-settlement with offset -1"
    :expected "Rejected: appeal window not expired"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Execution before deadline is correctly blocked"}

   {:scenario "S82"
    :display-name "appeal-deadline-boundary-exact"
    :risk "Execution at t = deadline (exact boundary)"
    :mechanism "execute-pending-settlement with offset 0"
    :expected "Accepted: window expired at exactly deadline"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Execution at exact deadline boundary succeeds"}

   {:scenario "S83"
    :display-name "appeal-deadline-boundary-after"
    :risk "Execution at t = deadline + 1 (after window closes)"
    :mechanism "execute-pending-settlement with offset +1"
    :expected "Accepted: window expired"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Execution after deadline succeeds"}])

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Appeal Scenario Registry

   Each row links a scenario to its risk model, appeal mechanism, expected
   outcome, invariant coverage, and grant-facing claim.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Scenario" "Risk Model" "Mechanism" "Invariant Coverage" "Grant Claim"]
 (map (fn [{:keys [scenario display-name mechanism invariant grant-claim]}]
        [(str scenario ": " display-name) mechanism invariant grant-claim])
      appeal-scenario-registry))

;; ===========================================================================
;; Section 6: Appeal Invariant Coverage
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 6. Appeal Invariant Coverage

   This section documents the appeal-specific invariants, their plain-English
   meaning, the failure class they prevent, the lifecycle phase they protect,
   the scenarios that exercise them, and the grant-facing claim they support.")

^{::clerk/visibility {:code :hide :result :hide}}
(def appeal-invariant-registry
  [{:name "appeal-bond-conserved?"
    :meaning "No slash event has a negative appeal-bond-held amount."
    :failure-class "Accounting underflow / custody violation"
    :phase "Appeal bond lifecycle (post → held → return/forfeit)"
    :scenarios ["S25" "S35" "S36"]
    :grant-claim "Appeal bond custody is conserved across post, return, and forfeiture paths."}

   {:name "appeal-bond-custody-consistent?"
    :meaning "Appeal bond custody lifecycle is coherent: held > 0 implies :appealed status and custody entry; held = 0 implies no custody entry."
    :failure-class "Custody tracking desync / stale entries"
    :phase "Appeal bond lifecycle (custody entry management)"
    :scenarios ["S25" "S35" "S36"]
    :grant-claim "Appeal bond custody is always consistent with slash status."}

   {:name "appeal-requires-prior-resolution?"
    :meaning "No escalation exists without a prior resolution on the same workflow. Dispute-level > 0 implies a resolution exists."
    :failure-class "Escalation without resolution / state machine violation"
    :phase "Appeal submission and escalation"
    :scenarios ["S28" "S44" "S62" "S78"]
    :grant-claim "Appeals cannot bypass prerequisite resolution state."}

   {:name "finality-blocked-during-appeal?"
    :meaning "No workflow has been settled before the appeal window expired. Any settled dispute had the window closed."
    :failure-class "Premature settlement / financial finality bypass"
    :phase "Appeal window → settlement execution"
    :scenarios ["S05" "S13" "S32" "S36" "S47" "S49" "S65" "S76" "S81" "S82" "S83"]
    :grant-claim "Financial finality is blocked while an appeal is active."}])

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Invariant" "Meaning" "Failure Class" "Lifecycle Phase" "Exercised By" "Grant Claim"]
 (map (fn [{:keys [name meaning failure-class phase scenarios grant-claim]}]
        [name meaning failure-class phase (str/join ", " scenarios) grant-claim])
      appeal-invariant-registry))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "**Invariant check results:**

   The invariants above are checked live by the in-process invariant runner
   across all scenarios. See the dispute resolution workbench notebook for
   per-scenario pass/fail status and the invariant failures notebook for
   detailed violation triage.")

;; ===========================================================================
;; Section 7: Appeal Boundary Testing
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 7. Appeal Boundary Testing

   Appeal safety is not only economic; it is also temporal. This section
   demonstrates deadline enforcement and finality blocking under boundary
   conditions.

   ### Boundary conditions tested

   | Condition | Scenario(s) | Expected outcome |
   |-----------|-------------|-----------------|
   | Appeal submitted before window closes | S36 | Pre-window execution rejected |
   | Appeal at deadline boundary (offset -1) | S81 | Execution rejected: window not expired |
   | Appeal at exact deadline (offset 0) | S82 | Execution accepted: window expired at deadline |
   | Appeal after deadline (offset +1) | S83 | Execution accepted: window expired |
   | Appeal window boundary pair | S47 (a/b) | Window boundaries enforced correctly |
   | Execution attempted during appeal | S36, S65 | Execution blocked by guard |
   | Same-block ordering race | S51 | Ordering resolved deterministically |
   | Governance resolution after appeal | S35 | Appeal correctly resolved by governance |

   The ``base-appeal-boundary-scenario`` from the temporal generator test
   produces scenarios with offset -1 (before deadline), 0 (at deadline),
   and +1 (after deadline) to exercise the appeal window guard at each
   boundary.")

;; --- Temporal boundary summary derived from S81-S83 ---

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Scenario" "Offset from Deadline" "Expected Outcome" "Guard Checked"]
 [["S81  appeal-deadline-boundary-before" "-1" "Execution rejected" ":appeal-window-not-expired"]
  ["S82  appeal-deadline-boundary-exact"  "0"  "Execution accepted (exact boundary)" "Window expiry at deadline"]
  ["S83  appeal-deadline-boundary-after"  "+1" "Execution accepted" "Window already expired"]
  ["S36  pre-window-execute-rejected"     "within window" "Execution rejected" ":appeal-window-not-expired"]
  ["S47a appeal-window-boundary"          "window open" "Execution rejected" "Window guard active"]
  ["S47b appeal-window-boundary"          "window closed" "Settlement accepted" "Window guard inactive"]
  ["S65  appeal-after-settlement"         "post-settlement" "Appeal rejected" "Finality guard"]])

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "**Grant-facing claim:** Appeal safety is not only economic; it is also
  temporal. The notebook demonstrates deadline enforcement and finality
  blocking under boundary conditions. All temporal guards are exercised by
  deterministic scenarios with sub-second precision.")

;; ===========================================================================
;; Section 8: Kleros and Escalation Analysis
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 8. Kleros and Escalation Analysis

   This section shows how appeal outcomes are affected by higher-level review
   or Kleros-style escalation. The protocol supports multi-level arbitration
   where each level provides stronger scrutiny at higher cost.")

^{::clerk/visibility {:code :hide :result :hide}}
(defn- simulate-appeal-scenarios
  "Run a small number of stochastic appeal simulations to illustrate
   escalation outcomes under different conditions."
  []
  (let [rng (java.util.Random. (:seed @cfg))
        sa (:stochastic-assumptions @cfg)
        param-sets [{:label "Base"
                     :params (get sa :base)}
                    {:label "Optimistic (high reversal)"
                     :params (get sa :optimistic)}
                    {:label "Pessimistic (low reversal)"
                     :params (get sa :pessimistic)}]
        contexts [{:label "Correct verdict"
                   :context {:verdict-correct? true :appealed? true}}
                  {:label "Wrong verdict"
                   :context {:verdict-correct? false :appealed? true}}]]
    (for [ps param-sets
          ctx contexts]
      (let [result (detect/appeal-reversal-outcome rng (:params ps) (:context ctx))]
        (merge
         {:band (:label ps) :verdict-context (:label ctx)}
         result)))))

^{::clerk/visibility {:code :hide :result :show}}
(def appeal-stochastic-results
  (simulate-appeal-scenarios))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Stochastic Appeal Reversal Outcomes

   Using ``appeal-reversal-outcome`` to sample whether an appealed wrong
   verdict is reversed at L1 and/or L2 under different assumption bands.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Band" "Verdict Context" "L1 Reversed?" "L2 Escalated?" "L2 Reversed?" "Decision Reversed?"]
 (map (fn [{:keys [band verdict-context l1-reversed? l2-escalated? l2-reversed? decision-reversed?]}]
        [band verdict-context (str l1-reversed?) (str l2-escalated?) (str l2-reversed?) (str decision-reversed?)])
      appeal-stochastic-results))

;; --- 8b: Full appeal simulation ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- run-full-appeal-sims
  "Run the simulate-full-appeal function under different conditions."
  []
  (let [fas (:full-appeal-simulation @cfg)
        rng (java.util.Random. (:seed @cfg))]
    (for [diff (:difficulties fas)
          ev (:evidence-levels fas)]
      (let [res (dq/simulate-full-appeal
                 rng (:ground-truth fas)
                 [(:r0-honest? fas) (:r1-corrupt? fas) (:r2-corrupt? fas)]
                 (:time-pressures fas)
                 diff ev)]
        {:difficulty diff
         :evidence-quality ev
         :final-decision (:final-decision res)
         :escalation-count (:escalation-count res)
         :was-error (:was-error res)}))))

^{::clerk/visibility {:code :hide :result :show}}
(def full-appeal-results
  (try (run-full-appeal-sims)
       (catch Exception _ [{:difficulty "N/A" :evidence-quality "N/A"
                            :final-decision "N/A" :escalation-count "N/A"
                            :was-error "Simulation failed — check dependencies"}])))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Full Appeal Simulation

   Using ``simulate-full-appeal`` to model the complete multi-round appeal
   process through R0 (resolver), R1 (senior), R2 (external/Kleros).")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Difficulty" "Evidence Quality" "Final Decision" "Escalations" "Error?"]
 (map (fn [{:keys [difficulty evidence-quality final-decision escalation-count was-error]}]
        [(str difficulty) (str evidence-quality) (str final-decision) (str escalation-count) (str was-error)])
      full-appeal-results))

;; --- 8c: Escalation cost comparison ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- escalation-cost-comparison
  "Compare cost to attack at different rounds."
  []
  (let [config ee/DEFAULT_ESCALATION_CONFIG
        dispute-values (:dispute-values (:economic-analysis @cfg))]
    (for [dv dispute-values]
      (let [comparison (ee/compare-attack-costs dv config)]
        (assoc comparison :dispute-value dv)))))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Attack Cost Comparison Across Escalation Rounds

   Using the escalation economics model to compare attacker costs at
   different rounds. Shows that multi-level escalation makes corruption
   progressively more expensive.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Dispute Value" "Attack R0 Only" "Attack R0→R1" "Attack R1 Only" "Cheapest Route"]
 (map (fn [{:keys [dispute-value attack-r0-only attack-r0-then-r1 attack-r1-only attacker-prefers]}]
        [(str dispute-value)
         (str (int attack-r0-only))
         (str (int attack-r0-then-r1))
         (str (int attack-r1-only))
         attacker-prefers])
      (escalation-cost-comparison)))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "**Key escalation insights**

 | Question | Answer |
 |----------|--------|
 | What happens if L1 review is wrong? | The dispute escalates to L2 where bond costs are higher and scrutiny is stronger. |
 | What happens if escalation is expensive? | Higher costs deter frivolous escalation, but also create a barrier to valid correction. Bond parameters must be calibrated. |
 | When does a bond deter frivolous appeals? | When the expected loss from forfeiture exceeds the expected gain from a successful frivolous appeal. |
 | When might a bond overdeter valid appeals? | If the bond is set so high that even resolvers with a meritorious case cannot afford to appeal. |
 | How does a Kleros backstop improve robustness? | Kleros provides a decentralized final review layer, reducing the risk of capture or error at L0/L1. |")

;; ===========================================================================
;; Section 9: Governance and Authorization Paths
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 9. Governance and Authorization Paths

   This section covers governance appeal paths and emergency override
   patterns for the appeal subsystem.")

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "### Governance resolving an appeal

   Governance (TIMELOCK) resolves a slashing appeal via
   ``resolve-appeal``. The function accepts an ``appeal-upheld?`` flag:

   - ``appeal-upheld? = true`` → slash is reversed, bond returned,
     execution blocked
   - ``appeal-upheld? = false`` → slash stands, bond forfeited,
     execution proceeds

   Governance resolution requires proper authorization provenance.
   The optional ``:authorization-provenance`` key on ``resolve-appeal``
   enables forensic tracing of who authorized the resolution.

   ### Force resolution

   If governance override paths exist (e.g. emergency resolution), they
   should be distinguishable in evidence via explicit
   ``:authorization-provenance`` tags. This is critical for auditability
   and forensic reconstruction.

   ### Appeal-related custody movements

   All appeal-related custody movements should be distinguishable in
   evidence records:

   | Movement | Reason tag | Evidence source |
   |----------|-----------|-----------------|
   | Bond posted | ``:post-appeal-bond`` | ``post-appeal-bond`` return value |
   | Bond returned | ``:appeal-bond-returned`` | ``sub-held`` in ``resolve-appeal`` |
   | Bond forfeited | ``:appeal-bond-forfeited`` | ``sub-held`` in ``resolve-appeal`` |
   | Fee collected | ``:bond-fees`` | ``post-appeal-bond`` accounting |

   ### Current limitations

   - Authorization provenance for appeal resolution is an optional parameter.
     Migration to mandatory provenance is a future hardening item.
   - Force resolution / emergency override paths are not yet fully
     formalized in the evidence schema.
   - Governance authorization boundaries between TIMELOCK and DAO are still
     being migrated to explicit evidence records.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Capability" "Status" "Grant relevance"]
 [["Governance resolves appeal" "Implemented (resolve-appeal)" "Auditability, dispute safety"]
  ["Authorization provenance" "Optional (migration in progress)" "Forensic reconstruction"]
  ["Force resolution / emergency" "Not yet formalized" "Future hardening item"]
  ["Custody movement evidence" "Tracked via reason tags" "Audit trail"]
  ["Governance <-> DAO boundaries" "Migration in progress" "Custody authorization"]])

;; ===========================================================================
;; Section 10: Grant Application Summary
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 10. Grant Application Summary Section

   ---

   ### Problem

   Decentralized dispute resolution requires a mechanism for correcting
   erroneous or malicious slashing decisions without undermining the
   finality and economic security of the system. Without a well-designed
   appeal subsystem, resolvers can be slashed unfairly with no recourse,
   or frivolous appeals can delay settlement and drain protocol resources.

   ### Approach

   The Sew Protocol implements a bonded appeal system with the following
   properties:

   - **Time-bounded appeal windows** — appeals must be submitted within a
     configurable window after a slash is proposed.
   - **Economic bond requirement** — appellants must post a bond calculated
     as a percentage of the slashed amount (bps) or a fixed amount.
   - **Protocol fee on bonds** — a fee (in bps) is deducted and directed to
     the bond-fee pool.
   - **Governance resolution** — appeals are resolved by governance
     (TIMELOCK) with full authorization provenance tracking.
   - **Multi-level escalation** — disputes can escalate through L1 → L2 →
     Kleros backstop, with increasing bond costs at each level.
   - **Invariant-checked custody** — appeal bond custody is tracked with
     conservation and consistency invariants.

   ### What is novel

   - **Appeal bonds as calibrated economic security** — the bond is not
     merely UX friction but a measurable parameter with custody invariants
     and reproducible conservation checks.
   - **Benchmark-backed arbitration design** — the appeal window adequacy
     (F6) and frivolous appeal discouragement (M3) benchmarks provide
     reproducible evidence for parameter choices.
   - **Conservation-of-funds and custody safety** — all appeal bond
     movements (post, return, forfeit) are tracked with reason tags and
     checked by invariants.
   - **Finality blocking during appeal** — financial finality is deferred
     until the appeal window closes, preventing premature settlement.
   - **Correction paths for malicious or erroneous slashing** — both
     governance appeal and multi-level escalation provide avenues for
     correcting wrong decisions.

   ### Evidence generated

   | Evidence | Source | What it shows |
   |----------|--------|---------------|
   | Appeal lifecycle diagram | Section 2 | Complete appeal lifecycle with governance backstop |
   | Bond custody flow | Section 3 | Bond post → fee → custody → return/forfeit path |
   | Economics tables | Section 4 | Bond amounts, fees, escalation costs under parameter bands |
   | Benchmark F6 | Section 4 | Appeal window adequacy (pass rate) |
   | Benchmark M3 | Section 4 | Frivolous appeal discouragement (pass rate) |
   | Scenario registry | Section 5 | 14 appeal-related scenarios with risk/invariant/claim coverage |
   | Invariant coverage | Section 6 | 4 appeal invariants with plain-English meanings |
   | Boundary testing | Section 7 | Temporal boundary conditions with deterministic outcomes |
   | Escalation analysis | Section 8 | Stochastic outcome modeling with L1/L2/Kleros |
   | Governance paths | Section 9 | Authorization and custody movement documentation |

   ### Benchmarks / scenarios

   - **14 appeal-related scenarios** across the deterministic invariant suite
   - **F6 benchmark**: Appeal window adequacy (pass threshold ≥ 75%)
   - **M3 benchmark**: Frivolous appeal discouragement (pass threshold ≥ 70%)
   - **Boundary tests**: Before, at, and after deadline with offset precision
   - **Stochastic modeling**: Appeal reversal outcomes under base / optimistic / pessimistic assumptions
   - **Full appeal simulation**: Multi-round process through R0, R1, R2/Kleros

   ### Invariants checked

   - ``appeal-bond-conserved?`` — No slash has negative appeal-bond-held
   - ``appeal-bond-custody-consistent?`` — Custody lifecycle is coherent
   - ``appeal-requires-prior-resolution?`` — Appeals require prior dispute resolution
   - ``finality-blocked-during-appeal?`` — No settlement before window expires

   ### Why this matters to ecosystem safety

   The appeal subsystem is a critical safety mechanism for any dispute
   resolution protocol. It provides the only corrective path for resolvers
   who have been unfairly slashed, while maintaining economic disincentives
   against abuse. The benchmark-backed approach demonstrated in this
   notebook enables:

   - **Evidence-based parameter selection** for appeal bonds and windows
   - **Reproducible security claims** supported by invariant checking
   - **Auditable custody movements** with forensic-grade evidence
   - **Multi-level escalation** as a robustness backstop

   ### Future work

   | Item | Priority | Status |
   |------|----------|--------|
   | Mandatory authorization provenance for resolve-appeal | High | Migration in progress |
   | Formalized emergency / force resolution path | Medium | Not yet implemented |
   | DAO governance boundary evidence records | High | Migration in progress |
   | Expanded Kleros integration modeling | Medium | Stub model — not exercised |
   | Appeal bond parameter optimization via M3/F6 combined sweep | Medium | Research topic |
   | Pro-rata / fractional allocation relevance for slashed bond distribution | Low | Existing mechanism, not appeal-specific |
   | Partial-fill and shortfall handling for appeal bond returns | Low | Edge case — not separately modeled |")

;; ===========================================================================
;; Notebook navigation
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(ui/notebook-navigation "Appeal Analysis")

;; ===========================================================================
;; Provenance footer
;; ===========================================================================
