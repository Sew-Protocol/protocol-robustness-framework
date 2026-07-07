;; # Sew Protocol — Appeal Bond Analysis
;;
;; **Audience:** Grant reviewers · Protocol researchers · Auditors · Security reviewers
;;
;; **Purpose:** Explain, exercise, and visualize the appeal bond subsystem in
;; isolation — what appeal bonds are, how they flow through custody, what they
;; cost, and which invariants guarantee their safety.
;;
;; Appeal bonds serve dual purpose: they deter frivolous appeals through
;; economic disincentive, and they provide a measurable security parameter
;; with custody invariants and reproducible conservation checks.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-appealed
  (:require [clojure.string :as str]
            [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.notebook-support.theme :refer [notebook-theme]]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.protocols.sew.research-models.escalation-economics :as ee]
            [resolver-sim.stochastic.detection :as detect]
            [resolver-sim.stochastic.decision-quality :as dq]))

;; ===========================================================================
;; Notebook-local constants
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :hide}}
(defn- styled-table
  "Render a table with consistent styling."
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
;; Section 1: What an Appeal Bond Is
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "## 1. What an Appeal Bond Is

  An appeal bond is posted by a resolver when appealing a fraud slash.
  It serves two purposes:

  - **Economic deterrent** — posting a bond imposes a cost on the appellant,
    discouraging frivolous appeals.
  - **Security parameter** — the bond magnitude is calibrated against escrow
    value and can be measured, validated, and benchmarked.

  ### Bond mechanics

  | Property | Description |
  |----------|-------------|
  | Who posts | The resolver who was slashed |
  | When | During the appeal window, before the deadline |
  | Amount | `bps(slash-amount)` or a fixed `appeal-bond-amount` from the protocol snapshot |
  | Fee | A protocol fee (in bps) is deducted from the bond at posting time |
  | Custody | The net bond is held in `:appeal-bond-held` with a custody entry in `:appeal-bond-custody` |
  | Returned | If the appeal is upheld — bond returned to appellant, custody cleared |
  | Forfeited | If the appeal is rejected — bond slashed, distributed via 50/30/20 split |

  ### Conservation expectation

  The total appeal bond amount is conserved across post, return, and forfeiture
  paths. No bond value is created or destroyed — it is either returned to the
  appellant or distributed to incentive pools.")

;; ===========================================================================
;; Section 2: Appeal Bond Custody Lifecycle
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 2. Appeal Bond Custody Lifecycle")

^{::clerk/visibility {:code :hide :result :hide}}
(defn appeal-bond-flow
  "Mermaid diagram showing appeal bond custody paths."
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
  | `post-appeal-bond` | Records bond custody, deducts protocol fee, updates totals | `:appeal-bond-held` non-negative |
  | `return-bond` | Returns bond to winning appellant, clears custody | Custody entry cleared |
  | `slash-bond` | Forfeits bond for losing appellant, applies 50/30/20 split | `:appeal-bond-held` zeroed |
  | `sub-held` | Low-level custody deduction with reason tag | Conservation-of-funds |")

;; ===========================================================================
;; Section 3: Appeal Bond Economics
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 3. Appeal Bond Economics

   This section shows how bond cost changes with escrow amount, bond basis
   points, protocol fee, and escalation depth. All values are in wei
   (1 ETH = 10^18 wei).")

;; --- 3a: Bond amount and fee table ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- bond-amount-table
  "Rows showing required bond and fee for escrow amounts and bond-bps bands."
  []
  (let [escrow-amounts [10000 50000 100000 500000]
        bond-bps-values [300 500 700 1000]
        fee-bps 150
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
        grouped (group-by :bond-bps rows)]
    (for [[bps items] (sort grouped)]
      [bps
       (map (fn [i] [(:escrow i) (:required-bond i) (:fee i) (:net-custody i)]) items)])))

^{::clerk/visibility {:code :hide :result :show}}
(def economics-bond-table
  (bond-amount-table))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Bond Amount and Fee by Escrow Size and Rate

   Required bond = `calculate-appeal-bond-amount(escrow, snap)` where
   snap provides either an absolute `:appeal-bond-amount` or a
   `:appeal-bond-bps` rate. Fee = `calculate-appeal-bond-fee(bond, fee-bps)`.")

^{::clerk/visibility {:code :hide :result :show}}
(doseq [[bps rows] economics-bond-table]
  (clerk/md (str "**Bond rate: " bps " bps (" (/ bps 100) "%)**"))
  (styled-table
   ["Escrow" "Required Bond" "Fee (150 bps)" "Net Custody"]
   (map (fn [[escrow bond fee net]]
          [(str escrow) (str bond) (str fee) (str net)])
        rows)))

;; --- 3b: Escalation cost table ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- escalation-cost-rows
  "Cost rows using the escalation economics model."
  []
  (let [config ee/DEFAULT_ESCALATION_CONFIG
        rounds [1 2 3]]
    (for [r rounds]
      {:round r
       :marginal-bond (ee/appeal-bond-at-round (dec r) config)
       :cumulative-cost (ee/total-appeal-cost-to-round r config)})))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Escalation Bond Cost by Round

   Each escalation level increases the appeal bond cost. Using
   `appeal-bond-at-round` and `total-appeal-cost-to-round` from the
   escalation economics model with default parameters.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Round" "Marginal Bond Cost" "Cumulative Appeal Cost"]
 (map (fn [{:keys [round marginal-bond cumulative-cost]}]
        [(str round) (str marginal-bond) (str cumulative-cost)])
      (escalation-cost-rows)))

