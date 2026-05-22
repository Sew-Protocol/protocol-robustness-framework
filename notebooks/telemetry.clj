(ns notebooks.telemetry
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebooks.ui :as ui]))

;; Deterministic synchronized workbench state.
^{::clerk/sync true}
(defonce !ui-state (atom ui/default-ui-state))

(defn ds-result []
  (try
    (require '[evaluation.xtdb])
    {:ok? true
     :ds ((resolve 'evaluation.xtdb/->datasource))
     :source "evaluation.xtdb/->datasource"}
    (catch Throwable e
      {:ok? false
       :error (or (.getMessage e) (str e))
       :source "evaluation.xtdb/->datasource"})))

(defn query-result [sqlvec]
  (let [{:keys [ok? ds] :as dsr} (ds-result)]
    (if-not ok?
      (merge dsr {:rows [] :sql sqlvec})
      (try
        (require '[next.jdbc])
        {:ok? true :rows ((resolve 'next.jdbc/execute!) ds sqlvec) :sql sqlvec}
        (catch Throwable e
          {:ok? false :rows [] :error (or (.getMessage e) (str e)) :sql sqlvec})))))

(defn to-uuid [x]
  (try
    (when (and x (not (str/blank? (str x))))
      (java.util.UUID/fromString (str x)))
    (catch Exception _ nil)))

(defn render-query-error [{:keys [kind error sql]}]
  (ui/query-error-callout "Telemetry query error" {:kind kind :error error :sql sql}))

(defn trial-row->view [r]
  {:trial-id (str (:_id r))
   :batch-id (str (:batch_id r))
   :protocol-id (str (:protocol_id r))
   :outcome (str (:outcome r))
   :invariants-ok (true? (:invariants_ok r))
   :divergence (true? (:divergence r))})

(defn event-row->view [e]
  {:block-time (:block_time e)
   :entity-id (:entity_id e)
   :event-type (:event_type e)
   :entity-state (:entity_state e)})

(defn apply-filters [rows {:keys [outcome invariants]}]
  (cond-> rows
    (not (str/blank? (or outcome "")))
    (->> (filter #(= (str (:outcome %)) outcome)))
    (= invariants "pass")
    (->> (filter #(true? (:invariants_ok %))))
    (= invariants "fail")
    (->> (filter #(false? (:invariants_ok %))))))

^{::clerk/no-cache true}
(let [ui-state @!ui-state
      {:keys [filters pagination selected-trial-id]} ui-state
      limit (-> (get pagination :limit 100) (max 1) (min 1000))
      dsr (ds-result)
      trials-r (query-result ["SELECT COUNT(*) AS count FROM sim_trial_results"])
      events-r (query-result ["SELECT COUNT(*) AS count FROM sim_entity_events"])
      protocols-r (query-result ["SELECT COUNT(DISTINCT protocol_id) AS count FROM sim_trial_results"])
      qres (query-result [(str "SELECT _id, batch_id, protocol_id, outcome, invariants_ok, divergence "
                               "FROM sim_trial_results ORDER BY _id DESC LIMIT " limit)])
      base-rows (:rows qres)
      filtered-rows (apply-filters base-rows filters)
      table-rows (mapv trial-row->view filtered-rows)
      selected-id (or selected-trial-id (:trial-id (first table-rows)))
      tid (to-uuid selected-id)
      t-res (if tid (query-result ["SELECT * FROM sim_trial_results WHERE _id = ?" tid]) {:ok? true :rows []})
      e-res (if tid (query-result ["SELECT * FROM sim_entity_events WHERE trial_id = ? ORDER BY block_time ASC" tid]) {:ok? true :rows []})
      trial (first (:rows t-res))
      events (mapv event-row->view (:rows e-res))]
  (clerk/html
   [:div
    [:h1 "Simulation Telemetry Workbench"]
    [:p {:style {:fontSize "0.9em" :color "#444"}}
     "Deterministic exploratory notebook with synchronized UI query state."]
    (ui/notebook-navigation "Telemetry Trial Timeline")

    (if (:ok? dsr)
      (ui/callout :green [:div [:strong "Database connection available"] [:code (:source dsr)]])
      (ui/callout :amber [:div [:strong "Database unavailable"] [:code (:error dsr)]]))

    [:div {:style {:display "flex" :gap "10px" :flexWrap "wrap" :marginBottom "12px"}}
     (ui/metric-card {:label "Trials" :value (if (:ok? trials-r) (or (:count (first (:rows trials-r))) 0) "—") :rag (if (:ok? trials-r) :green :red)})
     (ui/metric-card {:label "Events" :value (if (:ok? events-r) (or (:count (first (:rows events-r))) 0) "—") :rag (if (:ok? events-r) :green :red)})
     (ui/metric-card {:label "Protocols" :value (if (:ok? protocols-r) (or (:count (first (:rows protocols-r))) 0) "—") :rag (if (:ok? protocols-r) :green :red)})]

    (ui/filter-controls !ui-state)
    (ui/trial-selection-controls !ui-state)

    [:h2 "Trial Results"]
    [:p {:style {:fontSize "0.84em" :color "#475569" :marginTop "-6px"}}
     "You can select a trial in two ways: "
     "(1) click Trial ID in the table, or (2) paste full trial UUID in the Selected Trial input above."]
    (if-not (:ok? qres)
      (render-query-error qres)
      (if (seq table-rows)
        (ui/select-trial-view !ui-state table-rows)
        (ui/callout :amber [:div "No rows matched current filters."])))

    [:h2 "Event Trace Viewer"]
    (cond
      (nil? selected-id)
      (ui/callout :amber
                  [:div
                   [:div "No trial selected yet."]
                   [:ol {:style {:margin "8px 0 0 18px" :fontSize "0.86em"}}
                    [:li "Paste a full UUID in Selected Trial input, or click a Trial ID row."]
                    [:li "The Event Trace Viewer will refresh automatically."]]])

      (nil? tid)
      (ui/callout :red [:div "Selected trial id is not a valid UUID string."])

      (not (:ok? t-res))
      (render-query-error t-res)

      (not (:ok? e-res))
      (render-query-error e-res)

      (nil? trial)
      (ui/callout :amber [:div "No trial found for selected trial id."])

      :else
      [:div
       [:div {:style {:backgroundColor "#f8fafc" :border "1px solid #cbd5e1" :borderRadius "4px"
                      :padding "10px" :marginBottom "10px" :fontSize "0.84em"}}
        [:div [:strong "Trial ID: "] [:code (str (:_id trial))]]
        [:div [:strong "Protocol: "] (str (:protocol_id trial))]
        [:div [:strong "Outcome: "] (str (:outcome trial))]
        [:div [:strong "invariants_ok: "] (if (:invariants_ok trial) "true" "false")]
        [:div [:strong "divergence: "] (if (:divergence trial) "true" "false")]
        [:div [:strong "Events: "] (count events)]]
       (ui/event-timeline-view events)])]))
