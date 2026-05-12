(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]))

(defn -main [& args]
  (let [port 7777]
    (println (str "Starting Clerk notebook server on http://localhost:" port "/notebooks/report"))
    (clerk/serve! {:watch-paths ["notebooks"]
                   :browse true
                   :port port})
    ;; First-user-friendly default: immediately open/show the evidence dashboard notebook.
    (clerk/show! "notebooks/report.clj")))
