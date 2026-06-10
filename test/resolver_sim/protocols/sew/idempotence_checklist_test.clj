(ns resolver-sim.protocols.sew.idempotence-checklist-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.accounting :as acct]))

(def alice "0xAlice")
(def bob "0xBob")
(def resolver "0xResolver")
(def usdc "0xUSDC")

(defn- base-world
  [appeal-window-duration]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50
                                      :max-dispute-duration 3600
                                      :appeal-window-duration appeal-window-duration})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000
                               (t/make-escrow-settings {}) snap)
        w    (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
        (assoc-in [:dispute-timestamps 0] 1000))))

(deftest checklist-clear-claimable-v2-kind-idempotent
  (let [w0 (-> (t/empty-world 1000)
               (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
               (assoc-in [:claimable 0 bob] 100))
        w1 (acct/clear-claimable-v2-kind w0 0 :settlement/principal)
        w2 (acct/clear-claimable-v2-kind w1 0 :settlement/principal)]
    (is (nil? (get-in w1 [:claimable-v2 0 :settlement/principal bob])))
    (is (nil? (get-in w2 [:claimable-v2 0 :settlement/principal bob])))
    (is (nil? (get-in w2 [:claimable-v2 0 :settlement/principal nil]))
        "clear helper must not synthesize nil claimant keys")
    (is (empty? (get-in w2 [:claimable 0] {}))
        "legacy principal mirror remains clear after repeated cleanup")))

(deftest checklist-unfreeze-resolver-idempotent
  (let [w0 (-> (t/empty-world 1000)
               (assoc-in [:resolver-frozen-until resolver] 2000)
               (update :resolver-unavailable conj resolver))
        w1 (:world (res/unfreeze-resolver w0 resolver))
        w2 (:world (res/unfreeze-resolver w1 resolver))]
    (is (= 0 (get-in w1 [:resolver-frozen-until resolver])))
    (is (= 0 (get-in w2 [:resolver-frozen-until resolver])))
    (is (not (contains? (:resolver-unavailable w2) resolver)))))

(deftest checklist-execute-fraud-slash-single-execution
  (let [w0 (-> (t/empty-world 1000)
               (assoc-in [:resolver-stakes resolver] 5000)
               (assoc-in [:escrow-transfers 0] {:token usdc})
               (assoc-in [:pending-fraud-slashes 0]
                         {:resolver resolver
                          :amount 100
                          :reason :fraud
                          :status :pending
                          :proposed-at 900
                          :appeal-deadline 999
                          :appeal-bond-held 0
                          :contest-deadline 0}))
        r1 (res/execute-fraud-slash w0 0)
        r2 (res/execute-fraud-slash (:world r1) 0)]
    (is (:ok r1))
    (is (false? (:ok r2)))
    (is (= :already-executed (:error r2)))))

(deftest checklist-execute-pending-settlement-single-finalization
  (let [w0 (-> (base-world 120)
               (assoc :block-ts (java.time.Instant/ofEpochSecond 1240))
               (assoc-in [:pending-settlements 0]
                         (t/make-pending-settlement {:exists true
                                                     :is-release true
                                                     :appeal-deadline 1240
                                                     :resolution-hash "0xhash"})))
        r1 (res/execute-pending-settlement w0 0)
        r2 (res/execute-pending-settlement (:world r1) 0)]
    (is (:ok r1))
    (is (= :released (t/escrow-state (:world r1) 0)))
    (is (false? (:ok r2)))
    (is (= :no-pending-settlement (:error r2)))))

(deftest checklist-superseded-pending-single-finalization
  (let [w0 (-> (base-world 120)
               (assoc :block-ts (java.time.Instant/ofEpochSecond 1300))
               (assoc-in [:pending-settlements 0]
                         (t/make-pending-settlement {:exists true
                                                     :is-release true
                                                     :appeal-deadline 1300
                                                     :resolution-hash "0xhash"})))
        ;; Archive the active pending (simulating escalation), then clear it
        w1 (-> w0
               (update :pending-settlements dissoc 0)
               (update-in [:superseded-pending-settlements 0]
                          (fnil conj [])
                          {:pending (t/make-pending-settlement {:exists true
                                                                :is-release true
                                                                :appeal-deadline 1300
                                                                :resolution-hash "0xhash"})
                           :superseded-at 1180
                           :level 0}))
        ;; First execute — should succeed via superseded fallback
        r1 (res/execute-pending-settlement w1 0)
        ;; Second execute — should fail, escrow now terminal
        r2 (res/execute-pending-settlement (:world r1) 0)]
    (is (:ok r1))
    (is (= :released (t/escrow-state (:world r1) 0)))
    (is (false? (:ok r2)))
    (is (= :transfer-not-in-dispute (:error r2)))))
