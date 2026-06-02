(ns resolver-sim.contract-model.replay.validation
  "Scenario validation logic for replay engine.

   Decomposed from contract-model/replay to improve kernel modularity."
  (:require [clojure.string :as str]
            [resolver-sim.contract-model.replay.metrics :as metrics]
            [resolver-sim.scenario.schema-profile :as schema-profile]))

(def ^:private supported-versions (:supported-versions schema-profile/default-profile))

(defn validate-agents
  "Validate a list of agent maps {:id :address :role :strategy ...} for structural correctness.
   Returns {:ok true} or {:ok false :error kw :detail {...}}.

   Checks: non-empty, unique :id values, unique :address values."
  [agents]
  (let [id-counts   (frequencies (map :id agents))
        addr-counts (frequencies (map :address agents))]
    (cond
      (empty? agents)
      {:ok false :error :empty-agent-list}

      (seq (filter #(> (val %) 1) id-counts))
      {:ok false :error :duplicate-agent-id :detail (keys (filter #(> (val %) 1) id-counts))}

      (seq (filter #(> (val %) 1) addr-counts))
      {:ok false :error :duplicate-agent-address :detail (keys (filter #(> (val %) 1) addr-counts))}

      :else {:ok true})))

(defn validate-scenario
  "Validate a scenario map for structural correctness before replay.
   Accepts an optional effective-metrics set used to validate metric references
   in :expectations and :theory. Defaults to base-metrics (universal counters)."
  ([scenario] (validate-scenario scenario metrics/base-metrics))
  ([scenario effective-metrics]
   (let [version     (str (:schema-version scenario))
         agents      (:agents scenario)
         events      (sort-by :seq (:events scenario))
         known-ids   (set (map :id agents))
         init-time   (get scenario :initial-block-time 1000)
         agent-check (validate-agents agents)]
     (cond
       (not (contains? supported-versions version))
       {:ok false :error :unsupported-schema-version
        :detail {:expected supported-versions :got version}}

       (and (contains? (set (schema-profile/required-fields version)) :id)
            (not (:id scenario)))
       {:ok false :error :missing-id :detail "v1.1 scenarios must have a unique :id"}

       (and (contains? (set (schema-profile/required-fields version)) :title)
            (not (:title scenario)))
       {:ok false :error :missing-title :detail "v1.1 scenarios must have a human-readable :title"}

       (and (contains? (set (schema-profile/required-fields version)) :purpose)
            (not (:purpose scenario)))
       {:ok false :error :missing-purpose :detail "v1.1 scenarios must declare a :purpose"}

       (and (contains? (set (schema-profile/required-fields version)) :scenario-author)
            (not (string? (:scenario-author scenario))))
       {:ok false :error :missing-scenario-author
        :detail "v1.1 scenarios must include :scenario-author as a non-empty string"}

       (and (contains? (set (schema-profile/required-fields version)) :scenario-author)
            (str/blank? (:scenario-author scenario)))
       {:ok false :error :blank-scenario-author
        :detail ":scenario-author must not be blank"}

       ;; Purpose-based requirements are enforced for enriched schemas only
       ;; (v1.1+). Legacy v1.0 scenario packs are intentionally tolerated.
       (and (contains? (set (schema-profile/required-fields version)) :purpose)
            (schema-profile/requires-theory? (:purpose scenario))
            (not (:theory scenario)))
       {:ok false :error :theory-required
        :detail "purpose :theory-falsification requires a :theory block"}

       ;; :adversarial-robustness scenarios must include :theory or meaningful :expectations
       (and (contains? (set (schema-profile/required-fields version)) :purpose)
            (schema-profile/requires-theory-or-expectations? (:purpose scenario))
            (not (:theory scenario))
            (not (:expectations scenario)))
       {:ok false :error :theory-or-expectations-required
        :detail "purpose :adversarial-robustness requires either a :theory block or :expectations"}

       (not (:ok agent-check))
       agent-check

       :else {:ok true}))))
