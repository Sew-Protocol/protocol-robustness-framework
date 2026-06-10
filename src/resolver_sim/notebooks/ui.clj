(ns resolver-sim.notebooks.ui
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def default-ui-state
  {:selected-trial-id nil
   :selected-entity-id nil
   :filters {:outcome ""
             :invariants ""}
   :pagination {:limit 100}})

(defn rag-badge [rag text]
  (let [[bg border fg emoji]
        (case rag
          :green ["#dcfce7" "#16a34a" "#166534" "🟢"]
          :red   ["#fee2e2" "#dc2626" "#7f1d1d" "🔴"]
          ["#fef3c7" "#f59e0b" "#92400e" "🟠"])]
    [:span {:style {:display "inline-block" :padding "2px 8px"
                    :borderRadius "999px" :border (str "1px solid " border)
                    :backgroundColor bg :color fg :fontSize "0.75em" :fontWeight "600"}}
     (str emoji " " text)]))

(defn callout [rag body]
  (let [[bg border fg]
        (case rag
          :green ["#f0fdf4" "#16a34a" "#166534"]
          :red   ["#fef2f2" "#dc2626" "#7f1d1d"]
          ["#fffbeb" "#f59e0b" "#92400e"])]
    [:div {:style {:padding "12px 16px" :backgroundColor bg
                   :border (str "1px solid " border) :borderRadius "4px"
                   :marginBottom "12px" :color fg}}
     body]))

(defn query-error-callout
  ([title {:keys [kind error sql]}]
   (callout :red
            [:div
             [:strong (or title "Query error")]
             (when kind
               [:div {:style {:marginTop "6px" :fontSize "0.86em"}} (str "kind=" kind)])
             (when error
               [:div {:style {:marginTop "4px" :fontFamily "monospace" :fontSize "0.8em"}} error])
             (when sql
               [:details {:style {:marginTop "6px"}}
                [:summary {:style {:cursor "pointer"}} "SQL"]
                [:pre {:style {:fontSize "0.78em" :marginTop "6px"}} (pr-str sql)]])]))
  ([m]
   (query-error-callout "Query error" m)))

(defn metric-card [{:keys [label value rag note]}]
  [:div {:style {:border "1px solid #e2e8f0" :borderRadius "6px" :padding "10px" :minWidth "140px"}}
   [:div {:style {:display "flex" :gap "6px" :alignItems "center"}}
    (rag-badge rag label)]
   [:div {:style {:fontFamily "monospace" :fontSize "1.1em" :marginTop "4px"}} (str value)]
   (when note [:div {:style {:fontSize "0.75em" :color "#475569" :marginTop "4px"}} note])])

(defn filter-controls [!ui-state]
  (let [{:keys [filters pagination]} @!ui-state]
    [:div {:style {:backgroundColor "#eff6ff" :padding "12px" :borderRadius "4px"
                   :border "1px solid #93c5fd" :marginBottom "12px"}}
     [:h3 {:style {:margin "0 0 8px 0"}} "Filters"]
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 140px" :gap "8px"}}
      [:input {:type "text" :placeholder "Outcome (released/slashed/disputed)"
               :value (or (:outcome filters) "")
               :on-change #(swap! !ui-state assoc-in [:filters :outcome] (.. % -target -value))}]
      [:input {:type "text" :placeholder "Invariants: pass/fail"
               :value (or (:invariants filters) "")
               :on-change #(swap! !ui-state assoc-in [:filters :invariants] (.. % -target -value))}]
      [:input {:type "number" :min "1" :max "1000"
               :value (or (get pagination :limit) 100)
               :on-change #(let [raw (.. % -target -value)
                                 v   (try (Long/parseLong (str raw)) (catch Exception _ 100))
                                 v*  (-> v (max 1) (min 1000))]
                             (swap! !ui-state assoc-in [:pagination :limit] v*))}]]]))

(defn trial-selection-controls [!ui-state]
  (let [selected (:selected-trial-id @!ui-state)]
    [:div {:style {:backgroundColor "#fff7ed" :padding "12px" :borderRadius "4px"
                   :border "1px solid #fdba74" :marginBottom "12px"}}
     [:h3 {:style {:margin "0 0 8px 0"}} "Selected Trial"]
     [:p {:style {:fontSize "0.82em" :margin "0 0 8px 0" :color "#7c2d12"}}
      "Paste a trial UUID directly (works even if row-click interaction is unavailable)."]
     [:div {:style {:display "flex" :gap "8px"}}
      [:input {:type "text"
               :placeholder "Paste full trial UUID"
               :value (or selected "")
               :style {:width "100%" :padding "6px 8px" :fontFamily "monospace"}
               :on-change #(let [v (.. % -target -value)
                                 v* (when-not (str/blank? v) v)]
                             (swap! !ui-state assoc :selected-trial-id v*))}]
      [:button {:on-click #(swap! !ui-state assoc :selected-trial-id nil)
                :style {:padding "6px 10px" :cursor "pointer"}}
       "Clear"]]
     [:div {:style {:fontSize "0.78em" :marginTop "6px" :color "#9a3412"}}
      (str "Current selected-trial-id=" (pr-str selected))]]))

