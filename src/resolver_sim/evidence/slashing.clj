(ns resolver-sim.evidence.slashing
  "Domain-specific constructors for slashing evidence."
  (:require [resolver-sim.evidence.aggregate :as agg]))

;; ── Extractors ──────────────────────────────────────────────────────────

(defn extract-slash-context
  "Extract slash-specific context."
  [world slash-id workflow-id epoch trigger]
  {:slash-id slash-id
   :workflow-id workflow-id
   :epoch epoch
   :trigger trigger})

(defn extract-strategy-context
  "Extract strategy context for all resolvers in the world."
  [world]
  {:schema-version "strategy-context.v1"
   :snapshot-time (:block-time world)
   :snapshot-step (:step world 0)
   :resolvers
   (map (fn [[id resolver]]
          {:resolver-id id
           :strategy (:strategy resolver)
           :strategy-source (:strategy-source resolver :unknown)
           :strategy-since-step (:strategy-since-step resolver 0)
           :slashable-balance (:slashable-stake resolver 0)})
        (:resolver-stakes world))})

;; ── Constructor ────────────────────────────────────────────────────────

(defn build-prorata-slash-evidence
  [{:keys [world
           slash-id
           workflow-id
           epoch
           trigger
           allocation-input
           allocation-result
           transition-dependencies
           attribution]}]
  (agg/build-evidence-aggregate
   {:evidence-type :slash/prorata-allocation
    :schema-version "slash-prorata-allocation.v2"

    :world world
    :frame (agg/extract-decision-frame world)
    :subject {:slash-id slash-id
              :workflow-id workflow-id
              :epoch epoch
              :trigger trigger}

    :inputs {:allocation allocation-input
             :strategy/context (extract-strategy-context world)
             :slash/context (extract-slash-context world slash-id workflow-id epoch trigger)}

    :result allocation-result

    :dependencies transition-dependencies
    :attribution attribution}))
