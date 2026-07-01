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

(def ^:dynamic *scenario-dir*
  "Directory for executable scenario files, relative to project root."
  "scenarios/edn")

(def ^:dynamic *scenario-ext*
  "File extension for executable scenario files, including leading dot."
  ".edn")

(defn scenario-path
  "Convert a scenario ID (e.g. \"S01_baseline-happy-path\") to a canonical file path.
   Uses *scenario-dir* and *scenario-ext* so callers never hardcode dir or extension."
  [scenario-id]
  (str *scenario-dir* "/" scenario-id *scenario-ext*))

(defn scenario-file->id
  "Extract the scenario ID from a scenario file path.
   Inverse of scenario-path."
  [path]
  (-> (io/file path) .getName (str/replace #"\.(edn|json)$" "")))

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
