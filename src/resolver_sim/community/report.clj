(ns resolver-sim.community.report
  (:require [resolver-sim.community.graph :as graph]))

(def ^:const schema-version "community-report.v0")

(defn- message-summary [msg]
  {:message/hash (:message/hash msg)
   :message/type (:message/type msg)
   :sender (:sender msg)
   :signed? (some? (:message/signature msg))
   :timestamp (:timestamp msg)})

(defn build-report
  [{:keys [task executions attestations findings messages] :as opts}]
  (let [g (or (:graph opts)
              (graph/build-task-graph-projection
               {:task task :executions executions
                :attestations attestations :findings findings
                :messages messages}))
        summary (:summary g)
        execution-ats (filter #(= :runner/execution-attested (:attestation/predicate %))
                              (vec (or attestations [])))
        reproduction-ats (filter #(= :runner/result-reproduced (:attestation/predicate %))
                                 (vec (or attestations [])))
        challenge-ats (filter #(= :runner/result-challenged (:attestation/predicate %))
                              (vec (or attestations [])))
        report-msgs (map message-summary (vec (or messages [])))]
    {:schema-version schema-version
     :report/generated-at (str (java.time.Instant/now))
     :task {:id (:task/hash task) :ref (:task/ref task)
            :type (:task/type task) :title (:title task)
            :benchmark/id (:benchmark/id task) :suite/id (:suite/id task)
            :claim-ids (:claim-ids task) :acceptance-criteria (:acceptance-criteria task)}
     :original-run (when (seq execution-ats)
                     (let [a (first execution-ats)]
                       {:runner (get-in a [:assertion :runner/id])
                        :execution-hash (get-in a [:assertion :execution-node-hash])
                        :attestation-hash (:attestation/hash a)
                        :attestation-verified (some? (:attestation/signature a))}))
     :reproduction-run (when (seq reproduction-ats)
                         (let [a (first reproduction-ats)]
                           {:runner (get-in a [:assertion :runner/id])
                            :comparison-status (get-in a [:assertion :comparison-status])
                            :original-attestation (get-in a [:assertion :original-attestation-ref])
                            :attestation-hash (:attestation/hash a)
                            :attestation-verified (some? (:attestation/signature a))}))
     :challenges (mapv (fn [a]
                         {:attestation/hash (:attestation/hash a)
                          :predicate (:attestation/predicate a)
                          :runner/id (get-in a [:assertion :runner/id])
                          :challenged-attestation-ref (get-in a [:assertion :challenged-attestation-ref])
                          :reason (get-in a [:assertion :reason])
                          :signed? (some? (:attestation/signature a))
                          :issued-at (:issued-at a)})
                       (vec (or challenge-ats [])))
     :agreement (some #(= :AGREEMENT (:message/type %)) (vec (or messages [])))
     :disagreement (some #(= :DISAGREEMENT (:message/type %)) (vec (or messages [])))
     :mailbox-messages report-msgs
     :evidence-root (when (seq executions) (:node-hash (first executions)))
     :unresolved-references (when (seq challenge-ats)
                              (mapv (fn [a] (get-in a [:assertion :challenged-attestation-ref]))
                                    (vec (or challenge-ats []))))
     :graph-summary {:node-count (:node-count summary) :edge-count (:edge-count summary)}
     :task-status (:task-status summary)}))

(defn print-report [r]
  (println)
  (println "╔══════════════════════════════════════════════╗")
  (println "║        Community Task Evidence Report        ║")
  (println "╚══════════════════════════════════════════════╝")
  (println)
  (println "Task:")
  (println (str "  ID:    " (get-in r [:task :id])))
  (println (str "  Ref:   " (get-in r [:task :ref])))
  (println (str "  Type:  " (name (get-in r [:task :type]))))
  (println (str "  Title: " (get-in r [:task :title])))
  (println (str "  Benchmark: " (get-in r [:task :benchmark/id])))
  (println (str "  Suite: " (get-in r [:task :suite/id])))
  (println)
  (let [orig (:original-run r)]
    (when orig
      (println "Original Execution:")
      (println (str "  Runner:   " (:runner orig)))
      (println (str "  Evidence: " (:execution-hash orig)))
      (println (str "  Attestation: " (:attestation-hash orig)))
      (println (str "  Signature: " (if (:attestation-verified orig) "verified" "unsigned")))))
  (println)
  (let [rep (:reproduction-run r)]
    (when rep
      (println "Reproduction:")
      (println (str "  Runner:      " (:runner rep)))
      (println (str "  Comparison:  " (name (:comparison-status rep))))
      (println (str "  Attestation: " (:attestation-hash rep)))
      (println (str "  Signature:   " (if (:attestation-verified rep) "verified" "unsigned")))))
  (println)
  (let [challenges (:challenges r)]
    (when (seq challenges)
      (println "Challenges:")
      (doseq [c challenges]
        (println (str "  " (:attestation/hash c) " — " (:reason c))))))
  (println)
  (println (str "Task Status: " (name (:task-status r))))
  (println (str "Evidence Graph: " (get-in r [:graph-summary :node-count]) " nodes, "
                (get-in r [:graph-summary :edge-count]) " edges"))
  (println (str "Mailbox Messages: " (count (:mailbox-messages r))))
  (when (seq (:unresolved-references r))
    (println "Unresolved References:")
    (doseq [ref (:unresolved-references r)]
      (println (str "  " ref))))
  (println))
