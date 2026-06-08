(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.logging :as log]))

(defn- show-notebook! [path]
  (clerk/show! (str/trim (str path))))

(defn -main [& args]
  (let [port 7777]
    (log/info! "notebook/server-starting" {:port port})
    (println (str "Starting Clerk notebook server on http://localhost:" port "/notebooks/xtdb_overview"))
    (clerk/serve! {:watch-paths ["src" "notebooks" "data"]
                   :browse true
                   :port port})
    ;; Pre-evaluate all notebooks so they are reachable by URL without a file-change trigger.
    ;; show! evaluates the file and registers it; the last call sets the default landing page.
    (show-notebook! "notebooks/xtdb_overview.clj")
    (show-notebook! "notebooks/invariant_failures.clj")
    (show-notebook! "notebooks/telemetry.clj")
    (show-notebook! "notebooks/report.clj")
    (show-notebook! "notebooks/workbench_v2.clj")
    (show-notebook! "notebooks/yield_scenarios_workbench.clj")
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
    (show-notebook! "notebooks/protocol_provenance.clj")
    ;; Index is shown last — it becomes the default landing page.
     (show-notebook! "notebooks/security_validation.clj")

     (show-notebook! "notebooks/yield_shortfall_analysis.clj")
     (show-notebook! "src/resolver_sim/notebooks/yield_provider_demo.clj")
     (show-notebook! "src/resolver_sim/notebooks/subgame_counterfactual_workbench.clj")

     ;; Index is shown last — it becomes the default landing page.
     (show-notebook! "notebooks/index.clj")))

