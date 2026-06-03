(ns resolver-sim.protocols.registry
  "Central protocol registry for framework entrypoints.

   NOTE: This namespace is a transition point while decoupling framework code
   from concrete protocols.")

(def ^:private protocol-symbol-registry
  {"sew-v1"   'resolver-sim.protocols.sew/protocol
   "yield-v1" 'resolver-sim.protocols.yield/protocol
   "dummy"    'resolver-sim.protocols.dummy/protocol})

(def ^:private invariant-runners
  {"sew-v1" 'resolver-sim.io.scenario-runner/run-registry-suite-and-report})

(defn- resolve-var-value
  [sym]
  (when sym
    (require (symbol (namespace sym)))
    (when-let [v (resolve sym)]
      @v)))

(def default-protocol-id
  "sew-v1")

(defn known-protocol-ids []
  (keys protocol-symbol-registry))

(defn get-protocol [protocol-id]
  (resolve-var-value (get protocol-symbol-registry protocol-id)))

(defn get-invariant-runner [protocol-id]
  (get invariant-runners protocol-id))
