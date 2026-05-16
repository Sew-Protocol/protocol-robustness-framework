(ns resolver-sim.core
  "CLI entry point. Wires core.cli (option parsing) and core.phases (runners).
   Contains only -main; all logic lives in the sub-namespaces."
  (:require [clojure.string            :as str]
            [resolver-sim.core.cli    :as cli]
            [resolver-sim.core.phases :as phases]
            [resolver-sim.io.params   :as params]
            [resolver-sim.server.grpc :as grpc])
  (:gen-class))

;; Registry of protocol-specific invariant runners. Add new protocols here.
;; Each value is a fully-qualified symbol for the run-and-report fn, resolved
;; lazily so core.clj carries no compile-time dep on any protocol namespace.
(def ^:private protocol-runners
  {"sew-v1" 'resolver-sim.protocols.sew.invariant-runner/run-and-report})

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (cli/validate-args args)]
    (if exit-message
      (do (println exit-message)
          (System/exit (if ok? 0 1)))

      (if (:invariants options)
        (let [protocol-id (:protocol options "sew-v1")
              runner-sym  (get protocol-runners protocol-id)]
          (if runner-sym
            (System/exit ((requiring-resolve runner-sym)))
            (do (println (str "Unknown protocol: " protocol-id
                              ". Available: " (str/join ", " (keys protocol-runners))))
                (System/exit 1))))

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
