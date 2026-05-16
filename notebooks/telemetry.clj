(ns notebooks.telemetry
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.db.store :as store]
            [evaluation.store :as eval-store]
            [clojure.java.jdbc :as jdbc]))

;; # Simulation Telemetry Explorer
;; Browse and analyze simulation outcomes from the XTDB store.

(def ds (eval-store/->datasource))

(defn get-all-protocols []
  (jdbc/query ds ["SELECT DISTINCT protocol_id FROM sim_trial_results"]))

(defn load-batch-summary [protocol-id]
  (jdbc/query ds [(str "SELECT * FROM sim_trial_results WHERE protocol_id = '" protocol-id "'")]))

{::clerk/viewer :table}
(clerk/table
 (get-all-protocols))

;; ## Trial Results
;; Select a protocol to view outcomes.

(defn render-event [event]
  (clerk/html
   [:div.border-b.p-2
    [:span.font-mono (format "%03d" (:seq event))]
    [:span.ml-2.font-bold (:agent event)]
    [:span.ml-2.text-blue-600 (:action event)]
    [:span.ml-2.text-gray-500 (str (:params event))]]))

(defn render-trace [trace]
  (clerk/html
   [:div.bg-gray-50.p-4.rounded
    [:h3 "Full Trace Timeline"]
    (for [e trace]
      (render-event e))]))
