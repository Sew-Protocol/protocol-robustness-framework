;; # Protocol Robustness Framework — Validation Report
;;
;; **Audience:** Protocol reviewers, security researchers, contributors.
;;
;; **Purpose:** Unified evidence surface across the two protocol domains:
;;
;; | Domain | Coverage | Scope |
;; |--------|----------|-------|
;; | **SEW** (Settlement Escrow Workflow) | 130 scenarios | Escrow lifecycle, disputes, resolution, governance, forking strategist, SPE, equilibrium |
;; | **General Yield Module** | 22 scenarios | Yield accrual, shortfall handling, partial liquidity, deferred claims, AAVE integration |
;;
;; Every status indicator is paired with what it means, what it does *not* mean,
;; and the source artifact it is derived from.
;;
;; This notebook does not smooth over known
;; vulnerabilities or present results as "green = safe". Red can mean a
;; hard validation failure *or* a successful theory-falsification finding —
;; always inspect `status-kind` before interpreting colour.
;;
;; **Data contract:** All evidence is loaded from:
;; - `results/test-artifacts/test-summary.json` — canonical validation gate
;; - `results/test-artifacts/coverage.json` — transition/threat-tag coverage
;; - `data/fixtures/traces/*.trace.json` — scenario metadata
;; - `data/fixtures/golden/*.report.edn` — per-scenario replay outcomes
;; - `results/test-artifacts/equivalence-comparison-summary.json`
;;
;; If an artifact is absent, the relevant panel shows amber (missing data),
;; not red. Missing data is not the same as a protocol problem.
;;
;; Red in this report has *two* meanings that must not be conflated:
;;
;; 1. **Validation failure** — invariant violated, unexpected outcome, funds drift.
;; 2. **Successful falsification** — expected-negative scenario proving a known limit.
;;
;; Always inspect the `status-kind` column before acting on colour alone.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.report
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.notebooks.ui :as ui]
            [resolver-sim.notebooks.common :as common]
            [resolver-sim.notebook.views :as views]
            [resolver-sim.notebook.checks :as checks]
            [resolver_sim.notebooks.speds.data :as speds-data]))

(clerk/html (ui/notebook-navigation "Report Notebook"))

;; ---
;; ## R/A/G Legend
;;
;; | Colour | Symbol | Meaning |
;; |--------|--------|---------|
;; | 🟢 Green | `:green` | Validation condition holds / invariant passes |
;; | 🟠 Amber | `:amber` | Inconclusive / warning / research finding / artifact missing |
;; | 🔴 Red   | `:red`   | Hard gate failure / invariant violation / vulnerability evidence |
;;
;; **Critical note on red:** Red can mean *either*:
;; 1. **A validation failure** — invariant violated, unexpected outcome, funds drift.
;; 2. **A successful falsification** — expected-negative scenario proving a known limit.
;;
;; Always inspect the `status-kind` column:
;; - `:validation` — hard gate check
;; - `:research-finding` — model/theory finding
;; - `:expected-negative` — scenario designed to demonstrate a known limitation
;; - `:missing-data` — artifact absent; evidence incomplete

;; ---
;; ## Utilities

;; ---
;; ## Artifact Loading

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def test-summary
  (-> (speds-data/load-summary)
      (checks/assert-test-summary!)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def coverage-data (speds-data/load-coverage))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def equivalence-summary (speds-data/load-equivalence))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def all-traces
  (map (fn [d]
         {:id          (or (:scenario-id d)
                           (str/replace (:_filename d) ".trace.json" ""))
          :title       (or (:title d) "")
          :description (or (:description d) "")
          :purpose     (or (:purpose d) "")
          :threat-tags (or (:threat-tags d) [])
          :has-theory  (contains? d :theory)
          :theory      (:theory d)
          :trace-file  (:_filename d)})
       (speds-data/load-all-traces)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def golden-reports
  (-> (speds-data/load-all-golden-reports)
      (checks/assert-golden-reports!)))

;; ---
;; ## Display helpers — all delegated to resolver-sim.notebook.views
;; (views/rag-status, status-emoji, status-label, status-kind-label,
;;  target-status->rag, risk-digest->rag, render-card,
;;  scenario-row->rag, classify-validation-failure, triage-next-action)

