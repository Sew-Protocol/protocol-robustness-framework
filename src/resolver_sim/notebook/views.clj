(ns resolver-sim.notebook.views
  "Clerk display helpers for research notebooks."
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook.checks :as checks]))

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
  "Evidence-aware, low-confidence-first failure class derivation."
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

