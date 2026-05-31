with open("src/resolver_sim/protocols/sew/projection.clj", "r") as f:
    content = f.read()

old_func = """(defn trace-end-projection
  "Produce a stable, minimal projection of a Sew replay result for use by
   mechanism-property and equilibrium-concept validators.

   Returns a map with keys:
     :protocol                — the protocol implementation instance
     :agents                  — the agents involved in the scenario
     :protocol-params         — the protocol parameters
     :scenario-id             — the scenario identifier
     :terminal-world          — world state at end of trace
     :metrics                 — accumulated metrics
     :trace-summary           — high-level trace statistics
     :money-movement-summary  — workflow outcomes, pending lifecycle, token deltas
     :payoff-ledger-summary   — per-actor payoff ledger
     :stake-flow-summary      — per-resolver stake flow
     :decisions               — strategic decision nodes in trace
     :raw-trace               — the full trace vector

   Returns nil when result has no trace (e.g. :outcome :invalid with 0 events)."
  [protocol result]
  (println (format "Trace projection result keys: %s" (keys result)))
  (when-let [{:keys [trace world metrics agents halt-reason]} (build-trace-context result)]"""

new_func = """(defn trace-end-projection
  "Produce a stable, minimal projection of a Sew replay result for use by
   mechanism-property and equilibrium-concept validators.

   Returns a map with keys:
     :protocol                — the protocol implementation instance
     :agents                  — the agents involved in the scenario
     :protocol-params         — the protocol parameters
     :scenario-id             — the scenario identifier
     :terminal-world          — world state at end of trace
     :metrics                 — accumulated metrics
     :trace-summary           — high-level trace statistics
     :money-movement-summary  — workflow outcomes, pending lifecycle, token deltas
     :payoff-ledger-summary   — per-actor payoff ledger
     :stake-flow-summary      — per-resolver stake flow
     :decisions               — strategic decision nodes in trace
     :raw-trace               — the full trace vector

   Returns nil when result has no trace (e.g. :outcome :invalid with 0 events)."
  ([result] (trace-end-projection (:protocol result) result))
  ([protocol result]
   (when-let [{:keys [trace world metrics agents halt-reason]} (build-trace-context result)]"""

new_content = content.replace(old_func, new_func)
with open("src/resolver_sim/protocols/sew/projection.clj", "w") as f:
    f.write(new_content)
