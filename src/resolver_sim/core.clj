(ns resolver-sim.core
  "CLI entry point. Wires core.cli (option parsing) and core.phases (runners).
   Contains only -main; all logic lives in the sub-namespaces."
  (:require [resolver-sim.core.cli    :as cli]
            [resolver-sim.core.phases :as phases]
            [resolver-sim.io.params   :as params]
            [resolver-sim.server.grpc :as grpc])
  (:gen-class))

;; SEW invariant runner resolved lazily so core.clj carries no compile-time
;; dependency on the SEW protocol layer. Swap this sym to run a different
;; protocol's invariant suite from the CLI.
(def ^:private invariant-runner-sym
  'resolver-sim.protocols.sew.invariant-runner/run-and-report)

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (cli/validate-args args)]
    (if exit-message
      (do (println exit-message)
          (System/exit (if ok? 0 1)))

      (if (:invariants options)
        (System/exit ((requiring-resolve invariant-runner-sym)))

        (if (:serve options)
          (try
            (let [port (:port options)]
              (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable grpc/stop!))
              (grpc/start! port)
              (println "[grpc] Press Ctrl+C to stop.")
              (grpc/await-termination)
              (System/exit 0))
            (catch Throwable e
              (println "Error in server:" (.getMessage e))
              (.printStackTrace e)
              (System/exit 1)))

          (try
            (println "Loading params from:" (:params options))
            (let [p         (params/validate-and-merge (:params options))
                  output    (:output options)
                  phase-key (some #(when (get options %) %) (keys phases/phase-runners))
                  [label run-fn] (get phases/phase-runners phase-key)]
              (cond
                (:ring-spec p) (phases/run-ring-simulation p output)
                phase-key      (do (when label (println label))
                                   (run-fn p output))
                :else          (phases/run-simulation p output))
              (System/exit 0))

            (catch Exception e
              (println "Error:" (.getMessage e))
              (.printStackTrace e)
              (System/exit 1))))))))
