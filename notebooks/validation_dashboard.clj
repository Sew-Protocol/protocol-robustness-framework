(ns notebooks.validation-dashboard
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebooks.common :as common]))

;; # Validation Results Dashboard
;;
;; Aggregated view of suite-level validation results.
;; Loaded from: `results/test-artifacts/validation-root.json`

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [root (common/read-json "results/test-artifacts/validation-root.json")
       metrics (:metrics root)
       suites (:suite-results root)]
   [:div {:style {:font-family "JetBrains Mono, monospace" :padding "20px"}}
    [:h2 {:style {:color "#7ADDDC"}} (str "Overall Status: " (name (:status root)))]
    [:div {:style {:display "flex" :gap "20px" :margin-bottom "20px" :color "#cbd5e1"}}
     [:div "Checks: " (:checks metrics)]
     [:div "Passed: " (:passed metrics)]
     [:div "Failed: " (:failed metrics)]
     [:div "Warnings: " (:warnings metrics)]]
    [:h3 {:style {:color "#7ADDDC"}} "Suite Results"]
    [:table {:style {:width "100%" :border-collapse "collapse" :color "#cbd5e1"}}
     [:thead [:tr {:style {:border-bottom "1px solid #004D59"}}
              [:th "Suite"] [:th "Status"] [:th "Passed"] [:th "Failed"]]]
     [:tbody
      (for [[sid res] suites]
        [:tr {:style {:border-bottom "1px solid #004D59"}}
         [:td (name sid)]
         [:td (name (:status res))]
         [:td (get-in res [:metrics :passed])]
         [:td (get-in res [:metrics :failed])]])]]]))
