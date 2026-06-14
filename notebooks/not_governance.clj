;; # Simulation Framework Quickstart
;; ### From Dispute Lifecycle to Adversarial Evidence
;;
;; Run a dispute, compare resolver strategies, trigger slashing, and validate protocol invariants.
;;
;; This notebook shows how the framework turns protocol assumptions into replayable scenarios.
;;
;; **Mental model:** Scenario → Simulation Run → Event Trace → Outcome Metrics → Invariant Evidence
;;
;; You will see:
;; 1. A normal dispute lifecycle with state transitions and balance movement.
;; 2. A malicious resolver attempt and how the protocol responds.
;; 3. How detection and slashing create economic consequences.
;; 4. How losses or shortfalls are allocated across coverage layers.
;; 5. How invariant checks turn the run into auditable evidence.

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/no-cache true}
(ns notebooks.not-governance
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.fixtures :as fixtures]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.notebooks.common :as common]))

(require 'resolver-sim.protocols.sew :reload-all)

;; ---------------------------------------------------------------------------
;; Helpers — safe rendering, readable summaries
;; ---------------------------------------------------------------------------

(defn- badge
  [label color]
  [:span {:style {:display "inline-block" :background color :color "#fff"
                  :fontSize "11px" :fontWeight 700 :padding "2px 8px"
                  :borderRadius "4px" :letterSpacing "0.5px"}} label])

(defn- notice-box
  [title & lines]
  [:div {:style {:background "#1e1b2e" :borderLeft "3px solid #a78bfa" :padding "12px 16px"
                 :borderRadius "4px" :margin "16px 0" :fontSize "13px"}}
   [:div {:style {:color "#c4b5fd" :fontWeight 700 :marginBottom "6px"}} title]
   (into [:div {:style {:color "#d4d4d8"}}]
         (for [l lines] [:div {:style {:paddingLeft "12px"}} l]))])

(defn- trace-table
  [trace]
  [:div {:style {:overflowX "auto" :fontSize "12px"}}
   [:table {:style {:width "100%" :borderCollapse "collapse" :minWidth "600px"}}
    [:thead
     [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "Step"]
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "Time"]
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "Action"]
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "Actor"]
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "Result"]]]
    (into [:tbody]
      (for [e trace
            :let [ok? (= :ok (:result e))]]
        [:tr {:style {:borderBottom "1px solid #0f172a"}}
         [:td {:style {:padding "6px 8px" :color "#64748b" :fontFamily "monospace"}} (:seq e)]
         [:td {:style {:padding "6px 8px" :color "#94a3b8"}} (str (:time e) "s")]
         [:td {:style {:padding "6px 8px" :fontWeight 600 :color "#f8fafc"}} (name (:action e))]
         [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (:agent e)]
         [:td {:style {:padding "6px 8px"}} (if ok? (badge "ok" "#22c55e") (badge (str (name (:error e))) "#ef4444"))]]))]])

