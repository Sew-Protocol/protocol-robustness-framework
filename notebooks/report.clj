;; # Sew Protocol — Evidence Workbook
;;
;; **Audience:** Protocol reviewers, security researchers, contributors.
;;
;; **Purpose:** At-a-glance evidence surface for a validation run. Every
;; status indicator is paired with what it means, what it does *not* mean,
;; and the source artifact it is derived from.
;;
;; **Not a marketing dashboard.** This notebook does not smooth over known
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

;; Settings: default-code-visibility = :hide (hidden) or :show (visible)
^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(ns notebooks.report
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.notebooks.ui :as ui]
            [resolver-sim.notebooks.common :as common]
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

^{:nextjournal.clerk/visibility {:result :hide}}
(def test-summary (speds-data/load-summary))

^{:nextjournal.clerk/visibility {:result :hide}}
(def coverage-data (speds-data/load-coverage))

^{:nextjournal.clerk/visibility {:result :hide}}
(def equivalence-summary (speds-data/load-equivalence))

^{:nextjournal.clerk/visibility {:result :hide}}
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

^{:nextjournal.clerk/visibility {:result :hide}}
(def golden-reports (speds-data/load-all-golden-reports))

;; ---
;; ## R/A/G Status Helpers (pure, auditable)

(defn rag-status
  "Returns :green, :amber, or :red from a keyword, map {:rag/status kw}, or default amber."
  [x]
  (cond
    (keyword? x) (case x
                   (:green :pass :ok :hold)             :green
                   (:amber :warn :missing :inconclusive) :amber
                   (:red :fail :error :violation)        :red
                   :amber)
    (map? x)     (rag-status (or (:rag x) (:status x) :amber))
    :else         :amber))

(defn status-emoji [rag]
  (case rag :green "🟢" :amber "🟠" :red "🔴" "⚪"))

(defn status-label [rag]
  (case rag
    :green "Pass"
    :amber "Inconclusive / Warning"
    :red   "Fail / Finding"
    "Unknown"))

(defn status-kind-label [k]
  (case k
    :validation        "Validation"
    :research-finding  "Research finding"
    :expected-negative "Expected negative"
    :missing-data      "Missing data"
    (str k)))

(defn target-status->rag [s]
  (case (str s)
    "pass"    :green
    "fail"    :red
    "unknown" :amber
    :amber))

(defn risk-digest->rag [digest]
  (cond
    (nil? digest)                     :amber
    (seq (:critical_findings digest)) :red
    (seq (:warnings digest))          :amber
    :else                              :green))

(defn scenario-purpose->status-kind [purpose]
  (case (str purpose)
    "regression"             :validation
    "adversarial-robustness" :validation
    "theory-falsification"   :expected-negative
    "research"               :research-finding
    :validation))

(defn scenario-row->rag
  "Derives RAG + status-kind + reason for a scenario row.
  Returns {:rag kw :status-kind kw :reason str}."
  [{:keys [purpose]} golden]
  (let [kind       (scenario-purpose->status-kind purpose)
        outcome    (:outcome golden)
        violations (get-in golden [:metrics :invariant-violations] 0)
        atk-succ   (get-in golden [:metrics :attack-successes] 0)]
    (cond
      (nil? golden)
      {:rag :amber :status-kind :missing-data
       :reason "No golden report — replay outcome unknown"}

      (pos? violations)
      {:rag :red :status-kind :validation
       :reason (str "Invariant violation(s): " violations)}

      (and (= purpose "theory-falsification") (pos? atk-succ))
      {:rag :red :status-kind :expected-negative
       :reason (str "Theory falsified — attack succeeded (" atk-succ
                    "). Expected evidence, not a CI failure.")}

      (and (= outcome :pass) (pos? atk-succ) (not= purpose "theory-falsification"))
      {:rag :amber :status-kind :research-finding
       :reason (str "Attack succeeded (" atk-succ ") but invariants held — research finding")}

      (= outcome :pass)
      {:rag :green :status-kind kind :reason "Invariants pass; outcome as expected"}

      (= outcome :fail)
      {:rag :red :status-kind :validation
       :reason "Unexpected failure — invariant or expectation not met"}

      :else
      {:rag :amber :status-kind :missing-data
       :reason (str "Outcome: " (pr-str outcome))})))

