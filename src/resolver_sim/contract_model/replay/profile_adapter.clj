(ns resolver-sim.contract-model.replay.profile-adapter
  "Replay profile adapter dispatch — multimethod on [profile protocol-id].

   Separates protocol-ID routing from the public `simple-replay` entry point.
   Each protocol can register its own adapter method; the default implementation
   calls `replay-events`.

   === Adapter contract ===
   Every adapter must:
   1. Accept (protocol scenario replay-opts)
   2. Honour the common result contract (:replay-profile, :protocol-id, etc.)
   3. Explicitly reject unsupported options via structured ex-info
   4. Perform no persistent evidence or diagnostic I/O"
  (:require [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.contract-model.replay.yield :as yield-replay]))

;; ---------------------------------------------------------------------------
;; Unsupported/prohibited option validation
;; ---------------------------------------------------------------------------

(def prohibited-simple-options
  "Options that the simple replay profile must reject."
  #{:evidence-mode :signing-key :signing-password :tsa-url
    :skip-finalize :allow-dirty?})

(def option-type
  "Classify a replay option for error reporting."
  {:evidence-mode    :profile-flag
   :signing-key      :persistence
   :signing-password :persistence
   :tsa-url          :timestamping
   :skip-finalize    :evidence-chain
   :allow-dirty?     :evidence-chain})

(defn collect-unsupported-options
  "Return a sequence of unsupported option keys found in `replay-opts`.
   Each entry is {:option <k> :type <option-type> :rejected-by <adapter-origin>}."
  [replay-opts adapter-origin]
  (keep (fn [k]
          (when (contains? replay-opts k)
            {:option k
             :type (get option-type k :unknown)
             :rejected-by adapter-origin}))
        prohibited-simple-options))

(defn reject-unsupported!
  "Throw ex-info if `replay-opts` contains any prohibited options.
   Reports all violations together so callers get a complete picture."
  [replay-opts adapter-origin]
  (when (map? replay-opts)
    (let [violations (collect-unsupported-options replay-opts adapter-origin)]
      (when (seq violations)
        (throw (ex-info "Simple replay does not support evidence-chain, persistence or timestamping options"
                        {:violations violations
                         :replay-profile :simple
                         :adapter adapter-origin
                         :n-unsupported (count violations)}))))))

;; ---------------------------------------------------------------------------
;; Simple replay options that are allowed
;; ---------------------------------------------------------------------------

(defn extract-simple-opts
  "Extract allowed options from `replay-opts`, rejecting prohibited ones.
   Returns {:run-id <id or nil> :flags <map or nil>}."
  [replay-opts adapter-origin]
  (reject-unsupported! replay-opts adapter-origin)
  {:run-id (:run-id replay-opts)
   :flags  (:flags replay-opts)})

;; ---------------------------------------------------------------------------
;; Adapter multimethod — dispatch on [profile protocol-id]
;; ---------------------------------------------------------------------------

(defmulti run-simple-profile
  "Dispatch a simple-profile replay for the given protocol.

   profile    — keyword, currently always :simple
   protocol   — protocol instance (satisfies? SimulationAdapter)
   scenario   — prepared scenario map
   replay-opts — caller-provided options (already validated by extract-simple-opts)

   Default implementation calls replay-events with {:minimal true}.
   Protocol-specific adapters override for non-standard execution paths."
  (fn [profile protocol scenario replay-opts] [profile (proto/protocol-id protocol)]))

(defmethod run-simple-profile :default
  [profile protocol scenario replay-opts]
  (let [replay-events (requiring-resolve 'resolver-sim.contract-model.replay/replay-events)]
    (replay-events protocol scenario
                   (merge {:minimal true} replay-opts))))

;; ---------------------------------------------------------------------------
;; Yield adapter — temporary thin-runner path
;;
;; REMOVAL CONDITION: This adapter exists because yield-v1 has not yet achieved
;; canonical-loop parity. Remove when:
;;   1. All yield actions dispatch through the protocol interface
;;   2. World initialization is equivalent
;;   3. Existing yield invariants are preserved
;;   4. Trace information is not materially lost
;;   5. Core metrics remain available
;;   6. Caller options are honored consistently
;;   7. Parity tests pass
;; ---------------------------------------------------------------------------

(defmethod run-simple-profile [:simple "yield-v1"]
  [profile protocol scenario replay-opts]
  (yield-replay/replay-yield-events protocol scenario replay-opts))
