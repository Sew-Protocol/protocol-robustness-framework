(ns scripts.export-s62-traces
  (:require [resolver-sim.io.scenario-export :as export]
            [resolver-sim.protocols.sew.invariant-scenarios.extended :as extended]))

(def scenarios
  [extended/s62-cross-token-isolation-under-dispute-load
   extended/s62-cross-token-fee-on-transfer-under-dispute-load
   extended/s62-cross-token-parallel-appeal-depths-under-dispute-load
   extended/s62-resolver-capacity-concurrent-dispute-load])

(defn -main [& _]
  (doseq [s scenarios]
    (let [{:keys [trace-path scenario-path scenario-id]}
          (export/export-scenario-files! s)]
      (println "Exported" scenario-id "->" trace-path "," scenario-path))))