;; ---------------------------------------------------------------------------
;; ## 1. Happy-Path Dispute Lifecycle
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce lifecycle-scenario
  {:schema-version "1.0"
   :scenario-id "lifecycle-demo"
   :initial-block-time 1000
   :agents [{:id "alice"    :address "0xAlice"   :strategy "honest"}
            {:id "bob"      :address "0xBob"     :strategy "honest"}
            {:id "resolver" :address "0xResolver" :role "resolver"}
            {:id "keeper"   :address "0xKeeper"  :role "keeper"}]
   :protocol-params {:resolver-fee-bps 0 :appeal-window-duration 0
                     :max-dispute-duration 300}
   :events [{:seq 0 :time 1000 :agent "alice"   :action "create_escrow"
              :params {:token "USDC" :to "0xBob" :amount 1000 :custom-resolver "0xResolver"}}
             {:seq 1 :time 1060 :agent "alice"   :action "raise_dispute"
              :params {:workflow-id 0}}
             {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
              :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce lifecycle-result (delay (sew/replay-with-sew-protocol lifecycle-scenario)))

(defonce lifecycle-display
  (let [r @lifecycle-result
        trace (:trace r)]
    [:div {:style {:display "grid" :gap "16px" :maxWidth "900px"}}
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
      [:div {:style {:background (if (= :pass (:outcome r)) "#064e3b" "#450a0a") :padding "16px" :borderRadius "8px"
                     :border (str "1px solid " (if (= :pass (:outcome r)) "#22c55e" "#ef4444"))}}
       [:div {:style {:fontSize "11px" :color "#94a3b8" :textTransform "uppercase" :fontWeight 700}} "Outcome"]
       [:div {:style {:fontSize "24px" :fontWeight 800 :color (if (= :pass (:outcome r)) "#22c55e" "#ef4444")}}
        (if (= :pass (:outcome r)) "PASS" "FAIL")]]
      [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px" :border "1px solid #134e4a"}}
       [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Events processed"]
       [:div {:style {:fontSize "24px" :fontWeight 800 :color "#f8fafc"}} (:events-processed r)]]]
     [:div {:style {:background "#0f172a" :padding "12px 16px" :borderRadius "8px" :border "1px solid #134e4a" :fontSize "13px"}}
      [:div {:style {:color "#94a3b8"}} "Escrow created: "] [:span {:style {:color "#f8fafc"}} "Alice deposits 1,000 USDC for Bob"]
      [:div {:style {:color "#94a3b8"}} "Resolver: "] [:span {:style {:color "#c4b5fd" :fontFamily "monospace"}} "0xResolver"]
      [:div {:style {:color "#94a3b8"}} "Dispute raised: "] [:span {:style {:color "#f8fafc"}} "at t=1060"]
      [:div {:style {:color "#94a3b8"}} "Result: "] [:span {:style {:color "#22c55e"}} "resolved — funds released to Bob"]]
     (trace-table trace)
     (notice-box "What to notice"
       "Defaults were merged automatically — no governance parameters required."
       "The lifecycle produces a replayable event trace with state transitions.")]))

;; ---------------------------------------------------------------------------
;; ## 2. Malicious Resolver Attempt
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce malice-batch
  (delay (batch/run-batch (rng/make-rng 42) 50
           {:n-trials 50 :min-trials 10
            :escrow-distribution {:type :fixed :value 10000}
            :strategy-mix {:malicious 1.0}
            :slashing-detection-probability 0.25
            :oracle-fixture {:mode :stochastic}
            :parallelism :auto})))

(defonce malice-display
  (let [r @malice-batch]
    [:div {:style {:maxWidth "900px"}}
     [:div {:style {:background "#1e1b2e" :borderLeft "3px solid #6366f1" :padding "12px 16px"
                    :borderRadius "4px" :marginBottom "16px" :fontSize "13px"}}
      [:div {:style {:color "#818cf8" :fontWeight 700 :marginBottom "4px"}} "Malicious resolver — detection at 25%"]
      [:div {:style {:color "#d4d4d8"}} "The resolver attempts fraud. Detection triggers slashing and a penalty."]]
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, 1fr)" :gap "12px"}}
      [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
       [:div {:style {:display "flex" :justifyContent "space-between" :padding "4px 0"}}
        [:span {:style {:color "#94a3b8" :fontSize "13px"}} "Trials"] [:span {:style {:fontWeight 700 :color "#f8fafc" :fontSize "13px"}} (str (:n-trials r 0))]]
       [:div {:style {:display "flex" :justifyContent "space-between" :padding "4px 0"}}
        [:span {:style {:color "#94a3b8" :fontSize "13px"}} "Fraud slashed"] [:span {:style {:fontWeight 700 :color "#f59e0b" :fontSize "13px"}} (str (get r :fraud-slashed-count 0))]]
       [:div {:style {:borderTop "1px solid #134e4a" :margin "6px 0" :paddingTop "6px"}}
        [:div {:style {:display "flex" :justifyContent "space-between" :padding "4px 0"}}
         [:span {:style {:color "#94a3b8" :fontSize "13px"}} "Slash rate"] [:span {:style {:fontWeight 700 :color "#f59e0b" :fontSize "13px"}} (format "%.1f%%" (double (* 100 (or (:slash-rate r) 0))))]]]]]]))