;; ---
;; ## 1. Evidence Control Panel

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Evidence Control Panel"
   (fn []
     (let [summary    test-summary
           rd         (:risk_digest summary)
           sc         (:status_counts summary)
           critical-n (count (:critical_findings rd))
           warning-n  (count (:warnings rd))
           gate-rag   (if summary
                        (if (= "pass" (str (:overall_status summary))) :green :red)
                        :amber)
           risk-rag   (cond (nil? rd) :amber (pos? critical-n) :red :else :green)
           warn-rag   (cond (nil? rd) :amber (pos? warning-n) :amber :else :green)
           trace-n    (count all-traces)
           corpus-rag (if (zero? trace-n) :amber :green)
           ;; Corpus-visible counts: only golden reports that have a matching trace
           ;; (orphan goldens with no trace metadata are excluded from visible counts)
           trace-ids  (set (map :id all-traces))
           corpus-goldens (into {} (filter (fn [[k _]] (contains? trace-ids k)) golden-reports))
           ;; Total counts: all golden reports including orphans (no matching trace)
           total-golden-n (count golden-reports)
           total-inv  (reduce + (map #(get-in % [:metrics :invariant-violations] 0)
                                     (vals (or golden-reports {}))))
           orphan-n   (max 0 (- total-golden-n trace-n))
           ;; Visible corpus counts (trace-matched goldens only)
           corpus-golden-n (count corpus-goldens)
           corpus-inv (reduce + (map #(get-in % [:metrics :invariant-violations] 0)
                                     (vals corpus-goldens)))
           corpus-outcomes (frequencies (map :outcome (vals corpus-goldens)))
           corpus-pass-n (get corpus-outcomes :pass 0)
           corpus-fail-n (get corpus-outcomes :fail 0)
           funds-rag  (cond (zero? corpus-golden-n) :amber (pos? corpus-inv) :red :else :green)
           replay-missing-n (- trace-n corpus-golden-n)
           replay-rag (cond
                        (pos? corpus-fail-n) :red
                        (pos? replay-missing-n) :amber
                        :else :green)
           expected-negative-n (count (filter #(= :expected-negative
                                                   (:status-kind (views/scenario-row->rag % (get golden-reports (:id %)))))
                                             (or all-traces [])))
           vuln-count (count (filter #(= "theory-falsification" (:purpose %))
                                     (or all-traces [])))
           vuln-rag   (if (pos? vuln-count) :amber :green)
           run-id     (or (:run_id summary) "—")
           decision   (or (:acceptance_decision summary) "UNKNOWN")
           ;; Derive a corpus-aware decision that reflects visible evidence state
           corpus-decision (cond
                             (pos? corpus-fail-n) "CORPUS_FAIL"
                             (pos? replay-missing-n) "CORPUS_INCOMPLETE"
                             :else (str decision))]
      [:div
       [:h2 "Evidence Control Panel"]
       [:p {:style {:color "#f1f5f9" :fontSize "0.9em"}}
        "Aggregated status from the most recent validation run. "
        "Inspect each panel for detail before acting on colour alone."]
       [:div {:style {:background "#eff6ff" :border "1px solid #93c5fd" :borderRadius "6px"
                      :padding "10px 12px" :marginBottom "10px" :fontSize "0.84em" :color "#1e3a8a"}}
        [:strong "Why CI can be green while corpus is non-clean: "]
        "PASS_CLEAN means the configured CI targets completed successfully. "
        "It does not mean every trace in the evidence corpus has a passing golden replay. "
        "The corpus includes additional research and replay artifacts whose status is reported separately in this workbook."]
       [:div {:style {:fontSize "0.82em" :color "#f1f5f9" :marginBottom "12px"
                      :fontFamily "monospace" :background "#f5f5f5"
                      :padding "6px 10px" :borderRadius "3px"}}
        (str "Run ID: " run-id " │ CI decision: " decision
             " │ Corpus: " corpus-decision
             " │ Targets: " (get-in sc [:targets :total] "—")
             " │ traces=" trace-n ", corpus-golden=" corpus-golden-n
             (if (pos? orphan-n) (str " (+" orphan-n " orphan)") ""))]
        (views/render-card
         {:label "Top-level interpretation"
          :rag   (if (or (pos? corpus-fail-n) (pos? replay-missing-n)) :red :green)
          :value (cond
                   (pos? corpus-fail-n) (str "Corpus shows " corpus-fail-n " validation failure(s) — see triage below")
                   (pos? replay-missing-n) "Corpus has traces without replay outcomes — evidence incomplete"
                   :else "CI targets and replay corpus are aligned")
          :note  (str "CI decision: " decision ". Corpus decision: " corpus-decision ". "
                      "The CI gate only tracks target exit codes; corpus health is independent.")})
       (views/render-card
        {:label "CI target gate"
         :rag   gate-rag
         :value (str (get-in sc [:targets :pass] "—")
                     "/" (get-in sc [:targets :total] "—") " targets pass")
         :note  "Hard gate: did all canonical test targets complete successfully? Source: test-summary.json"})
        (views/render-card
         {:label "Replay corpus status (visible)"
          :rag   replay-rag
          :value (str corpus-pass-n " pass, " corpus-fail-n " fail, " replay-missing-n " missing golden"
                      (if (pos? orphan-n) (str " (" orphan-n " orphan)") ""))
          :note  "Counts from trace-matched goldens only. Orphan goldens (no trace metadata) are excluded from this tally."})
       (views/render-card
        {:label "Structured risk digest criticals"
         :rag   risk-rag
         :value (str critical-n)
          :note  (str "Structured critical findings in risk_digest. "
                      (if (pos? critical-n) "Review test-summary.json immediately." "Clean."))})
        (views/render-card
         {:label "Replay validation failures requiring investigation"
          :rag   (if (pos? corpus-fail-n) :red :green)
          :value (str corpus-fail-n)
          :note  (str "Visible corpus failures (trace-matched). "
                      (if (pos? orphan-n) (str orphan-n " orphan goldens excluded — see corpus coverage card.") ""))})
       (views/render-card
        {:label "Expected-negative findings"
         :rag   (if (pos? expected-negative-n) :amber :green)
         :value (str expected-negative-n)
         :note  "Count of scenarios with status-kind :expected-negative (theory-falsification evidence)."})
       (views/render-card
        {:label "Warnings"
         :rag   warn-rag
         :value (str warning-n " warning(s)")
          :note  "Warning count from risk_digest. Amber = warnings present; does not imply protocol failure."})
        (views/render-card
         {:label "Trace corpus coverage"
          :rag   corpus-rag
          :value (str trace-n " traces; " corpus-golden-n " with matching golden"
                      (if (pos? orphan-n) (str " (+" orphan-n " orphan golden)") ""))
          :note  (str replay-missing-n " traces lack replay outcomes; "
                      orphan-n " golden reports have no matching trace metadata. "
                      "Source: data/fixtures/traces/ and data/fixtures/golden/")})
        (views/render-card
         {:label "Invariant conservation (visible corpus)"
          :rag   funds-rag
          :value (str corpus-inv " violation(s) across " corpus-golden-n " trace-matched golden reports")
          :note  (str "Total (including orphans): " total-inv ". "
                      "Non-zero = protocol invariant breached — investigate.")})
       (views/render-card
        {:label "Theory-falsification scenarios declared"
         :rag   vuln-rag
         :value (str vuln-count " theory-falsification scenario(s)")
         :note  "Known theory-falsification scenarios exist. These are research findings, NOT test failures. See status-kind in Scenario Matrix."})]))))

