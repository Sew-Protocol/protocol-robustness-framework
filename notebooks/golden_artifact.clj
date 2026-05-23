(ns notebooks.golden-artifact
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]))

;; # Sew Protocol — Technical Validation Story
;; ## Scenario S26: 100M Liquid Reorg Deflection

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
    }

    .status-badge.success {
      background: rgba(3, 218, 198, 0.1);
      border-color: #03DAC6;
      color: #03DAC6;
    }

    /* Frame 2 Visuals */
    .trace-line {
      height: 4px;
      background: #FF9800;
      width: 80%;
      position: relative;
      box-shadow: 0 0 15px #FF980066;
    }
    .trace-node {
      width: 12px;
      height: 12px;
      background: #FF9800;
      border-radius: 50%;
      position: absolute;
      right: -6px;
      top: -4px;
    }

    /* Frame 3 Visuals */
    .shield-wall {
      position: absolute;
      right: 100px;
      top: 40px;
      bottom: 40px;
      width: 12px;
      background: #7ADDDC;
      box-shadow: 0 0 30px #7ADDDC99;
      z-index: 10;
    }
    .shatter {
      position: absolute;
      right: 112px;
      top: 50%;
      color: #FF9800;
      font-family: 'JetBrains Mono', monospace;
      font-size: 8px;
    }

    .hash-scroll {
      font-family: 'JetBrains Mono', monospace;
      font-size: 14px;
      opacity: 0.8;
      word-break: break-all;
      line-height: 1.2;
    }
  "]

  [:div.frame-carousel
   
   ;; FRAME 1: THE HOOK
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: ALERT"]
    [:div.frame-content
     [:div.status-badge "THREAT_DETECTED"]
     [:h1.hero-text.orange {:style {:fontSize "48px"}} "100M LIQUID" [:br] "REORG ATTACK"]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}} 
      "Attacker attempts to force a fraudulent settlement by manipulating L1 block-finality."]]
    [:div.frame-footer
     [:span "TRACE_ID: 8f2a...1b9c"]
     [:span "BLOCK_H: 14,200,000"]]]

   ;; FRAME 2: THE CONFLICT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: INJECTING"]
    [:div.frame-content
     [:div {:style {:marginBottom "40px"}}
      [:div {:style {:fontSize "12px" :fontWeight 800 :marginBottom "8px"}} "ADVERSARIAL INJECTION"]
      [:div.trace-line [:div.trace-node]]]
     [:h2.hero-text {:style {:fontSize "32px" :color "#fff"}} "MALICIOUS" [:br] "EXTRACTION" [:br] "ATTEMPTED"]
     [:div {:style {:marginTop "32px" :display "flex" :gap "10px"}}
      [:span {:style {:fontSize "10px" :background "#004D59" :padding "4px 8px" :color "#7ADDDC" :fontWeight 800}} "AUTH_BYPASS"]
      [:span {:style {:fontSize "10px" :background "#004D59" :padding "4px 8px" :color "#7ADDDC" :fontWeight 800}} "STATE_LEAK"]]]
    [:div.frame-footer
     [:span "VECTOR: L1_FORK_REORG"]
     [:span "ENGINE: CLOJURE-gRPC"]]]

   ;; FRAME 3: THE INTERCEPT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: INTERCEPTED"]
    [:div.frame-content
     [:div.shield-wall]
     [:div.shatter "× REJECTED" [:br] "× SHATTERED" [:br] "× DROPPED"]
     [:div.status-badge.success {:style {:marginBottom "8px"}} "INVARIANT_G04: TRIGGERED"]
     [:h2.hero-text.teal {:style {:fontSize "52px"}} "ATTACK" [:br] "DEFLECTED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#7ADDDC" :fontWeight 700 :maxWidth "250px"}} 
      "State-machine guard blocked execution. Zero funds leaked to unauthorized fork."]]
    [:div.frame-footer
     [:span "LATENCY: 0.1ms"]
     [:span "OUTCOME: TERMINAL_REJECT"]]]

   ;; FRAME 4: THE PROOF
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: VERIFIED"]
    [:div.frame-content
     [:div.status-badge.success "DETERMINISTIC_PROOF: MATCH"]
     [:div.hash-scroll 
      "8f2a74c1e5f6d3b2a1c9c8d7e6f5a4b3" [:br]
      "c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7" [:br]
      "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"]
     [:h2.hero-text {:style {:fontSize "32px" :marginTop "32px" :color "#fff"}} "SIGNED" [:br] "EVIDENCE" [:br] "BUNDLE"]
     [:div {:style {:marginTop "24px" :padding "12px" :background "#03DAC6" :color "#020617" :textAlign "center" :fontWeight 900 :fontSize "14px" :borderRadius "2px"}}
      "VERIFY DETERMINISTIC REPLAY"]]
    [:div.frame-footer
     [:span "VERSION: v1.1.0-alpha"]
     [:span "SIGNATURE: 0x92f...7c1a"]]]

  ]])
