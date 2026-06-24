(ns resolver-sim.evidence.slashing
  "Domain-specific constructors for slashing evidence.

   Claims are now produced through resolver-sim.claims.engine/evaluate-claims
   with explicit evidence-node references, not through raw allocation-input
   tunneling."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.claims.engine :as claims-engine]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.evidence.aggregate :as agg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.economics.payoffs :as payoffs]
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

;; ── Claim evaluation evidence node ─────────────────────────────────────

(defn build-claim-evaluation-node
  "Build a lightweight evidence node carrying the allocation facts needed by
   claim evaluators. Hash is computed on the node content for deterministic
   addressing."
  [allocation-input projection-artifact allocation-result
   projection-artifact-again projection-result]
  (let [content {:claims/input-context
                 {:liable-parties (:liable-parties allocation-input [])
                  :total-basis (long (:total-basis allocation-result 0))
                  :slash-obligation (or (:slash-obligation allocation-input)
                                        (:slash-amount allocation-input)
                                        0)
                  :basis-field (:basis allocation-input :slashable-stake)
                  :cap-field (:cap-field allocation-input :available-slashable)
                  :unmet-policy (:unmet-policy allocation-input :record-only)}
                 :claims/direct-result allocation-result
                 :claims/projection-artifact projection-artifact
                 :claims/projection-artifact-again projection-artifact-again
                 :claims/projection-result projection-result}
        node-hash (hc/hash-with-intent {:hash/intent :evidence-record} content)]
    {:node-hash node-hash
     :result content
     :claims/evaluation-context true}))

(defn build-claim-requests
  "Build claim requests referencing the claim-evaluation node hash."
  [evaluation-node-hash]
  (mapv (fn [claim-id]
          {:claim-id claim-id
           :evidence-references [evaluation-node-hash]})
        (pro-rata-claims/registered-claim-ids)))

;; ── Result shaping ─────────────────────────────────────────────────────

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

;; ── Main evidence constructor ──────────────────────────────────────────

(defn build-prorata-slash-evidence
  "Build pro-rata slash evidence with embedded allocation result artifact.
   Returns {:evidence <evidence-map> :artifact <pro-rata-allocation-result-artifact>}."
  [{:keys [world
           slash-id
           workflow-id
           epoch
           trigger
           allocation-input
           projection-artifact
           allocation-result
           transition-dependencies
           attribution
           world-before-hash
           action-hash
           action-hash-at]}]
  (let [projection-artifact (or projection-artifact
                                (sew-economics/build-sew-slash-projection-artifact
                                 (cond-> allocation-input
                                   world-before-hash (assoc :world-before-hash world-before-hash)
                                   action-hash-at (assoc :action-hash-at action-hash-at))))
        projection-artifact-again (sew-economics/build-sew-slash-projection-artifact allocation-input)
        projection-result (sew-economics/calculate-sew-slash-allocation-from-projection projection-artifact)
        claim-eval-node (build-claim-evaluation-node
                         allocation-input projection-artifact allocation-result
                         projection-artifact-again projection-result)
        claim-eval-hash (:node-hash claim-eval-node)
        claim-requests (build-claim-requests claim-eval-hash)
        {:keys [claim-results]}
        (claims-engine/evaluate-claims
         claim-requests [claim-eval-node]
         {:evaluator-resolver pro-rata-claims/evaluator-resolver})
        shaped-claims (mapv (fn [cr]
                              (claim-result-entry (:claim-id cr) cr))
                            claim-results)
        world-after-hash (hc/hash-with-intent {:hash/intent :world-structure} world)
        result-artifact (payoffs/build-pro-rata-allocation-result-artifact
                         {:projection-artifact projection-artifact
                          :allocation-result allocation-result
                          :world-before-hash world-before-hash
                          :world-after-hash world-after-hash
                          :action-hash action-hash
                          :action-hash-at action-hash-at
                          :claims shaped-claims
                          :invariant-links []})
        allocation-result-hash (:allocation-result-hash result-artifact)
        allocation-hash (hc/hash-with-intent {:hash/intent :evidence-record} allocation-result)
        evidence-result {:projection (projection-summary projection-artifact)
                         :pro-rata {:intent {:id :pro-rata/slash-obligation-allocation
                                             :version 1}
                                    :projection-hash (:projection-hash projection-artifact)
                                    :allocation-hash allocation-hash
                                    :allocation-result-hash allocation-result-hash
                                    :claims shaped-claims
                                    :summary (merge (claim-summary shaped-claims)
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
    {:evidence (assoc evidence-record
                      :evidence/hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                                          (dissoc evidence-record :evidence/hash :evidence-hash)))
     :artifact result-artifact}))

(defn write-allocation-result-artifact!
  "Persist a pro-rata allocation result artifact to disk and register it in the
   chain registry. Returns the file path written."
  [artifact]
  (let [result-id (:allocation-result-id artifact)
        filename (str "allocation-result-" result-id ".json")
        out-dir (str (evcfg/artifact-dir))
        f (io/file out-dir filename)]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str artifact {:key-fn name :indent true}))
    (println "Wrote allocation result artifact:" filename)
    (chain/register-additional-artifact!
     (chain/index-artifact-entry (keyword result-id) filename
                                 "allocation-result.v1" "CORE"))
    (.getPath f)))