;; ---
;; ## 2. Corpus Evidence Status

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Corpus Evidence Status"
  (fn []
    (let [traces (or all-traces [])
          golds (or golden-reports {})
          total-traces (count traces)
          trace-ids (set (map :id traces))
          matched-golden-n (count (into {} (filter (fn [[k _]] (contains? trace-ids k)) golds)))
          missing-count (- total-traces matched-golden-n)
          with-golden (keep #(get golds (:id %)) traces)
          outcomes (frequencies (map :outcome with-golden))
          pass-count (get outcomes :pass 0)
          fail-count (get outcomes :fail 0)
          evidence-rag (cond
                         (pos? fail-count) :red
                         (pos? missing-count) :amber
                         :else :green)]
      [:div
       [:h2 "Corpus Evidence Status"]
       [:p {:style {:fontSize "0.85em" :color "#f1f5f9"}}
        "Separate corpus-level evidence summary to track replay outcome coverage and quality independently of CI target status."]
       [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b" :borderRadius "6px" :padding "10px 12px" :marginBottom "10px" :fontSize "0.84em"}}
        [:strong "Coverage statement: "]
        (str matched-golden-n "/" total-traces
             " traces have matching replay outcome artifacts; "
             (- (count golds) matched-golden-n) " golden reports have no matching trace."
             " Coverage is broad at trace metadata level, but evidence-backed replay coverage is partial.")]
       [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit, minmax(180px, 1fr))" :gap "10px" :marginBottom "10px"}}
        (views/render-card {:label "Evidence status" :rag evidence-rag :value (name evidence-rag)
                      :note "Red: replay fails present. Amber: missing replay outcomes. Green: full evidence-backed pass."})
        (views/render-card {:label "Trace metadata coverage" :rag (if (pos? total-traces) :green :amber)
                      :value (str total-traces " traces") :note "Total scenarios discovered in trace metadata."})
         (views/render-card {:label "Replay outcome coverage" :rag (if (zero? missing-count) :green :amber)
                       :value (str matched-golden-n "/" total-traces) :note "Trace-matched golden reports."})
        (views/render-card {:label "Replay failures" :rag (if (pos? fail-count) :red :green)
                      :value (str fail-count) :note "Golden outcomes marked :fail."})
        (views/render-card {:label "Missing replay artifacts" :rag (if (pos? missing-count) :amber :green)
                      :value (str missing-count) :note "Traces lacking golden outcome artifacts."})
        (views/render-card {:label "Replay passes" :rag :green :value (str pass-count)
                      :note "Golden outcomes marked :pass."})]]))))

;; ---
;; ## 3. Validation Work Queue (Top 10)

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Validation Work Queue (Top 10)"
  (fn []
    (let [priority-ids #{{"s04" "s14" "s15" "s17" "s18" "s19" "s20" "s21" "s22" "s23"}}
          id-prefix? (fn [id prefix] (str/starts-with? (str/lower-case (str id)) prefix))
          selected? (fn [id]
                      (or (id-prefix? id "s04")
                          (id-prefix? id "s14")
                          (id-prefix? id "s15")
                          (id-prefix? id "s17")
                          (id-prefix? id "s18")
                          (id-prefix? id "s19")
                          (id-prefix? id "s20")
                          (id-prefix? id "s21")
                          (id-prefix? id "s22")
                          (id-prefix? id "s23")))
           rows (->> (or all-traces [])
                     (filter #(selected? (:id %)))
                     (keep (fn [t]
                             (let [golden (get golden-reports (:id t))
                                   verdict (views/scenario-row->rag t golden)]
                               (when (and golden (= :fail (:outcome golden)))
                                 {:id (:id t)
                                  :title (:title t)
                                  :status-kind (:status-kind verdict)
                                  :replay-outcome (name (:outcome golden))
                                  :failure-class (views/classify-validation-failure t golden (:status-kind verdict))}))))
                     (sort-by :id))]
      [:div
       [:h2 "Validation Work Queue (Top 10)"]
       [:p {:style {:fontSize "0.84em" :color "#f1f5f9"}}
        "Primary reviewer queue promoted ahead of the full matrix: s04, s14–s15, s17, s18–s21, s22, s23."]
       (if (seq rows)
         [:div {:style {:overflowX "auto"}}
          [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
           [:thead
            [:tr {:style {:background "#f3f4f6" :textAlign "left"}}
             [:th {:style {:padding "6px 8px"}} "ID"]
             [:th {:style {:padding "6px 8px"}} "Title"]
             [:th {:style {:padding "6px 8px"}} "status-kind"]
             [:th {:style {:padding "6px 8px"}} "Replay"]
             [:th {:style {:padding "6px 8px"}} "Failure class"]]]
           (into [:tbody]
                 (for [r rows]
                   [:tr {:style {:borderBottom "1px solid #e5e7eb"
                                 :background (if (= :validation (:status-kind r)) "#fef2f2" "#fff7ed")}}
                    [:td {:style {:padding "5px 8px" :fontFamily "monospace" :whiteSpace "nowrap"}} (:id r)]
                    [:td {:style {:padding "5px 8px"}} (:title r)]
                    [:td {:style {:padding "5px 8px"}} (views/status-kind-label (:status-kind r))]
                    [:td {:style {:padding "5px 8px" :textAlign "center"}} (:replay-outcome r)]
                    [:td {:style {:padding "5px 8px"}} (:failure-class r)] ]))]]
         (ui/callout :amber [:div "No queue scenarios found in current trace corpus."]))]))) )

