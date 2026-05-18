(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]))

(defn -main [& args]
  (let [port 7777]
    (println (str "Starting Clerk notebook server on http://localhost:" port "/notebooks/report"))
    (clerk/serve! {:watch-paths ["notebooks"]
                   :browse true
                   :port port})
    ;; Pre-evaluate all notebooks so they are reachable by URL without a file-change trigger.
    ;; show! evaluates the file and registers it; the last call sets the default landing page.
    (clerk/show! "notebooks/telemetry.clj")
    (clerk/show! "notebooks/report.clj")
    ;; Block the main thread so the JVM stays alive (HTTP-kit uses daemon threads).
    @(promise)))
