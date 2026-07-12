(ns resolver-sim.commands.registry-validate
  "Validate the PRF command registry against the dispatch table.
   Port of scripts/commands_validate.clj."
  (:require [resolver-sim.cli.registry :as reg]
            [clojure.string :as str]))

(defn validate
  "Validate parity between registry and dispatch table.
   Checks:
   - Every :native command has a dispatch handler
   - Every dispatch handler has a registry entry
   - All command paths are unique (no collisions)
   - Every :native command path is resolvable by dispatch/resolve-command
   Returns {:exit-code 0|1 :message str :errors [...]}."
  [{:keys [json?] :as opts}]
  (let [errors (atom [])
        commands (reg/list-commands)
        native-cmds (filter #(= :native (:jar-avail %)) commands)]
    ;; Every :native command must have a dispatch handler
    (let [get-handlers (requiring-resolve 'resolver-sim.cli.dispatch/get-command-handlers)
          handlers (get-handlers)]
      (doseq [cmd native-cmds]
        (when-not (get handlers (:id cmd))
          (swap! errors conj (str "No dispatch handler for native command: " (:id cmd)))))
      ;; Every dispatch handler must have a registry entry
      (doseq [cmd-id (keys handlers)]
        (when-not (reg/get-command cmd-id)
          (swap! errors conj (str "Registry entry missing for dispatch handler: " (name cmd-id))))))
    ;; Validate registry structure
    (let [{:keys [ok? errors reg-errors]} (reg/validate-registry)]
      (when-not ok?
        (swap! errors into reg-errors)))
    ;; Validate path uniqueness (no collisions)
    (let [{:keys [ok? errors path-errors]} (reg/validate-paths)]
      (when-not ok?
        (swap! errors into path-errors)))
    ;; Validate every native command is resolvable by path
    (let [resolve-fn (requiring-resolve 'resolver-sim.cli.dispatch/resolve-command)]
      (doseq [cmd native-cmds]
        (let [path-str (str/join " " (:path cmd))]
          (when-not (resolve-fn path-str)
            (swap! errors conj (str "Command \"" path-str "\" (" (:id cmd)
                                    ") not resolvable by dispatch"))))))
    ;; Validate bb-surface commands exist in bb.edn
    (let [bb-result (reg/validate-bb-tasks)]
      (when-not (:ok? bb-result)
        (swap! errors into (:errors bb-result))))
    (if (empty? @errors)
      (do (println "Command registry valid.")
          {:exit-code 0 :message "Command registry valid." :errors []})
      (do (doseq [e @errors] (println (str "  ✗ " e)))
          {:exit-code 1 :message (str (count @errors) " validation error(s)")
           :errors @errors}))))
