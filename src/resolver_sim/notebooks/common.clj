(ns resolver-sim.notebooks.common
  "Backward-compatibility wrapper. New code should use resolver-sim.manifest.common."
  (:require [resolver-sim.manifest.common :as mc]))

(defn safe-slurp [& args] (apply mc/safe-slurp args))
(defn read-json [& args] (apply mc/read-json args))
(defn read-edn [& args] (apply mc/read-edn args))

(defn safe-render
  "Wraps a panel render fn in try/catch. Returns a hiccup error callout on failure.
   Notebook-only helper — not in manifest.common."
  [label f]
  (try
    (f)
    (catch Exception e
      [:div {:style {:background "#fef2f2" :border "1px solid #dc2626"
                     :borderRadius "4px" :padding "12px" :margin "8px 0"}}
       [:strong (str "⚠ " label " render error: ")]
       [:code (.getMessage e)]])))
