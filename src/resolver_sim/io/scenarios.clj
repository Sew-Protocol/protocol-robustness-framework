(ns resolver-sim.io.scenarios
  "Load and parse scenario JSON files for the contract-model replay engine.

   Layering: io/* shell layer — file I/O only.  Callers combine this with
   resolver-sim.protocols.sew/replay-with-sew-protocol for full file replay."
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
