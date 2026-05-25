(ns notebooks.workbench-production
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebooks.speds.core :as speds]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.story :as story]
            [resolver-sim.notebooks.speds.tokens :as tokens]
            [resolver-sim.notebooks.speds.config :as config]
            [clojure.string :as str]))

;; # Sew Protocol — Production Evidence Workbench
;; ## Integrated Robustness Atlas & Narrative Engine

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 (let [artifacts (data/load-run-artifacts)
       {:keys [summary coverage manifest]} artifacts
        scenarios (sort-by :id (:scenarios coverage))
        {:keys [replay-match-label scenario-count]} (data/narrative-metrics artifacts)]
   [:div.workbench-container
    [:style "
      /* Force full width on all possible Clerk containers */
      .clerk-view, .viewer-notebook, .prose, .max-w-prose, .max-w-5xl, .mx-auto { 
        max-width: none !important; 
        width: 100% !important; 
        margin-left: 0 !important; 
        margin-right: 0 !important; 
        padding: 0 !important; 
      }
      .workbench-container { 
        font-family: 'Inter', sans-serif;
        background: #020617; 
        color: #7ADDDC; 
        padding: 60px;
        min-height: 100vh;
        width: 100%;
      }
      .hero-strip {
        display: grid;
        grid-template-columns: repeat(4, 1fr);
        gap: 24px;
        margin-bottom: 60px;
      }
      .metric-panel {
        background: #0f172a;
        border: 1px solid #004D59;
        border-radius: 4px;
        padding: 24px;
        box-shadow: inset 0 0 20px rgba(0, 77, 89, 0.2);
      }
      .metric-label { font-size: 10px; text-transform: uppercase; color: #004D59; font-weight: 800; letter-spacing: 0.1em; margin-bottom: 10px; }
      .metric-value { font-family: 'JetBrains Mono'; font-size: 1.5rem; font-weight: 900; color: #fff; }
    "]

    ;; 1. Mission Control Hero
    [:div.hero-strip
     [:div.metric-panel [:div.metric-label "Validation Gate"] [:div.metric-value (str/upper-case (or (:overall_status summary) "FAIL"))]]
     [:div.metric-panel [:div.metric-label "Scenario Corpus"] [:div.metric-value scenario-count]]
     [:div.metric-panel [:div.metric-label "Replay Match"] [:div.metric-value replay-match-label]]
     [:div.metric-panel [:div.metric-label "Git Lineage"] [:div.metric-value (subs (or (:git_sha summary) (:git-sha config/protocol-defaults)) 0 7)]]]

    ;; 2. The Atlas (Automated Engine)
    [:div {:style {:marginBottom "60px"}}
     (story/generate-atlas-view artifacts)]

    ;; 3. Dynamic Validation Story
    (let [selection (try (clojure.edn/read-string (slurp "notebooks/snapshot_selection.edn")) (catch Exception _ {}))
          selected-id (or (:selected-scenario selection)
                          (:id (first scenarios))
                          "scenarios/S26_forking-strategist-l1-reversal")]
      [:div {:style {:marginBottom "60px"}}
       [:h2 {:style {:fontSize "1.2rem" :fontWeight 900 :textTransform "uppercase" :marginBottom "24px" :display "flex" :alignItems "center" :gap "12px"}}
        [:span {:style {:width "4px" :height "20px" :background "#FF9800"}}] 
        (str "Evidence Discovery: " (or (:title (data/find-scenario-by-id coverage selected-id)) selected-id))]
       (story/generate-deflection-story selected-id artifacts)])

    ;; 4. Honesty Surface
    [:div {:style {:marginTop "40px" :padding "40px" :border "1px dashed #FF9800" :background "rgba(255, 152, 0, 0.05)"}}
     [:h3 {:style {:color "#FF9800" :marginTop 0 :fontSize "1.5rem" :fontWeight 900}} "Scientific Honesty Surface"]
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "40px"}}
      [:div
       [:h4 {:style {:color "#7ADDDC"}} "Theory-Falsification Observations"]
       [:p {:style {:fontSize "0.9rem" :opacity 0.8 :marginBottom "16px"}} "Known edge-cases identified during research sweeps. These are not system regressions."]
       [:ul {:style {:fontSize "0.85rem" :color "#94a3b8" :paddingLeft "20px"}}
        (for [s (take 5 (filter #(= (:purpose %) "theory-falsification") scenarios))]
          [:li {:style {:marginBottom "6px"}} [:code (:id s)] ": " (:title s)])]]
      [:div
       [:h4 {:style {:color "#FF9800"}} "Open Verification Gaps"]
       [:ul {:style {:fontSize "0.9rem" :color "#cbd5e1" :paddingLeft "20px"}}
        [:li "Multi-chain L2 finality confirmation (Optimistic/ZK focus)."]
        [:li "Dynamic market liquidity variables in economic stress models."]
        [:li "Resolver bond secondary market secondary-slashing incentives."]]]]]
    
    ;; 5. Footer
    [:div {:style {:marginTop "80px" :paddingTop "40px" :borderTop "1px solid #004D59" :display "flex" :justifyContent "space-between" :fontSize "11px" :color "#004D59" :fontWeight 800}}
     [:div "© 2026 SEW PROTOCOL FOUNDATION"]
     [:div (str "EVIDENCE_BUNDLE: " (or (:run_id summary) (:run-id config/protocol-defaults)))]]]))
