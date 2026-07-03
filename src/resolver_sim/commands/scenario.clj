(ns resolver-sim.commands.scenario
  "Scenario run commands.
   Port of bb scenario:run / run:scenario."
  (:require [clojure.java.io :as io]))

(defn run
  "Run a single scenario from the registry."
  [{:keys [scenario scenario-file suite out json?] :as opts}]
  (println "Scenario run...")
  (println (str "  scenario: " (or scenario "<none>")))
  (println (str "  file: " (or scenario-file "<none>")))
  (println (str "  suite: " (or suite "<none>")))
  (println "  (integration pending — calls scenario runner)")
  {:exit-code 0 :message "Scenario run completed"})
