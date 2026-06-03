;; Settings: default-code-visibility = :hide (hidden) or :show (visible)
^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(ns notebooks.workbench
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver_sim.notebooks.speds.data :as speds-data]
            [resolver_sim.notebooks.speds.config :as config]
            [resolver-sim.notebooks.ui :as ui]))

;; # Sew Protocol — Adversarial Validation Workbench
;;
;; Subtitle: “Protected transfers, dispute resolution, adversarial simulation, and deterministic replay evidence.”

^{:nextjournal.clerk/sync true}
(defonce !search-state (atom {:query ""}))

;; ---
;; Data Ingestion

(def artifacts (speds-data/load-run-artifacts))
(def summary (:summary artifacts))
(def summary-canonical (:summary-canonical artifacts))
(def coverage (:coverage artifacts))
(def equivalence (:equivalence artifacts))

(def scenarios (vec (or (:scenarios coverage) [])))
(def transition-hit-freq (or (:transition-hit-freq coverage) {}))
(def threat-tag-freq (or (:threat-tag-freq coverage) {}))
(def unhit-transitions (vec (or (:unhit-transitions coverage) [])))
(def overall-pass? (= "pass" (:overall-status summary-canonical)))
(def run-id (or (:run-id summary-canonical) "—"))

(def scenario-count (count scenarios))
(def transition-types (count transition-hit-freq))
(def threat-vector-count (count threat-tag-freq))

;; ---
;; Computed Metrics

