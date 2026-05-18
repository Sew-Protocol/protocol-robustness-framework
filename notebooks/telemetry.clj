(ns notebooks.telemetry
  (:require [nextjournal.clerk :as clerk]
            [next.jdbc :as jdbc]))

;; # Simulation Telemetry Workbench
;; Browse and analyze simulation outcomes from the XTDB store.
;;
;; This notebook displays trial outcomes and event traces from the SEW Protocol
;; simulation. All data is queried live from the XTDB instance running on
;; localhost:5432.
;;
;; ## Design
;; - **Researcher mode**: Edit filter defs below to narrow results, then reload
;; - **Status indicators**: 🟢 = DB connected, 🟠 = DB unavailable
;; - **Graceful degradation**: All sections work with or without DB

;; ---
;; ## Database connection

(defn try-datasource []
  (try
    (require '[evaluation.xtdb])
    ((resolve 'evaluation.xtdb/->datasource))
    (catch Throwable e
      (println "Failed to load datasource:" (.getMessage e))
      nil)))

(def ^:private -datasource (try-datasource))

(defn make-ds []
  (or -datasource
      (try
        (require '[evaluation.xtdb])
        ((resolve 'evaluation.xtdb/->datasource))
        (catch Throwable _ nil))))

(defn q
  "Execute a SQL query; return empty list if DS unavailable"
  [sql]
  (if-let [ds (make-ds)]
    (try
      (jdbc/execute! ds sql)
      (catch Exception e
        (println "Query error:" (.getMessage e))
        []))
    []))

;; ---
;; ## Status display

^{::clerk/no-cache true}
(clerk/html
 (let [ds (make-ds)
       trial-count (if ds
                     (try
                       (:count (first (jdbc/execute! ds ["SELECT COUNT(*) as count FROM sim_trial_results"])))
                       (catch Exception _ nil))
                     nil)]
   (if trial-count
     [:div {:style {:padding "12px 16px" :backgroundColor "#dcfce7" :border "1px solid #16a34a"
                    :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#166534"}}
      [:strong "🟢 Database connected"] " | " trial-count " trials in store"]
     [:div {:style {:padding "12px 16px" :backgroundColor "#fef3c7" :border "1px solid #f59e0b"
                    :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#92400e"}}
      [:strong "🟠 Database unavailable"] " | XTDB required on localhost:5432"])))

;; ---
;; ## Outcome Distribution (P1)

^{::clerk/no-cache true}
(let [rows (q ["SELECT outcome, COUNT(*) as count FROM sim_trial_results GROUP BY outcome ORDER BY count DESC"])]
  (clerk/html
   (if (seq rows)
     [:div
      [:h3 "Outcome Distribution"]
      [:table {:style {:borderCollapse "collapse" :fontSize "0.9em" :width "100%" :marginBottom "20px"}}
       [:thead [:tr
                [:th {:style {:padding "8px 12px" :textAlign "left" :fontWeight "600"
                             :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Outcome"]
                [:th {:style {:padding "8px 12px" :textAlign "right" :fontWeight "600"
                             :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Count"]]]
       (into [:tbody]
             (for [r rows]
               [:tr
                [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0"}}
                 (let [outcome (:outcome r)
                       color (case outcome
                               "released" "#10b981"
                               "slashed" "#ef4444"
                               "disputed" "#f59e0b"
                               "#94a3b8")]
                   [:span {:style {:display "inline-block" :padding "3px 8px" :borderRadius "3px"
                                   :backgroundColor color :color "white" :fontSize "0.75em"
                                   :fontWeight "600"}}
                    outcome])]
                [:td {:style {:padding "5px 12px" :textAlign "right" :borderBottom "1px solid #e2e8f0"
                             :fontFamily "monospace" :fontSize "0.85em"}}
                 (:count r)]]))]
      ]
     [:div {:style {:padding "20px" :textAlign "center" :color "#94a3b8"
                    :backgroundColor "#f8fafc" :borderRadius "4px"
                    :border "1px solid #e2e8f0" :fontSize "0.9em"}}
      "No outcomes found"])))

;; ---
;; ## Trial Results with Filters (P1)

;; Filter state — edit these then reload to apply filters
(def filter-outcome "")
(def filter-invariants "")

;; Filter UI
(clerk/html
 [:div {:style {:backgroundColor "#f0f9ff" :padding "12px 16px" :borderRadius "4px"
                :border "1px solid #bfdbfe" :marginBottom "16px"}}
  [:div {:style {:fontSize "0.85em" :fontWeight "600" :color "#1e40af" :marginBottom "12px"}}
   "🔍 Filters (edit defs below, reload notebook)"]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
   [:div
    [:label {:style {:fontSize "0.8em" :color "#475569" :fontWeight "500" :display "block"
                    :marginBottom "4px"}}
     "Outcome filter"]
    [:code {:style {:fontSize "0.75em" :color "#666"}}
     "(def filter-outcome "" filter-outcome "")"]]
   [:div
    [:label {:style {:fontSize "0.8em" :color "#475569" :fontWeight "500" :display "block"
                    :marginBottom "4px"}}
     "Invariants filter"]
    [:code {:style {:fontSize "0.75em" :color "#666"}}
     "(def filter-invariants "" filter-invariants "")"]]]])

;; Trial Results table
^{::clerk/no-cache true}
(let [rows (q ["SELECT _id, batch_id, outcome, invariants_ok FROM sim_trial_results ORDER BY _id DESC LIMIT 100"])
      filtered-rows (cond-> rows
                      (not-empty filter-outcome)
                      (filter (fn [r] (= (str (:outcome r)) filter-outcome)))
                      
                      (= filter-invariants "pass")
                      (filter (fn [r] (:invariants_ok r)))
                      
                      (= filter-invariants "fail")
                      (filter (fn [r] (not (:invariants_ok r)))))]
  (clerk/html
   (if (seq filtered-rows)
     [:div
      [:h3 "Trial Results (" (count filtered-rows) " trials)"]
      [:table {:style {:borderCollapse "collapse" :fontSize "0.9em" :width "100%"}}
       [:thead [:tr
                [:th {:style {:padding "8px 12px" :textAlign "left" :fontWeight "600"
                             :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Trial ID"]
                [:th {:style {:padding "8px 12px" :textAlign "left" :fontWeight "600"
                             :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Batch"]
                [:th {:style {:padding "8px 12px" :textAlign "left" :fontWeight "600"
                             :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Outcome"]
                [:th {:style {:padding "8px 12px" :textAlign "center" :fontWeight "600"
                             :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Inv OK?"]]]
       (into [:tbody]
             (for [r filtered-rows]
               (let [trial-id (str (:_id r))
                     short-id (if (> (count trial-id) 8)
                                (str (subs trial-id 0 8) "…")
                                trial-id)
                     outcome (:outcome r)
                     color (case outcome
                             "released" "#10b981"
                             "slashed" "#ef4444"
                             "disputed" "#f59e0b"
                             "#94a3b8")]
                 [:tr
                  [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0"
                               :fontFamily "monospace" :fontSize "0.8em"}}
                   short-id]
                  [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0"
                               :fontFamily "monospace" :fontSize "0.8em"}}
                   (subs (str (:batch_id r)) 0 8)]
                  [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0"}}
                   [:span {:style {:display "inline-block" :padding "3px 8px" :borderRadius "3px"
                                   :backgroundColor color :color "white" :fontSize "0.75em"
                                   :fontWeight "600"}}
                    outcome]]
                  [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0"
                               :textAlign "center"}}
                   (if (:invariants_ok r)
                     [:span {:style {:color "#16a34a" :fontWeight "600"}} "✓"]
                     [:span {:style {:color "#dc2626" :fontWeight "600"}} "✗"])]])))]
      ]
     [:div {:style {:padding "20px" :textAlign "center" :color "#94a3b8"
                    :backgroundColor "#f8fafc" :borderRadius "4px"
                    :border "1px solid #e2e8f0" :fontSize "0.9em"}}
      "No trials found matching filters"])))

;; ---
;; ## Event Trace Viewer (P2)

(def selected-trial-id nil)

^{::clerk/no-cache true}
(clerk/html
 (if selected-trial-id
   [:div
    [:h3 "Event Trace for trial " (subs (str selected-trial-id) 0 8)]
    [:p "🚧 Event timeline coming in P2"]]
   [:div {:style {:padding "12px 16px" :backgroundColor "#f0f9ff" :border "1px solid #bfdbfe"
                  :borderRadius "3px"}}
    [:p "Set " [:code "selected-trial-id"] " to inspect a trial's event timeline"]]))

;; ---
;; ## Notes

(clerk/html
 [:div {:style {:padding "12px 16px" :backgroundColor "#f3f4f6" :borderRadius "4px"
                :marginTop "24px" :fontSize "0.85em" :color "#4b5563"}}
  [:strong "💭 Research workbench design:"]
  [:ul
   [:li "All filters are code-driven — edit defs and reload"]
   [:li "All selections auditable in source code"]
   [:li "Status colors explicit — never ambiguous"]
   [:li "Missing data marked, not hidden"]]
  [:p {:style {:marginTop "12px"}}
   "🚧 Coming next: P2 event timeline, P3 trial comparison"]])
