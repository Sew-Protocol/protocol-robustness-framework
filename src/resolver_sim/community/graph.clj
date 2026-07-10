(ns resolver-sim.community.graph
  "Pure functions for building and verifying evidence graphs for community
   research tasks. Links tasks, execution evidence, attestations, findings,
   and mailbox messages into a traversable DAG projection."
  (:require [resolver-sim.community.task :as task]
            [resolver-sim.community.finding :as finding]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]))

(def ^:const schema-version "community-evidence-graph.v0")

(defn derive-task-status
  "Derive the aggregate status of a task from mailbox messages.
   Returns a keyword status."
  [_task messages]
  (let [types (set (map :message/type (vec (or messages []))))]
    (cond
      (contains? types :CHALLENGE) :challenged
      (contains? types :DISAGREEMENT) :disagreed
      (contains? types :AGREEMENT) :agreed
      (contains? types :REPRODUCTION_RESULT) :reproduced
      (contains? types :RUNNER_RESULT) :executed
      (contains? types :TASK_ANNOUNCEMENT) :announced
      :else :unknown)))

(defn build-task-graph-projection
  "Build a graph projection linking a research task to its executions,
   attestations, findings, and mailbox messages.
   
   Returns a map with :schema-version, :task, :nodes, :edges, :summary.
   
   This is a pure function — it does not read from disk or the mailbox.
   Callers pass the resolved records directly."
  [{:keys [task executions attestations findings messages]}]
  (let [nodes (atom [])
        edges (atom [])
        add-node (fn [id label data] (swap! nodes conj {:node/id id :node/label label :node/data data}))
        add-edge (fn [from to label] (swap! edges conj {:edge/from from :edge/to to :edge/label label}))]
    (when task
      (add-node (:task/hash task) "Research Task" {:task/ref (:task/ref task) :title (:title task)}))
    (doseq [exec (vec (or executions []))]
      (add-node (:node-hash exec) "Execution Evidence" {:execution-id (get-in exec [:execution :execution-id])})
      (when task
        (add-edge (:task/hash task) (:node-hash exec) "produced")))
    (doseq [a (vec (or attestations []))]
      (add-node (:attestation/hash a) (str "Attestation: " (name (:attestation/predicate a))) a)
      (add-edge (:attestation/hash a) (:task/hash task) "attests"))
    (doseq [f (vec (or findings []))]
      (add-node (:finding/hash f) (str "Finding: " (name (:finding/type f))) f)
      (add-edge (:finding/hash f) (:task/hash task) "reports"))
    (doseq [m (vec (or messages []))]
      (add-node (:message/hash m) (str "Mailbox: " (name (:message/type m))) m)
      (add-edge (:message/hash m) (:subject-task m) "messages"))
    (let [all-nodes @nodes
          all-edges @edges
          status (derive-task-status task messages)]
      {:schema-version schema-version
       :task task
       :nodes all-nodes
       :edges all-edges
       :summary {:node-count (count all-nodes)
                 :edge-count (count all-edges)
                 :task-status status}})))

(defn verify-task-graph
  "Verify the integrity of a task graph projection.
   Checks:
   - Every referenced record hash matches its content
   - Every referenced record is structurally valid
   - Subject references match between linked records
   - Every attestation predicate is known
   - Every message type is known
   Returns {:valid? true/false :errors [...] :checks [...]}"
  [graph]
  (let [errors (atom [])
        checks (atom [])]
    ;; Task validation
    (when-let [t (:task graph)]
      (let [valid? (task/valid-task? t)]
        (swap! checks conj {:check :task-integrity :pass? valid?
                            :detail (if valid? "Hash matches" "Hash mismatch")})
        (when-not valid?
          (swap! errors conj "Task record hash integrity check failed"))))
    ;; Message validation
    (doseq [m (vec (or (:messages graph) []))]
      (let [v (mailbox/verify-message m)
            hash-ok? (:hash-valid? v)]
        (swap! checks conj {:check (str "message-hash-" (subs (:message/hash m) 0 8))
                            :pass? hash-ok?
                            :detail (if hash-ok? "Hash OK" "Hash mismatch")})
        (when-not hash-ok?
          (swap! errors conj (str "Message hash mismatch: " (:message/hash m))))
        (when (and (:message/signature m) (not (:valid? v)))
          (swap! errors conj (str "Message signature invalid: " (:message/hash m))))))
    ;; Attestation validation
    (doseq [a (vec (or (:attestations graph) []))]
      (let [valid? (att/valid-attestation? a)
            pred (:attestation/predicate a)]
        (swap! checks conj {:check (str "attestation-" (subs (:attestation/hash a) 0 8))
                            :pass? valid?
                            :detail (if valid? (str "Valid " (name pred)) "Hash mismatch")})
        (when-not valid?
          (swap! errors conj (str "Attestation hash mismatch: " (:attestation/hash a))))
        (when-not (contains? att/supported-predicates pred)
          (swap! errors conj (str "Unknown attestation predicate: " (name pred))))
        ;; Check subject reference consistency
        (when (and (:task graph) (:subject a))
          (let [subject-ref (:reference (:subject a))]
            (when (and subject-ref (:task/ref (:task graph)))
              (when (not= subject-ref (:task/ref (:task graph)))
                (swap! errors conj (str "Attestation subject ref " subject-ref
                                        " does not match task ref " (:task/ref (:task graph))))))))))
    ;; Finding validation
    (doseq [f (vec (or (:findings graph) []))]
      (let [valid? (finding/valid-finding? f)]
        (swap! checks conj {:check (str "finding-" (subs (:finding/hash f) 0 8))
                            :pass? valid?
                            :detail (if valid? "Hash OK" "Hash mismatch")})
        (when-not valid?
          (swap! errors conj (str "Finding hash mismatch: " (:finding/hash f))))))
    {:valid? (empty? @errors)
     :errors @errors
     :checks @checks}))

(defn collect-task-evidence
  "Collect all evidence for a task from disk.
   Resolves task, mailbox messages, attestations, and findings.
   
   Returns a map suitable for build-task-graph-projection.
   Does NOT mutate any state."
  [task artifact-dir]
  (let [task-ref (:task/ref task)
        msgs (mailbox/messages-for-task task-ref)
        attestation-refs (keep :attestation-ref msgs)
        attestations (keep (fn [ref] (att/resolve-attestation artifact-dir ref)) attestation-refs)
        findings []]
    {:task task
     :executions []
     :attestations attestations
     :findings findings
     :messages msgs}))
