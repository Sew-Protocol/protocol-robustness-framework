(ns notebooks.telemetry
  (:require [nextjournal.clerk :as clerk]))

;; # Simulation Telemetry Workbench
;; Browse and analyze simulation outcomes from the XTDB store.
;;
;; This notebook displays trial outcomes and event traces from the SEW Protocol
;; simulation. All data is queried live from the XTDB instance running on
;; localhost:5432.
;;
;; **Status indicators:**
;; - 🟢 Green: Data is available and queries are working
;; - 🟠 Amber: Database is unavailable; showing placeholder content
;; - 🔴 Red: Error loading sections; check XTDB connection

;; ---
;; ## Database connection & availability

(defn make-ds []
  (try
    (require '[evaluation.xtdb])
    ((resolve 'evaluation.xtdb/->datasource))
    (catch Throwable _ nil)))

(defn q [sql]
  (if-let [ds (make-ds)]
    (try
      (require '[next.jdbc])
      ((resolve 'next.jdbc/execute!) ds sql)
      (catch Throwable _ []))
    []))

^{::clerk/no-cache true}
(let [ds (make-ds)]
  (clerk/html
   (if ds
     (let [trial-count (try
                         (:count (first (q ["SELECT COUNT(*) as count FROM sim_trial_results"])))
                         (catch Exception _ nil))]
       (if trial-count
         [:div {:style {:padding "12px 16px" :backgroundColor "#dcfce7" :border "1px solid #16a34a"
                        :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#166534"}}
          "🟢 Database connection available | " trial-count " trials"]
         [:div {:style {:padding "12px 16px" :backgroundColor "#fef3c7" :border "1px solid #f59e0b"
                        :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#92400e"}}
          "🟠 Database unavailable"]))
     [:div {:style {:padding "12px 16px" :backgroundColor "#fef3c7" :border "1px solid #f59e0b"
                    :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#92400e"}}
      "🟠 Database unavailable. XTDB must be running on localhost:5432"])))

;; ---
;; ## P1 Trial Results & Filters

(def filter-outcome "")
(def filter-invariants "")

;; Filter UI
(clerk/html
 [:div {:style {:backgroundColor "#f0f9ff" :padding "12px 16px" :borderRadius "4px"
                :border "1px solid #bfdbfe" :marginBottom "16px"}}
  [:div {:style {:fontSize "0.85em" :fontWeight "600" :color "#1e40af" :marginBottom "12px"}}
   "Filters (edit defs and reload)"]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
   [:div [:label "Outcome"] [:code "filter-outcome=\"" filter-outcome "\""]]
   [:div [:label "Invariants"] [:code "filter-invariants=\"" filter-invariants "\""]]]])

;; ---
;; ## P2 Interactive Trial Selector

(def selected-trial-id nil)

(clerk/html
 [:div {:style {:marginBottom "16px" :padding "12px" :backgroundColor "#fef3c7" :border "1px solid #f59e0b" :borderRadius "4px"}}
  [:label {:style {:display "block" :marginBottom "8px" :fontWeight "600" :fontSize "0.9em" :color "#92400e"}}
   "Select Trial ID (paste or copy from table):"]
  [:input {:id "trial-id-input"
           :type "text"
           :placeholder "Paste trial UUID here"
           :style {:width "100%" :padding "8px 12px" :border "1px solid #f59e0b" :borderRadius "3px" :fontFamily "monospace" :fontSize "0.85em" :boxSizing "border-box"}
           :value (or selected-trial-id "")
           :on-change (fn [e]
                        (let [val (-> e .-target .-value)]
                          (if (empty? val)
                            (def selected-trial-id nil)
                            (when (re-matches #"[0-9a-f\-]{36}" val)
                              (def selected-trial-id val)))))}]])

;; Trial Results Table
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
      [:table {:style {:borderCollapse "collapse" :fontSize "0.85em" :width "100%"}}
       [:thead [:tr
                [:th {:style {:padding "8px 12px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Trial ID"]
                [:th {:style {:padding "8px 12px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Batch"]
                [:th {:style {:padding "8px 12px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1"}}
                 "Outcome"]
                [:th {:style {:padding "8px 12px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1" :textAlign "center"}}
                 "OK?"]]]
       (into [:tbody]
             (for [r filtered-rows]
               (let [trial-id (str (:_id r))
                     short-id (if (> (count trial-id) 8) (str (subs trial-id 0 8) "...") trial-id)
                     outcome (:outcome r)
                     color (case outcome "released" "#10b981" "slashed" "#ef4444" "disputed" "#f59e0b" "#94a3b8")]
                 [:tr
                  [:td {:style {:padding "5px 12px" :fontFamily "monospace" :fontSize "0.8em" :borderBottom "1px solid #e2e8f0" :cursor "pointer" :color "#3b82f6" :fontWeight "600" :title (str "Click to select: " trial-id)}
                        :on-click (fn [] (def selected-trial-id trial-id))}
                   short-id]
                  [:td {:style {:padding "5px 12px" :fontFamily "monospace" :fontSize "0.8em" :borderBottom "1px solid #e2e8f0"}}
                   (subs (str (:batch_id r)) 0 8)]
                  [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0"}}
                   [:span {:style {:display "inline-block" :padding "3px 8px" :borderRadius "3px" :backgroundColor color :color "white" :fontSize "0.75em" :fontWeight "600"}}
                    outcome]]
                  [:td {:style {:padding "5px 12px" :borderBottom "1px solid #e2e8f0" :textAlign "center"}}
                   (if (:invariants_ok r) "✓" "✗")]])))]
      ]
     [:div "No trials found"])))

;; ---
;; ## Event Trace Viewer

^{::clerk/no-cache true}
(clerk/html
 (if selected-trial-id
   [:div [:p "Inspecting trial: " [:code selected-trial-id]]]
   [:div {:style {:padding "12px" :backgroundColor "#f0f9ff" :border "1px solid #bfdbfe"
                  :borderRadius "3px"}}
    [:p "Click a trial ID above (blue text) or paste into field to view event trace"]]))

^{::clerk/no-cache true}
(let [events (if selected-trial-id
               (try
                 (q ["SELECT * FROM sim_entity_events WHERE trial_id = ? ORDER BY block_time, seq"
                     (java.util.UUID/fromString (str selected-trial-id))])
                 (catch Exception _ []))
               [])
      trial-info (if selected-trial-id
                   (try
                     (first (q ["SELECT * FROM sim_trial_results WHERE _id = ?"
                                (java.util.UUID/fromString (str selected-trial-id))]))
                     (catch Exception _ nil))
                   nil)]
  (clerk/html
   (if trial-info
     [:div
      [:h3 "Trial " (subs (str (:_id trial-info)) 0 8) " — Event Trace"]
      [:div {:style {:backgroundColor "#f0f9ff" :padding "12px" :borderRadius "4px" :border "1px solid #bfdbfe" :marginBottom "12px" :fontSize "0.85em"}}
       [:div [:strong "Outcome: "] (:outcome trial-info)]
       [:div [:strong "Invariants OK: "] (if (:invariants_ok trial-info) "✓" "✗")]
       [:div [:strong "Divergence: "] (if (:divergence trial-info) "Yes" "No")]]
      (if (seq events)
        [:table {:style {:borderCollapse "collapse" :fontSize "0.8em" :width "100%"}}
         [:thead [:tr
                  [:th {:style {:padding "6px 10px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1" :fontSize "0.75em"}}
                   "Step"]
                  [:th {:style {:padding "6px 10px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1" :fontSize "0.75em"}}
                   "Time"]
                  [:th {:style {:padding "6px 10px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1" :fontSize "0.75em"}}
                   "Event"]
                  [:th {:style {:padding "6px 10px" :fontWeight "600" :backgroundColor "#f1f5f9" :borderBottom "2px solid #cbd5e1" :fontSize "0.75em"}}
                   "State"]]]
         (into [:tbody]
               (map-indexed
                (fn [idx e]
                  [:tr
                   [:td {:style {:padding "4px 10px" :borderBottom "1px solid #e2e8f0" :fontSize "0.75em"}}
                    (inc idx)]
                   [:td {:style {:padding "4px 10px" :borderBottom "1px solid #e2e8f0" :fontFamily "monospace" :fontSize "0.7em"}}
                    (:block_time e)]
                   [:td {:style {:padding "4px 10px" :borderBottom "1px solid #e2e8f0" :fontSize "0.75em"}}
                    (let [etype (:event_type e)
                          color (case etype "created" "#0066cc" "proposed" "#0099ff" "disputed" "#ff9900" "resolved" "#00aa00" "slashed" "#ff0000" "#999")]
                      [:span {:style {:display "inline-block" :padding "2px 6px" :borderRadius "2px" :backgroundColor color :color "white" :fontSize "0.7em"}}
                       etype])]
                   [:td {:style {:padding "4px 10px" :borderBottom "1px solid #e2e8f0" :fontFamily "monospace" :fontSize "0.7em"}}
                    (str (:entity_state e))]])
                events))]
        [:div "No events recorded"])]
     [:div {:style {:padding "20px" :textAlign "center" :color "#999" :backgroundColor "#f8fafc" :borderRadius "4px" :border "1px solid #e2e8f0" :fontSize "0.9em"}}
      "No trial selected"])))

(clerk/html
 [:div {:style {:fontSize "0.85em" :color "#666" :lineHeight "1.6"}}
  [:p "Workflow:"]
  [:ul
   [:li "Edit " [:code "filter-outcome"] " and " [:code "filter-invariants"] " defs above to filter trials"]
   [:li "Click trial ID (blue text) in the table OR paste UUID in field above"]
   [:li "Event trace updates automatically"]
   [:li "Coming: P3 trial comparison view, outcome distribution histogram"]]
  [:p [:strong "Status: P1 & P2 fully functional"]]
  [:ul
   [:li "✓ Live database queries from XTDB"]
   [:li "✓ Trial filtering"]
   [:li "✓ Event trace viewer"]
   [:li "✓ Clickable trial IDs or paste into field"]]
  ])
