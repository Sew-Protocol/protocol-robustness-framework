(ns resolver-sim.cli.registry
  "Load and query the PRF command registry.
   Registry is stored as a classpath resource at prf/commands/registry.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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
           :description (:command/description cmd)})
        (:commands (load-registry))))

(defn get-command
  "Look up a command by its :command/id keyword."
  [id]
  (first (filter #(= (:command/id %) id) (:commands (load-registry)))))

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
