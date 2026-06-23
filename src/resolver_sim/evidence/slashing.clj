(ns resolver-sim.evidence.slashing
  "Domain-specific constructors for slashing evidence."
  (:require [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.evidence.aggregate :as agg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.yield.pro-rata-claims :as pro-rata-claims]))

;; ── Extractors ──────────────────────────────────────────────────────────

(defn extract-slash-context
  "Extract slash-specific context."
  [slash-id workflow-id epoch trigger]
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
   (mapv (fn [[id resolver]]
           {:resolver-id id
            :strategy (:strategy resolver)
            :strategy-source (:strategy-source resolver :unknown)
            :strategy-since-step (:strategy-since-step resolver 0)
            :slashable-balance (:slashable-stake resolver 0)})
         (sort-by first (:resolver-stakes world)))})

;; ── Constructor ────────────────────────────────────────────────────────

(defn- claim-definition-by-id
  [claim-id]
  (some #(when (= claim-id (:id %)) %)
        (:claim-definitions registries/claim-definition-registry)))

(defn- claim-result-entry
  [claim-id claim-result]
  (let [definition (claim-definition-by-id claim-id)
        base {:claim-id claim-id
              :claim-definition-hash (:canonical-hash definition)
              :holds? (boolean (:holds? claim-result))
              :violations (vec (:violations claim-result))
              :status (if (:holds? claim-result) :pass :fail)}]
    (assoc base
           :claim-result-hash (hc/hash-with-intent {:hash/intent :evidence-record} base))))

(defn- claim-summary
  [claim-results]
  {:claim-count (count claim-results)
   :passed-count (count (filter :holds? claim-results))
   :failed-count (count (remove :holds? claim-results))
   :holds? (every? :holds? claim-results)})

(defn- projection-summary
  [projection-artifact]
  (select-keys projection-artifact
               [:projection-hash :projection-definition-hash :summary]))

(defn- pro-rata-summary
  [allocation-result]
  (select-keys allocation-result
               [:total-requested :total-allocated :total-unmet :remainder :policy]))

(defn build-prorata-slash-evidence
  [{:keys [world
           slash-id
           workflow-id
           epoch
           trigger
           allocation-input
           projection-artifact
           allocation-result
           transition-dependencies
           attribution]}]
  (let [projection-artifact (or projection-artifact
                                (sew-economics/build-sew-slash-projection-artifact allocation-input))
        claim-results-by-id (pro-rata-claims/evaluate-all {:sew-slash-input allocation-input})
        claim-results (mapv (fn [claim-id]
                              (claim-result-entry claim-id (get claim-results-by-id claim-id)))
                            (pro-rata-claims/registered-claim-ids))
        allocation-hash (hc/hash-with-intent {:hash/intent :evidence-record} allocation-result)
        evidence-result {:projection (projection-summary projection-artifact)
                         :pro-rata {:intent {:id :pro-rata/slash-obligation-allocation
                                             :version 1}
                                    :projection-hash (:projection-hash projection-artifact)
                                    :allocation-hash allocation-hash
                                    :claims claim-results
                                    :summary (merge (claim-summary claim-results)
                                                    (pro-rata-summary allocation-result))}
                         :allocation allocation-result}
        evidence-record (agg/build-evidence-aggregate
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
                                   :slash/context (extract-slash-context slash-id workflow-id epoch trigger)}

                          :result evidence-result

                          :dependencies transition-dependencies
                          :attribution attribution})]
    (assoc evidence-record
           :evidence/hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                               (dissoc evidence-record :evidence/hash :evidence-hash)))))
