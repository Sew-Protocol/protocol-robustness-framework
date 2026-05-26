(ns resolver-sim.notebooks.common
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [resolver-sim.logging :as log]))

(defn safe-slurp [path]
  (try
    (let [f (io/file path)]
      (when (.exists f) (slurp f)))
    (catch Exception e
      (log/warn! "notebook/safe-slurp-failed" {:path path :error (.getMessage e)})
      (println "WARN: could not read" path "-" (.getMessage e))
      nil)))

(defn read-json [path]
  (when-let [s (safe-slurp path)]
    (try
      (json/read-str s {:key-fn keyword})
      (catch Exception e
        (log/warn! "notebook/read-json-failed" {:path path :error (.getMessage e)})
        (println "WARN: JSON parse error in" path "-" (.getMessage e))
        nil))))

(defn read-edn [path]
  (when-let [s (safe-slurp path)]
    (try
      (edn/read-string s)
      (catch Exception e
        (log/warn! "notebook/read-edn-failed" {:path path :error (.getMessage e)})
        (println "WARN: EDN parse error in" path "-" (.getMessage e))
        nil))))

(defn safe-render
  "Wraps a panel render fn in try/catch. Returns a hiccup error callout on failure."
  [label f]
  (try
    (f)
    (catch Exception e
      [:div {:style {:background "#fef2f2" :border "1px solid #dc2626"
                     :borderRadius "4px" :padding "12px" :margin "8px 0"}}
       [:strong (str "⚠ " label " render error: ")]
       [:code (.getMessage e)]])))