;; Debug panel for notebook sync/path drift issues.
;; This makes the data source explicit so stale sessions / wrong working dir are obvious.
^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Queue Data Source Debug"
  (fn []
    (let [ids ["s04-dispute-timeout-autocancel"
               "s14-dr3-module-authorized"
               "s15-dr3-module-unauthorized-rejected"
               "s17-ieo-dispute-no-resolver-timeout"
               "s18-dr3-kleros-l0-resolves"
               "s19-dr3-kleros-escalation-rejected-l0-resolves"
               "s20-dr3-kleros-max-escalation-guard"
               "s21-dr3-kleros-pending-cleared-on-escalation"
               "s22-status-leak-agree-cancel-over-dispute"
               "s23-preemptive-escalation-blocked"]]
      [:details {:style {:margin "8px 0 14px" :background "#eff6ff" :border "1px solid #93c5fd"
                         :borderRadius "6px" :padding "8px 10px"}}
       [:summary {:style {:cursor "pointer" :fontWeight "600"}}
        "Debug: queue data source (cwd + loaded golden files)"]
       [:div {:style {:fontSize "0.8em" :color "#334155" :marginTop "8px"}}
        [:div [:strong "user.dir:"] " " (System/getProperty "user.dir")]
        [:div [:strong "golden dir absolute:"] " " (.getAbsolutePath (io/file "data/fixtures/golden"))]
        [:div [:strong "golden count loaded:"] " " (count (or golden-reports {}))]]
       [:div {:style {:overflowX "auto" :marginTop "8px"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.8em"}}
         [:thead
          [:tr {:style {:background "#dbeafe" :textAlign "left"}}
           [:th {:style {:padding "5px 8px"}} "ID"]
           [:th {:style {:padding "5px 8px"}} "Outcome"]
           [:th {:style {:padding "5px 8px"}} "Source file"]
           [:th {:style {:padding "5px 8px"}} "MTime(ms)"]]]
         (into [:tbody]
               (for [id ids
                     :let [g (get golden-reports id)]]
                 [:tr {:style {:borderBottom "1px solid #bfdbfe"}}
                  [:td {:style {:padding "5px 8px" :fontFamily "monospace" :whiteSpace "nowrap"}} id]
                  [:td {:style {:padding "5px 8px" :fontFamily "monospace"}}
                   (if g (name (:outcome g)) "missing")]
                  [:td {:style {:padding "5px 8px" :fontFamily "monospace" :fontSize "0.75em"}}
                   (or (:_source-file g) "—")]
                  [:td {:style {:padding "5px 8px" :fontFamily "monospace"}}
                   (or (:_source-mtime g) "—")]]))]]]))))