;; --- 3c: Security improvement from escalation ---

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

;; --- 3d: Escalation cost comparison ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- escalation-cost-comparison
  "Compare cost to attack at different rounds."
  []
  (let [config ee/DEFAULT_ESCALATION_CONFIG
        dispute-values [10000 50000 100000]]
    (for [dv dispute-values]
      (let [comparison (ee/compare-attack-costs dv config)]
        (assoc comparison :dispute-value dv)))))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Attack Cost Comparison Across Escalation Rounds

   Using the escalation economics model to compare attacker costs at
   different rounds. Multi-level escalation makes corruption progressively
   more expensive.")

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

;; ===========================================================================
;; Section 4: Appeal Bond Invariants
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 4. Appeal Bond Invariants

   Two invariants guarantee that appeal bond custody is always safe,
   consistent, and conserved across all lifecycle states.")

^{::clerk/visibility {:code :hide :result :hide}}
(def appeal-bond-invariant-registry
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
    :grant-claim "Appeal bond custody is always consistent with slash status."}])

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Invariant" "Meaning" "Failure Class" "Lifecycle Phase" "Exercised By" "Grant Claim"]
 (map (fn [{:keys [name meaning failure-class phase scenarios grant-claim]}]
        [name meaning failure-class phase (str/join ", " scenarios) grant-claim])
      appeal-bond-invariant-registry))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md
 "**Conservation invariant details:**

  `appeal-bond-conserved?` enforces that `:appeal-bond-held >= 0` for every
  slash entry — no custody amount goes negative, which would indicate an
  accounting underflow or custody violation.

  `appeal-bond-custody-consistent?` enforces three rules:
  - `:appeal-bond-held >= 0` always
  - If held > 0 → slash status must be `:appealed` AND a custody entry
    must exist in `:appeal-bond-custody`
  - If held = 0 → custody entry must NOT exist

  These invariants are checked by the in-process invariant runner on every
  replay step and post-hoc by the benchmark runner.")

;; ===========================================================================
;; Section 5: Bond-Related Scenarios
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 5. Bond-Related Scenarios

   This section lists scenarios from the deterministic invariant suite that
   directly exercise appeal bond custody, amounts, and lifecycle transitions.")

