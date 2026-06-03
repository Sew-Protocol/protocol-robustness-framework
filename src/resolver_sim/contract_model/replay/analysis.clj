(ns resolver-sim.contract-model.replay.analysis
  "Error analysis and result finalization logic for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.set                    :as set]
            [clojure.string                 :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.yield-metrics :as yield-metrics]))

(defn action->transition-id
  "Map an event action string/keyword to canonical transition semantic id.
   Keeps backward compatibility by tolerating hyphen/underscore forms."
  [action]
  (let [s (-> (if (keyword? action) (name action) (str action))
              str/lower-case
              (str/replace "-" "_"))
        k (keyword s)]
    (if (defs/transition-def k)
      (keyword "scenario.transition" s)
      (keyword "scenario.transition" "unknown"))))

(defn normalize-error-value
  [error]
  (cond
    (keyword? error) error
    (string? error)  (let [s (if (.startsWith ^String error ":")
                               (subs error 1)
                               error)]
                       (keyword s))
    :else error))

(defn expected-error-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn rejected-entry-key
  [{:keys [seq action error]}]
  [seq action (normalize-error-value error)])

(defn analyze-expected-errors
  "Compare rejected trace entries against scenario :expected-errors.

   Returns {:ok? bool :matched [...] :missing [...] :unexpected [...]}.
   Matching is exact on [:seq :action :error]."
  [scenario trace]
  (let [expected        (vec (:expected-errors scenario []))
        expected-set    (set (map expected-error-key expected))
        rejected        (->> trace
                             (filter #(= :rejected (:result %)))
                             (map #(select-keys % [:seq :action :error]))
                             vec)
        rejected-set    (set (map rejected-entry-key rejected))
        matched-set     (set/intersection expected-set rejected-set)
        missing-set     (set/difference expected-set rejected-set)
        unexpected-set  (set/difference rejected-set expected-set)]
    {:ok?        (and (empty? missing-set) (empty? unexpected-set))
     :matched    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? matched-set (expected-error-key %)) expected)))
     :missing    (vec (sort-by (juxt :seq :action)
                               (filter #(contains? missing-set (expected-error-key %)) expected)))
     :unexpected (vec (sort-by (juxt :seq :action)
                               (filter #(contains? unexpected-set (rejected-entry-key %)) rejected)))}))

(defn finalize-scenario-result
  "Apply yield metrics, expected-outcomes, and :expectations checks."
  [scenario result]
  (let [result'      (yield-metrics/merge-yield-metrics result)
        outcomes     (expectations/analyze-expected-outcomes scenario (:trace result'))
        expect       (when (:expectations scenario)
                       (expectations/evaluate-expectations result' (:expectations scenario)))
        outcomes-ok? (:ok? outcomes true)
        expect-ok?   (or (nil? expect) (:ok? expect))
        checks-ok?   (and outcomes-ok? expect-ok?)]
    (cond-> (assoc result'
              :expected-outcomes outcomes
              :expectations expect)
      (and (= (:outcome result') :pass) (not checks-ok?))
      (assoc :outcome :fail
             :halt-reason (if (not outcomes-ok?)
                            :expected-outcome-mismatch
                            :expectation-mismatch)
             :expectation-violations
             (vec (concat (:violations outcomes [])
                          (:violations expect [])))))))
