(ns resolver-sim.protocols.sew.invariant-registry-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.types :as t]))

(def ^:private snap (t/make-module-snapshot {:escrow-fee-bps 50}))

(defn- sample-world []
  (:world (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" 1000
                            (t/make-escrow-settings {}) snap)))

(deftest canonical-ids-union
  (is (= inv/canonical-ids
         (set/union inv/world-invariant-ids inv/transition-invariant-ids))))

(deftest world-invariant-ids-match-check-all
  (testing "every world invariant ID is executed by check-all"
    (let [world  (sample-world)
          run-ids (set (keys (:results (inv/check-all world))))]
      (is (= inv/world-invariant-ids run-ids)
          (str "drift: check-all=" (sort run-ids)
               " registry=" (sort inv/world-invariant-ids))))))

(deftest transition-invariant-ids-match-check-transition
  (testing "every transition invariant ID is executed by check-transition"
    (let [w0     (sample-world)
          w1     (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
          run-ids (set (keys (:results (inv/check-transition w0 w1))))]
      (is (= inv/transition-invariant-ids run-ids)
          (str "drift: check-transition=" (sort run-ids)
               " registry=" (sort inv/transition-invariant-ids))))))

(deftest no-stale-canonical-ids
  (testing "canonical-ids has no orphans outside the two check runners"
    (let [world  (sample-world)
          w1     (assoc-in world [:escrow-transfers 0 :escrow-state] :released)
          executed (set/union (set (keys (:results (inv/check-all world))))
                                (set (keys (:results (inv/check-transition world w1)))))]
      (is (set/subset? inv/canonical-ids executed))
      (is (set/subset? executed inv/canonical-ids)))))

(deftest persisted-escrow-state-valid-rejects-none
  (let [world (assoc-in (sample-world) [:escrow-transfers 0 :escrow-state] :none)]
    (is (not (:holds? (inv/persisted-escrow-state-valid? world))))
    (is (= [{:workflow-id 0
             :escrow-state :none
             :allowed #{:pending :disputed :released :refunded :resolved}}]
           (:violations (inv/persisted-escrow-state-valid? world))))))

(deftest dispute-level-bounded-rejects-pending-with-level
  (let [world (-> (sample-world)
                  (assoc-in [:dispute-levels 0] 1))]
    (is (not (:holds? (inv/dispute-level-bounded? world))))
    (is (= :dispute-level-on-non-disputed
           (:reason (first (:violations (inv/dispute-level-bounded? world))))))))

(deftest dispute-level-bounded-allows-terminal-with-level
  (let [world (-> (sample-world)
                  (assoc-in [:escrow-transfers 0 :escrow-state] :released)
                  (assoc-in [:dispute-levels 0] 1))]
    (is (:holds? (inv/dispute-level-bounded? world)))))