(defn classify-validation-failure
  "Evidence-aware, low-confidence-first failure class derivation.
   Returns — when available data does not strongly support a class label."
  [{:keys [threat-tags]} golden status-kind]
  (let [tags (set (map #(-> % name str/lower-case) (or threat-tags [])))
        tag-text (str/join " " tags)
        outcome (:outcome golden)
        reverts (get-in golden [:metrics :reverts] 0)
        resolutions (get-in golden [:metrics :resolutions-executed] 0)
        tag-has? (fn [needle] (str/includes? tag-text needle))]
    (cond
      (= status-kind :missing-data)
      "no replay artifact"

      (not= status-kind :validation)
      "—"

      (= outcome :pass)
      "—"

      (and (= outcome :fail) (pos? reverts) (zero? resolutions))
      "likely unexpected revert / missing terminal state"

      (or (tag-has? "timeout") (tag-has? "deadline"))
      "timeout behavior mismatch"

      (or (tag-has? "appeal") (tag-has? "escalation"))
      "escalation lifecycle mismatch"

      (or (tag-has? "authorization")
          (tag-has? "unauthorized")
          (tag-has? "auth"))
      "authorization policy mismatch"

      (or (tag-has? "state-leak")
          (tag-has? "state_leak")
          (tag-has? "status-leak")
          (tag-has? "state-machine")
          (tag-has? "guard"))
      "state machine guard mismatch"

      :else
      "—")))

;; ---
;; ## Card / Layout Helpers

(defn- card-style [rag]
  (let [border (case rag :green "#16a34a" :red "#dc2626" "#d97706")
        bg     (case rag :green "#f0fdf4" :red "#fef2f2" "#fffbeb")
        color  (case rag :green "#14532d" :red "#7f1d1d" "#78350f")]
    {:borderLeft   (str "4px solid " border)
     :background   bg
     :color        color
     :padding      "12px 16px"
     :borderRadius "4px"
     :marginBottom "8px"}))

(defn render-card [{:keys [label rag value note]}]
  [:div {:style (card-style rag)}
   [:div {:style {:display "flex" :alignItems "baseline" :gap "8px"}}
    [:span {:style {:fontSize "1.1em"}} (status-emoji rag)]
    [:strong label]
    [:span {:style {:fontSize "0.95em" :opacity "0.85"}} value]]
   (when note
     [:div {:style {:fontSize "0.82em" :marginTop "4px" :opacity "0.8"}} note])])

;; ---
;; ## 1. Evidence Control Panel

^{:nextjournal.clerk/visibility {:code :hide}}
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
          golden-n   (count golden-reports)
          trace-n    (count all-traces)
          corpus-rag (if (zero? trace-n) :amber :green)
          total-inv  (reduce + (map #(get-in % [:metrics :invariant-violations] 0)
                                    (vals (or golden-reports {}))))
          funds-rag  (cond (zero? golden-n) :amber (pos? total-inv) :red :else :green)
          replay-outcomes (frequencies (map :outcome (vals (or golden-reports {}))))
          replay-pass-n (get replay-outcomes :pass 0)
          replay-fail-n (get replay-outcomes :fail 0)
          replay-missing-n (- trace-n golden-n)
          replay-rag (cond
                       (pos? replay-fail-n) :red
                       (pos? replay-missing-n) :amber
                       :else :green)
          expected-negative-n (count (filter #(= :expected-negative
                                                  (:status-kind (scenario-row->rag % (get golden-reports (:id %)))))
                                            (or all-traces [])))
          vuln-count (count (filter #(= "theory-falsification" (:purpose %))
                                    (or all-traces [])))
          vuln-rag   (if (pos? vuln-count) :amber :green)
          run-id     (or (:run_id summary) "—")
          decision   (or (:acceptance_decision summary) "UNKNOWN")]
      [:div
       [:h2 "Evidence Control Panel"]
       [:p {:style {:color "#555" :fontSize "0.9em"}}
        "Aggregated status from the most recent validation run. "
        "Inspect each panel for detail before acting on colour alone."]
       [:div {:style {:background "#eff6ff" :border "1px solid #93c5fd" :borderRadius "6px"
                      :padding "10px 12px" :marginBottom "10px" :fontSize "0.84em" :color "#1e3a8a"}}
        [:strong "Why CI can be green while corpus is non-clean: "]
        "PASS_CLEAN means the configured CI targets completed successfully. "
        "It does not mean every trace in the evidence corpus has a passing golden replay. "
        "The corpus includes additional research and replay artifacts whose status is reported separately in this workbook."]
       [:div {:style {:fontSize "0.82em" :color "#444" :marginBottom "12px"
                      :fontFamily "monospace" :background "#f5f5f5"
                      :padding "6px 10px" :borderRadius "3px"}}
        (str "Run ID: " run-id " │ Decision: " decision
             " │ Targets: " (get-in sc [:targets :total] "—")
             " │ traces=" trace-n ", golden=" golden-n)]
       (render-card
        {:label "Top-level interpretation"
         :rag   (if (or (pos? replay-fail-n) (pos? replay-missing-n)) :red :green)
         :value (if (or (pos? replay-fail-n) (pos? replay-missing-n))
                  "CI targets passed, but replay corpus contains validation failures or missing evidence"
                  "CI targets and replay corpus are aligned")
         :note  "Do not interpret PASS_CLEAN alone as protocol-clean. Evaluate replay corpus status and scenario matrix together."})
       (render-card
        {:label "CI target gate"
         :rag   gate-rag
         :value (str (get-in sc [:targets :pass] "—")
                     "/" (get-in sc [:targets :total] "—") " targets pass")
         :note  "Hard gate: did all canonical test targets complete successfully? Source: test-summary.json"})
       (render-card
        {:label "Replay corpus status"
         :rag   replay-rag
         :value (str replay-pass-n " pass, " replay-fail-n " fail, " replay-missing-n " missing golden")
         :note  "Scenario replay/evidence status from golden corpus. Failures/missing data keep evidence status non-clean even when CI gate passes."})
       (render-card
        {:label "Structured risk digest criticals"
         :rag   risk-rag
         :value (str critical-n)
         :note  (str "Structured critical findings in risk_digest. "
                     (if (pos? critical-n) "Review test-summary.json immediately." "Clean."))})
       (render-card
        {:label "Replay validation failures requiring investigation"
         :rag   (if (pos? replay-fail-n) :red :green)
         :value (str replay-fail-n)
         :note  "Count of scenarios with status-kind :validation (requires investigation)."})
       (render-card
        {:label "Expected-negative findings"
         :rag   (if (pos? expected-negative-n) :amber :green)
         :value (str expected-negative-n)
         :note  "Count of scenarios with status-kind :expected-negative (theory-falsification evidence)."})
       (render-card
        {:label "Warnings"
         :rag   warn-rag
         :value (str warning-n " warning(s)")
         :note  "Warning count from risk_digest. Amber = warnings present; does not imply protocol failure."})
       (render-card
        {:label "Trace corpus coverage"
         :rag   corpus-rag
         :value (str trace-n " traces; " golden-n " with golden outcome")
         :note  (str (- trace-n golden-n) " traces have no golden report (amber in matrix). "
                     "Source: data/fixtures/traces/ and data/fixtures/golden/")})
       (render-card
        {:label "Invariant conservation"
         :rag   funds-rag
         :value (str "Total invariant violations across corpus: " total-inv)
         :note  "Aggregated violations from all golden reports. Non-zero = protocol invariant breached — investigate."})
       (render-card
        {:label "Theory-falsification scenarios declared"
         :rag   vuln-rag
         :value (str vuln-count " theory-falsification scenario(s)")
         :note  "Known theory-falsification scenarios exist. These are research findings, NOT test failures. See status-kind in Scenario Matrix."})]))))

