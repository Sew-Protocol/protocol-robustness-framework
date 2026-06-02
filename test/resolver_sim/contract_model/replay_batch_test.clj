(ns resolver-sim.contract-model.replay-batch-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]))

(defrecord BatchTestProtocol []
  proto/SimulationAdapter
  (protocol-id [_] "batch-test")
  (init-world [_ _] {:block-time 1000 :flag false :applied []})
  (build-execution-context [_ agents _] {:agent-index (into {} (map (juxt :id identity) agents))})
  (dispatch-action [_ _ world event]
    (case (:action event)
      "set-flag" {:ok true :world (-> world (assoc :flag true) (update :applied conj (:seq event)))}
      "guarded-write" (if (:flag world)
                        {:ok false :error :guard-failed}
                        {:ok true :world (-> world (assoc :flag true) (update :applied conj (:seq event)))})
      "touch" {:ok true :world (update world :applied conj (:seq event))}
      {:ok false :error :unknown-action}))
  (check-invariants-single [_ _] {:ok? true})
  (check-invariants-transition [_ _ _] {:ok? true})
  (world-snapshot [_ world] (select-keys world [:block-time :flag :applied]))
  (available-actions [_ _ _] [])
  (resolve-id-alias [_ event _] {:ok true :event event})
  (created-id [_ _ _] nil)
  (open-entities [_ _] [])
  (project-state [_ _ _] nil)

  proto/BatchConflictModel
  (event-conflict-domains [_ _ event]
    (set (get-in event [:params :domains] #{[:global :unknown]}))))

(def ^:private protocol (->BatchTestProtocol))

(defn- base-scenario
  [events]
  {:schema-version "1.1"
   :id "batch-test"
   :scenario-id "batch-test"
   :title "Batch test"
   :scenario-author "@tests"
   :purpose :regression
   :agents [{:id "a" :address "0xa" :role "actor"}
            {:id "b" :address "0xb" :role "actor"}]
   :events events
   :execution-mode :deterministic-batch
   :theory {:claim-id :test/batch :assumptions [] :falsifies-if [{:metric :reverts :op :>= :value 0}]}})

(deftest preflight-eligible-but-commit-guard-fails
  (let [result (replay/replay-with-protocol
                protocol
                (base-scenario
                 [{:seq 0 :time 1000 :agent "a" :action "set-flag"
                   :params {:domains #{[:workflow 1]}}}
                  {:seq 1 :time 1000 :agent "b" :action "guarded-write"
                   :params {:domains #{[:workflow 2]}}}]))
        e1 (some #(when (= 1 (:seq %)) %) (:trace result))
        checkpoint (some #(when (= :post-batch (:invariant-phase %)) %) (:trace result))]
    (is (= :pass (:outcome result)))
    (is (= :eligible (:preflight-status e1)))
    (is (= :rejected (:result e1)))
    (is (= :guard-failed (:error e1)))
    (is (= :batch-commit (:reject-phase e1)))
    (is (= :ok (:result checkpoint)))
    (is (= 1 (:batch-buckets (:metrics result))))
    (is (= 2 (:batch-events (:metrics result))))))

(deftest multi-domain-intersection-rejects-as-batch-conflict
  (let [result (replay/replay-with-protocol
                protocol
                (base-scenario
                 [{:seq 0 :time 1000 :agent "a" :action "touch"
                   :params {:domains #{[:workflow 1] [:resolver "r1"]}}}
                  {:seq 1 :time 1000 :agent "b" :action "touch"
                   :params {:domains #{[:resolver "r1"]}}}]))
        e1 (some #(when (= 1 (:seq %)) %) (:trace result))]
    (testing "any shared domain intersects and rejects second event"
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "r1"] (:conflict-domain e1)))
      (is (= 0 (:conflict-with-seq e1)))
      (is (= :eligible (:preflight-status e1)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))