^{::clerk/visibility {:code :hide :result :hide}}
(def bond-scenario-registry
  "Scenarios that exercise appeal bond mechanics."
  [{:scenario "S25"
    :display-name "profit-maximizer-slash-lifecycle"
    :bond-mechanism "Fraud slash lifecycle: slash proposed, appealed, bond posted and resolved"
    :invariant "appeal-bond-conserved?, appeal-bond-custody-consistent?"
    :grant-claim "Appeal bond lifecycle completes correctly through post → hold → return/forfeit"}

   {:scenario "S35"
    :display-name "profit-maximizer-governance-wins-appeal"
    :bond-mechanism "Governance upholds appeal: bond returned to appellant"
    :invariant "appeal-bond-conserved?, appeal-bond-custody-consistent?"
    :grant-claim "Appeal bond return path works correctly"}

   {:scenario "S36"
    :display-name "profit-maximizer-pre-window-execute-rejected"
    :bond-mechanism "Execution blocked during appeal window: bond still in custody"
    :invariant "finality-blocked-during-appeal? (bond custody preserved during window)"
    :grant-claim "Bond custody is preserved while appeal window is open"}

   {:scenario "S62"
    :display-name "multi-appeal-escalation-chain"
    :bond-mechanism "Multiple concurrent appeal bonds at different escalation levels"
    :invariant "appeal-bond-conserved?, appeal-bond-custody-consistent?"
    :grant-claim "Multiple concurrent appeal bonds do not cause custody corruption"}

   {:scenario "S63"
    :display-name "frivolous-appeal-slashing"
    :bond-mechanism "Frivolous appeal penalized via bond forfeiture, 50/30/20 split"
    :invariant "appeal-bond-custody-consistent?"
    :grant-claim "Frivolous appeals are economically deterred through bond forfeiture"}

   {:scenario "S65"
    :display-name "appeal-after-settlement-rejected"
    :bond-mechanism "Appeal attempted post-settlement: bond never posted, custody never created"
    :invariant "finality-blocked-during-appeal?"
    :grant-claim "Bond custody cannot be created after settlement finality"}])

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Scenario" "Bond Mechanism" "Invariant Coverage" "Grant Claim"]
 (map (fn [{:keys [scenario display-name bond-mechanism invariant grant-claim]}]
        [(str scenario ": " display-name) bond-mechanism invariant grant-claim])
      bond-scenario-registry))

;; ===========================================================================
;; Section 6: Analytic Benchmarks
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 6. Bond-Related Analytic Benchmarks

   Two benchmarks directly relate to appeal bond parameter choices.")

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
(clerk/md "### Benchmark F6 — Appeal Window Adequacy

   Tests whether the appeal window duration is adequate given the bond
   posting mechanics. A window too short prevents valid appeals; a window
   too long delays finality.")

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md (let [{:keys [benchmark-id label passed? summary]} @f6-results]
            (str "**" label " (" benchmark-id ")** — "
                 (if passed? "PASS" "FAIL")
                 (when summary
                   (str " — pass rate: " (int (* 100 (:pass-rate summary 0))) "%"
                        " (" (:healthy-trials summary 0) "/" (:total-trials summary 0) " trials)")))))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "### Benchmark M3 — Frivolous Appeal Discouragement

   Tests whether the appeal bond amount is sufficient to deter frivolous
   appeals without over-deterring valid ones.")

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md (let [{:keys [benchmark-id label passed? summary]} @m3-results]
            (str "**" label " (" benchmark-id ")** — "
                 (if passed? "PASS" "FAIL")
                 (when summary
                   (str " — discouragement rate: " (int (* 100 (:discouraged-trials summary 0))) "%"
                        " (" (:discouraged-trials summary 0) "/" (:total-trials summary 0) " trials)")))))

;; ===========================================================================
;; Section 7: Stochastic Bond Outcome Modeling
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 7. Stochastic Bond Outcome Modeling

   Appeal bond outcomes are not purely economic — they also depend on
   detection probabilities and decision quality. This section models
   appeal outcomes under different assumption bands.")

