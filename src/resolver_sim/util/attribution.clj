(ns resolver-sim.util.attribution
  "Utilities for propagating contextual metadata across execution boundaries.
   Supports both diagnostic logging and research-grade evidence annotation."
  (:require [resolver-sim.logging :as log]))

(def ^:dynamic *attribution* {})

(def ^:private attribution-version 1)

;; ── Requirements ─────────────────────────────────────────────────────────────

(def required-evidence-keys
  "Minimum keys for complete attribution in research evidence."
  #{:ctx/run-id})

(def scenario-evidence-keys
  "Additional keys expected for scenario-based evidence."
  #{:ctx/scenario-id :ctx/event-index})

;; ── Internal Context Embedding ───────────────────────────────────────────────

(defrecord AttributedState [state attribution])

(defn wrap-state
  "Wraps raw state and attribution into an internal envelope."
  [state attribution]
  (->AttributedState state attribution))

(defn unwrap-state
  "Unwraps raw state from the internal envelope."
  [attributed-state]
  (if (instance? AttributedState attributed-state)
    (:state attributed-state)
    attributed-state))

(defn get-attribution
  "Retrieves attribution from an AttributedState or attribution map or dynamic context."
  [attributed-state-or-map]
  (cond
    (instance? AttributedState attributed-state-or-map) (:attribution attributed-state-or-map)
    (map? attributed-state-or-map) attributed-state-or-map
    :else *attribution*))

;; ── Logic ────────────────────────────────────────────────────────────────────

(defmacro with-attribution
  "Execute body with merged attribution context.
   Inner keys override outer keys. Keys should be namespaced (e.g., :ctx/id)."
  [attr & body]
  `(binding [*attribution* (merge *attribution* ~attr)]
     ~@body))

(defn current-attribution [] *attribution*)

(defn artifact-safe-value?
  "Predicate for values safe to include in persisted JSON/EDN artifacts."
  [v]
  (or (nil? v)
      (string? v)
      (keyword? v)
      (number? v)
      (boolean? v)
      (and (map? v) (every? (fn [[k v]] (and (keyword? k) (artifact-safe-value? v))) v))
      (and (sequential? v) (every? artifact-safe-value? v))))

(defn sanitize-attribution
  "Drop non-namespaced or non-serializable values from attribution map."
  [attr]
  (into {}
        (filter (fn [[k v]]
                  (and (keyword? k)
                       (namespace k)
                       (artifact-safe-value? v))))
        attr))

(defn attribution-quality
  "Analyze the provided attribution context against required keys.
   Returns {:quality :complete|:partial|:missing, :missing [...], :attribution {...}}"
  [attr requirements]
  (let [safe-attr (sanitize-attribution attr)
        missing   (seq (remove #(contains? safe-attr %) requirements))]
    {:quality (cond
                (nil? missing) :complete
                (seq safe-attr) :partial
                :else :missing)
     :missing missing
     :attribution safe-attr}))

(defn current-evidence-attribution
  "Helper for evidence capture modules to get a standardized attribution block.
   Accepts an optional explicit attribution map; falls back to *attribution*."
  ([] (current-evidence-attribution required-evidence-keys nil))
  ([requirements] (current-evidence-attribution requirements nil))
  ([requirements explicit-attr]
   (attribution-quality (or explicit-attr *attribution*) requirements)))

;; ── Logging & Helpers ────────────────────────────────────────────────────────

(defn attribution-envelope
  "Return a versioned, sanitized envelope of the current context."
  []
  {:attribution/version attribution-version
   :attribution/context (sanitize-attribution *attribution*)})

(defn annotate-evidence
  "Attach the current attribution envelope to an evidence record."
  [evidence]
  (assoc evidence :attribution (attribution-envelope)))

(defn log-with-attr [level msg & [data]]
  (log/log! level msg (merge data *attribution*)))

(defn make-context [data] data)

(defn log-annotated! [level msg ctx data]
  (log/log! level msg (merge data ctx)))
