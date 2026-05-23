(ns notebooks.collusion-artifact
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]))

;; # Sew Protocol — Technical Validation Story
;; ## Scenario S42: Resolver-Buyer Bribery Loop Detection

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
      background: rgba(255, 152, 0, 0.1);
      border: 1px solid #FF9800;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      font-weight: 800;
      margin-bottom: 16px;
      color: #FF9800;
    }

    .status-badge.success {
      background: rgba(3, 218, 198, 0.1);
      border-color: #03DAC6;
      color: #03DAC6;
    }

    /* Collusion Primitive */
    .collusion-loop {
      width: 120px;
      height: 120px;
      border: 4px dashed #FF9800;
      border-radius: 50%;
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 30px;
    }
    .actor-node {
      width: 40px;
      height: 40px;
      background: #020617;
      border: 2px solid #FF9800;
      display: flex;
      align-items: center;
      justify-content: center;
      font-family: 'JetBrains Mono';
      font-size: 10px;
      font-weight: 800;
    }

    /* Slash Shield */
    .slash-impact {
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      width: 300px;
      height: 300px;
      border: 20px double #03DAC6;
      border-radius: 50%;
      opacity: 0.2;
      z-index: 0;
    }
  "]

  [:div.frame-carousel
   
   ;; FRAME 1: THE THREAT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S42 | STATUS: MONITORING"]
    [:div.frame-content
     [:div.status-badge "THREAT: COALITION_FORMATION"]
     [:h1.hero-text.orange {:style {:fontSize "48px"}} "RESOLVER" [:br] "BRIBERY" [:br] "LOOP"]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}} 
      "Buyer and Resolver form a coalition to siphon funds via fraudulent verdicts."]]
    [:div.frame-footer
     [:span "VECTOR: COLLUSIVE_SETTLEMENT"]
     [:span "MODEL: GAME_THEORETIC_STRESS"]]]

   ;; FRAME 2: THE MALICIOUS VERDICT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S42 | STATUS: FRAUD_EMITTED"]
    [:div.frame-content
     [:div.collusion-loop
      [:div.actor-node {:style {:position "absolute" :top "-20px"}} "BYR"]
      [:div.actor-node {:style {:position "absolute" :bottom "-20px"}} "RES"]
      [:div.orange {:style {:fontSize "20px"}} "↺"]]
     [:h2.hero-text {:style {:fontSize "36px"}} "FRAUDULENT" [:br] "RELEASE" [:br] "EMITTED"]
     [:div {:style {:marginTop "24px" :fontFamily "JetBrains Mono" :fontSize "10px" :color "#FF9800"}} 
      "VERDICT_ID: 0x42...f00d | STATUS: PENDING_EXECUTION"]]
    [:div.frame-footer
     [:span "TARGET: 50,000 USDC"]
     [:span "WINDOW: CHALLENGE_OPEN"]]]

   ;; FRAME 3: THE DETECTION
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S42 | STATUS: SLASHER_ACTIVE"]
    [:div.frame-content
     [:div.slash-impact]
     [:div.status-badge.success "BOUNTY_CHALLENGE: TRIGGERED"]
     [:h2.hero-text.teal {:style {:fontSize "50px"}} "BRIBERY" [:br] "INTERCEPTED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#03DAC6" :fontWeight 800 :maxWidth "300px"}} 
      "Phase L monitor detected verdict inconsistency. Honest observer triggered emergency bond slash."]]
    [:div.frame-footer
     [:span "CHALLENGER: 0x8b5...f6e2"]
     [:span "BOUNTY: 500 SEW"]]]

   ;; FRAME 4: THE PENALTY
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S42 | STATUS: EQUILIBRIUM_RESTORED"]
    [:div.frame-content
     [:div.status-badge.success "INVARIANT_SOLVENCY: HOLDING"]
     [:div {:style {:display "flex" :gap "20px" :marginBottom "32px"}}
      [:div {:style {:padding "16px" :background "#004D59" :border "1px solid #7ADDDC"}}
       [:div {:style {:fontSize "10px"}} "RESOLVER BOND"]
       [:div.orange {:style {:fontSize "18px" :fontWeight 900}} "-5,000 SEW"]]
      [:div {:style {:padding "16px" :background "#004D59" :border "1px solid #7ADDDC"}}
       [:div {:style {:fontSize "10px"}} "NET PROFIT"]
       [:div.orange {:style {:fontSize "18px" :fontWeight 900}} "-12.4%"]]]
     [:h2.hero-text {:style {:fontSize "32px"}} "COLLUSION" [:br] "ECONOMICALLY" [:br] "DEFEATED"]
     [:div {:style {:marginTop "20px" :fontFamily "JetBrains Mono" :fontSize "10px" :opacity 0.6}} "sha256:f00df00df00df00df00df00df00df00d"]]
    [:div.frame-footer
     [:span "REPLAY: DETERMINISTIC"]
     [:span "CERT: SEW-S42-AUDIT"]]]

  ]])