;; ---
;; ## 2. Corpus Evidence Status

^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/html
 (common/safe-render
  "Corpus Evidence Status"
  (fn []
    (let [traces (or all-traces [])
          golds (or golden-reports {})
          total-traces (count traces)
          golden-count (count golds)
          missing-count (max 0 (- total-traces golden-count))
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
       [:p {:style {:fontSize "0.85em" :color "#555"}}
        "Separate corpus-level evidence summary to track replay outcome coverage and quality independently of CI target status."]
       [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b" :borderRadius "6px" :padding "10px 12px" :marginBottom "10px" :fontSize "0.84em"}}
        [:strong "Coverage statement: "]
        (str "Only " golden-count "/" total-traces
             " traces currently have replay outcome artifacts. Coverage is broad at trace metadata level, but evidence-backed replay coverage is partial.")]
       [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit, minmax(180px, 1fr))" :gap "10px" :marginBottom "10px"}}
        (render-card {:label "Evidence status" :rag evidence-rag :value (name evidence-rag)
                      :note "Red: replay fails present. Amber: missing replay outcomes. Green: full evidence-backed pass."})
        (render-card {:label "Trace metadata coverage" :rag (if (pos? total-traces) :green :amber)
                      :value (str total-traces " traces") :note "Total scenarios discovered in trace metadata."})
        (render-card {:label "Replay outcome coverage" :rag (if (zero? missing-count) :green :amber)
                      :value (str golden-count "/" total-traces) :note "Scenarios with golden replay artifacts."})
        (render-card {:label "Replay failures" :rag (if (pos? fail-count) :red :green)
                      :value (str fail-count) :note "Golden outcomes marked :fail."})
        (render-card {:label "Missing replay artifacts" :rag (if (pos? missing-count) :amber :green)
                      :value (str missing-count) :note "Traces lacking golden outcome artifacts."})
        (render-card {:label "Replay passes" :rag :green :value (str pass-count)
                      :note "Golden outcomes marked :pass."})]]))))

