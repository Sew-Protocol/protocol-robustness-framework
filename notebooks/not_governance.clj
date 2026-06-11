;; # Simulation Framework — Quickstart
;; ### Run disputes, compare strategies, validate invariants
;; 
;; This workbook shows the simulation framework from the outside in.
;; No governance parameter knowledge required — defaults are applied automatically.
;; Each section is self-contained; run cells in order or jump to any section.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(ns notebooks.not-governance
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.fixtures :as fixtures]
            [resolver-sim.notebooks.common :as common]))

;; ---------------------------------------------------------------------------
;; ## 1. Dispute Lifecycle
;; Walk through one dispute from creation to settlement.
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
             :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
            {:seq 3 :time 1200 :agent "keeper"  :action "execute_pending_settlement"
             :params {:workflow-id 0}}]})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce lifecycle-result (delay (sew/replay-with-sew-protocol lifecycle-scenario)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:display "grid" :gap "12px" :fontSize "13px" :maxWidth "800px"}}
  [:div {:style {:display "flex" :gap "16px" :marginBottom "8px"}}
   (common/safe-render "outcome" #(clerk/html
    [:div {:style {:background "#0f172a" :padding "12px 24px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight "700"}} "Outcome"]
     [:div {:style {:fontSize "20px" :fontWeight "800" :color (if (= :pass (:outcome @lifecycle-result)) "#22c55e" "#ef4444")}}
      (if (= :pass (:outcome @lifecycle-result)) "PASS" "FAIL")]]))
   (common/safe-render "events" #(clerk/html
    [:div {:style {:background "#0f172a" :padding "12px 24px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight "700"}} "Events processed"]
     [:div {:style {:fontSize "20px" :fontWeight "800" :color "#f8fafc"}} (:events-processed @lifecycle-result)]]))
   (common/safe-render "trace" #(clerk/html
    [:div {:style {:background "#0f172a" :padding "12px 24px" :borderRadius "8px" :border "1px solid #134e4a"}}
     [:div {:style {:fontSize "11px" :color "#7ADDDC" :textTransform "uppercase" :fontWeight "700"}} "Buckets"]
     [:div {:style {:fontSize "20px" :fontWeight "800" :color "#f8fafc"}} (:batch-buckets (:metrics @lifecycle-result) 0)]]))]
  [:h4 {:style {:color "#7ADDDC" :margin "16px 0 8px"}} "Event trace"]
  (into [:div {:style {:display "grid" :gap "4px"}}]
    (for [e (:trace @lifecycle-result)
          :let [ok? (= :ok (:result e))]]
      [:div {:style {:display "grid" :gridTemplateColumns "40px 80px 220px 60px"
                     :gap "8px" :padding "4px 8px"
                     :background (if ok? "#0f172a" "#1a0f0f")
                     :borderRadius "4px" :fontSize "12px"}}
       [:span {:style {:color "#64748b" :fontFamily "monospace"}} (str (:seq e))]
       [:span {:style {:color "#94a3b8"}} (str (:time e) "s")]
       [:span {:style {:fontWeight 600 :color "#f8fafc"}} (name (:action e))]
       [:span {:style {:color (if ok? "#22c55e" "#ef4444") :fontWeight 600}}
        (if ok? "ok" (str (name (:error e))))]]))])

