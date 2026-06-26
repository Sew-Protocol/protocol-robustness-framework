(ns resolver-sim.run.overview
  "Normalized run overview for runner comparison and consensus.
   Produces a stable overview from a :scenario-run/result map, stripping
   volatile fields (timestamps, paths, timing, raw execution data) so that
   two runners executing the same suite produce identical overview hashes.

   Usage:
     (require '[resolver-sim.run.overview :as overview])

     (overview/build-overview scenario-run-result)
     ;; => {:overview/schema-version \"run-overview.v1\"
     ;;     :suite {:registry-key :default ...}
     ;;     :results [{:scenario-id \"Y01\" :status :passed ...} ...]
     ;;     :totals {:passed N :failed N :total N}
     ;;     :consensus {:eligible? true}}

     (overview/overview-hash overview)
     ;; => \"abc123...\"  (stable across runners)"
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "run-overview.v1")

(def scenario-stable-keys
  "Top-level keys extracted from each scenario result entry for the normalized
   overview.  Volatile fields (execution raw data, runner metadata, timing,
   absolute paths) are intentionally excluded."
  #{:scenario-id :pass? :outcome :halt-reason :checks :violations
    :dispatcher-id :expected-fail? :scenario-path})

(def volatile-run-result-keys
  "Keys stripped from the scenario-run/result when building the overview.
   These differ across runners even for identical execution (timing, paths,
   raw execution data, diagnostic metadata)."
  #{:diagnostics :execution/raw :replay-result :scenario :runner})

(defn- stable-scenario-entry
  "Extract stable fields from one scenario result entry."
  [entry]
  (select-keys entry scenario-stable-keys))

(defn build-overview
  "Build a normalized overview from a :scenario-run/result map.

   The overview contains only stable fields suitable for cross-runner
   comparison and hashing.  Volatile fields (timestamps, paths, timing,
   raw execution data) are excluded.

   Returns a map conforming to run-overview.v1 schema."
  [{:keys [results runner-selection] :as run-result}]
  (let [suite-key (:suite/key run-result)
        stable-results (mapv stable-scenario-entry results)
        passed (count (filter :pass? stable-results))
        total (count stable-results)
        failed (- total passed)
        expected-failed (count (filter :expected-fail? stable-results))
        unexpected-failed (count (filter (fn [r] (and (not (:pass? r)) (not (:expected-fail? r))))
                                         stable-results))]
    {:overview/schema-version schema-version
     :suite {:suite/key suite-key
             :scenario-count (count results)
             :runner-selection (select-keys runner-selection [:mode :runner-id])}
     :results stable-results
     :totals {:passed passed
              :failed failed
              :total total
              :expected-failed expected-failed
              :unexpected-failed unexpected-failed}
     :consensus {:eligible? true
                 :basis :normalized-result-hash}}))

(defn overview-hash
  "Compute a stable hash of a normalized overview.
   Two runners executing the same suite produce the same hash.
   Uses domain-separated hashing via :run-overview intent."
  [overview]
  (hc/hash-with-intent {:hash/intent :run-overview} overview))
