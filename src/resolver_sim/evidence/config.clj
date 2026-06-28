(ns resolver-sim.evidence.config
  "Canonical evidence chain configuration from config/evidence.json.
   All consumers should read from this namespace rather than hardcoding paths or versions."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.io File]))

(def ^:private config-path "config/evidence.json")

(def ^:private config
  (delay
    (-> config-path io/reader json/read)))

(defn get-config
  "Return the full evidence config map, reading from disk on first call."
  []
  @config)

(defn schema
  "Resolve a schema key to its version string, e.g. (schema :test-summary) → \"test-summary.v2\""
  [k]
  (get-in (get-config) ["schemas" (name k)]))

(defn producer
  "Resolve a producer key to its ID string, e.g. (producer :summary) → \"summary-emitter.v1\""
  [k]
  (get-in (get-config) ["producers" (name k)]))

(defn artifact
  "Return the artifact definition map for the given id keyword or string."
  [artifact-id]
  (let [id (name artifact-id)
        arts (get (get-config) "artifacts" [])]
    (first (filter #(= id (get % "id")) arts))))

(defn artifact-file
  "Resolve an artifact id to its filename, e.g. (artifact-file :test-summary) → \"test-summary.json\"."
  [artifact-id]
  (get (artifact artifact-id) "file"))

(defn artifact-path
  "Resolve an artifact id to its full path relative to project root."
  [artifact-id]
  (let [adir (get (get-config) "artifact_dir")
        f    (artifact-file artifact-id)]
    (str adir "/" f)))

(defn contract-version []
  (get (get-config) "contract_version"))

(defn rounding-policy []
  (get (get-config) "rounding_policy"))

(defn framework []
  (get (get-config) "framework"))

(defn artifact-dir
  "Return the artifact directory path.
   Checks PRF_ARTIFACT_DIR env var first (for per-run workspaces),
   falls back to config/evidence.json's artifact_dir."
  []
  (or (System/getenv "PRF_ARTIFACT_DIR")
      (get (get-config) "artifact_dir")))

(defn runs-root []
  (get (get-config) "runs_root"))

(defn evidence-bundle-dir []
  (get (get-config) "evidence_bundle_dir"))

(defn strict-mode?
  "Return true when strict validation mode is enabled in config.
   In strict mode, recommended checks are promoted to required (warnings → failures)."
  []
  (boolean (get (get-config) "strict_mode" false)))