(defn select-trial-view [!ui-state rows]
  [:div {:style {:overflowX "auto"}}
   [:table {:style {:borderCollapse "collapse" :fontSize "0.83em" :width "100%"}}
   [:thead
    [:tr {:style {:backgroundColor "#f1f5f9"}}
     [:th {:style {:padding "6px 10px" :textAlign "left"}} "Trial ID"]
     [:th {:style {:padding "6px 10px" :textAlign "left"}} "Batch"]
     [:th {:style {:padding "6px 10px" :textAlign "left"}} "Protocol"]
     [:th {:style {:padding "6px 10px" :textAlign "left"}} "Outcome"]
     [:th {:style {:padding "6px 10px" :textAlign "center"}} "invariants_ok"]
     [:th {:style {:padding "6px 10px" :textAlign "center"}} "divergence"]]]
   (into [:tbody]
         (for [{:keys [trial-id batch-id protocol-id outcome invariants-ok divergence]} rows]
           [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
            [:td {:style {:padding "5px 10px" :fontFamily "monospace" :fontSize "0.8em"
                          :cursor "pointer" :color "#1d4ed8" :fontWeight "600"
                          :whiteSpace "nowrap" :minWidth "320px" :userSelect "text"}
                  :title (str trial-id)
                  :on-click #(swap! !ui-state assoc :selected-trial-id trial-id)}
             (str trial-id)]
            [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} (str batch-id)]
            [:td {:style {:padding "5px 10px" :fontFamily "monospace"}} (str protocol-id)]
            [:td {:style {:padding "5px 10px"}} (str outcome)]
            [:td {:style {:padding "5px 10px" :textAlign "center"}}
             (if invariants-ok (rag-badge :green "pass") (rag-badge :red "fail"))]
            [:td {:style {:padding "5px 10px" :textAlign "center"}}
             (if divergence (rag-badge :amber "yes") (rag-badge :green "no"))]]))]])

(defn event-timeline-view [events]
  (if (seq events)
    [:table {:style {:borderCollapse "collapse" :fontSize "0.8em" :width "100%"}}
     [:thead
      [:tr {:style {:backgroundColor "#f1f5f9"}}
       [:th {:style {:padding "6px 10px" :textAlign "left"}} "#"]
       [:th {:style {:padding "6px 10px" :textAlign "left"}} "time"]
       [:th {:style {:padding "6px 10px" :textAlign "left"}} "entity_id"]
       [:th {:style {:padding "6px 10px" :textAlign "left"}} "event_type"]
       [:th {:style {:padding "6px 10px" :textAlign "left"}} "entity_state"]]]
     (into [:tbody]
           (map-indexed
            (fn [i {:keys [block-time entity-id event-type entity-state]}]
              [:tr {:style {:borderBottom "1px solid #e2e8f0"}}
               [:td {:style {:padding "4px 10px"}} (inc i)]
               [:td {:style {:padding "4px 10px" :fontFamily "monospace"}} (str block-time)]
               [:td {:style {:padding "4px 10px" :fontFamily "monospace"}} (str entity-id)]
               [:td {:style {:padding "4px 10px"}} (str event-type)]
               [:td {:style {:padding "4px 10px" :fontFamily "monospace"}} (str entity-state)]])
            events))]
    (callout :amber [:div "No events recorded for this trial."])))

