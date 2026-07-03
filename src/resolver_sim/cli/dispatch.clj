(ns resolver-sim.cli.dispatch
  "Command dispatch for the PRF CLI.
   Maps :command/id from the registry to handler functions.
   Every registered :native command must have a handler here.
   Every handler must have a registry entry (validated by commands:validate)."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [resolver-sim.cli.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Command handler registry
;; Every :command/id with :jar-availability :native must have an entry here.
;; Use resolve at runtime to avoid circular load dependencies.
;; ---------------------------------------------------------------------------

(def ^:private handler-cache (atom nil))

(defn get-command-handlers
  "Return the command handler map, building it on first call.
   Requires command namespaces lazily to break circular load deps."
  []
  (when-not @handler-cache
    (reset! handler-cache
            {:backstop              (requiring-resolve 'resolver-sim.commands.backstop/run-default)
             :backstop-fast         (requiring-resolve 'resolver-sim.commands.backstop/run-fast)
             :commands-validate     (requiring-resolve 'resolver-sim.commands.registry-validate/validate)
             :evidence-verify-chain (requiring-resolve 'resolver-sim.commands.evidence/verify-chain)
             :evidence-validate     (requiring-resolve 'resolver-sim.commands.evidence/validate)
             :evidence-coverage     (requiring-resolve 'resolver-sim.commands.evidence/coverage)
             :evidence-backstop     (requiring-resolve 'resolver-sim.commands.evidence/run-backstop)
             :validate              (requiring-resolve 'resolver-sim.commands.validate/run)
             :concepts-validate     (requiring-resolve 'resolver-sim.commands.concepts/validate)
             :benchmark-validate    (requiring-resolve 'resolver-sim.commands.benchmark/validate)
             :scenario-run          (requiring-resolve 'resolver-sim.commands.scenario/run)
             :fmt-check             (requiring-resolve 'resolver-sim.commands.validate/fmt-check)
             :lint                  (requiring-resolve 'resolver-sim.commands.validate/lint)}))
  @handler-cache)

;; ---------------------------------------------------------------------------
;; CLI option definitions
;; ---------------------------------------------------------------------------

(def cli-options
  [["-h" "--help" "Show help"]
   ["-j" "--json" "Output results as JSON"]
   [nil "--artifact-dir DIR" "Artifact directory path"
    :default "target/run"]
   [nil "--scenario ID" "Scenario ID to run"]
   [nil "--scenario-file PATH" "Scenario file path"]
   [nil "--suite NAME" "Suite name"]
   [nil "--pack NAME" "Benchmark pack name"]
   [nil "--fast" "Run fast tier only"]
   [nil "--full" "Run full tier"]
   [nil "--strict" "Strict validation mode"]
   [nil "--explain" "Explain results in detail"]
   [nil "--out DIR" "Output directory"
    :default "target/report"]])

;; ---------------------------------------------------------------------------
;; Command path resolution
;; ---------------------------------------------------------------------------

(defn- command-path
  "Extract the command path from raw args, removing options.
   Returns [path-keyword, remaining-args]."
  [args]
  (let [[first-tok & rest-args] args
        ;; Collect subcommand tokens until we hit a -- flag
        [sub-toks, opts-args] (split-with #(not (.startsWith % "--")) rest-args)
        full-path (into [first-tok] sub-toks)
        path-str (str/join " " full-path)]
    [path-str opts-args]))

(defn resolve-command
  "Turn a path like 'evidence verify-chain' into a :command/id keyword."
  [path-str]
  (case path-str
    "backstop"                 :backstop
    "commands validate"        :commands-validate
    "evidence verify-chain"    :evidence-verify-chain
    "evidence validate"        :evidence-validate
    "evidence coverage"        :evidence-coverage
    "evidence backstop"        :evidence-backstop
    "validate"                 :validate
    "concepts validate"        :concepts-validate
    "benchmark validate"       :benchmark-validate
    "scenario run"             :scenario-run
    nil))

;; ---------------------------------------------------------------------------
;; Help
;; ---------------------------------------------------------------------------

(defn print-help
  "Print available commands and their descriptions."
  []
  (println "PRF CLI — Protocol Robustness Framework")
  (println)
  (println "Usage: java -jar prf.jar <command> [options]")
  (println)
  (println "Commands:")
  (doseq [{:keys [path description]} (registry/list-commands)]
    (printf "  %-30s %s\n" (str/join " " path) description))
  (println)
  (println "Use: java -jar prf.jar <command> --help for command-specific help.")
  {:exit-code 0, :message "help"})

;; ---------------------------------------------------------------------------
;; Command execution (for backstop orchestration)
;; ---------------------------------------------------------------------------

(defn dispatch-command
  "Look up and execute a command handler by :command/id keyword.
   Returns {:exit-code N :message str}.
   Used by backstop and commands:validate to run commands without
   directly depending on the handler map at compile time."
  [cmd-id opts]
  (if-let [handler-var (get (get-command-handlers) cmd-id)]
    (try
      (let [result ((deref handler-var) opts)]
        (or (when (map? result) result)
            {:exit-code (or result 0) :message "ok"}))
      (catch Exception e
        {:exit-code 1 :message (.getMessage e)}))
    {:exit-code 2 :message (str "No handler for command: " (name cmd-id))}))

;; ---------------------------------------------------------------------------
;; Run dispatch
;; ---------------------------------------------------------------------------

(defn- run-command
  "Execute a resolved command handler. Returns exit code."
  [handler-var opts cmd-path]
  (try
    (let [result (handler-var (assoc opts
                                     :cmd/path cmd-path))]
      (or (:exit-code result) 0))
    (catch Exception e
      (println "Error executing command:" (.getMessage e))
      (when (:explain opts)
        (.printStackTrace e))
      4)))

(defn run
  "Parse args, resolve command, and execute handler.
   Returns an exit code for System/exit."
  [args]
  (let [parsed (cli/parse-opts args cli-options :in-order true)
        {:keys [options arguments summary errors]} parsed]
    (cond
      errors
      (do (doseq [e errors] (println e))
          (println)
          (println summary)
          2)

      (:help options)
      0

      (empty? arguments)
      (do (println "Usage: java -jar prf.jar <command> [options]")
          (println "Run 'java -jar prf.jar help' for available commands.")
          2)

      (= "help" (first arguments))
      (let [h (print-help)]
        (:exit-code h))

      :else
      (let [[cmd-path raw-opts] (command-path arguments)
            ;; Re-parse remaining arg tokens as options for the subcommand
            sub-parsed (cli/parse-opts raw-opts cli-options)
            merged-opts (merge options (:options sub-parsed))
            cmd-id (resolve-command cmd-path)
            handler-var (get (get-command-handlers) cmd-id)]
        (if handler-var
          (run-command handler-var merged-opts cmd-path)
          (do (println "Unknown command:" cmd-path)
              (println "Run 'java -jar prf.jar help' for available commands.")
              2))))))
