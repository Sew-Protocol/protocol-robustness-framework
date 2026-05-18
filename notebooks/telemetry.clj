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

(defn try-datasource []
  (try
    (require '[evaluation.store])
    ((resolve 'evaluation.store/->datasource))
    (catch Throwable _ nil)))

(def datasource (try-datasource))

(defn query [sql]
  (if (not datasource)
    []
    (try
      (require '[next.jdbc])
      ((resolve 'next.jdbc/execute!) datasource sql)
      (catch Throwable _ []))))

;; Status display

^{::clerk/no-cache true}
(clerk/html
 (if datasource
   [:div {:style {:padding "12px 16px" :backgroundColor "#dcfce7" :border "1px solid #16a34a"
                  :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#166534"}}
    "🟢 Database connection available"]
   [:div {:style {:padding "12px 16px" :backgroundColor "#fef3c7" :border "1px solid #f59e0b"
                  :borderRadius "3px" :marginBottom "20px" :fontSize "0.9em" :color "#92400e"}}
    "🟠 Database unavailable. XTDB must be running on localhost:5432"]))

;; ---
;; ## Quick Stats

^{::clerk/no-cache true}
(let [results (query ["SELECT COUNT(*) as total, COUNT(DISTINCT protocol_id) as protocols FROM sim_trial_results"])]
  (clerk/html
   (if (seq results)
     (let [r (first results)]
       [:div [:p (str "Total trials: " (:total r) " | Protocols: " (:protocols r))]])
     [:div [:p "No data available"]])))

;; ---
;; ## Selected Trial Inspector
;; Edit the trial-id below to inspect a specific trial

(def selected-trial-id nil)

^{::clerk/no-cache true}
(clerk/html
 (if selected-trial-id
   [:div [:p (str "Inspecting trial: " selected-trial-id)]]
   [:div {:style {:padding "12px" :backgroundColor "#f0f9ff" :border "1px solid #bfdbfe"
                  :borderRadius "3px"}}
    [:p "Set " [:code "selected-trial-id"] " to inspect a trial"]]))

;; ---
;; ## Live Telemetry (coming soon)

(clerk/html
 [:div {:style {:padding "12px" :backgroundColor "#f3f4f6" :borderRadius "3px" :fontSize "0.9em"
                :color "#666"}}
  [:p "🚧 Sections coming in P1-P3:"]
  [:ul
   [:li "Trial Results table with filters (P1)"]
   [:li "Outcome distribution histogram (P1)"]
   [:li "Event trace timeline (P2)"]
   [:li "Trial comparison view (P3)"]]])