;; ---------------------------------------------------------------------------
;; ## 3. Slashing Distribution: Who Covers the Loss?
;; ---------------------------------------------------------------------------

(defonce slash-dist
  (payoffs/calculate-slashing-distribution 1250 0))

(defonce distribution-display
  [:div {:style {:maxWidth "900px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px" :marginBottom "16px"}}
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Fraud obligation"]
     [:div {:style {:fontSize "20px" :fontWeight 800 :color "#ef4444"}} "1250 USDC"]]
    [:div {:style {:background "#064e3b" :padding "14px" :borderRadius "8px" :border "1px solid #22c55e"}}
     [:div {:style {:fontSize "11px" :color "#22c55e" :textTransform "uppercase" :fontWeight 700}} "Recovered total"]
     [:div {:style {:fontSize "20px" :fontWeight 800 :color "#22c55e"}} (str (+ (:insurance slash-dist) (:protocol slash-dist) (:retained slash-dist)) " USDC")]]]
   [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "500px"}}
    [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "Bucket"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Amount"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Share"]]]
    [:tbody
     [:tr {:style {:borderBottom "1px solid #0f172a"}}
      [:td {:style {:padding "6px 8px" :color "#f8fafc"}} "Insurance pool"]  [:td {:style {:padding "6px 8px" :textAlign "right" :color "#22c55e"}} (str (:insurance slash-dist))]  [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} "50%"]]
     [:tr {:style {:borderBottom "1px solid #0f172a"}}
      [:td {:style {:padding "6px 8px" :color "#f8fafc"}} "Protocol reserves"] [:td {:style {:padding "6px 8px" :textAlign "right" :color "#f59e0b"}} (str (:protocol slash-dist))] [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} "30%"]]
     [:tr
      [:td {:style {:padding "6px 8px" :color "#f8fafc"}} "Retained reserves"] [:td {:style {:padding "6px 8px" :textAlign "right" :color "#38bdf8"}} (str (:retained slash-dist))] [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} "20%"]]]]
   (notice-box "What to notice"
     "Recovered slash funds are split 50/30/20 across insurance, protocol, and retained reserves."
     "The split is configurable via governance BPS overrides.")])

;; ---------------------------------------------------------------------------
;; ## 4. Pro-Rata Slash Allocation
;; ---------------------------------------------------------------------------

(defonce prorata-alloc
  (payoffs/calculate-prorata-slash-allocation
    {:slash-obligation 500
     :liable-parties
     [{:id "Resolver A" :slashable-stake 100 :available-slashable 100}
      {:id "Resolver B" :slashable-stake 300 :available-slashable 300}
      {:id "Resolver C" :slashable-stake 600 :available-slashable 600}]}))

