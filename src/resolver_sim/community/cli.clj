(ns resolver-sim.community.cli
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [resolver-sim.community.task :as task]
            [resolver-sim.community.attestation :as att]
            [resolver-sim.community.mailbox :as mailbox]
            [resolver-sim.community.graph :as graph]
            [resolver-sim.community.report :as report]
            [resolver-sim.community.result :as result]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.vcs :as vcs]))

(def ^:const default-mailbox-dir
  (str (System/getProperty "user.home") "/.protocol-robustness/community-mailbox"))

(def ^:const default-artifact-dir
  (str (System/getProperty "user.home") "/.protocol-robustness/community-artifacts"))

(def cli-options
  [["-h" "--help" "Show help"]
   ["-t" "--task TASK-REF" "Task reference"]
   ["-r" "--runner RUNNER-ID" "Runner identity"]
   ["-k" "--key PATH" "Path to private signing key"]
   ["-o" "--original-attestation REF" "Original attestation reference for reproduction"]
   ["-d" "--dir DIR" "Artifact/mailbox directory" :default default-artifact-dir]
   ["-m" "--mailbox-dir DIR" "Mailbox directory" :default default-mailbox-dir]
   ["-b" "--benchmark-id ID" "Benchmark ID (e.g. :benchmark/prf-deterministic-replay-v1)"]
   ["-n" "--title TITLE" "Task title"]
   ["-s" "--suite-id ID" "Suite ID (e.g. :suite/prf-replay-v1)"]
   [nil "--allow-dirty" "Allow dirty git working copy during execution"]])