;; ---
;; ## 3. Validation Work Queue (Top 10)

^{:nextjournal.clerk/visibility {:code :hide}}
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
                    (map (fn [t]
                           (let [golden (get golden-reports (:id t))
                                 verdict (scenario-row->rag t golden)]
                             {:id (:id t)
                              :title (:title t)
                              :status-kind (:status-kind verdict)
                              :replay-outcome (if golden (name (:outcome golden)) "missing")
                              :failure-class (classify-validation-failure t golden (:status-kind verdict))})))
                    (sort-by :id))]
      [:div
       [:h2 "Validation Work Queue (Top 10)"]
       [:p {:style {:fontSize "0.84em" :color "#555"}}
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
                    [:td {:style {:padding "5px 8px"}} (status-kind-label (:status-kind r))]
                    [:td {:style {:padding "5px 8px" :textAlign "center"}} (:replay-outcome r)]
                    [:td {:style {:padding "5px 8px"}} (:failure-class r)] ]))]]
         (ui/callout :amber [:div "No queue scenarios found in current trace corpus."]))]))) )

;; Debug panel for notebook sync/path drift issues.
;; This makes the data source explicit so stale sessions / wrong working dir are obvious.
^{:nextjournal.clerk/visibility {:code :hide}}
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

^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/html
 (common/safe-render
  "Scenario Matrix"
  (fn []
    (let [traces (or all-traces [])
          golds (or golden-reports {})
          rows (for [t traces
                     :let [golden (get golds (:id t))
                           verdict (scenario-row->rag t golden)
                           rag (:rag verdict)
                           kind (:status-kind verdict)
                           reason (:reason verdict)
                           failure-class (classify-validation-failure t golden kind)
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
                  :status (status-emoji rag)
                  :status-kind (status-kind-label kind)
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
                                              ["Status" "status-kind" "ID" "Title" "Purpose" "Failure class" "Threat tags" "Inv.viol" "Atk.succ" "Outcome" "Reason"]
                                              ["Status" "status-kind" "ID" "Title" "Purpose" "Threat tags" "Inv.viol" "Atk.succ" "Outcome" "Reason"]))) ]
                                (into [:tbody]
                                      (for [r rows']
                                        [:tr {:style {:background (:bg r)}}
                                         [:td {:style {:textAlign "center" :fontSize "1.1em"}} (:status r)]
                                         [:td {:style {:fontSize "0.75em" :color "#555"}} (:status-kind r)]
                                         [:td {:style {:fontFamily "monospace" :fontSize "0.8em" :whiteSpace "nowrap"}} (:id r)]
                                         [:td {:style {:fontSize "0.82em" :maxWidth "220px"}} (:title r)]
                                         [:td {:style {:fontSize "0.78em"}} (:purpose r)]
                                         (when validation?
                                           [:td {:style {:fontSize "0.78em" :maxWidth "170px"}} (:failure-class r)])
                                         [:td {:style {:fontSize "0.78em" :maxWidth "160px"}} (:tags r)]
                                         [:td {:style {:textAlign "center"}} (:inv-v r)]
                                         [:td {:style {:textAlign "center"}} (:atk-s r)]
                                         [:td {:style {:fontSize "0.78em"}} (:outcome r)]
                                         [:td {:style {:fontSize "0.72em" :color "#555" :maxWidth "220px"}} (:reason r)]]))]]
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
       [:p {:style {:fontSize "0.85em" :color "#555"}}
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
;; ## 4. Validation Failures Triage

(defn triage-next-action
  "Reviewer-oriented next action from likely failure class."
  [likely-class invariants-pass?]
  (cond
    (not invariants-pass?) "Escalate: investigate invariant breach and funds/accounting state first"
    (= likely-class "unexpected revert") "Inspect revert reason and authorization/guard preconditions"
    (= likely-class "missing terminal state") "Trace terminal transition path and verify fixture completion conditions"
    (= likely-class "unauthorized path accepted/rejected mismatch") "Reconcile auth policy expectations vs observed path outcome"
    (= likely-class "timeout behavior mismatch") "Review deadlines/timeout boundary assumptions and event ordering"
    (= likely-class "escalation lifecycle mismatch") "Audit escalation lifecycle transitions and pending-state clearing"
    :else "Compare expected outcome contract vs replay artifact for mismatch source"))

^{:nextjournal.clerk/visibility {:code :hide}}
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
                             verdict (scenario-row->rag t golden)
                             status-kind (:status-kind verdict)
                             replay-ok? (and golden (not= :fail (:outcome golden)))
                             invariants-pass? (boolean (and golden (zero? (get-in golden [:metrics :invariant-violations] 0))))
                             likely-class (classify-validation-failure t golden status-kind)]
                         (when (= status-kind :validation)
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
                            :next-action (triage-next-action likely-class invariants-pass?)}))))
               (sort-by (juxt (fn [r] (if (:invariants-pass r) 1 0)) :id)))]
      [:div
       [:h2 "Validation Failures Triage"]
       [:p {:style {:fontSize "0.84em" :color "#555"}}
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
;; ## 5. Substatus Detail
;;
;; Expanded columns for scenarios with a golden report.
;; Helps distinguish: protocol invariant pass, research claim status,
;; adversarial success, and accounting metrics.

^{:nextjournal.clerk/visibility {:code :hide}}
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
       [:p {:style {:fontSize "0.85em" :color "#555"}}
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
;; ## 4. Research Interpretation Layer
;;
;; This section explains the dual semantics of red in this workbook.

^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/html
 (common/safe-render
  "Research Interpretation Layer"
  (fn []
    [:div {:style {:background "#fffbeb" :border "1px solid #f59e0b"
                   :borderRadius "6px" :padding "16px 20px" :margin "16px 0"}}
     [:h3 {:style {:marginTop "0" :color "#78350f"}} "⚠ Research Interpretation Note"]
     [:p "Red in this workbook can mean one of two things:"]
     [:ol
      [:li [:strong "A validation failure"] " — an invariant was violated, funds drifted, or an expected outcome was not met. These require investigation."]
      [:li [:strong "A successful falsification / vulnerability finding"] " — a scenario designed to demonstrate a known protocol limitation. "
       "This is expected evidence, not a build breakage."]]
     [:p "Always inspect " [:code "status-kind"] " before interpreting colour:"]
     [:ul
      [:li [:code ":validation"] " — hard gate check; red = requires investigation"]
      [:li [:code ":expected-negative"] " — theory-falsification; red = expected evidence of known limitation"]
      [:li [:code ":research-finding"] " — model/hypothesis result; amber/red = inconclusive or partial result"]
      [:li [:code ":missing-data"] " — artifact absent; amber = incomplete evidence, not failure"]]
     [:p {:style {:fontSize "0.85em" :color "#555"}}
      "Examples: "
      [:em "governance-decay-exploit"] " red → vulnerability evidence (expected, not CI fail). "
      [:em "invariants"] " target red → hard validation failure. "
      [:em "funds-drift ≠ 0"] " red → accounting failure. "
      [:em "missing artifact"] " amber → incomplete evidence."]])))

;; ---
;; ## 5. Funds / Accounting Panel
;;
;; Framework-level reconciliation view. Conservation is modeled — it tracks
;; invariant-violation counts, not live ledger entries.
;; Bucket interpretation is adapter-defined.

^{:nextjournal.clerk/visibility {:code :hide}}
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
       [:p {:style {:fontSize "0.85em" :color "#555"}}
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
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (status-emoji funds-rag)]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Modeled: zero invariant violations across all golden reports"]]
         [:tr {:style {:background (if (zero? drift-total) "#f0fdf4" "#fef2f2")}}
          [:td {:style {:padding "5px 10px"}} "total-invariant-violations"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str drift-total)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (status-emoji (if (zero? drift-total) :green :red))]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Aggregate across all golden corpus entries"]]
         [:tr {:style {:background (if (pos? missing-n) "#fffbeb" "white")}}
          [:td {:style {:padding "5px 10px"}} "scenarios-missing-golden-report"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str missing-n)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (status-emoji (if (zero? missing-n) :green :amber))]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Accounting projection not available for all scenarios"]]
         [:tr
          [:td {:style {:padding "5px 10px"}} "scenarios-with-golden-report"]
          [:td {:style {:padding "5px 10px" :textAlign "right" :fontFamily "monospace"}} (str golden-n)]
          [:td {:style {:padding "5px 10px" :textAlign "center"}} (status-emoji :green)]
          [:td {:style {:padding "5px 10px" :fontSize "0.85em"}} "Covered by golden report artifact"]]]]
       (when (seq viol-rows)
         [:div {:style {:background "#fef2f2" :border "1px solid #dc2626"
                        :borderRadius "4px" :padding "10px" :marginTop "8px"}}
          [:strong "⚠ Invariant violations found in:"]
          (into [:ul] (map #(vector :li {:style {:fontFamily "monospace" :fontSize "0.85em"}} (:id %))
                           viol-rows))])
       [:p {:style {:fontSize "0.78em" :color "#666" :marginTop "8px" :fontStyle "italic"}}
        "Framework-level reconciliation view; bucket interpretation is adapter-defined. "
        "See docs/overview/USE_OF_FUNDS.md for accounting semantics."]]))))