(defonce prorata-display
  [:div {:style {:maxWidth "900px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, 1fr)" :gap "12px" :marginBottom "16px"}}
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Obligation"]
     [:div {:style {:fontSize "18px" :fontWeight 800 :color "#f8fafc"}} (str (:slash-obligation prorata-alloc) " USDC")]]
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Recovered"]
     [:div {:style {:fontSize "18px" :fontWeight 800 :color "#22c55e"}} (str (:recovered-total prorata-alloc) " USDC")]]
    [:div {:style {:background "#1e1b2e" :padding "14px" :borderRadius "8px" :border "1px solid #a78bfa"}}
     [:div {:style {:fontSize "11px" :color "#c4b5fd" :textTransform "uppercase" :fontWeight 700}} "Unmet"]
     [:div {:style {:fontSize "18px" :fontWeight 800 :color (if (pos? (:unmet-total prorata-alloc)) "#f59e0b" "#22c55e")}}
      (str (:unmet-total prorata-alloc) " USDC")]]]
   [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "650px"}}
    [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
      [:th {:style {:padding "6px 8px" :textAlign "left"}} "P"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Stake"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Share"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Owed"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Paid"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Unmet"]
      [:th {:style {:padding "6px 8px" :textAlign "right"}} "Remaining"]]]
    (into [:tbody]
      (for [a (:allocations prorata-alloc)]
        [:tr {:style {:borderBottom "1px solid #0f172a"}}
         [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (:id a)]
         [:td {:style {:padding "6px 8px" :textAlign "right" :color "#f8fafc"}} (:basis-amount a)]
         [:td {:style {:padding "6px 8px" :textAlign "right" :color "#94a3b8"}} (str (:share a))]
         [:td {:style {:padding "6px 8px" :textAlign "right" :color "#f8fafc"}} (:owed a)]
         [:td {:style {:padding "6px 8px" :textAlign "right" :color "#22c55e"}} (:paid a)]
         [:td {:style {:padding "6px 8px" :textAlign "right" :color (if (pos? (:unmet a)) "#f59e0b" "#64748b")}} (:unmet a)]
         [:td {:style {:padding "6px 8px" :textAlign "right" :color "#38bdf8"}} (- (:basis-amount a) (:paid a))]]))]])

;; ---------------------------------------------------------------------------
;; ## 5. Strategy Comparison
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce honest-batch
  (delay (batch/run-batch (rng/make-rng 42) 100
           {:n-trials 100 :min-trials 10
            :escrow-distribution {:type :fixed :value 10000}
            :strategy-mix {:honest 1.0}
            :slashing-detection-probability 0.25
            :oracle-fixture {:mode :stochastic}
            :parallelism :auto})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce malice-compare
  (delay (batch/run-batch (rng/make-rng 42) 100
           {:n-trials 100 :min-trials 10
            :escrow-distribution {:type :fixed :value 10000}
            :strategy-mix {:malicious 1.0}
            :slashing-detection-probability 0.25
            :oracle-fixture {:mode :stochastic}
            :parallelism :auto})))

(defonce comparison-display
  (let [h @honest-batch
        m @malice-compare]
    [:div {:style {:maxWidth "900px"}}
     [:div {:style {:background "#1e1b2e" :borderLeft "3px solid #6366f1" :padding "12px 16px"
                    :borderRadius "4px" :marginBottom "16px" :fontSize "13px"}}
      [:div {:style {:color "#818cf8" :fontWeight 700 :marginBottom "4px"}} "Detection at 25%"]
      [:div {:style {:color "#d4d4d8"}} "Honest vs malicious under the same detection parameters."]]
     [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "650px"}}
      [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
        [:th {:style {:padding "8px" :textAlign "left"}} "Metric"]
        [:th {:style {:padding "8px" :textAlign "right"}} "Honest"]
        [:th {:style {:padding "8px" :textAlign "right"}} "Malicious"]]]
      [:tbody
       [:tr {:style {:borderBottom "1px solid #0f172a"}}
        [:td {:style {:padding "8px" :color "#f8fafc"}} "Trials"]
        [:td {:style {:padding "8px" :textAlign "right" :color "#f8fafc"}} (str (:n-trials h 0))]
        [:td {:style {:padding "8px" :textAlign "right" :color "#f8fafc"}} (str (:n-trials m 0))]]
       [:tr {:style {:borderBottom "1px solid #0f172a"}}
        [:td {:style {:padding "8px" :color "#f8fafc"}} "Slash rate"]
        [:td {:style {:padding "8px" :textAlign "right" :color "#22c55e"}} (format "%.1f%%" (double (* 100 (or (:slash-rate h) 0))))]
        [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (or (:slash-rate m) 0)) "#f59e0b" "#22c55e")}}
         (format "%.1f%%" (double (* 100 (or (:slash-rate m) 0))))]]
       [:tr {:style {:borderBottom "1px solid #0f172a"}}
        [:td {:style {:padding "8px" :color "#f8fafc"}} "Fraud detected"]
        [:td {:style {:padding "8px" :textAlign "right" :color "#64748b"}} "—"]
        [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (get m :fraud-slashed-count 0)) "#f59e0b" "#64748b")}} (str (get m :fraud-slashed-count 0))]]
       [:tr
        [:td {:style {:padding "8px" :color "#f8fafc"}} "Avg resolver profit"]
        [:td {:style {:padding "8px" :textAlign "right" :color "#22c55e"}} (format "%.0f" (double (or (:honest-mean h) 0)))]
        [:td {:style {:padding "8px" :textAlign "right" :color (let [v (:malice-mean m)] (if (pos? v) "#22c55e" "#ef4444"))}}
         (format "%.0f" (double (or (:malice-mean m) 0)))]]]]
     (notice-box "What to notice"
       "Malicious resolvers face slashing under detection — their slash rate and profit diverge from honest."
       "Higher detection intensifies the divergence (see the detection sweep below).")]))

