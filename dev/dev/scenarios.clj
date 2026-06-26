(ns dev.scenarios
  "REPL dev helpers for running single in-process registry scenarios.
   Routes through resolver-sim.io.scenario-runner (public path).
   For file-backed scenarios use io.scenario-runner/run-scenario-file directly."
  (:require [resolver-sim.io.scenario-runner :as sr]))

(defn- find-scenario
  "Look up a scenario map by keyword id (e.g. :S18) from the S01–S107 registry."
  [scenario-id]
  (let [scenarios (requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/all-scenarios)]
    (when-let [entry (first (filter (fn [[name _]]
                                     (when (string? name)
                                       (let [id (re-find #"S\d+" name)]
                                         (and id (= (keyword id) scenario-id)))))
                                   @scenarios))]
      (let [[_ data] entry]
        (if (vector? data)
          {:pair data}
          data)))))

(defn- sew-replay-fn []
  (requiring-resolve 'resolver-sim.protocols.sew/replay-with-sew-protocol))

(defn run-scenario
  "Run a single in-process registry scenario by keyword id (e.g. :S18).
   Routes through io.scenario-runner/run-registry-scenario."
  [scenario-id]
  (let [scenario (or (find-scenario scenario-id)
                     (throw (ex-info "Unknown scenario" {:scenario-id scenario-id})))
        result   (sr/run-registry-scenario scenario (sew-replay-fn))]
    (tap> {:type :scenario/result
           :scenario-id scenario-id
           :result result})
    result))

(defn run-scenario-summary
  [scenario-id]
  (let [result (run-scenario scenario-id)
        summary (select-keys result [:outcome :halt-reason :metrics])]
    (tap> summary)
    summary))

(defn run-yield-shortfall-demo
  []
  (run-scenario-summary :S107))

(defn run-baseline
  []
  (mapv run-scenario-summary
        [:S01 :S02 :S03 :S04 :S05 :S06 :S07 :S08 :S09]))