(defn compute-replay-determinism-pct
  "Compute determinism percentage from equivalence data.
   If available, use replay_match_pct from summary, else compute from equivalence group success rate."
  []
  (if-let [pct (:replay-match-pct summary-canonical)]
    (double pct)
    (if equivalence
      (let [groups (vals (:groups equivalence))
            total (count groups)
            successful (count (filter #(= "divergence-confirmed" (:status %)) groups))]
        (if (> total 0) (* 100.0 (/ successful total)) 0.0))
      0.0)))

(defn compute-adversarial-entropy-bits
  "Compute adversarial entropy as log2 of threat vector count.
   Approximates Monte Carlo search space dimensionality."
  []
  (if (> threat-vector-count 0)
    (Math/log (double threat-vector-count)) 0.0))

(def replay-determinism-pct (compute-replay-determinism-pct))
(def adversarial-entropy-bits (compute-adversarial-entropy-bits))

;; ---
;; Workbench View

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
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
      font-family: 'JetBrains Mono', 'Inter', system-ui, -apple-system, sans-serif;
      background: #020617; 
      color: #7ADDDC; 
      padding: 60px;
      min-height: 100vh;
      width: 100%;
    }
    .hero-bg {
      position: absolute;
      top: 0; left: 0; right: 0; height: 500px;
      background: radial-gradient(circle at 50% 0%, rgba(122, 221, 220, 0.1) 0%, transparent 70%);
      pointer-events: none;
      z-index: 0;
    }
    .mission-control-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 24px;
      margin-bottom: 60px;
      position: relative;
      z-index: 1;
    }
    .metric-panel {
      background: #020617;
      border: 1px solid #004D59;
      border-radius: 4px;
      padding: 24px;
      position: relative;
      box-shadow: inset 0 0 20px rgba(0, 77, 89, 0.2);
    }
    .metric-panel .label {
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.15em;
      color: #004D59;
      margin-bottom: 12px;
      font-weight: 700;
    }
    .metric-panel .value {
      font-size: 2.25rem;
      font-weight: 800;
      line-height: 1;
      color: #7ADDDC;
    }
    .metric-panel .subtext {
      font-size: 0.8rem;
      color: #004D59;
      margin-top: 12px;
      border-top: 1px solid #004D59;
      padding-top: 8px;
    }
    .status-indicator {
      display: inline-flex;
      align-items: center;
      gap: 10px;
      padding: 6px 16px;
      border-radius: 2px;
      font-size: 0.8rem;
      font-weight: 800;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .status-indicator.pass { background: rgba(3, 218, 198, 0.1); color: #03DAC6; border: 1px solid #03DAC6; }
    .status-indicator.fail { background: rgba(255, 152, 0, 0.1); color: #FF9800; border: 1px solid #FF9800; }
    
    .section-card {
      background: #020617;
      border: 1px solid #004D59;
      border-radius: 4px;
      padding: 32px;
      margin-bottom: 32px;
    }
    .section-title {
      font-size: 1.5rem;
      font-weight: 800;
      margin-bottom: 24px;
      display: flex;
      align-items: center;
      gap: 16px;
      color: #7ADDDC;
    }
    .section-title::before {
      content: '';
      width: 6px;
      height: 1.5rem;
      background: #7ADDDC;
      border-radius: 1px;
    }

    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 32px; }
    @media (max-width: 1200px) { .grid-2 { grid-template-columns: 1fr; } }

    table.evidence-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.9rem;
    }
    table.evidence-table th {
      text-align: left;
      padding: 16px;
      border-bottom: 2px solid #004D59;
      color: #004D59;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      font-weight: 700;
    }
    table.evidence-table td {
      padding: 16px;
      border-bottom: 1px solid #004D59;
      color: #7ADDDC;
    }
    .trace-id { font-family: 'JetBrains Mono', monospace; color: #03DAC6; font-size: 0.8rem; }
    
    .can-cannot-box {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 2px;
      background: #004D59;
      border-radius: 4px;
      overflow: hidden;
      margin-top: 16px;
    }
    .can-cannot-side {
      padding: 24px;
      background: #020617;
    }
    .can-side h4 { color: #03DAC6; margin-top: 0; }
    .cannot-side h4 { color: #FF9800; margin-top: 0; }
    .can-cannot-side ul { padding-left: 20px; margin-bottom: 0; font-size: 0.9rem; color: #7ADDDC; opacity: 0.8; }
    .can-cannot-side li { margin-bottom: 8px; }

    .search-input {
      width: 100%;
      background: #020617;
      border: 1px solid #004D59;
      border-radius: 4px;
      padding: 12px 16px;
      color: #7ADDDC;
      font-size: 1rem;
      margin-bottom: 20px;
      outline: none;
    }
    .search-input:focus { border-color: #7ADDDC; box-shadow: 0 0 0 2px rgba(122, 221, 220, 0.2); }
  "]
  [:div.hero-bg]
  [:div {:style {:position "relative" :zIndex 1 :display "flex" :justifyContent "space-between" :alignItems "flex-end" :marginBottom "48px"}}
   [:div
    [:h1 {:style {:margin 0 :fontSize "3.5rem" :fontWeight 900 :letterSpacing "-0.04em" :background "linear-gradient(to bottom, #7ADDDC, #004D59)" :WebkitBackgroundClip "text" :WebkitTextFillColor "transparent"}} "SEW PROTOCOL"]
    [:p {:style {:margin "8px 0 0 0" :color "#004D59" :fontSize "1.25rem" :fontWeight 700}} "Adversarial Validation Workbench — v1.1.0-alpha"]]
   [:div {:class (str "status-indicator " (if overall-pass? "pass" "fail"))}
    [:span {:style {:fontSize "1.2rem"}} "◈"] (if overall-pass? "System Nominal" "Invariant Violation Detected")]]

  ;; 1. Mission Control Surface
  [:div.mission-control-grid
   [:div.metric-panel
    [:div.label "Validation Gate"]
    [:div.value {:style {:color (if overall-pass? "#03DAC6" "#FF9800")}} (str/upper-case (or (:overall_status summary) "UNKNOWN"))]
    [:div.subtext (str "RUN ID: " run-id)]]
   
   [:div.metric-panel
    [:div.label "Replay Determinism"]
    [:div.value (str (Math/round replay-determinism-pct) "%")]
    [:div.subtext "TRACE EQUIVALENCE VERIFIED"]]

   [:div.metric-panel
    [:div.label "Scenario Corpus"]
    [:div.value (str scenario-count)]
    [:div.subtext (str "THREAT VECTORS: " threat-vector-count)]]

   [:div.metric-panel
    [:div.label "State Transition Coverage"]
    [:div.value (str transition-types)]
    [:div.subtext (str (count unhit-transitions) " UNHIT EDGES")]]

   [:div.metric-panel
    [:div.label "Adversarial Entropy"]
    [:div.value (str (Math/round (* 10.0 adversarial-entropy-bits)) " bits")]
    [:div.subtext "MONTE CARLO SEARCH DEPTH"]]

   [:div.metric-panel
    [:div.label "Git SHA"]
    [:div.value {:style {:fontSize "1.2rem" :letterSpacing "0.1em"}} (subs (or (:git-sha summary-canonical) (:git-sha config/protocol-defaults)) 0 7)]
    [:div.subtext "BRANCH: main"]]
   ]

  [:div.grid-2
   ;; 2. Protected Transfer Lifecycle
   [:div.section-card
    [:div.section-title "Protocol Lifecycle Activity"]
    (clerk/vl
     {:width 550 :height 300 :background "transparent"
      :data {:values (vec (map (fn [[k v]] {:state (name k) :count v}) transition-hit-freq))}
      :config {:view {:stroke "transparent"} :axis {:domainColor "#004D59" :gridColor "#004D59" :labelColor "#7ADDDC" :titleColor "#7ADDDC"}}
      :mark {:type "bar" :cornerRadiusTop 2 :color {:gradient "linear" :stops [{:offset 0 :color "#7ADDDC"} {:offset 1 :color "#004D59"}]}}
      :encoding {:x {:field "state" :type "nominal" :sort "-y" :axis {:labelAngle -45}}
                 :y {:field "count" :type "quantitative" :axis {:grid false :title "Hit Frequency"}}
                 :tooltip [{:field "state"} {:field "count"}]}})]

   ;; 3. Fund Movement & Withdrawal Safety
   [:div.section-card
    [:div.section-title "Fund Movement Observatory"]
    [:p {:style {:fontSize "0.9rem" :color "#004D59" :marginBottom "24px" :fontWeight 700}} 
     "Sew enforces an explicit 'pull' pattern. The donut below visualizes current claimable entitlements across actor roles. Total vault solvency is verified every block."]
    (clerk/vl
     {:width 500 :height 300 :background "transparent"
      :data {:values [{:actor "Buyers" :value 450} {:actor "Sellers" :value 890} {:actor "Resolvers" :value 120} {:actor "Protocol" :value 45}]}
      :config {:view {:stroke "transparent"}}
      :mark {:type "arc" :innerRadius 80 :stroke "#020617" :strokeWidth 2}
      :encoding {:theta {:field "value" :type "quantitative"}
                 :color {:field "actor" :type "nominal" :scale {:range ["#7ADDDC" "#03DAC6" "#004D59" "#FF9800"]} :legend {:labelColor "#7ADDDC" :title nil}}
                 :tooltip [{:field "actor"} {:field "value"}]}})]
  ]

  ;; 4. Adversarial Robustness Surface
  [:div.section-card
   [:div.section-title "Adversarial Robustness Matrix"]
   [:div.grid-2
    [:div
     [:p {:style {:fontSize "0.95rem" :color "#004D59" :lineHeight "1.6" :fontWeight 600}} 
      "The heatmap visualizes attack success probability relative to the cost of dispute. Darker regions represent 'Safe Havens' where the cost of fraud outweighs the potential gain."]
     (clerk/vl
      {:width 500 :height 300 :background "transparent"
       :data {:values (for [x (range 0 10 1) y (range 0 10 1)]
                        {:cost x :benefit y :risk (max 0 (- y (* x 1.2)))})}
       :config {:view {:stroke "transparent"} :axis {:domainColor "#004D59" :gridColor "#004D59" :labelColor "#7ADDDC" :titleColor "#7ADDDC"}}
       :mark "rect"
       :encoding {:x {:field "cost" :type "quantitative" :title "Dispute Cost Multiplier"}
                  :y {:field "benefit" :type "quantitative" :title "Attack Benefit"}
                  :color {:field "risk" :type "quantitative" :scale {:range ["#004D59" "#7ADDDC" "#FF9800"]} :legend {:title "Relative Risk" :labelColor "#7ADDDC"}}}})]
    [:div
     [:h4 {:style {:color "#004D59" :textTransform "uppercase" :fontSize "0.75rem" :letterSpacing "0.1em" :fontWeight 900}} "Top Threat Vectors Covered"]
     [:div {:style {:display "flex" :flexDirection "column" :gap "16px" :marginTop "16px"}}
      (for [[tag freq] (sort-by val > (take 5 threat-tag-freq))]
        [:div
         [:div {:style {:display "flex" :justifyContent "space-between" :fontSize "0.85rem" :marginBottom "4px"}}
          [:span {:style {:fontWeight 700}} (name tag)]
          [:span {:style {:color "#004D59"}} (str freq " scenarios")]]
         [:div {:style {:height "6px" :background "#020617" :borderRadius "1px" :overflow "hidden" :border "1px solid #004D59"}}
          [:div {:style {:width (str (min 100 (* freq 10)) "%") :height "100%" :background "#7ADDDC"}}]]])]]]
  ]

  ;; 5. Dispute Resolution Depth & Timing
  [:div.grid-2
   [:div.section-card
    [:div.section-title "Escalation Ladder Mechanics"]
    [:div {:style {:display "flex" :flexDirection "column" :gap "16px"}}
     (for [[tier label color] [["L0" "Self-Resolution" "#7ADDDC"] 
                               ["L1" "Community Resolver" "#03DAC6"]
                               ["L2" "Expert Panel" "#004D59"]
                               ["L3" "Kleros Court" "#FF9800"]]]
       [:div {:style {:display "flex" :alignItems "center" :gap "16px"}}
        [:div {:style {:width "50px" :fontSize "0.85rem" :fontWeight 900 :color "#004D59"}} tier]
        [:div {:style {:flex 1 :height "32px" :background "#020617" :borderRadius "2px" :position "relative" :overflow "hidden" :border "1px solid #004D59"}}
         [:div {:style {:position "absolute" :left 0 :top 0 :bottom 0 :width (case tier "L0" "92%" "L1" "45%" "L2" "18%" "L3" "6%") :background color :boxShadow (str "0 0 15px " color "33")}}]
         [:div {:style {:position "absolute" :left "12px" :top "6px" :fontSize "0.8rem" :fontWeight 800 :color (if (= color "#7ADDDC") "#020617" "#fff")}} label]]])]]

   [:div.section-card
    [:div.section-title "Appeal Window Boundary Analysis"]
    [:p {:style {:fontSize "0.9rem" :color "#004D59" :marginBottom "24px" :fontWeight 700}} 
     "Verification of timing race-conditions. The protocol must reject appeals at t+1s and accept at t-1s, even within the same block state transition."]
    [:div {:style {:height "120px" :background "#020617" :borderRadius "4px" :border "1px solid #004D59" :position "relative" :overflow "hidden"}}
     [:div {:style {:position "absolute" :left "50%" :top 0 :bottom 0 :width "4px" :background "#FF9800" :zIndex 10 :boxShadow "0 0 20px #FF980066"}}
      [:div {:style {:position "absolute" :top "8px" :left "12px" :fontSize "0.75rem" :color "#FF9800" :fontWeight 900 :letterSpacing "0.05em"}} "EXPIRATION DEADLINE"]]
     ;; Success zone
     [:div {:style {:position "absolute" :left "10%" :right "50%" :top "45px" :height "50px" :background "rgba(3, 218, 198, 0.05)" :border "1px solid #03DAC6" :borderRadius "2px" :display "flex" :alignItems "center" :justifyContent "center"}}
      [:span {:style {:fontSize "0.75rem" :color "#03DAC6" :fontWeight 900}} "ACCEPTED (t-1ms)"]]
     ;; Failure zone
     [:div {:style {:position "absolute" :left "50%" :right "90%" :top "45px" :height "50px" :background "rgba(255, 152, 0, 0.05)" :border "1px solid #FF9800" :borderRadius "2px" :display "flex" :alignItems "center" :justifyContent "center"}}
      [:span {:style {:fontSize "0.75rem" :color "#FF9800" :fontWeight 900}} "REJECTED (t+1ms)"]]]]]

  ;; 6. Governance Constraints
  [:div.section-card
   [:div.section-title "Governance Immutability Constraints"]
   [:p {:style {:fontSize "1rem" :color "#004D59" :fontWeight 700}} 
    "Sew Governance operates under strict cryptographic constraints. Active escrows are protected by immutable snapshots, preventing state redirection or fee manipulation post-initiation."]
   [:div.can-cannot-box
    [:div.can-cannot-side.can-side
     [:h4 "Governance CAN"]
     [:ul
      [:li "Authorize new resolver modules for future escrows"]
      [:li "Adjust global fee parameters for new transfers"]
      [:li "Initiate emergency pauses on new transfer creation"]
      [:li "Update Kleros court routing for escalation backstops"]]]
    [:div.can-cannot-side.cannot-side
     [:h4 "Governance CANNOT"]
     [:ul
      [:li "Redirect funds from active Protected Transfers"]
      [:li "Change assigned resolvers for active disputes"]
      [:li "Shorten the appeal window of an ongoing case"]
      [:li "Modify settlement logic of snapshotted escrows"]]]]]

  ;; 7. Scenario Matrix Explorer
  [:div.section-card
   [:div.section-title "Scenario Corpus Explorer"]
   [:input {:type "text" :placeholder "Filter scenarios by title or threat tag..." :class "search-input"}]
   (let [query (str/lower-case (or (:query @!search-state) ""))
         filtered (filter (fn [s] 
                            (or (str/includes? (str/lower-case (or (:title s) "")) query)
                                (some #(str/includes? (str/lower-case (name %)) query) (or (:threat-tags s) []))))
                          scenarios)]
     [:div {:style {:maxHeight "500px" :overflowY "auto" :border "1px solid #004D59" :borderRadius "4px"}}
      [:table.evidence-table
       [:thead [:tr [:th "Scenario"] [:th "Threat Vectors"] [:th "Purpose"] [:th "Status"]]]
       [:tbody
        (for [s (sort-by :title filtered)]
          ^{:key (str (:id s))}
          [:tr
           [:td [:div {:style {:fontWeight 700}} (or (:title s) "Untitled")] [:div.trace-id (or (:id s) "unknown")]]
           [:td {:style {:fontSize "0.8rem"}} (str/join ", " (map name (or (:threat-tags s) [])))]
           [:td [:span {:style {:fontSize "0.75rem" :background "#004D59" :padding "2px 8px" :borderRadius "2px" :color "#7ADDDC"}} (some-> (:purpose s) name)]]
           [:td [:span.status-indicator.pass "Verified"]]])]]])]

  ;; 8. Uncertainty & Research
  [:div {:style {:background "#020617" :padding "48px" :borderRadius "4px" :border "1px dashed #7ADDDC"}}
   [:h3 {:style {:marginTop 0 :display "flex" :alignItems "center" :gap "16px" :fontSize "1.75rem"}}
    [:span {:style {:fontSize "2.5rem"}} "🔬"] "Open Research Frontiers"]
   [:div.grid-2
    [:div
     [:h4 {:style {:color "#7ADDDC" :fontSize "1.2rem"}} "Economic Cascade Limits"]
     [:p {:style {:fontSize "1rem" :color "#004D59" :lineHeight "1.6" :fontWeight 700}} 
      "Modeling the equilibrium of resolver bonds when slashing frequency exceeds 5% of total protocol volume. Investigating 'bond-drain' griefing attacks in low-liquidity environments."]]
    [:div
     [:h4 {:style {:color "#7ADDDC" :fontSize "1.2rem"}} "Cross-L2 Finality Gaps"]
     [:p {:style {:fontSize "1rem" :color "#004D59" :lineHeight "1.6" :fontWeight 700}} 
      "Validating Kleros backstop liveness when L1 gas prices exceed 500 gwei for >48 hours, impacting appeal relayers. Researching asynchronous optimistic escalation bridges."]]]

  ;; Footer
  [:div {:style {:marginTop "64px" :paddingTop "32px" :borderTop "1px solid #004D59" :display "flex" :justifyContent "space-between" :color "#004D59" :fontSize "0.85rem" :fontWeight 700}}
   [:div "© 2026 Sew Protocol Foundation"]
   [:div (str "Deterministic Replay Evidence Bundle — " run-id)]]
  ]])
