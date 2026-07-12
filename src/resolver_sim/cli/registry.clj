(ns resolver-sim.cli.registry
  "Load and query the PRF command registry.
    Registry is stored as a classpath resource at prf/commands/registry.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def registry-resource "prf/commands/registry.edn")

(defonce ^:private registry-cache
  (atom nil))

(defn load-registry
  "Load the command registry from classpath resource.
   Returns the registry map, caching it for subsequent calls."
  []
  (or @registry-cache
      (let [url (io/resource registry-resource)]
        (if url
          (let [reg (edn/read-string (slurp url))]
            (reset! registry-cache reg)
            reg)
          (throw (RuntimeException.
                  (str "Command registry not found on classpath: " registry-resource)))))))

(defonce ^:private path-map-cache
  (atom nil))

(defn path->command-id-map
  "Build a {path-string → :command/id} mapping from the registry.
   Returns nil if duplicate paths are found (path collisions)."
  []
  (or @path-map-cache
      (let [path-map (reduce (fn [acc cmd]
                               (let [path-str (str/join " " (:command/path cmd))
                                     cmd-id (:command/id cmd)]
                                 (if (contains? acc path-str)
                                   (reduced nil)
                                   (assoc acc path-str cmd-id))))
                             {}
                             (:commands (load-registry)))]
        (when path-map
          (reset! path-map-cache path-map))
        path-map)))

(defn validate-paths
  "Validate that all registry command paths are unique and resolvable.
   Returns {:ok? true} or {:ok? false :errors [...]}."
  []
  (let [errors (volatile! [])]
    (when-not (path->command-id-map)
      (doseq [cmd (:commands (load-registry))]
        (let [path-str (str/join " " (:command/path cmd))
              dups (filter #(= path-str (str/join " " (:command/path %)))
                           (:commands (load-registry)))]
          (when (< 1 (count dups))
            (vswap! errors conj (str "Duplicate path \"" path-str "\" for commands: "
                                     (pr-str (map :command/id dups))))))))
    (if (empty? @errors)
      {:ok? true}
      {:ok? false :errors @errors})))

(defn list-commands
  "Return a seq of {:path [...] :description str} for all registered commands."
  []
  (mapv (fn [cmd]
          {:path        (:command/path cmd)
           :id          (:command/id cmd)
           :category    (:command/category cmd)
           :tier        (:command/backstop-tier cmd)
           :jar-avail   (:command/jar-availability cmd)
           :runtime     (:command/runtime cmd)
           :surface     (:command/surface cmd)
           :description (:command/description cmd)})
        (:commands (load-registry))))

(defn get-command
  "Look up a command by its :command/id keyword."
  [id]
  (first (filter #(= (:command/id %) id) (:commands (load-registry)))))

(defn load-bb-edn
  "Read bb.edn from the project root and return its task map.
   Returns nil if bb.edn is not found or unreadable."
  []
  (try
    (let [bb-file (io/file "bb.edn")]
      (when (.exists bb-file)
        (let [bb-edn (edn/read-string (slurp bb-file))]
          (:tasks bb-edn))))
    (catch Exception _ nil)))

(defn validate-bb-tasks
  "Validate that every :bb-surface command has a matching task in bb.edn.
   Returns {:ok? true} or {:ok? false :errors [...]}."
  []
  (let [errors (volatile! [])
        bb-tasks (load-bb-edn)]
    (doseq [cmd (:commands (load-registry))]
      (when (= :bb (:command/surface cmd))
        (let [bb-task-name (:command/bb-task cmd)]
          (if-not bb-task-name
            (vswap! errors conj (str "BB command " (:command/id cmd) " missing :command/bb-task"))
            (when bb-tasks
              (when-not (contains? bb-tasks (symbol bb-task-name))
                (vswap! errors conj (str "bb-task \"" bb-task-name "\" not found in bb.edn for command " (:command/id cmd)))))))))
    (if (empty? @errors)
      {:ok? true}
      {:ok? false :errors @errors})))

(defn validate-registry
  "Validate the registry structure.
   Returns {:ok? true} or {:ok? false :errors [...]}."
  []
  (let [reg (load-registry)
        errors (volatile! [])]
    (when-not (= "prf.commands.registry.v1" (:schema-version reg))
      (vswap! errors conj "Invalid schema version"))
    (doseq [cmd (:commands reg)]
      (when-not (:command/id cmd)
        (vswap! errors conj "Command missing :command/id"))
      (when-not (:command/path cmd)
        (vswap! errors conj (str "Command " (:command/id cmd) " missing :command/path")))
      (when-not (:command/category cmd)
        (vswap! errors conj (str "Command " (:command/id cmd) " missing :command/category")))
      (when-not (:command/description cmd)
        (vswap! errors conj (str "Command " (:command/id cmd) " missing :command/description"))))
    (if (empty? @errors)
      {:ok? true}
      {:ok? false :errors @errors})))
