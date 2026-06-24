(ns resolver-sim.io.scenarios
  "Load and parse file-backed executable scenarios.

   Layering: io/* shell layer — file I/O only. Callers combine this with
   replay/replay-with-protocol (using resolver-sim.protocols.registry) for
   full file replay. EDN is the canonical executable format; JSON remains
   supported during migration and emits a deprecation warning."
  (:require [clojure.data.json :as json]
            [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [clojure.string   :as str]
            [resolver-sim.logging :as log]))

(defn- json-key->kw [s]
  (keyword (str/replace s "_" "-")))

(defn- scenario-format [path]
  (let [name (.getName (io/file path))]
    (cond
      (str/ends-with? name ".edn") :edn
      (str/ends-with? name ".json") :json
      :else :json)))

(defn load-scenario-file
  "Load and parse a scenario file. EDN is preferred; JSON is deprecated."
  [path]
  (case (scenario-format path)
    :edn (edn/read-string (slurp path))
    :json (do
            (log/warn! :scenario-json-deprecated
                       {:path path
                        :preferred-format :edn
                        :message "Executable JSON scenario input is deprecated; use EDN instead"})
            (with-open [r (io/reader path)]
              (json/read r :key-fn json-key->kw)))))