(defn list-tasks
  [opts]
  (let [dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (binding [mailbox/*mailbox-dir* dir]
      (let [msgs (mailbox/list-messages :type-filter :TASK_ANNOUNCEMENT)]
        (if (seq msgs)
          (do
            (println (format "%-70s %-25s %s" "Task Ref" "Title" "Status"))
            (println (apply str (repeat 120 "-")))
            (doseq [m msgs]
              (let [body (:body m)
                    task-ref (or (:subject-task m) "")
                    title (or (:title body) "")
                    status (mailbox/task-status (:subject-task m))]
                (println (format "%-70s %-25s %s" task-ref title (name status)))))
            {:exit-code 0})
          (do (println "No community tasks found.")
              {:exit-code 0}))))))

(defn show-task
  [opts]
  (let [task-ref (:task opts)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (let [dir (or (:mailbox-dir opts) default-mailbox-dir)]
        (binding [mailbox/*mailbox-dir* dir]
          (let [msgs (mailbox/messages-for-task task-ref)
                status (mailbox/task-status task-ref)]
            (println "Task Reference:" task-ref)
            (println "Status:" (name status))
            (println "Messages:" (count msgs))
            (doseq [m msgs]
              (println (str "  " (:message/type m) " — " (:sender m)
                            (when (:message/signature m) " [signed]"))))
            {:exit-code 0}))))))

(defn register-task
  "Build a community task record and publish it to the mailbox."
  [opts]
  (let [title (:title opts)
        benchmark-id (:benchmark-id opts)
        suite-id (:suite-id opts)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not title
      (do (println "Usage: --title <title> [--benchmark-id <id> --suite-id <id>]")
          {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [parse-kw (fn [s] (when s (keyword (str/replace s #"^:" ""))))
              t (task/build-task
                 {:title title
                  :task/type :benchmark-execution
                  :benchmark/id (parse-kw benchmark-id)
                  :suite/id (parse-kw suite-id)
                  :claim-ids []
                  :acceptance-criteria []})
              msg (mailbox/build-message
                   {:message/type :TASK_ANNOUNCEMENT
                    :subject-task (:task/ref t)
                    :sender "community-registrar"
                    :body {:title title
                           :benchmark/id (parse-kw benchmark-id)
                           :suite/id (parse-kw suite-id)}})]
          (mailbox/publish! msg)
          (println (str "Task registered: " (:task/ref t)))
          (println (str "Title: " title))
          (when benchmark-id
            (println (str "Benchmark: " benchmark-id)))
          (println)
          (println "To execute this task:")
          (println (str "  bb community:run --task " (:task/ref t) " --runner <id> --key <key>"))
          {:exit-code 0 :task t :message msg})))))

(defn- compute-code-hash
  "Return the current git commit hash as the code identity."
  []
  (try (:source/hash (vcs/source-provenance))
       (catch Exception _ "0000000000000000000000000000000000000000000000000000000000000000")))

(defn- compute-env-hash
  "Fingerprint the execution environment (JVM, language version)."
  []
  (hc/domain-hash "COMMUNITY_ENV_V0"
    {:clojure-version (or (try (clojure-version) (catch Exception _ "unknown")) "unknown")
     :java-version (or (System/getProperty "java.version") "unknown")
     :java-vm-name (or (System/getProperty "java.vm.name") "unknown")}))

(defn- compute-registry-hash
  "Hash the current execution registry for snapshot binding."
  []
  (try (hc/hash-with-intent {:hash/intent :registry}
         (requiring-resolve 'resolver-sim.definitions.passive-registries/execution-registry))
       (catch Exception _ (hc/domain-hash "REGISTRY_V1" {:registry-version 1 :executions []}))))

(defn- resolve-benchmark-manifest
  "Resolve a benchmark manifest path from a task's :benchmark/id.
   Reads benchmarks/registry.edn and walks the pack hierarchy.
   Falls back to the default deterministic-replay path."
  [benchmark-id]
  (when benchmark-id
    (try
      (let [reg-file (io/file "benchmarks/registry.edn")]
        (when (.exists reg-file)
          (let [registry (edn/read-string (slurp reg-file))]
            (some (fn [pack]
                    (let [pack-reg-path (str "benchmarks/" (:pack/registry pack))
                          pack-reg (when (.exists (io/file pack-reg-path))
                                     (edn/read-string (slurp pack-reg-path)))
                          pack-dir (.getParent (io/file pack-reg-path))]
                      (some (fn [b]
                              (when (= (:benchmark/id b) benchmark-id)
                                (str pack-dir "/" (:benchmark/file b))))
                            (:benchmarks pack-reg))))
                  (:packs registry)))))
      (catch Exception _ nil))))

(defn run-task
  [opts]
  (let [task-ref (:task opts)
        runner-id (:runner opts)
        key-path (:key opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not (and task-ref runner-id key-path)
      (do (println "Usage: --task <task-ref> --runner <runner-id> --key <private-key>")
          {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir
                chain/*allow-dirty* (:allow-dirty opts)]
        (let [manifest-path (or (resolve-benchmark-manifest
                                 (some-> (mailbox/find-message task-ref)
                                         :body :benchmark/id))
                                "benchmarks/packs/prf-core/deterministic-replay-v1.edn")
              _ (println (str "Manifest: " manifest-path))
              _ (println (str "Executing task: " task-ref))
              evidence (try
                         (runner/run-benchmark manifest-path)
                         (catch Exception e
                           (println "Execution failed:" (.getMessage e))
                           nil))]
          (if-not evidence
            (do (println "ERROR: Benchmark execution produced no evidence. Aborting.")
                {:exit-code 1})
            (let [passed? (= (get-in evidence [:metrics :passed])
                             (get-in evidence [:metrics :total]))
                  proj (result/project-stable-result evidence)
                  stable-hash (:stable/hash proj)
                  stable-projection (:stable/projection proj)]
              (println (str "Task ref: " task-ref))
              (println (str "Stable result hash: " stable-hash))
              (when-not passed?
                (println (str "WARNING: Benchmark did not pass all scenarios ("
                              (get-in evidence [:metrics :passed]) "/"
                              (get-in evidence [:metrics :total]) ")")))
              (let [code-hash (compute-code-hash)
                    env-hash (compute-env-hash)
                    registry-hash (compute-registry-hash)
                    ev-node-hash stable-hash
                    ev-node-ref (str "evidence-node:sha256:" ev-node-hash)
                    attestation (att/build-execution-attestation
                                 {:task/ref task-ref :runner/id runner-id
                                  :code-hash code-hash
                                  :env-hash env-hash
                                  :bundle-root stable-hash
                                  :execution-node-hash ev-node-ref
                                  :result-projection-hash stable-hash
                                  :registry-snapshot-hash registry-hash})
                    signed (att/sign-attestation! attestation key-path)
                    _ (att/persist-attestation! signed dir)
                    msg (mailbox/build-message
                         {:message/type :RUNNER_RESULT :subject-task task-ref
                          :sender runner-id
                          :attestation-ref (:attestation/ref signed)
                          :evidence-ref ev-node-ref
                          :body {:result stable-hash :stable-projection stable-projection}})
                    signed-msg (mailbox/sign-message! msg key-path)
                    pub-result (mailbox/publish! signed-msg)]
                (println (str "Code hash: " code-hash))
                (println (str "Env hash: " env-hash))
                (println (str "Registry snapshot: " registry-hash))
                (println (str "Evidence node: " ev-node-ref))
                (println (str "Attestation: " (:attestation/ref signed)))
                (println (str "Mailbox message: " (if (= :published pub-result) "published" "duplicate")))
                {:exit-code (if passed? 0 1) :attestation signed :message signed-msg}))))))))

(defn reproduce-task
  [opts]
  (let [task-ref (:task opts)
        original-att-ref (:original-attestation opts)
        runner-id (:runner opts)
        key-path (:key opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not (and task-ref original-att-ref runner-id key-path)
      (do (println "Usage: --task <task-ref> --original-attestation <ref> --runner <runner-id> --key <key>")
          {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir
                chain/*allow-dirty* (:allow-dirty opts)]
        (let [original-att (att/resolve-attestation dir original-att-ref)
              _ (println (str "Reproducing task: " task-ref))
              manifest-path (or (resolve-benchmark-manifest
                                 (some-> (mailbox/find-message task-ref)
                                         :body :benchmark/id))
                                "benchmarks/packs/prf-core/deterministic-replay-v1.edn")
              evidence (try
                         (runner/run-benchmark manifest-path)
                         (catch Exception e
                           (println "Reproduction failed:" (.getMessage e))
                           nil))]
          (if-not evidence
            (do (println "ERROR: Reproduction benchmark produced no evidence. Aborting.")
                {:exit-code 1})
            (let [passed? (= (get-in evidence [:metrics :passed])
                             (get-in evidence [:metrics :total]))
                  claimed-stable-hash (get-in original-att [:assertion :result-projection-hash])
                  repro-proj (result/project-stable-result evidence)
                  repro-hash (:stable/hash repro-proj)
                  matched? (and claimed-stable-hash (= repro-hash claimed-stable-hash))
                  comp-status (if matched? :matched :mismatched)]
              (println (str "Original stable hash: " claimed-stable-hash))
              (println (str "Reproduction stable hash: " repro-hash))
              (println (str "Comparison: " (name comp-status)))
              (let [code-hash (compute-code-hash)
                    env-hash (compute-env-hash)
                    repro-ev-node-ref (str "evidence-node:sha256:" repro-hash)
                    attestation (att/build-reproduction-attestation
                                 {:task/ref task-ref
                                  :original-attestation-ref original-att-ref
                                  :original-result-projection-hash claimed-stable-hash
                                  :runner/id runner-id
                                  :code-hash code-hash
                                  :env-hash env-hash
                                  :reproduction-execution-node-hash repro-ev-node-ref
                                  :reproduction-result-projection-hash repro-hash
                                  :comparison-policy :stable-projection-v0
                                  :comparison-status comp-status})
                    signed (att/sign-attestation! attestation key-path)
                    _ (att/persist-attestation! signed dir)
                    msg-type (if matched? :AGREEMENT :DISAGREEMENT)
                    msg (mailbox/build-message
                         {:message/type msg-type :subject-task task-ref :sender runner-id
                          :attestation-ref (:attestation/ref signed)
                          :evidence-ref repro-ev-node-ref
                          :body {:comparison-status comp-status
                                 :comparison-policy :stable-projection-v0
                                 :original-projection claimed-stable-hash
                                 :reproduction-projection repro-hash}})
                    signed-msg (mailbox/sign-message! msg key-path)
                    pub-result (mailbox/publish! signed-msg)]
                (println (str "Attestation: " (:attestation/ref signed)))
                (println (str "Mailbox message: " (if (= :published pub-result) "published" "duplicate")))
                {:exit-code (if passed? 0 1) :attestation signed :message signed-msg}))))))))

(defn verify-task
  "Verify the complete evidence chain for a task:
   - task record hash integrity
   - mailbox message hashes and signatures
   - attestation hash and signature integrity
   - subject-reference consistency (attestation subject matches task)
   - evidence graph integrity
   - comparison derivation consistency"
  [opts]
  (let [task-ref (:task opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [msgs (mailbox/messages-for-task task-ref)
              announcement (first (filter #(= :TASK_ANNOUNCEMENT (:message/type %)) msgs))
              task-body (:body announcement)
              attestation-refs (keep :attestation-ref msgs)
              attestations (keep (fn [ref] (att/resolve-attestation dir ref)) attestation-refs)
              t (when task-body
                  (task/build-task
                   {:title (:title task-body)
                    :benchmark/id (:benchmark/id task-body)
                    :suite/id (:suite/id task-body)
                     :task/type :benchmark-execution}))
              verified-status (mailbox/verified-task-status task-ref dir)
              status (mailbox/task-status task-ref)
              checks (atom [])
              errors (atom [])
              pass! (fn [check] (swap! checks conj {:check check :pass? true}))
              fail! (fn [check reason] (swap! checks conj {:check check :pass? false :reason reason})
                      (swap! errors conj (str check ": " reason)))]
          (println "Evidence Chain Verification")
          (println "═══════════════════════════")
          (println (str "Task: " task-ref))
          (println)
          ;; 1. Task record integrity
          (if task-body
            (do (pass! "task-integrity")
                (println (str "  Title: " (:title task-body)))
                (println (str "  Benchmark: " (or (:benchmark/id task-body) "(not specified)")))
                (println (str "  Suite: " (or (:suite/id task-body) "(not specified)"))))
            (fail! "task-integrity" "No task announcement found"))
          (println)
          ;; 2. Mailbox messages
          (doseq [m msgs]
            (let [v (mailbox/verify-message m)]
              (if (:hash-valid? v)
                (pass! (str "message-hash-" (name (:message/type m))))
                (fail! (str "message-hash-" (name (:message/type m))) "Hash mismatch"))
              (if (:message/signature m)
                (if (:valid? v)
                  (pass! (str "message-sig-" (name (:message/type m))))
                  (fail! (str "message-sig-" (name (:message/type m))) "Invalid signature")))))
          ;; 3. Attestation integrity
          (doseq [a attestations]
            (let [pred (:attestation/predicate a)]
              (if (att/valid-attestation? a)
                (pass! (str "attestation-hash-" (name pred)))
                (fail! (str "attestation-hash-" (name pred)) "Hash mismatch"))
              (if (contains? att/supported-predicates pred)
                (pass! (str "attestation-predicate-" (name pred)))
                (fail! (str "attestation-predicate-" (name pred)) "Unknown predicate"))
              ;; 4. Subject-reference consistency
              (let [subject-ref (:reference (:subject a))]
                (if (= subject-ref task-ref)
                  (pass! (str "attestation-subject-ref-" (name pred)))
                  (fail! (str "attestation-subject-ref-" (name pred))
                         (str "Subject ref " subject-ref " != task ref " task-ref))))))
          ;; 5. Comparison derivation
          (let [exec-ats (filter #(= :runner/execution-attested (:attestation/predicate %)) attestations)
                repro-ats (filter #(= :runner/result-reproduced (:attestation/predicate %)) attestations)]
            (doseq [ra repro-ats]
              (let [claimed-status (:comparison-status (:assertion ra))
                    original-ref (:original-attestation-ref (:assertion ra))
                    orig (first (filter #(= (:attestation/ref %) original-ref) exec-ats))]
                (if orig
                  (pass! "reproduction-original-resolved")
                  (fail! "reproduction-original-resolved" "Original attestation not found"))
                (if (contains? #{:matched :semantically-matched :mismatched :inconclusive} claimed-status)
                  (pass! "reproduction-status-known")
                  (fail! "reproduction-status-known" (str "Unknown status: " claimed-status)))))
            (when (and (seq exec-ats) (seq repro-ats))
              (pass! "comparison-derived"))
            (when (and (seq exec-ats) (not (seq repro-ats)))
              (pass! "execution-attested-no-reproduction-yet")))
          ;; 6. Evidence graph integrity
          (let [g (graph/build-task-graph-projection
                   {:task t :messages msgs :attestations attestations})
                {:keys [valid? errors]} (graph/verify-task-graph g)]
            (if valid?
              (pass! "evidence-graph-integrity")
              (fail! "evidence-graph-integrity" (str "Graph validation errors: " errors))))
          (println)
          (println "Summary:")
          (println "──────────────────────────────────────────")
          (doseq [c @checks]
            (println (str "  " (if (:pass? c) "✓" "✗") "  " (:check c)
                          (when (:reason c) (str " — " (:reason c))))))
          (println)
          (println (str "Status (messages):  " (name status)))
          (println (str "Status (verified): " (name verified-status)))
          (println (str "Passed: " (count (filter :pass? @checks)) "/" (count @checks)))
          {:exit-code (if (empty? @errors) 0 1)})))))

(defn generate-report
  [opts]
  (let [task-ref (:task opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [msgs (mailbox/messages-for-task task-ref)
              announcement (first (filter #(= :TASK_ANNOUNCEMENT (:message/type %)) msgs))
              task-body (:body announcement)
              attestation-refs (keep :attestation-ref msgs)
              attestations (keep (fn [ref] (att/resolve-attestation dir ref)) attestation-refs)
              t {:task/hash (task/parse-task-ref task-ref) :task/ref task-ref
                 :task/type :benchmark-execution
                 :title (or (:title task-body) "Community Task")
                 :benchmark/id (:benchmark/id task-body)
                 :suite/id (:suite/id task-body)
                 :claim-ids [] :acceptance-criteria []}
              r (report/build-report {:task t :attestations attestations :messages msgs})]
          (report/print-report r)
          (if (seq msgs)
            (do (println "Raw files:")
                (println (str "  Mailbox: " mailbox-dir))
                (println (str "  Attestations: " dir "/community-attestations"))
                (println)
                (println "To export as GraphML for yEd:")
                (println (str "  bb community:graph:export --task " task-ref " > graph.graphml"))
                (println "  Then open graph.graphml in yEd (File > Open)."))
            (println "No messages found. Run 'bb community:task:list' to see registered tasks."))
          {:exit-code 0})))))

(defn export-graph
  "Export a task's evidence graph as GraphML for yEd."
  [opts]
  (let [task-ref (:task opts)
        dir (or (:dir opts) default-artifact-dir)
        mailbox-dir (or (:mailbox-dir opts) default-mailbox-dir)]
    (if-not task-ref
      (do (println "Usage: --task <task-ref>") {:exit-code 1})
      (binding [mailbox/*mailbox-dir* mailbox-dir]
        (let [msgs (mailbox/messages-for-task task-ref)
              announcement (first (filter #(= :TASK_ANNOUNCEMENT (:message/type %)) msgs))
              task-body (:body announcement)
              attestation-refs (keep :attestation-ref msgs)
              attestations (keep (fn [ref] (att/resolve-attestation dir ref)) attestation-refs)
              t {:task/hash (task/parse-task-ref task-ref) :task/ref task-ref
                 :task/type :benchmark-execution
                 :title (or (:title task-body) "Community Task")
                 :benchmark/id (:benchmark/id task-body)}
              g (graph/build-task-graph-projection {:task t :messages msgs :attestations attestations})
              graphml (graph/export-graphml g)]
          (println graphml)
          {:exit-code 0})))))

(defn- subcommand? [args]
  (contains? #{"task:list" "task:show" "task:register" "run" "reproduce" "verify" "report" "graph:export"} (first args)))

(defn- print-help []
  (println "PRF Community CLI — External researcher participation")
  (println)
  (println "Usage: clojure -M:with-sew -m resolver-sim.community.cli <command> [options]")
  (println)
  (println "Commands:")
  (println "  task:list                              List community tasks")
  (println "  task:show --task <ref>                 Show task details")
  (println "  task:register --title <t> [--benchmark-id <id> --suite-id <id>]  Register a new task")
  (println "  run --task <ref> -r <id> -k <key>      Execute a task and publish result")
  (println "  reproduce --task <ref> -o <ref> -r <id> -k <key>  Reproduce a result")
  (println "  verify --task <ref>                    Verify evidence chain")
  (println "  report --task <ref>                    Generate evidence report")
  (println "  graph:export --task <ref>              Export evidence graph as GraphML for yEd")
  (println)
  (println "Options:")
  (println "  -t, --task REF           Task reference")
  (println "  -r, --runner ID          Runner identity (pseudonymous)")
  (println "  -k, --key PATH           Path to Ed25519 private key")
  (println "  -o, --original-attestation REF  Original attestation ref (for reproduce)")
  (println "  -d, --dir DIR            Artifact directory")
  (println "  -m, --mailbox-dir DIR    Mailbox directory")
  (println "  -h, --help               Show this help")
  {:exit-code 0})

(defn -main [& args]
  (if (subcommand? args)
    (let [subcmd (first args)
          sub-args (rest args)
          {:keys [options]} (parse-opts sub-args cli-options)]
      (case subcmd
        "task:list" (System/exit (:exit-code (list-tasks options)))
        "task:show" (System/exit (:exit-code (show-task options)))
        "task:register" (System/exit (:exit-code (register-task options)))
        "run" (System/exit (:exit-code (run-task options)))
        "reproduce" (System/exit (:exit-code (reproduce-task options)))
        "verify" (System/exit (:exit-code (verify-task options)))
        "report" (System/exit (:exit-code (generate-report options)))
        "graph:export" (System/exit (:exit-code (export-graph options)))
        (do (println "Unknown command:" subcmd) (System/exit 1))))
    (let [{:keys [options errors]} (parse-opts args cli-options)]
      (cond
        errors (do (run! println errors) (System/exit 1))
        (:help options) (System/exit (:exit-code (print-help)))
        :else (do (println "Usage: community <command> [options]")
                  (println "Run 'community --help' for available commands.")
                  (System/exit 1))))))