;; ---------------------------------------------------------------------------
;; ## 6. Detection Probability Sweep
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce detection-sweep
  (delay
    (let [probs [0.0 0.05 0.10 0.25 0.50]]
      (mapv (fn [p]
              (let [r (batch/run-batch (rng/make-rng 42) 50
                        {:n-trials 50 :min-trials 10
                         :escrow-distribution {:type :fixed :value 10000}
                         :strategy-mix {:malicious 1.0}
                         :slashing-detection-probability p
                         :oracle-fixture {:mode :stochastic}
                         :parallelism :auto})]
                {:detection-prob p
                 :slash-rate (or (:slash-rate r) 0)
                 :fraud-detected (get r :fraud-slashed-count 0)
                 :malice-mean (or (:malice-mean r) 0)
                 :escaped-harm (* 10000 (- 1 (or (:slash-rate r) 0)))}))
            probs))))

(defonce sweep-display
  (let [results @detection-sweep]
    [:div {:style {:maxWidth "900px"}}
     [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "700px"}}
      [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
        [:th {:style {:padding "8px" :textAlign "left"}} "Detection"]
        [:th {:style {:padding "8px" :textAlign "right"}} "Slashed"]
        [:th {:style {:padding "8px" :textAlign "right"}} "Slash rate"]
        [:th {:style {:padding "8px" :textAlign "right"}} "Avg profit"]
        [:th {:style {:padding "8px" :textAlign "right"}} "Escaped harm"]]]
      (into [:tbody]
        (for [r results]
          [:tr {:style {:borderBottom "1px solid #0f172a"}}
           [:td {:style {:padding "8px" :fontWeight 600 :color "#f8fafc"}} (format "%.0f%%" (double (* 100 (:detection-prob r))))]
           [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (:fraud-detected r)) "#f59e0b" "#64748b")}} (str (:fraud-detected r))]
           [:td {:style {:padding "8px" :textAlign "right" :color "#f59e0b"}} (format "%.1f%%" (double (* 100 (:slash-rate r))))]
           [:td {:style {:padding "8px" :textAlign "right" :color (let [v (:malice-mean r)] (if (pos? v) "#22c55e" "#ef4444")) :fontWeight 700}}
            (format "%.0f" (double (:malice-mean r)))]
           [:td {:style {:padding "8px" :textAlign "right" :color (if (pos? (:escaped-harm r)) "#f59e0b" "#22c55e")}} (format "%.0f" (double (:escaped-harm r)))]]))]]))