(defn entity-history-view [events entity-id]
  (let [rows (filter #(= (str entity-id) (str (:entity-id %))) events)]
    (event-timeline-view rows)))

(defn notebook-navigation
  ([] (notebook-navigation nil nil))
  ([current-label] (notebook-navigation current-label nil))
  ([current-label custom-links]
   (let [default-links [{:label "Dispute Resolution Workbench" :href "/notebooks/dispute_resolution"}
                        {:label "Report Notebook" :href "/notebooks/report"}
                        {:label "Invariant Failure Triage" :href "/notebooks/invariant_failures"}
                        {:label "Telemetry Trial Timeline" :href "/notebooks/telemetry"}
                        {:label "XTDB Overview" :href "/notebooks/xtdb_overview"}]
         links (or custom-links default-links)]
     [:div {:style {:backgroundColor "#eef2ff" :border "1px solid #a5b4fc" :borderRadius "6px"
                    :padding "10px 12px" :marginBottom "12px"}}
      [:div {:style {:fontWeight "600" :marginBottom "6px"}} "Notebook navigation"]
      [:div {:style {:display "flex" :gap "10px" :flexWrap "wrap" :fontSize "0.9em"}}
       (for [{:keys [label href]} links]
         [:a {:href href :style {:color "#1d4ed8"}} label])
       (when current-label
         [:span {:style {:color "#334155"}} (str "• current: " current-label)])]])))

(defn reference-validation-status []
  (let [required ["suites/reference-validation-v1/expected/summary.json"
                  "suites/reference-validation-v1/actual/summary.json"
                  "suites/reference-validation-v1/expected/summary.sha256"
                  "suites/reference-validation-v1/actual/summary.sha256"
                  "suites/reference-validation-v1/reports/reference-validation-v1.md"]
        present? (fn [p] (.exists (io/file p)))
        present (filter present? required)
        missing (remove present? required)
        rag (cond
              (empty? present) :red
              (seq missing) :amber
              :else :green)]
    (callout rag
             [:div
              [:strong "Reference validation status"]
              [:div {:style {:marginTop "6px" :fontSize "0.86em"}}
               (str (count present) "/" (count required) " core artifacts present")]
              (when (seq missing)
                [:details {:style {:marginTop "6px"}}
                 [:summary "Missing artifacts"]
                 [:ul {:style {:margin "6px 0 0 18px"}}
                  (for [m missing] [:li m])]])
              [:div {:style {:marginTop "8px" :fontSize "0.86em"}}
               [:a {:href "/notebooks/report" :style {:color "#1d4ed8"}} "Open Report notebook"]
               " · "
               [:a {:href "suites/reference-validation-v1/reports/reference-validation-v1.md" :style {:color "#1d4ed8"}}
                "Open reference validation report"]]])))

(defn provenance-footer [run]
  (let [manifest (:manifest run)
        registry (:registry run)
        run-id   (get manifest :run_id "—")
        registry-sha (or (get-in manifest [:framework :registry_sha256]) "—")]
    [:div {:style {:marginTop "40px" :padding "20px" :borderTop "1px solid #e2e8f0" :fontSize "0.75em" :color "#64748b"}}
     [:div "Artifact provenance:"]
     [:div {:style {:fontFamily "monospace"}}
      "Run ID: " run-id [:br]
      "Registry Hash: " registry-sha]]))

