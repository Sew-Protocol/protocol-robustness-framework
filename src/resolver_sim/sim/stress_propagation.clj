(ns resolver-sim.sim.stress-propagation
"Causal propagation engine for protocol failures.")

(defrecord StressNode [id cause action])

(defn record-failure
  "Create a new StressNode for a failed action."
  [action error]
  (->StressNode (keyword (str action "-" (name error))) error action))

(defn build-stress-dag
  "Construct a causal graph (DAG) from a sequence of StressNodes in a trace."
  [trace]
  (let [failures (filter :stress-node (map :stress-node trace))]
    (reduce (fn [dag node]
              (assoc dag (:id node)
                     {:cause (:cause node)
                      :action (:action node)
                      :predecessors (keys dag)}))
            {}
            failures)))