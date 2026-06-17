(ns resolver-sim.util.evidence
  "Hardened evidence boundary for producing tamper-evident, attribution-bound
   evidence records. This is the single entry point all evidence-producing code
   should pass through.

   Key principle: keep the world semantically pure; make transition evidence
   richly attributed and content-hashed.

   See `make-evidence-record` for the canonical evidence record shape, and
   `emit-evidence!` for the primary emission API."
  (:require [clojure.walk :as walk]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.util.attribution :as attr])
  (:import [java.security MessageDigest]))

;; ── Attribution Context ──────────────────────────────────────────────────────

(defn current-attribution
  "Return the current dynamic attribution context."
  []
  (attr/current-attribution))

;; ── Required Attribution Schema ──────────────────────────────────────────────

(def required-attribution
  "Map from evidence artifact-kind to the set of attribution keys that must
   be present for evidence of that kind to be valid."
  {:transition [:ctx/run-id :ctx/scenario-id :ctx/step :ctx/event-id]
   :invariant  [:ctx/run-id :ctx/scenario-id :ctx/step :ctx/invariant-id]
   :scenario   [:ctx/run-id :ctx/scenario-id]
   :sweep      [:ctx/run-id :ctx/sweep-id :ctx/scenario-id]})

(defn require-attribution!
  "Assert that the attribution context contains all required keys
   for the given evidence kind. Returns the sanitized attribution on success.
   Throws with :missing keys on failure.

   If explicit-attr is provided, it uses that; otherwise it falls back to
   the dynamic *attribution* context."
  ([evidence-kind] (require-attribution! evidence-kind nil))
  ([evidence-kind explicit-attr]
   (let [required (get required-attribution evidence-kind [])
         ;; Use the provided attribution, or fallback to dynamic *attribution*
         raw-attr (if explicit-attr
                    (attr/get-attribution explicit-attr)
                    (attr/current-attribution))
         attr (attr/sanitize-attribution raw-attr)
         missing (seq (remove #(contains? attr %) required))]
     (when missing
       (throw (ex-info "Missing required attribution context for evidence"
                       {:evidence-kind evidence-kind
                        :missing (vec missing)
                        :attribution attr
                        :required (vec required)
                        :explicit? (some? explicit-attr)})))
     attr)))

;; ── Stable Hashing ───────────────────────────────────────────────────────────

(defn- canonicalize
  "Recursively sort map keys for deterministic serialization."
  [data]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map) x)
        x))
    data))

(defn- sha256-hex
  "Return the SHA-256 hex digest of a UTF-8 string."
  [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn stable-hash
  "Compute a deterministic, content-addressed hash of any Clojure value.
   Same input always produces the same hex string, across JVM invocations."
  [x]
  (sha256-hex (pr-str (canonicalize x))))

;; ── Evidence Record ──────────────────────────────────────────────────────────

(defn make-evidence-record
  "Build a versioned, content-hashed evidence record from a specification map.

   Expected keys in spec:
     :artifact-kind  - keyword identifying the kind of evidence
     :block-time     - simulation time of transition
     :step           - simulation step index
     :before         - world state before the transition (hashed, not stored raw)
     :after          - world state after the transition (hashed, not stored raw)
     :action         - the action/event that caused the transition (stored raw + hashed)
     :result         - the outcome/result of the action (stored raw + hashed)
     :attribution    - attribution context map

   The returned record includes an :evidence-hash that covers all other fields,
   making the record tamper-evident."
  [{:keys [artifact-kind block-time step before after action result attribution]}]
  (let [context-hash (stable-hash attribution)
        before-hash (stable-hash before)
        after-hash (stable-hash after)
        action-hash (stable-hash action)
        result-hash (stable-hash result)
        base {:schema-version (evcfg/schema :evidence-record)
              :artifact-kind artifact-kind
              :temporal-context {:block-time block-time :step step}
              :attribution attribution
              :context-hash context-hash
              :before-hash before-hash
              :after-hash after-hash
              :action action
              :action-hash action-hash
              :result result
              :result-hash result-hash}
        evidence-hash (stable-hash base)
        group-id (:ctx/evidence-group-id attribution)]
    (cond-> (assoc base :evidence-hash evidence-hash)
      group-id (assoc :evidence/group-id group-id :evidence/layer :generic-trace))))

;; ── Evidence Emission ────────────────────────────────────────────────────────

(defn emit-evidence!
  "Primary evidence emission API. Validates attribution context and produces a
   content-hashed evidence record.

   Call shape:
     (emit-evidence!
       {:artifact-kind     :transition
        :block-time        current-block-time
        :step              current-step
        :before            before-world
        :after             after-world
        :action            action-map
        :result            result-map
        :attribution-context (optional) AttributedState or attribution map})

   Returns the evidence record. Throws if required attribution keys are missing
   or if temporal context is absent."
  [{:keys [artifact-kind block-time step attribution-context] :as spec}]
  (when-not (and block-time step)
    (throw (ex-info "Missing required temporal context (block-time/step) for evidence"
                    {:block-time block-time :step step})))
  (let [attribution (require-attribution! (or artifact-kind :transition) attribution-context)]
    (make-evidence-record (assoc spec :attribution attribution))))

;; ── Async Attribution Preservation ───────────────────────────────────────────

(defn wrap-attribution
  "Higher-order function that captures current attribution and rebinds it
   when the returned function is called. Use at async boundaries."
  [f]
  (let [attr (attr/current-attribution)]
    (fn [& args]
      (binding [attr/*attribution* attr]
        (apply f args)))))

(defmacro contextual-future
  "Execute body in a future that preserves the current attribution context."
  [& body]
  `(let [attr# (attr/current-attribution)]
     (future
       (binding [attr/*attribution* attr#]
         ~@body))))

(defn contextual-pmap
  "Parallel map that preserves the current attribution context across worker threads.
   Prefer this over raw pmap in simulation and evidence-producing code."
  [f coll]
  (let [attr (attr/current-attribution)
        wrapper (fn [x]
                  (binding [attr/*attribution* attr]
                    (f x)))]
    (pmap wrapper coll)))