;; ---
;; ## 4. Scenario Matrix
;;
;; One row per trace. **Red ≠ CI failure** — inspect `status-kind`.
;; Theory-falsification scenarios marked `:expected-negative` are red as
;; evidence of a known protocol boundary, not a build breakage.

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Scenario Matrix"
  (fn []
    (let [traces (or all-traces [])
          golds (or golden-reports {})
           yield? (fn [t]
                    (let [text (str/lower-case (str (:id t) " " (:title t) " "
                                                    (str/join " " (map name (:threat-tags t)))))]
                      (or (str/includes? text "yield")
                          (str/includes? text "accrual")
                          (str/includes? text "shortfall")
                          (str/includes? text "liquidity")
                          (str/includes? text "aave"))))
           rows (for [t traces
                      :let [golden (get golds (:id t))
                            verdict (views/scenario-row->rag t golden)
                            rag (:rag verdict)
                            kind (:status-kind verdict)
                            reason (:reason verdict)
                            domain (if (yield? t) "Yield" "SEW")
                            failure-class (views/classify-validation-failure t golden kind)
                            tags (if (seq (:threat-tags t))
                                   (str/join ", " (map name (:threat-tags t)))
                                   "—")
                            inv-v (if golden (str (get-in golden [:metrics :invariant-violations] 0)) "—")
                            atk-s (if golden (str (get-in golden [:metrics :attack-successes] 0)) "—")
                            outcome (if golden (name (:outcome golden)) "—")
                            bg (case rag :green "#f0fdf4" :amber "#fffbeb" :red "#fef2f2" "white")]]
                  {:kind (cond
                           (= kind :validation) :validation-failure
                           (= kind :expected-negative) :expected-negative
                           (= kind :missing-data) :missing-golden
                           :else :passing-validation)
                   :domain domain
                  :status (views/status-emoji rag)
                  :status-kind (views/status-kind-label kind)
                  :id (:id t)
                  :title (:title t)
                  :purpose (:purpose t)
                  :failure-class failure-class
                  :tags tags
                  :inv-v inv-v
                  :atk-s atk-s
                  :outcome outcome
                  :reason reason
                  :bg bg})
          by-kind (group-by :kind rows)
          missing-golden-priority
          (let [missing (get by-kind :missing-golden [])
                classify-priority
                (fn [r]
                  (let [id-l (str/lower-case (str (:id r)))
                        title-l (str/lower-case (str (:title r)))
                        purpose-l (str/lower-case (str (:purpose r)))
                        text (str id-l " " title-l " " purpose-l " " (str/lower-case (str (:tags r))))]
                    (cond
                      ;; High priority security/adversarial
                      (or (str/includes? text "auth")
                          (str/includes? text "unauthorized")
                          (str/includes? text "escalat")
                          (str/includes? text "appeal")
                          (str/includes? text "race")
                          (str/includes? text "settlement")
                          (str/includes? text "state leak")
                          (str/includes? text "status leak")
                          (str/includes? text "state-machine")
                          (str/includes? text "guard")
                          (str/includes? text "adversarial")
                          (str/includes? text "attack"))
                      {:priority 1 :bucket "High priority security/adversarial"}

                      ;; Boundary/timing
                      (or (str/includes? text "window")
                          (str/includes? text "1s")
                          (str/includes? text "deadline")
                          (str/includes? text "timeout")
                          (str/includes? text "boundary")
                          (str/includes? text "same-block")
                          (str/includes? text "ordering")
                          (str/includes? text "max escalation"))
                      {:priority 2 :bucket "Boundary/timing"}

                      ;; Economic/SPE research
                      (or (str/includes? text "spe")
                          (str/includes? text "nash")
                          (str/includes? text "incentive")
                          (str/includes? text "collusion")
                          (str/includes? text "economic")
                          (str/includes? text "equilibrium")
                          (str/includes? text "theory"))
                      {:priority 3 :bucket "Economic/SPE research"}

                      ;; Lower-risk regression coverage
                      :else
                      {:priority 4 :bucket "Lower-risk regression coverage"})))]
            (->> missing
                 (map (fn [r] (merge r (classify-priority r))))
                 (sort-by (juxt :priority :id))))
          render-section (fn [title rows' default-open? tone validation?]
                           [:details (cond-> {:style {:marginBottom "10px" :border "1px solid #e2e8f0" :borderRadius "6px" :padding "8px 10px"
                                                      :backgroundColor (case tone :red "#fff7f7" :amber "#fffbeb" :green "#f0fdf4" "white")}}
                                        default-open? (assoc :open true))
                            [:summary {:style {:cursor "pointer" :fontWeight "600"}} (str title " (" (count rows') ")")]
                            (if (seq rows')
                              [:div {:style {:overflowX "auto" :marginTop "8px"}}
                               [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.9em"}}
                                [:thead
                                 (into [:tr {:style {:background "#f3f4f6" :textAlign "left"}}]
                                       (map #(vector :th {:style {:padding "6px 8px"}} %)
                                                (if validation?
                                                ["Status" "status-kind" "Domain" "ID" "Title" "Purpose" "Failure class" "Threat tags" "Inv.viol" "Atk.succ" "Outcome" "Reason"]
                                                ["Status" "status-kind" "Domain" "ID" "Title" "Purpose" "Threat tags" "Inv.viol" "Atk.succ" "Outcome" "Reason"]))) ]
                                (into [:tbody]
                                      (for [r rows']
                                        [:tr {:style {:background (:bg r)}}
                                         [:td {:style {:textAlign "center" :fontSize "1.1em"}} (:status r)]
                                         [:td {:style {:fontSize "0.75em" :color "#f1f5f9"}} (:status-kind r)]
                                         [:td {:style {:fontSize "0.72em" :fontWeight "600" :color (if (= "Yield" (:domain r)) "#0891b2" "#6b7280")}} (:domain r)]
                                         [:td {:style {:fontFamily "monospace" :fontSize "0.8em" :whiteSpace "nowrap"}} (:id r)]
                                         [:td {:style {:fontSize "0.82em" :maxWidth "220px"}} (:title r)]
                                         [:td {:style {:fontSize "0.78em"}} (:purpose r)]
                                         (when validation?
                                           [:td {:style {:fontSize "0.78em" :maxWidth "170px"}} (:failure-class r)])
                                         [:td {:style {:fontSize "0.78em" :maxWidth "160px"}} (:tags r)]
                                         [:td {:style {:textAlign "center"}} (:inv-v r)]
                                         [:td {:style {:textAlign "center"}} (:atk-s r)]
                                         [:td {:style {:fontSize "0.78em"}} (:outcome r)]
                                         [:td {:style {:fontSize "0.72em" :color "#f1f5f9" :maxWidth "220px"}} (:reason r)]]))]]
                              [:div {:style {:fontSize "0.84em" :color "#64748b" :marginTop "6px"}} "None in this section."])])]
      [:div
       [:h2 "Scenario Matrix"]
       [:div {:style {:position "sticky" :top "8px" :zIndex "10" :background "#ffffff" :border "1px solid #e2e8f0"
                      :borderRadius "6px" :padding "6px 10px" :marginBottom "10px" :fontSize "0.82em"}}
        [:strong {:style {:marginRight "8px"}} "Jump to:"]
        [:a {:href "#scenario-validation-failures" :style {:marginRight "10px"}} "Failures"]
        [:a {:href "#scenario-expected-negative" :style {:marginRight "10px"}} "Expected-negative"]
        [:a {:href "#scenario-passing" :style {:marginRight "10px"}} "Passing"]
        [:a {:href "#scenario-missing"} "Missing"]]
       [:p {:style {:fontSize "0.85em" :color "#f1f5f9"}}
        "One row per scenario trace. "
        [:strong "Red ≠ CI failure"] " — inspect status-kind column. "
        "Rows without a golden report show amber (missing data, not failure)."]
       [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b" :borderRadius "6px" :padding "10px 12px" :marginBottom "10px" :fontSize "0.84em"}}
        [:strong "Replay coverage status: "]
        (str "Only " (count golden-reports) "/" (count all-traces)
             " traces currently have replay outcome artifacts. Coverage is broad at trace metadata level, but evidence-backed replay coverage is partial.")]
       [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1" :borderRadius "6px" :padding "10px 12px" :marginBottom "10px" :fontSize "0.84em"}}
        [:strong "Interpretation for replay failures with invariants pass:"]
        [:div "These failures do not show funds drift or invariant breach. They show expected outcome mismatch, rejected path, missing resolution, or model/fixture divergence."]]
       [:div {:id "scenario-validation-failures"}
        (render-section "Validation failures requiring investigation" (get by-kind :validation-failure []) true :red true)]
       [:div {:id "scenario-expected-negative"}
        (render-section "Expected-negative / theory-falsification findings" (get by-kind :expected-negative []) false :amber false)]
       [:div {:id "scenario-passing"}
        (render-section "Passing validation scenarios" (get by-kind :passing-validation []) false :green false)]
       [:div {:id "scenario-missing"}
        (render-section "Missing golden reports" (get by-kind :missing-golden []) false :amber false)]
       [:details {:style {:marginTop "8px" :background "#fff7ed" :border "1px solid #fdba74" :borderRadius "6px" :padding "8px 10px"}}
        [:summary {:style {:cursor "pointer" :fontWeight "600"}}
         (str "Prioritised missing golden reports (" (count missing-golden-priority) ")")]
        [:p {:style {:fontSize "0.83em" :color "#7c2d12" :marginTop "6px"}}
         "Grouped triage buckets: security/adversarial → boundary/timing → economic/SPE → lower-risk regression."]
        (let [grouped (group-by :bucket missing-golden-priority)
              ordered-buckets ["High priority security/adversarial"
                               "Boundary/timing"
                               "Economic/SPE research"
                               "Lower-risk regression coverage"]]
          (into [:div]
                (for [bucket ordered-buckets
                      :let [bucket-rows (sort-by :id (get grouped bucket []))]]
                  [:details {:style {:marginTop "8px" :background "#fff" :border "1px solid #fed7aa"
                                     :borderRadius "6px" :padding "8px 10px"}}
                   [:summary {:style {:cursor "pointer" :fontWeight "600"}}
                    (str bucket " (" (count bucket-rows) ")")]
                   (if (seq bucket-rows)
                     [:div {:style {:overflowX "auto" :marginTop "6px"}}
                      [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
                       [:thead
                        [:tr {:style {:background "#fff7ed" :textAlign "left"}}
                         [:th {:style {:padding "6px 8px"}} "Scenario ID"]
                         [:th {:style {:padding "6px 8px"}} "Title"]
                         [:th {:style {:padding "6px 8px"}} "Purpose"]]]
                       (into [:tbody]
                             (for [r bucket-rows]
                               [:tr
                                [:td {:style {:padding "5px 8px" :fontFamily "monospace" :fontSize "0.8em"}} (:id r)]
                                [:td {:style {:padding "5px 8px" :fontSize "0.8em"}} (:title r)]
                                [:td {:style {:padding "5px 8px" :fontSize "0.78em"}} (:purpose r)]]))]]
                     [:div {:style {:fontSize "0.8em" :color "#9a3412" :marginTop "6px"}}
                      "No missing scenarios in this bucket."])])))]]))))

;; ---
;; ## 5. Validation Failures Triage

;; triage-next-action moved to resolver-sim.notebook.views

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Validation Failures Triage"
  (fn []
    (let [traces (or all-traces [])
          golds (or golden-reports {})
          triage-rows
          (->> traces
               (keep (fn [t]
                       (let [golden (get golds (:id t))
                             verdict (views/scenario-row->rag t golden)
                             status-kind (:status-kind verdict)
                             replay-ok? (and golden (not= :fail (:outcome golden)))
                             invariants-pass? (boolean (and golden (zero? (get-in golden [:metrics :invariant-violations] 0))))
                             likely-class (views/classify-validation-failure t golden status-kind)]
                          (when (and (= status-kind :validation) (not replay-ok?))
                           {:id (:id t)
                            :title (:title t)
                            :threat-tags (if (seq (:threat-tags t))
                                           (str/join ", " (map name (:threat-tags t)))
                                           "—")
                            :replay-ok replay-ok?
                            :invariants-pass invariants-pass?
                            :reverts (if golden (get-in golden [:metrics :reverts] 0) "—")
                            :resolutions (if golden (get-in golden [:metrics :resolutions-executed] 0) "—")
                            :likely-class likely-class
                            :next-action (views/triage-next-action likely-class invariants-pass?)}))))
               (sort-by (juxt (fn [r] (if (:invariants-pass r) 1 0)) :id)))]
      [:div
       [:h2 "Validation Failures Triage"]
       [:p {:style {:fontSize "0.84em" :color "#f1f5f9"}}
        "Reviewer-priority queue for validation failures. Focus here first when replay corpus is non-clean."]
       (if (seq triage-rows)
         [:div {:style {:overflowX "auto"}}
          [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.84em"}}
           [:thead
            [:tr {:style {:background "#f3f4f6" :textAlign "left"}}
             [:th {:style {:padding "6px 8px"}} "ID"]
             [:th {:style {:padding "6px 8px"}} "Title"]
             [:th {:style {:padding "6px 8px"}} "Threat tags"]
             [:th {:style {:padding "6px 8px"}} "Replay ok?"]
             [:th {:style {:padding "6px 8px"}} "Invariants pass?"]
             [:th {:style {:padding "6px 8px"}} "Reverts"]
             [:th {:style {:padding "6px 8px"}} "Resolutions"]
             [:th {:style {:padding "6px 8px"}} "Likely class"]
             [:th {:style {:padding "6px 8px"}} "Next action"]]]
           (into [:tbody]
                 (for [r triage-rows]
                   [:tr {:style {:borderBottom "1px solid #e5e7eb"
                                 :background (if (:invariants-pass r) "#fff7ed" "#fef2f2")}}
                    [:td {:style {:padding "5px 8px" :fontFamily "monospace" :fontSize "0.8em" :whiteSpace "nowrap"}} (:id r)]
                    [:td {:style {:padding "5px 8px" :fontSize "0.8em"}} (:title r)]
                    [:td {:style {:padding "5px 8px" :fontSize "0.78em"}} (:threat-tags r)]
                    [:td {:style {:padding "5px 8px" :textAlign "center"}} (if (:replay-ok r) "✓" "✗")]
                    [:td {:style {:padding "5px 8px" :textAlign "center"}} (if (:invariants-pass r) "✓" "✗")]
                    [:td {:style {:padding "5px 8px" :textAlign "center"}} (str (:reverts r))]
                    [:td {:style {:padding "5px 8px" :textAlign "center"}} (str (:resolutions r))]
                    [:td {:style {:padding "5px 8px" :fontSize "0.78em"}} (:likely-class r)]
                    [:td {:style {:padding "5px 8px" :fontSize "0.78em" :maxWidth "320px"}} (:next-action r)]]))]]
         (ui/callout :green [:div "No validation-failure rows detected in current corpus."]))]))))

;; ---
;; ## 6. Substatus Detail
;;
;; Expanded columns for scenarios with a golden report.
;; Helps distinguish: protocol invariant pass, research claim status,
;; adversarial success, and accounting metrics.

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Substatus Detail"
  (fn []
    (let [golds (or golden-reports {})
          rows
          (for [t (or all-traces [])
                :let [golden (get golds (:id t))]
                :when golden
                :let [m          (:metrics golden)
                      inv-pass?  (zero? (get m :invariant-violations 0))
                      atk-succ   (get m :attack-successes 0)
                      bg         (cond (not inv-pass?) "#fef2f2"
                                       (pos? atk-succ)  "#fffbeb"
                                       :else            "white")]]
            [:tr {:style {:borderBottom "1px solid #e5e7eb" :background bg}}
             [:td {:style {:fontFamily "monospace" :fontSize "0.78em" :padding "4px 8px"}} (:id t)]
             [:td {:style {:fontSize "0.78em" :padding "4px 8px"}} (:purpose t)]
             [:td {:style {:textAlign "center" :padding "4px 8px"
                           :color (if (not= :fail (:outcome golden)) "#16a34a" "#dc2626")}}
              (if (not= :fail (:outcome golden)) "✓" "✗")]
             [:td {:style {:textAlign "center" :padding "4px 8px"
                           :color (if inv-pass? "#16a34a" "#dc2626")}}
              (if inv-pass? "✓" "✗")]
             [:td {:style (merge {:textAlign "center" :padding "4px 8px"}
                                 (when (pos? atk-succ) {:color "#d97706" :fontWeight "bold"}))}
              atk-succ]
             [:td {:style {:textAlign "right" :padding "4px 8px" :fontFamily "monospace"}}
              (str (get m :total-volume "—"))]
             [:td {:style {:textAlign "center" :padding "4px 8px"}} (get m :disputes-triggered 0)]
             [:td {:style {:textAlign "center" :padding "4px 8px"}} (get m :reverts 0)]
             [:td {:style {:textAlign "center" :padding "4px 8px"}} (get m :resolutions-executed 0)]])]
      [:div
       [:h2 "Substatus Detail"]
       [:p {:style {:fontSize "0.85em" :color "#f1f5f9"}}
        "Per-scenario substatus for all scenarios with golden reports. "
        "Columns: replay-ok, invariants-pass, attack-successes, volume, disputes, reverts, resolutions."]
       [:div {:style {:overflowX "auto"}}
        [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.85em"}}
         [:thead
          (into [:tr {:style {:background "#f3f4f6" :textAlign "left"}}]
                (map #(vector :th {:style {:padding "5px 8px" :textAlign (if (= % "ID") "left" "center")}} %)
                     ["ID" "Purpose" "replay-ok?" "inv-pass?" "atk-succ"
                      "volume" "disputes" "reverts" "resolutions"]))]
         (into [:tbody] rows)]]]))))

