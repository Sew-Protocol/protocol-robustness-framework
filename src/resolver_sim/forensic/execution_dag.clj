(ns resolver-sim.forensic.execution-dag
  "Formal execution DAG: typed nodes and edges with input/output hashes.
   Plan DAG is created before execution; Evidence DAG is populated during run.
   Callers may provide an explicit execution directory; the legacy arity writes
   to results/runs/<run-id>/execution-dag.json."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(defn sha256 [s]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes (str s) "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- node-hash [node]
  (sha256 (pr-str (dissoc node :node/hash))))

(defn- edge-hash [edge]
  (sha256 (pr-str edge)))

(defn make-plan-node
  "Create a plan DAG node (no output hashes yet)."
  [{:keys [id type input-hashes] :or {type :scenario-run}}]
  (let [base {:node/id id :node/type type :node/input-hashes input-hashes}
        h (node-hash base)]
    (assoc base :node/hash h)))

(defn make-plan-edge
  "Create a DAG edge."
  [{:keys [from to type] :or {type :dependency}}]
  (let [base {:edge/from from :edge/to to :edge/type type}
        h (edge-hash base)]
    (assoc base :edge/hash h)))

(defn record-output
  "Add output hashes to a plan node, producing an evidence node."
  [plan-node {:keys [trace-hash world-final-hash invariant-results-hash evidence-hash]
              :or {trace-hash "" world-final-hash "" invariant-results-hash "" evidence-hash ""}}]
  (let [base (assoc plan-node
                    :node/output-hashes {:trace-hash trace-hash
                                         :world-final-hash world-final-hash
                                         :invariant-results-hash invariant-results-hash
                                         :evidence-hash evidence-hash}
                    :node/status :completed
                    :node/completed-at (str (Instant/now)))
        h (node-hash base)]
    (assoc base :node/hash h)))

(defn record-invariant-check
  "Add an invariant check result to a node."
  [node {:keys [invariant-id result world-before-hash world-after-hash evidence-hash]
         :or {world-before-hash "" world-after-hash "" evidence-hash ""}}]
  (let [check {:invariant/id invariant-id
               :invariant/hash (sha256 (str invariant-id))
               :result result
               :world-before-hash world-before-hash
               :world-after-hash world-after-hash
               :evidence-hash evidence-hash}
        existing (:node/invariant-checks node [])
        updated (assoc node :node/invariant-checks (conj existing check))]
    (assoc updated :node/hash (node-hash updated))))

(defn build-dag
  "Assemble a full DAG from plan nodes and edges."
  [nodes edges]
  (let [root-str (pr-str (sort-by :node/id nodes) (sort-by :edge/from edges))]
    {:dag/schema-version "execution-dag.v1"
     :dag/generated-at (str (Instant/now))
     :dag/nodes nodes
     :dag/edges edges
     :dag/node-count (count nodes)
     :dag/edge-count (count edges)
     :dag/root-hash (sha256 root-str)}))

(defn write-dag!
  "Write DAG to disk. Returns the path written.

   The two-argument arity preserves the legacy results/runs location. Supplying
   execution-dir keeps a structured run self-contained."
  ([dag run-id]
   (write-dag! dag run-id nil))
  ([dag run-id execution-dir]
   (let [dir (or execution-dir
                 (str (io/file "results" "runs" (or run-id "unknown"))) )
         f (io/file dir "execution-dag.json")]
     (.mkdirs (io/file dir))
     (spit f (json/write-str dag {:indent true}))
     (.getPath f))))
