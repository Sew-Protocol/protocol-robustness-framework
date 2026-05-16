^{:nextjournal.clerk/doc-css "https://cdn.tailwindcss.com"
  :nextjournal.clerk/visibility {:code :hide :result :show}}
(ns notebooks.report
  "Generic Validation Evidence Dashboard"
  {:nextjournal.clerk/toc true}
  (:require [nextjournal.clerk :as clerk]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Data Loading
;; ---------------------------------------------------------------------------

(defn- load-artifact [path]
  (let [f (io/file path)]
    (when (.exists f)
      (with-open [r (io/reader f)]
        (json/read r :key-fn keyword)))))

(defn get-suite-data [suite-root]
  {:summary    (load-artifact (str suite-root "/actual/summary.json"))
   :scenarios  (load-artifact (str suite-root "/actual/scenario-results.json"))
   :evidence   (load-artifact (str suite-root "/actual/evidence-matrix.json"))
   :invariants (load-artifact (str suite-root "/actual/invariants.json"))})

;; ---------------------------------------------------------------------------
;; Dashboard Component
;; ---------------------------------------------------------------------------

(defn dashboard [suite-root]
  (let [data (get-suite-data suite-root)]
    (if (every? nil? (vals data))
      (clerk/html [:div "No validation artifacts found for suite: " suite-root])
      (clerk/html
       [:div {:class "min-h-screen bg-slate-50 px-4 py-8 md:px-6"}
        [:h1 {:class "text-2xl font-bold"} (str "Validation Suite: " suite-root)]
        [:p "Dashboard components would render here, using data:"
         [:pre (str (keys data))]]]))))