;; ---
;; ## 7. Funds / Accounting Panel
;;
;; Framework-level reconciliation view. Conservation is modeled — it tracks
;; invariant-violation counts, not live ledger entries.
;; Bucket interpretation is adapter-defined.

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Funds / Accounting Panel"
  (fn []
    (let [golds        (or golden-reports {})
          traces       (or all-traces [])
          total-scen   (count traces)
          with-golden  (filter #(contains? golds (:id %)) traces)
          golden-n     (count with-golden)
          missing-n    (- total-scen golden-n)
          drift-total  (reduce +
                               (map #(get-in (get golds (:id %))
                                             [:metrics :invariant-violations] 0)
                                    with-golden))
          conservation? (zero? drift-total)
          funds-rag    (cond (zero? golden-n) :amber
                             (not conservation?) :red
                             :else :green)
          viol-rows    (filter #(pos? (get-in (get golds (:id %))
                                              [:metrics :invariant-violations] 0))
                               with-golden)]
      [:div
       [:h2 "Funds / Accounting Panel"]
       [:p {:style {:fontSize "0.85em" :color "#f1f5f9"}}
        "Framework-level reconciliation view. "
        "Conservation is modeled — tracks invariant-violation counts, not live ledger entries. "
        "Bucket interpretation is adapter-defined."]
       [:table {:style {:borderCollapse "collapse" :width "100%" :marginBottom "12px"}}
        [:thead
         [:tr {:style {:background "#f3f4f6"}}
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Metric"]
          [:th {:style {:padding "6px 10px" :textAlign "right"}} "Value"]
          [:th {:style {:padding "6px 10px" :textAlign "center"}} "Status"]
          [:th {:style {:padding "6px 10px" :textAlign "left"}} "Interpretation"]]]
        [:tbody
         [:tr {:style {:background (if conservation? "#f0fdf4" "#fef2f2")}}
          [:td {:style {:padding "5px 10px"}} "invariant-conservation-holds?"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str conservation?)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (views/status-emoji funds-rag)]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Modeled: zero invariant violations across all golden reports"]]
         [:tr {:style {:background (if (zero? drift-total) "#f0fdf4" "#fef2f2")}}
          [:td {:style {:padding "5px 10px"}} "total-invariant-violations"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str drift-total)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (views/status-emoji (if (zero? drift-total) :green :red))]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Aggregate across all golden corpus entries"]]
         [:tr {:style {:background (if (pos? missing-n) "#fffbeb" "white")}}
          [:td {:style {:padding "5px 10px"}} "scenarios-missing-golden-report"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str missing-n)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (views/status-emoji (if (zero? missing-n) :green :amber))]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Accounting projection not available for all scenarios"]]
         [:tr
          [:td {:style {:padding "5px 10px"}} "scenarios-with-golden-report"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str golden-n)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (views/status-emoji :green)]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Covered by golden report artifact"]]]]
       (when (seq viol-rows)
         [:div {:style {:background "#fef2f2" :border "1px solid #dc2626"
                        :borderRadius "4px" :padding "10px" :marginTop "8px"}}
          [:strong "⚠ Invariant violations found in:"]
          (into [:ul] (map #(vector :li {:style {:fontFamily "monospace" :fontSize "0.85em"}} (:id %))
                           viol-rows))])
       [:p {:style {:fontSize "0.78em" :color "#cbd5e1" :marginTop "8px" :fontStyle "italic"}}
        "Framework-level reconciliation view; bucket interpretation is adapter-defined. "
        "See docs/overview/USE_OF_FUNDS.md for accounting semantics."]]))))