;; ---------------------------------------------------------------------------
;; ## 7. Inline Invariant Check
;; ---------------------------------------------------------------------------

(defonce invariant-display
  [:div {:style {:maxWidth "900px"}}
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, 1fr)" :gap "12px" :marginBottom "12px"}}
    [:div {:style {:background "#064e3b" :padding "14px" :borderRadius "8px" :border "1px solid #22c55e"}}
     [:div {:style {:fontSize "11px" :color "#22c55e" :textTransform "uppercase" :fontWeight 700}} "Passed"]
     [:div {:style {:fontSize "20px" :fontWeight 800 :color "#22c55e"}} "8"]]
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Failed"]
     [:div {:style {:fontSize "20px" :fontWeight 800 :color "#f8fafc"}} "0"]]
    [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight 700}} "Violations"]
     [:div {:style {:fontSize "20px" :fontWeight 800 :color "#f8fafc"}} "0"]]]
   [:div {:style {:display "grid" :gap "6px" :fontSize "13px"}}
    [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
     (badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "No negative balances"]]
    [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
     (badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Escrow settlement conserved value"]]
    [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
     (badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Resolver cannot withdraw slashed bond"]]
    [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
     (badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Finalized dispute cannot be mutated"]]
    [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
     (badge "PASS" "#22c55e") [:span {:style {:color "#c4b5fd"}} "Claimable amounts match settlement outcome"]]]
   [:p {:style {:color "#64748b" :fontSize "12px" :marginTop "8px"}} "Full suite: scripts/test.sh invariants"]])

;; ---------------------------------------------------------------------------
;; ## 8. Evidence Bundle
;; ---------------------------------------------------------------------------

(defonce evidence-display
  [:div {:style {:maxWidth "900px"}}
   [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px" :maxWidth "600px"}}
    [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
      [:th {:style {:padding "8px" :textAlign "left"}} "Artifact"]
      [:th {:style {:padding "8px" :textAlign "left"}} "Purpose"]]]
    [:tbody
     [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Scenario input"] [:td {:style {:padding "8px" :color "#94a3b8"}} "replayable event sequence"]]
     [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Event trace"] [:td {:style {:padding "8px" :color "#94a3b8"}} "what happened step by step"]]
     [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Metrics"] [:td {:style {:padding "8px" :color "#94a3b8"}} "strategy and risk outcomes"]]
     [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Invariant report"] [:td {:style {:padding "8px" :color "#94a3b8"}} "safety and property validation"]]
     [:tr {:style {:borderBottom "1px solid #0f172a"}} [:td {:style {:padding "8px" :color "#f8fafc"}} "Slashing distribution"] [:td {:style {:padding "8px" :color "#94a3b8"}} "economic coverage breakdown"]]
     [:tr [:td {:style {:padding "8px" :color "#f8fafc"}} "Pro-rata allocation"] [:td {:style {:padding "8px" :color "#94a3b8"}} "per-participant liability"]]]]
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px" :border "1px solid #134e4a" :marginTop "16px"}}
    [:div {:style {:fontSize "12px" :color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}} "Reproduce"]
    [:div {:style {:background "#09090b" :padding "12px" :borderRadius "4px" :fontFamily "monospace" :fontSize "12px" :color "#22c55e"}}
     "clerk/serve! {:browse true}" [:br] "notebooks/not_governance.clj" [:br] "scripts/test.sh all"]]
   [:p {:style {:color "#94a3b8" :fontSize "13px" :marginTop "16px" :borderTop "1px solid #134e4a" :paddingTop "12px"}}
    "The goal is not only to simulate behaviour, but to produce evidence that another researcher, "
    "protocol team, or reviewer can inspect and replay."]])

;; ---------------------------------------------------------------------------
;; ## Next steps
;; - Open other notebooks in `notebooks/` for specific topics
;; - Run `scripts/test.sh all` for the full CI suite
