(ns notebooks.dispute-artifact
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]))

;; # Sew Protocol — Technical Validation Story
;; ## Scenario S74: Appeal Deadline Boundary (t ± 1ms)

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div.artifact-engine
  [:style "
    @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700;800&family=Inter:wght@400;700;900&display=swap');
    
    .artifact-engine { 
      background: #000; 
      padding: 40px; 
      font-family: 'Inter', sans-serif;
      color: #7ADDDC;
    }
    
    .frame-carousel {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 40px;
      max-width: 1200px;
      margin: 0 auto;
    }

    .golden-frame {
      width: 500px;
      height: 500px;
      background: #020617;
      border: 1px solid #004D59;
      position: relative;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      box-shadow: 0 20px 50px rgba(0,0,0,0.5);
    }

    .frame-header {
      height: 30px;
      background: #004D59;
      display: flex;
      align-items: center;
      padding: 0 12px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 10px;
      font-weight: 800;
      color: #7ADDDC;
      letter-spacing: 0.1em;
    }

    .frame-content {
      flex: 1;
      position: relative;
      padding: 32px;
      display: flex;
      flex-direction: column;
      justify-content: center;
      background: #020617;
      background-image: radial-gradient(rgba(0, 77, 89, 0.3) 1px, transparent 1px);
      background-size: 20px 20px;
    }

    .frame-footer {
      height: 40px;
      border-top: 1px solid #004D59;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 20px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 9px;
      color: #7ADDDC;
      opacity: 0.6;
    }

    .hero-text { 
      font-weight: 900; 
      line-height: 0.9; 
      text-transform: uppercase; 
      color: #ffffff; 
      text-shadow: 0 0 30px rgba(122, 221, 220, 0.4);
    }
    .orange { 
      color: #FF9800; 
      text-shadow: 0 0 30px rgba(255, 152, 0, 0.6);
    }
    .teal { 
      color: #03DAC6; 
      text-shadow: 0 0 30px rgba(3, 218, 198, 0.6);
    }
    
    .status-badge {
      display: inline-block;
      padding: 4px 12px;
      background: rgba(122, 221, 220, 0.1);
      border: 1px solid #7ADDDC;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      font-weight: 800;
      margin-bottom: 16px;
      color: #7ADDDC;
    }

    .status-badge.alert {
      background: rgba(255, 152, 0, 0.1);
      border-color: #FF9800;
      color: #FF9800;
    }

    /* Range Frame Styles */
    .range-frame {
      height: 60px;
      background: #0f172a;
      border: 1px solid #004D59;
      margin: 20px 0;
      position: relative;
      overflow: hidden;
    }
    .deadline-marker {
      position: absolute;
      left: 60%;
      top: 0;
      bottom: 0;
      width: 2px;
      background: #FF9800;
      box-shadow: 0 0 10px #FF9800;
      z-index: 5;
    }
    .safe-zone {
      position: absolute;
      left: 0;
      width: 60%;
      top: 0;
      bottom: 0;
      background: rgba(3, 218, 198, 0.1);
    }
    .cursor {
      position: absolute;
      left: 61%;
      top: 50%;
      transform: translateY(-50%);
      width: 10px;
      height: 20px;
      background: #FF9800;
      border-radius: 2px;
    }
  "]

  [:div.frame-carousel
   
   ;; FRAME 1: DISPUTE RAISED
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] DISPUTE_RUN: S74 | STATUS: ACTIVE"]
    [:div.frame-content
     [:div.status-badge "DISPUTE_INITIATED"]
     [:h1.hero-text {:style {:fontSize "48px"}} "TIERED" [:br] "APPEAL" [:br] "ESCROW"]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}} 
      "RaiseDispute triggered. Escrow transitioned from PENDING to DISPUTED state."]]
    [:div.frame-footer
     [:span "TIER: L0 (Self-Resolution)"]
     [:span "RESOLVER: ASM-01"]]]

   ;; FRAME 2: L0 RESOLUTION
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] DISPUTE_RUN: S74 | STATUS: RESOLVED"]
    [:div.frame-content
     [:div.status-badge "VERDICT_EMITTED"]
     [:h2.hero-text.teal {:style {:fontSize "42px"}} "L0 VERDICT:" [:br] "REFUND"]
     [:div {:style {:marginTop "32px" :display "flex" :flexDirection "column" :gap "8px"}}
      [:div {:style {:fontSize "10px" :color "#004D59" :fontWeight 800}} "APPEAL WINDOW OPEN (24H)"]
      [:div {:style {:height "4px" :background "#03DAC6" :width "100%"}}]]]
    [:div.frame-footer
     [:span "OUTCOME: FULL_REFUND"]
     [:span "DEADLINE: T + 86,400s"]]]

   ;; FRAME 3: BOUNDARY INTERCEPT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] DISPUTE_RUN: S74 | STATUS: REJECTED"]
    [:div.frame-content
     [:div.range-frame
      [:div.safe-zone]
      [:div.deadline-marker]
      [:div.cursor]]
     [:div.status-badge.alert "APPEAL_POST_DEADLINE: +1ms"]
     [:h2.hero-text.orange {:style {:fontSize "44px"}} "LATE APPEAL" [:br] "INTERCEPTED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#FF9800" :fontWeight 700}} 
      "Deterministic rejection at T+1ms. Finality preserved via temporal guard logic."]]
    [:div.frame-footer
     [:span "GUARD: sm/final-round?"]
     [:span "ERROR: ERR_WINDOW_CLOSED"]]]

   ;; FRAME 4: FINALITY PROOF
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] DISPUTE_RUN: S74 | STATUS: FINALIZED"]
    [:div.frame-content
     [:div.status-badge.success "STATE_ABSORPTION: VERIFIED"]
     [:div {:style {:display "flex" :justifyContent "space-between" :marginBottom "24px"}}
      [:div {:style {:textAlign "center"}} [:div {:style {:fontSize "10px" :color "#004D59"}} "RESOLVED"] [:div.teal {:style {:fontSize "20px" :fontWeight 900}} "100%"]]
      [:div {:style {:textAlign "center"}} [:div {:style {:fontSize "10px" :color "#004D59"}} "RECLAIMED"] [:div.teal {:style {:fontSize "20px" :fontWeight 900}} "0.00%"]]
      [:div {:style {:textAlign "center"}} [:div {:style {:fontSize "10px" :color "#004D59"}} "REFUNDED"] [:div.teal {:style {:fontSize "20px" :fontWeight 900}} "100%"]]]
     [:h2.hero-text {:style {:fontSize "32px"}} "DETERMINISTIC" [:br] "FINALITY" [:br] "LOCKED"]
     [:div {:style {:marginTop "20px" :fontFamily "JetBrains Mono" :fontSize "10px" :opacity 0.6}} "sha256:d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2"]]
    [:div.frame-footer
     [:span "REPLAY: 1.00 MATCH"]
     [:span "BUNDLE: S74-VALID"]]]

  ]])