;; ---
;; ## 6. Coverage Summary

^{:nextjournal.clerk/visibility {:code :hide}}
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
         [:p {:style {:fontSize "0.85em" :color "#555"}}
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
          [:summary {:style {:cursor "pointer" :fontSize "0.85em" :color "#555"}}
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
;; ## 7. Replay / Temporal Inspection — Reserved
;;
;; *Not yet implemented.* Reserved for the replay timeline inspector,
;; state-transition viewer, and first-divergence finder.
;;
;; Intended contents:
;; - Scenario selector
;; - Ordered event list (from trace events array)
;; - Before/after world state summary
;; - First divergence point between counterfactual pairs
;; - Invariant snapshots over time

^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/html
 [:div {:style {:background "#f8fafc" :border "1px dashed #94a3b8"
                :borderRadius "6px" :padding "16px 20px" :color "#475569"}}
  [:h3 {:style {:marginTop "0"}} "⏱ Replay / Temporal Inspection — Reserved"]
  [:p "This section will provide:"]
  [:ul
   [:li "Scenario selector for individual trace playback"]
   [:li "Ordered event list from the trace events array"]
   [:li "Before / after world-state summary at each step"]
   [:li "First divergence point between counterfactual pairs"]
   [:li "Invariant snapshots over simulation time"]]
  [:p {:style {:fontSize "0.82em" :fontStyle "italic"}}
   "Evidence is already present in data/fixtures/traces/*.trace.json (events arrays). "
   "Implementation deferred to a later phase of the workbook."]])

;; ---
;; ## 8. Reproducibility / Provenance Panel

^{:nextjournal.clerk/visibility {:code :hide}}
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
                    [:td {:style {:padding "5px 10px" :fontSize "0.82em" :color "#666"}} note])])
               rows))]
       [:p {:style {:fontSize "0.78em" :color "#666" :marginTop "8px"}}
        "To reproduce: " [:code "clojure -M:run -- --invariants"] " (deterministic) or "
        [:code "scripts/monte-carlo/test-all.sh"] " (stochastic phases)."]]))))