;; ---
;; ## 8. Coverage Summary

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Coverage Summary"
  (fn []
    (if (nil? coverage-data)
      [:div {:style {:background "#fffbeb" :padding "12px" :borderRadius "4px"}}
       "🟠 Coverage artifact not found (results/test-artifacts/coverage.json). Evidence incomplete."]
      (let [cov        coverage-data
            trans-freq (or (:transition-hit-freq cov) {})
            unhit      (or (:unhit-transitions cov) [])
            tag-freq   (or (:threat-tag-freq cov) {})]
        [:div
         [:h2 "Coverage Summary"]
         [:p {:style {:fontSize "0.85em" :color "#f1f5f9"}}
          "Transition and threat-tag coverage derived from trace corpus. "
          "Source: results/test-artifacts/coverage.json"]
         [:div {:style {:display "flex" :gap "16px" :flexWrap "wrap" :marginBottom "12px"}}
          [:div {:style {:background "#f0fdf4" :border "1px solid #16a34a"
                         :borderRadius "4px" :padding "10px" :minWidth "140px"}}
           [:div {:style {:fontSize "1.4em" :fontWeight "bold" :color "#14532d"}} (count trans-freq)]
           [:div {:style {:fontSize "0.82em" :color "#15803d"}} "transitions hit"]]
          [:div {:style {:border       (str "1px solid " (if (seq unhit) "#d97706" "#16a34a"))
                         :borderRadius "4px" :padding "10px" :minWidth "140px"
                         :background   (if (seq unhit) "#fffbeb" "#f0fdf4")}}
           [:div {:style {:fontSize "1.4em" :fontWeight "bold"
                          :color    (if (seq unhit) "#78350f" "#14532d")}} (count unhit)]
           [:div {:style {:fontSize "0.82em"}} "transitions not hit"]]
          [:div {:style {:background "#f0fdf4" :border "1px solid #16a34a"
                         :borderRadius "4px" :padding "10px" :minWidth "140px"}}
           [:div {:style {:fontSize "1.4em" :fontWeight "bold" :color "#14532d"}} (count tag-freq)]
           [:div {:style {:fontSize "0.82em" :color "#15803d"}} "threat tags covered"]]]
         (when (seq unhit)
           [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b"
                          :borderRadius "4px" :padding "10px" :marginBottom "8px"}}
            [:strong "Transitions not hit: "]
            [:span {:style {:fontFamily "monospace" :fontSize "0.85em"}}
             (str/join ", " (map name unhit))]])
         [:details
          [:summary {:style {:cursor "pointer" :fontSize "0.85em" :color "#f1f5f9"}}
           "Show transition hit frequencies"]
          [:table {:style {:borderCollapse "collapse" :fontSize "0.82em" :marginTop "6px"}}
           [:thead
            [:tr
             [:th {:style {:padding "4px 10px" :textAlign "left"}} "Transition"]
             [:th {:style {:padding "4px 10px" :textAlign "right"}} "Hits"]]]
           (into [:tbody]
                 (for [[t n] (sort trans-freq)]
                   [:tr
                    [:td {:style {:padding "3px 10px" :fontFamily "monospace"}} (name t)]
                    [:td {:style {:padding "3px 10px" :textAlign "right"}} (str n)]]))]]])))))