^{::clerk/visibility {:code :hide :result :hide}}
(defn- simulate-appeal-scenarios
  "Run stochastic appeal simulations to illustrate escalation outcomes."
  []
  (let [rng (java.util.Random. 42)
        param-sets [{:label "Base"
                     :params {:p-l1-reversal 0.3 :p-l2-escalation 0.5 :p-l2-reversal 0.4}}
                    {:label "Optimistic (high reversal)"
                     :params {:p-l1-reversal 0.6 :p-l2-escalation 0.7 :p-l2-reversal 0.6}}
                    {:label "Pessimistic (low reversal)"
                     :params {:p-l1-reversal 0.1 :p-l2-escalation 0.3 :p-l2-reversal 0.2}}]
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
(clerk/md "### Appeal Reversal Outcomes

   Using `appeal-reversal-outcome` to sample whether an appealed wrong
   verdict is reversed at L1 and/or L2 under different assumption bands.
   Bond forfeiture depends on these outcomes.")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Band" "Verdict Context" "L1 Reversed?" "L2 Escalated?" "L2 Reversed?" "Decision Reversed?"]
 (map (fn [{:keys [band verdict-context l1-reversed? l2-escalated? l2-reversed? decision-reversed?]}]
        [band verdict-context (str l1-reversed?) (str l2-escalated?) (str l2-reversed?) (str decision-reversed?)])
      appeal-stochastic-results))

;; --- 7b: Full appeal simulation ---

^{::clerk/visibility {:code :hide :result :hide}}
(defn- run-full-appeal-sims
  "Run simulate-full-appeal under different conditions."
  []
  (let [difficulties [:easy :medium :hard]
        evidence-levels [0.3 0.7 0.9]
        rng (java.util.Random. 42)]
    (for [diff difficulties
          ev evidence-levels]
      (let [res (dq/simulate-full-appeal
                 rng true
                 [true false false]
                 [0.3 0.4 0.2]
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

   Using `simulate-full-appeal` to model the complete multi-round appeal
   process through R0 (resolver), R1 (senior), R2 (external/Kleros).")

^{::clerk/visibility {:code :hide :result :show}}
(styled-table
 ["Difficulty" "Evidence Quality" "Final Decision" "Escalations" "Error?"]
 (map (fn [{:keys [difficulty evidence-quality final-decision escalation-count was-error]}]
        [(str difficulty) (str evidence-quality) (str final-decision) (str escalation-count) (str was-error)])
      full-appeal-results))

;; ===========================================================================
;; Section 8: Key Economic Insights
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 8. Key Economic Insights

  | Question | Answer |
  |----------|--------|
  | What happens if L1 review is wrong? | The dispute escalates to L2 where bond costs are higher and scrutiny is stronger. |
  | What happens if escalation is expensive? | Higher costs deter frivolous escalation, but also create a barrier to valid correction. Bond parameters must be calibrated. |
  | When does a bond deter frivolous appeals? | When the expected loss from forfeiture exceeds the expected gain from a successful frivolous appeal. |
  | When might a bond overdeter valid appeals? | If the bond is set so high that even resolvers with a meritorious case cannot afford to appeal. |
  | How does a Kleros backstop improve bond security? | Kleros provides a decentralized final review layer, reducing the risk of capture or error at L0/L1 where bond amounts are lowest. |

  **Key design insight:** The appeal bond is not merely UX friction. It is a
  measurable security parameter with custody invariants and reproducible
  conservation checks. The bond amount must be:

  - **High enough** to deter frivolous appeals (tested by M3 benchmark)
  - **Low enough** to permit valid appeals (tested by F6 window adequacy)
  - **Conserved** across all lifecycle states (enforced by invariants)
  - **Traceable** through custody evidence records (guaranteed by evidence schema)")

;; ===========================================================================
;; Section 9: Grant Summary
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "## 9. Grant Application Summary

  ### Problem

  Decentralized dispute resolution requires an appeal mechanism that allows
  resolvers to contest unfair slashes without opening the door to frivolous
  appeals or endless delay. The appeal bond is the key parameter balancing
  these concerns, but it must be measurable, testable, and provably safe.

  ### What this notebook demonstrates

  | Evidence | Section | What it shows |
  |----------|---------|---------------|
  | Bond custody diagram | 2 | Complete bond lifecycle with post → fee → custody → return/forfeit |
  | Bond economics tables | 3a | Bond amounts and fees across escrow sizes and bps bands |
  | Escalation bond costs | 3b-c | Cost by escalation round, security improvement |
  | Attack cost comparison | 3d | Multi-level escalation makes corruption progressively more expensive |
  | Invariant coverage | 4 | 2 appeal bond invariants with plain-English meanings |
  | Scenario registry | 5 | 6 bond-related scenarios across the deterministic invariant suite |
  | Benchmarks F6 / M3 | 6 | Appeal window adequacy and frivolous appeal discouragement pass rates |
  | Stochastic modeling | 7 | Appeal reversal outcomes under base / optimistic / pessimistic assumptions |

  ### Invariants checked

  - `appeal-bond-conserved?` — No slash has negative appeal-bond-held
  - `appeal-bond-custody-consistent?` — Custody lifecycle is coherent

  ### Parameters demonstrated

  - Bond bps rates: 300, 500, 700, 1000 (3%–10%)
  - Protocol fee: 150 bps (1.5%)
  - Escrow amounts: 10K–500K wei
  - Escalation rounds: R0 → R1 → R2 (Kleros)
  - Escalation bond costs: marginal and cumulative per round")

;; ===========================================================================
;; Notebook navigation
;; ===========================================================================

^{::clerk/visibility {:code :hide :result :show}}
(ui/notebook-navigation "Appeal Bond Analysis")