;; ---------------------------------------------------------------------------
;; ## 2. Honest vs Malicious
;; Compare resolver strategies side by side.
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce honest-result
  (delay (batch/run-batch (rng/make-rng 42) 100
           {:n-trials 100 :min-trials 10
            :escrow-distribution {:type :fixed :value 10000}
            :strategy-mix {:honest 1.0}
            :oracle-fixture {:mode :stochastic}
            :parallelism :auto})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce malice-result
  (delay (batch/run-batch (rng/make-rng 42) 100
           {:n-trials 100 :min-trials 10
            :escrow-distribution {:type :fixed :value 10000}
            :strategy-mix {:malicious 1.0}
            :oracle-fixture {:mode :stochastic}
            :parallelism :auto})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defn- result-card
  [label r]
  [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px" :border "1px solid #134e4a"}}
   [:h3 {:style {:fontSize "14px" :color "#7ADDDC" :marginBottom "12px"}} label]
   [:div {:style {:display "grid" :gap "8px"}}
    [:div {:style {:display "flex" :justifyContent "space-between"}}
     [:span {:style {:color "#94a3b8"}} "Disputes"] [:span {:style {:fontWeight 700 :color "#f8fafc"}} (:disputes-triggered r 0)]]
    [:div {:style {:display "flex" :justifyContent "space-between"}}
     [:span {:style {:color "#94a3b8"}} "Correct"] [:span {:style {:fontWeight 700 :color "#22c55e"}} (:correct-decisions r 0)]]
    [:div {:style {:display "flex" :justifyContent "space-between"}}
     [:span {:style {:color "#94a3b8"}} "Slash rate"] [:span {:style {:fontWeight 700 :color "#f59e0b"}} (format "%.1f%%" (double (* 100 (or (:slash-rate r) 0))))]]
    [:div {:style {:display "flex" :justifyContent "space-between"}}
     [:span {:style {:color "#94a3b8"}} "Avg profit"] [:span {:style {:fontWeight 700 :color (if (pos? (or (:mean-profit r) 0)) "#22c55e" "#ef4444")}} (format "%.0f" (double (or (:mean-profit r) 0)))]]]])

^{:nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "24px" :maxWidth "800px"}}
  (result-card "Honest resolvers" @honest-result)
  (result-card "Malicious resolvers" @malice-result)])

;; ---------------------------------------------------------------------------
;; ## 3. Detection Mechanism Comparison
;; How detection probability affects deterrence.
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(defonce detection-results
  (delay
    (let [probs [0.0 0.05 0.10 0.25 0.50]]
      (map (fn [p]
             (let [r (batch/run-batch (rng/make-rng 42) 50
                      {:n-trials 50 :min-trials 10
                       :escrow-distribution {:type :fixed :value 10000}
                       :strategy-mix {:malicious 1.0}
                       :slashing-detection-probability p
                       :oracle-fixture {:mode :stochastic}
                       :parallelism :auto})]
               {:detection-prob p
                :slash-rate (or (:slash-rate r) 0)
                :profit (or (:mean-profit r) 0)}))
           probs))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:maxWidth "600px"}}
  [:h4 {:style {:color "#7ADDDC" :marginBottom "12px"}} "Malicious resolver profit vs detection probability"]
  [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "13px"}}
   [:thead
    [:tr {:style {:borderBottom "1px solid #134e4a"}}
     [:th {:style {:textAlign "left" :padding "8px" :color "#94a3b8"}} "Detection prob"]
     [:th {:style {:textAlign "right" :padding "8px" :color "#94a3b8"}} "Slash rate"]
     [:th {:style {:textAlign "right" :padding "8px" :color "#94a3b8"}} "Avg profit"]]]
   (into [:tbody]
      (for [r @detection-results]
       [:tr {:style {:borderBottom "1px solid #0f172a"}}
        [:td {:style {:padding "8px" :fontWeight 600}} (format "%.0f%%" (double (* 100 (:detection-prob r))))]
        [:td {:style {:padding "8px" :textAlign "right" :color "#f59e0b"}} (format "%.1f%%" (double (* 100 (:slash-rate r))))]
        [:td {:style {:padding "8px" :textAlign "right"
                      :color (if (pos? (:profit r)) "#22c55e" "#ef4444")
                      :fontWeight 700}} (format "%.0f" (double (:profit r)))]]))]])

;; ---------------------------------------------------------------------------
;; ## 4. Invariant Suite
;; Run all Sew protocol invariants and check pass rate.
;; ---------------------------------------------------------------------------

^{:nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:p {:style {:color "#94a3b8" :padding "12px" :border "1px solid #134e4a" :borderRadius "8px"}}
  "Invariant suite not included in workbook — run via:  scripts/test.sh invariants"])

;; ---------------------------------------------------------------------------
;; ## Next steps
;; - Open other notebooks in `notebooks/` for specific topics
;; - Run `scripts/test.sh all` for the full CI suite
;; - Governance parameters are transparently available via `io/params/merge-defaults`