;; ---
;; ## 9. Reproducibility / Provenance Panel

^{:nextjournal.clerk/visibility {:code :fold}}
(clerk/html
 (common/safe-render
  "Reproducibility / Provenance"
  (fn []
    (let [summary  test-summary
          run-id   (or (:run_id summary) "—")
          mode     (or (:mode summary) "—")
          decision (or (:acceptance_decision summary) "UNKNOWN")
          git-sha  (try
                     (str/trim (slurp (io/file ".git/refs/heads/further-refactoring")))
                     (catch Exception _ "unavailable"))
          git-branch (try
                       (let [head (str/trim (slurp (io/file ".git/HEAD")))]
                         (if (str/starts-with? head "ref: refs/heads/")
                           (subs head (count "ref: refs/heads/"))
                           head))
                       (catch Exception _ "unavailable"))
          rows [["Run ID"              (str run-id) ""]
                ["Acceptance decision" decision     ""]
                ["Mode"               mode          ""]
                ["Git SHA"            git-sha       ""]
                ["Git branch"         git-branch    ""]
                ["Suite root"         "data/fixtures/suites" ""]
                ["Params dir"         "data/params"          ""]
                ["Primary artifact"   "results/test-artifacts/test-summary.json" ""]
                ["Deterministic?"     "Yes" "invariant suite and fixture suites are fully deterministic"]
                ["Stochastic?"        "Yes" "Monte Carlo phases (O–AI) use random seeds — see param EDN files"]]]
      [:div
       [:h2 "Reproducibility / Provenance"]
       [:table {:style {:borderCollapse "collapse" :width "100%" :fontSize "0.85em"}}
        (into [:tbody]
              (map-indexed
               (fn [i [k v note]]
                 [:tr {:style {:background (if (odd? i) "#fafafa" "white")}}
                  [:th {:style {:padding "5px 10px" :textAlign "left"
                                :background "#f3f4f6" :width "200px"}} k]
                  [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} v]
                  (when (seq note)
                    [:td {:style {:padding "5px 10px" :fontSize "0.82em" :color "#cbd5e1"}} note])])
               rows))]
       [:p {:style {:fontSize "0.78em" :color "#cbd5e1" :marginTop "8px"}}
        "To reproduce: " [:code "clojure -M:run -- --invariants"] " (deterministic) or "
        [:code "scripts/monte-carlo/test-all.sh"] " (stochastic phases)."]]))))
