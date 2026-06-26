(ns resolver-sim.run.bundle-root
  "Bundle root construction and validation for reproducible suite runs.
   A bundle root captures the run request, registry snapshot, execution
   summary, and normalized overview hash — enough information to re-run
   the same suite and verify the same execution graph.

   Usage:
     (require '[resolver-sim.run.bundle-root :as br])

     (br/build-bundle-root run-request run-result)
     ;; => {:bundle/schema-version \"bundle-root.v1\"
     ;;     :bundle/id \"<hash>\"
     ;;     :run/request {...}
     ;;     :registry/snapshot {...}
     ;;     :execution/summary {...}
     ;;     :overview/hash \"...\"}

     (br/runnable? bundle-root registries)
     ;; => {:runnable? true} | {:runnable? false :errors [...]}"
  (:require [clojure.walk :as walk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.run.overview :as overview]
            [resolver-sim.run.criteria :as criteria]))

(def ^:const schema-version "bundle-root.v1")

;; ── Registry snapshot helpers ─────────────────────────────────────────────────

(defn- canonicalize-registry
  "Prepare a registry value for hashing: sets become sorted vectors,
   symbols become strings, fns become their names."
  [registry]
  (walk/postwalk
   (fn [x]
     (cond
       (set? x) (vec (sort x))
       (symbol? x) (str x)
       (fn? x) (str (some-> x meta :name (or (class x))))
       (instance? clojure.lang.Var x) (str (.sym ^clojure.lang.Var x))
       :else x))
   registry))

(defn- registry-hash
  "Compute a stable hash of a registry value."
  [registry]
  (hc/hash-with-intent {:hash/intent :registry} (canonicalize-registry registry)))

(defn- lazy-resolve
  "Resolve a var by fully-qualified symbol, loading the namespace if needed."
  [sym]
  (try
    (when-let [v (requiring-resolve sym)]
      @v)
    (catch Exception _ nil)))

(defn registry-snapshot
  "Compute a snapshot of all active registries at call time.
   Uses lazy resolution to avoid hard dependencies on every registry namespace.

   Returns a map suitable for :registry/snapshot in the bundle root."
  []
  (let [exec-reg (lazy-resolve 'resolver-sim.definitions.passive-registries/execution-registry)
        ep-reg   (lazy-resolve 'resolver-sim.definitions.passive-registries/evidence-policy-registry)
        att-reg  (lazy-resolve 'resolver-sim.definitions.passive-registries/attestor-registry)
        cd-reg   (lazy-resolve 'resolver-sim.definitions.passive-registries/claim-definition-registry)
        suites   (lazy-resolve 'resolver-sim.scenario.suites/suites)
        protocols (try
                    (let [v (requiring-resolve 'resolver-sim.protocols.registry/known-protocol-ids)]
                      (vec (v)))
                    (catch Exception _ nil))]
    {:registry-hash (when att-reg (registry-hash att-reg))
     :scenario-suite-hash (when suites (registry-hash suites))
     :dispatcher-registry-hash (when protocols (registry-hash protocols))
     :execution-registry-hash (when exec-reg (registry-hash exec-reg))
     :evidence-policy-hash (when ep-reg (registry-hash ep-reg))
     :claim-definition-registry-hash (when cd-reg (registry-hash cd-reg))}))

;; ── Environment helpers ───────────────────────────────────────────────────────

(defn- git-commit
  []
  (try
    (let [proc (.exec (Runtime/getRuntime) (into-array String ["git" "rev-parse" "HEAD"]))
          reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))]
      (.trim (.readLine reader)))
    (catch Exception _ nil)))

(defn- git-dirty?
  []
  (try
    (let [proc (.exec (Runtime/getRuntime) (into-array String ["git" "status" "--porcelain"]))
          reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))]
      (some? (.readLine reader)))
    (catch Exception _ nil)))

(defn environment
  "Capture the current execution environment."
  []
  {:git/commit (git-commit)
   :git/dirty? (git-dirty?)
   :clojure/version (clojure-version)
   :java/version (System/getProperty "java.version")
   :os/name (System/getProperty "os.name")})

;; ── Bundle root builder ──────────────────────────────────────────────────────

(defn build-bundle-root
  "Build a bundle root from a run request and run result.

   request — the :scenario-run/request map
   result  — the :scenario-run/result map

   Returns a bundle-root.v1 map with:
   - run request (for reproducibility)
   - registry snapshot hashes
   - execution environment
   - execution summary
   - normalized overview hash
   - self-referential bundle hash"
  [request result]
  (let [overview (overview/build-overview result)
        overview-h (overview/overview-hash overview)
        req (select-keys request
                         [:runner/backend :runner-selection
                          :suite/key :protocol/default-id
                          :evidence/profile :output/profile])
        base {:bundle/schema-version schema-version
              :run/request (assoc req
                                  :registry-key (or (:registry-key request) :default)
                                  :workspace (or (:workspace request) :current))
              :registry/snapshot (registry-snapshot)
              :run/environment (environment)
              :execution/summary (select-keys result [:totals :status])
              :overview/hash overview-h
              :overview overview}
        bundle-hash (hc/hash-with-intent {:hash/intent :bundle-root} base)]
    (assoc base
           :bundle/id bundle-hash
           :bundle/hash bundle-hash)))

;; ── Bundle root validation ────────────────────────────────────────────────────

(defn runnable?
  "Check whether a bundle root is reproducible.
   Delegates to criteria/runnable-bundle-root?.

   Returns {:runnable? true} or {:runnable? false :errors [...]}."
  [bundle-root]
  (criteria/runnable-bundle-root? bundle-root))
