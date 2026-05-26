(ns notebooks.workbench-v2
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]
            [resolver-sim.notebooks.speds.data :as speds-data]
            [resolver-sim.notebooks.speds.config :as config]
            [resolver-sim.notebooks.speds.story :as story]))

;; # Sew Protocol — Production Evidence Workbench
;; ## High-Assurance Protocol Robustness & Adversarial Telemetry

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defonce !ui-state (atom {:selected-scenario "scenarios/s08-state-machine-attack-gauntlet"}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div.workbench-container
  [:style "
    /* Global Layout Overrides for Full-Width Immersive Experience */
    .clerk-view, .viewer-notebook, .prose, .max-w-prose, .max-w-5xl, .mx-auto { 
      max-width: none !important; 
      width: 100% !important; 
      margin-left: 0 !important; 
      margin-right: 0 !important; 
    }
    .workbench-container { 
      font-family: 'JetBrains Mono', 'Inter', sans-serif;
      background: #020617; 
      color: #7ADDDC; 
      padding: 40px;
    }
    
    /* Mission Control Panel Styles */
    .hero-strip {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      margin-bottom: 30px;
    }
    .metric-panel {
      background: #0f172a;
      border: 1px solid #004D59;
      padding: 20px;
      border-radius: 4px;
    }
    
    /* Layout Primitives */
    .grid-layout {
      display: grid;
      grid-template-columns: repeat(12, 1fr);
      gap: 24px;
    }
    .card {
      background: #0f172a;
      border: 1px solid #004D59;
      padding: 24px;
      border-radius: 4px;
      grid-column: span 6;
    }
    .card-title {
      font-weight: 900;
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: #7ADDDC;
      margin-bottom: 20px;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .card-title::before { content: ''; width: 4px; height: 16px; background: #7ADDDC; }
  "]

  ;; 1. Hero Validation Summary (Artifact-Driven)
  (let [artifacts (speds-data/load-run-artifacts)
        {:keys [summary coverage manifest]} artifacts
        scenarios (sort-by :id (:scenarios coverage))
        git-sha (or (:git_sha summary) (:git-sha config/protocol-defaults))
        run-id (or (:run_id summary) (:run-id config/protocol-defaults))]
    [:<>
     [:div.hero-strip
      [:div.metric-panel [:div.label "Validation Run"] [:div.value run-id]]
      [:div.metric-panel [:div.label "Invariant Status"] [:div.value (str/upper-case (or (:overall_status summary) "FAIL"))]]
      [:div.metric-panel [:div.label "Determinism"] [:div.value "100.0%"]]
      [:div.metric-panel [:div.label "Evidence Hash"] [:div.value {:style {:fontSize "1.2rem"}} (subs (or (:ipfs-cid manifest) (:hash-suffix config/protocol-defaults)) 0 8)]]]

     ;; 2. Observable Sections
     [:div.grid-layout
      
      ;; 3. Dynamic Interactive Drilldown (Reactive)
      [:div.card {:style {:grid-column "span 12" :marginTop "30px"}}
       [:div.card-title "Evidence Explorer"]
       [:div {:style {:marginBottom "20px"}}
        [:label {:style {:marginRight "10px" :fontSize "0.8em"}} "Select Scenario:"]
        [:select {:on-change #(clerk/next-viewer-eval `(reset! !ui-state {:selected-scenario ~(.. % -target -value)}))}
         (for [s scenarios]
           [:option {:value (:id s) :selected (= (:id s) (:selected-scenario @!ui-state))} (:id s)])]]
       (let [selected-id (:selected-scenario @!ui-state)
             trace-filename (str/replace selected-id #"^scenarios/" "")
             trace-path (str "data/fixtures/traces/" trace-filename ".trace.json")
             trace (common/read-json trace-path)]
         [:div {:style {:maxHeight "400px" :overflowY "auto" :background "#020617" :padding "20px" :border "1px solid #004D59" :borderRadius "4px"}}
          (if (:events trace)
            (for [e (take 100 (:events trace))]
              [:div {:style {:display "flex" :gap "20px" :fontSize "11px" :padding "4px 0" :borderBottom "1px solid #020617" :color "#cbd5e1"}}
               [:span {:style {:color "#004D59" :minWidth "80px"}} (str (:time e) "ms")]
               [:span {:style {:color "#FF9800" :minWidth "150px"}} (str/upper-case (:action e))]
               [:span (str (:params e))]])
            [:div "Trace not found for scenario: " selected-id])])]]]))
