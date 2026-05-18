(ns resolver-sim.io.scenarios
  "Load and parse scenario JSON files for the contract-model replay proto.

   Layering: io/* shell layer — file I/O only.  Callers combine this with
   replay/replay-with-protocol (using resolver-sim.protocols.registry) for
   full file replay."
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str]))

(defn- json-key->kw [s]
  (keyword (str/replace s "_" "-")))

(defn load-scenario-file
  "Load and parse a scenario JSON file.  Converts snake_case keys to kebab-case keywords."
  [path]
  (with-open [r (io/reader path)]
    (json/read r :key-fn json-key->kw)))
