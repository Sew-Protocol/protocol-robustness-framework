(ns resolver-sim.validation.root
  "Shared validation root that accumulates suite-local results into a
   single canonical validation state.

   Reducer:
     (reduce merge-validation-result empty-validation-root suite-results)
       → validation-root map

   The root is designed to be:
     - Hashable for evidence-chain anchoring
     - Machine-readable for CI and registry
     - Human-readable for notebooks and dashboards"
  (:require [clojure.set :as set]))

(def critical-error-keys
  "Errors at this severity invalidate the run or its evidence chain."
  #{:registry/dangling-dependency
    :artifact/hash-mismatch
    :replay/non-deterministic
    :invariant/broken
    :financial-finality/invalid
    :evidence/binding-mismatch
    :bank-run})

(def empty-validation-root
  {:status       :unknown
   :status-keys  #{}
   :warning-keys #{}
   :error-keys   #{}
   :errors       []
   :warnings     []
   :evidence     []
   :suite-results {}
   :metrics      {:checks 0 :passed 0 :failed 0 :warnings 0}})

(defn merge-metrics
  "Combine two metrics maps by summing corresponding counters."
  [a b]
  (merge-with + a b))

(defn merge-validation-result
  "Accumulate one suite result into the validation root.

   Suite result shape (from suite-result/suite-result):
     {:suite/id     :yield
      :suite/type   :protocol
      :status       :passed|:warning|:failed
      :status-keys  #{...}
      :warning-keys #{...}
      :error-keys   #{...}
      :errors       [...]
      :warnings     [...]
      :evidence     [...]
      :metrics      {:checks N :passed N :failed N :warnings N}}"
  [root suite-result]
  (let [sid (:suite/id suite-result)]
    (-> root
        (update :status-keys into (:status-keys suite-result))
        (update :warning-keys into (:warning-keys suite-result))
        (update :error-keys into (:error-keys suite-result))
        (update :errors into (:errors suite-result))
        (update :warnings into (:warnings suite-result))
        (update :evidence into (:evidence suite-result))
        (update :suite-results assoc sid suite-result)
        (update :metrics merge-metrics (:metrics suite-result)))))

(defn derive-root-status
  "Derive final validation root status from accumulated error and warning keys.

   Status ladder:
     :failed-critical  — at least one critical-error-keys is present
     :failed           — at least one error-key present
     :warning          — at least one warning-key present, no errors
     :passed           — no errors or warnings"
  [root]
  (let [error-keys   (:error-keys root #{})
        warning-keys (:warning-keys root #{})]
    (cond
      (seq (set/intersection critical-error-keys error-keys)) :failed-critical
      (seq error-keys)                                         :failed
      (seq warning-keys)                                       :warning
      :else                                                    :passed)))

(defn build-validation-root
  "Reduce a collection of suite results into a single validation root.

   Args:
     suite-results — vector of suite result maps (from suite-result/suite-result)
     extra         — optional map merged into the root (:run-id, :source-revision, etc.)

   Returns a validation-root map with :status derived from accumulated errors/warnings."
  ([suite-results] (build-validation-root suite-results {}))
  ([suite-results extra]
   (let [root (reduce merge-validation-result
                       empty-validation-root
                       suite-results)]
     (-> root
         (assoc :status (derive-root-status root))
         (merge extra)))))

(defn status-summary
  "Return a concise one-line summary of the validation root.
   Useful for terminal output and CI status lines."
  [root]
  (let [s (:status root)
        m (:metrics root)]
    (format "Validation: %s  (%d checks, %d passed, %d failed, %d warnings)"
            (name s)
            (:checks m 0) (:passed m 0) (:failed m 0) (:warnings m 0))))

(defn error-summary
  "Return a vector of concise error strings from the root's error list."
  [root]
  (mapv (fn [e]
          (format "[%s] %s — %s"
                  (name (:severity e :warning))
                  (name (:key e :unknown))
                  (:message e "")))
        (:errors root)))
