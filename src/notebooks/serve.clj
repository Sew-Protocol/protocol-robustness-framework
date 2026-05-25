(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]))

(defn- show-notebook! [path]
  (clerk/show! (str/trim (str path))))

(defn -main [& args]
  (let [port 7777]
    (println (str "Starting Clerk notebook server on http://localhost:" port "/notebooks/xtdb_overview"))
    (clerk/serve! {:watch-paths ["notebooks"]
                   :browse true
                   :port port})
    ;; Pre-evaluate all notebooks so they are reachable by URL without a file-change trigger.
    ;; show! evaluates the file and registers it; the last call sets the default landing page.
    (show-notebook! "notebooks/xtdb_overview.clj")
    (show-notebook! "notebooks/invariant_failures.clj")
    (show-notebook! "notebooks/telemetry.clj")
    (show-notebook! "notebooks/report.clj")
    (show-notebook! "notebooks/workbench_production.clj")
    (show-notebook! "notebooks/golden_artifact.clj")
    (show-notebook! "notebooks/atlas_artifact.clj")
    (show-notebook! "notebooks/dispute_artifact.clj")
    (show-notebook! "notebooks/collusion_artifact.clj")
    (show-notebook! "notebooks/game_theory_artifact.clj")
    (show-notebook! "notebooks/economic_artifact.clj")
    (show-notebook! "notebooks/hardening_artifact.clj")
    (show-notebook! "notebooks/evidence_drawer.clj")
    (show-notebook! "notebooks/evidence_explorer.clj")
    (show-notebook! "notebooks/challenge_drilldown.clj")
    ;; Block the main thread so the JVM stays alive (HTTP-kit uses daemon threads).
    @(promise)))
