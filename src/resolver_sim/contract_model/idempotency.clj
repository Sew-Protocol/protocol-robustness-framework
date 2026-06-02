(ns resolver-sim.contract-model.idempotency
  "Protocol-agnostic idempotency helpers for replay/kernel-level operations.

   This namespace is intentionally generic: it stores opaque operation keys and
   applies a caller-provided transition at most once for each key.")

(defn applied?
  "True if op-key has already been applied in this world snapshot."
  [world op-key]
  (contains? (get world :idempotency/applied #{}) op-key))

(defn mark-applied
  "Record op-key as applied."
  [world op-key]
  (update world :idempotency/applied (fnil conj #{}) op-key))

(defn apply-once
  "Apply apply-fn exactly once for a given op-key.

   Returns:
     - first apply: result from apply-fn with :extra {:idempotency :applied-once}
     - duplicate:   {:ok true :world world :extra {:idempotency :no-op-duplicate :op-key op-key}}

   Contract:
   - apply-fn must return a standard transition result map ({:ok bool ...}).
   - on success, op-key is recorded in :idempotency/applied.
   - on failure, op-key is NOT recorded."
  [world op-key apply-fn]
  (if (applied? world op-key)
    {:ok true
     :world world
     :extra {:idempotency :no-op-duplicate
             :op-key op-key}}
    (let [result (apply-fn world)]
      (if (:ok result)
        (-> result
            (update :world mark-applied op-key)
            (update :extra (fnil merge {}) {:idempotency :applied-once}))
        result))))

(defn ensure-not-duplicate
  "Guard helper for operation handlers that need an explicit failure on duplicate.
   Returns {:ok false :error :duplicate-operation} when op-key already exists,
   otherwise {:ok true :world world}."
  [world op-key]
  (if (applied? world op-key)
    {:ok false :error :duplicate-operation}
    {:ok true :world world}))
