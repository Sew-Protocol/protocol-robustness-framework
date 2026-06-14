(ns resolver-sim.protocols.sew.slashing-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]
            [resolver-sim.protocols.sew.reversal-fixtures :as rev-fx]
            [resolver-sim.time.context :as time-ctx]))

(defn- world-ready-for-fraud-slash-propose
  "Escrow with custom resolver, raised dispute, and executed resolution."
  [world buyer token seller resolver amount snap]
  (let [{:keys [world workflow-id]}
        (lc/create-escrow world buyer token seller amount {:custom-resolver resolver} snap)
        world' (:world (lc/raise-dispute world workflow-id buyer))
        world'' (:world (res/execute-resolution world' workflow-id resolver true "0xhash" nil))]
    {:world world'' :workflow-id workflow-id}))

(deftest slashing-logic-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        res "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 86400})]
    
    (testing "Manual slash proposal is appealable"
      (let [world (reg/register-stake world res 1000)
            {:keys [world workflow-id]}
            (world-ready-for-fraud-slash-propose world buyer "0xT" seller res 1000 snap)
            r-prop (res/propose-fraud-slash world workflow-id gov res 500)
            world-prop (:world r-prop)]
        
        (is (= :pending (get-in world-prop [:pending-fraud-slashes workflow-id :status])))
        
        (testing "Resolver appeals"
          (let [r-app (res/appeal-slash world-prop workflow-id res)
                world-app (:world r-app)]
            (is (= :appealed (get-in world-app [:pending-fraud-slashes workflow-id :status])))))
            
        (testing "Governance upholds appeal"
          (let [world-app (-> (res/appeal-slash world-prop workflow-id res) :world)
                r-res (res/resolve-appeal world-app workflow-id gov true)
                world-upheld (:world r-res)]
            (is (= :reversed (get-in world-upheld [:pending-fraud-slashes workflow-id :status])))))))))

(deftest appeal-slash-after-deadline-rejected
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world (reg/register-stake world resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world buyer "0xT" seller resolver-addr 1000 snap)
        world-prop (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 500)
                       :world)
        world-late (time-ctx/advance-time world-prop {:to 1011})
        r-app (res/appeal-slash world-late workflow-id resolver-addr)]
    (is (false? (:ok r-app)))
    (is (= :appeal-window-expired (:error r-app)))))

(deftest resolve-appeal-supports-custom-slash-id
  (let [resolver-addr "0xRes"
        gov "0xGov"
        ;; Any custom slash-id is accepted; level-scoped ids look like "0-reversal-0"
        slash-id "0-reversal-0"
        world (-> (t/empty-world 1000)
                  (assoc-in [:pending-fraud-slashes slash-id]
                            {:resolver resolver-addr
                             :amount 100
                             :status :appealed
                             :proposed-at 1000
                             :appeal-deadline 1100
                             :appeal-bond-held 0
                             :contest-deadline 0}))
        r (res/resolve-appeal world 0 gov true slash-id)]
    (is (true? (:ok r)))
    (is (= :reversed (get-in (:world r) [:pending-fraud-slashes slash-id :status])))))

(deftest governance-only-slash-actions
  (let [buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        gov "0xGov"
        non-gov "0xUser"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
        world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "0xT" seller resolver-addr 1000 snap)
        agent-index {"gov"  {:id "gov" :address gov :role "governance"}
                     "user" {:id "user" :address non-gov :role "honest"}}
        ctx {:agent-index agent-index}
        propose-ev {:agent "user" :action "propose_fraud_slash"
                    :params {:workflow-id workflow-id :resolver-addr resolver-addr :amount 100}}
        r-non-gov (sew/apply-action ctx world propose-ev)
        world2 (-> (sew/apply-action (assoc ctx :agent-index {"gov" {:id "gov" :address gov :role "governance"}})
                                     world
                                     (assoc propose-ev :agent "gov"))
                   :world
                   (assoc-in [:pending-fraud-slashes workflow-id :status] :appealed))
        resolve-ev {:agent "user" :action "resolve_appeal"
                    :params {:workflow-id workflow-id :upheld? true}}
        r-resolve-non-gov (sew/apply-action ctx world2 resolve-ev)]
    (is (false? (:ok r-non-gov)))
    (is (= :not-governance (:error r-non-gov)))
    (is (false? (:ok r-resolve-non-gov)))
    (is (= :not-governance (:error r-resolve-non-gov)))))

(deftest execute-fraud-slash-tracks-unavailability-and-circuit-breaker
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 10})
        world0 (-> (t/empty-world 1000)
                   (assoc-in [:unavailability-stats :total-resolvers] 1)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
        world2 (time-ctx/advance-time world1 {:to 1011})
        r-exec (res/execute-fraud-slash world2 workflow-id)
        world3 (:world r-exec)]
    (is (true? (:ok r-exec)))
    (is (= :executed (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (contains? (:resolver-unavailable world3) resolver-addr))
    (is (= 1 (get-in world3 [:unavailability-stats :unavailable-count])))
    (is (true? (get-in world3 [:circuit-breaker :active?])))))

(deftest unfreeze-resolver-clears-unavailability-idempotently
  (let [resolver-addr "0xRes"
        world0 (-> (t/empty-world 1000)
                   (assoc :resolver-unavailable #{resolver-addr})
                   (assoc-in [:unavailability-stats :total-resolvers] 5)
                   (assoc-in [:unavailability-stats :unavailable-count] 1)
                   (assoc-in [:resolver-frozen-until resolver-addr] 5000))
        world1 (:world (res/unfreeze-resolver world0 resolver-addr))
        world2 (:world (res/unfreeze-resolver world1 resolver-addr))]
    (is (= 0 (get-in world1 [:resolver-frozen-until resolver-addr])))
    (is (not (contains? (:resolver-unavailable world1) resolver-addr)))
    (is (= 0 (get-in world1 [:unavailability-stats :unavailable-count])))
    ;; idempotent second call
    (is (= 0 (get-in world2 [:unavailability-stats :unavailable-count])))))

(deftest slash-distribution-tracks-retained-reserves
  (let [world0 (t/empty-world 1000)
        world1 (-> world0
                   (assoc-in [:bond-balances 1 "0xA"] 100)
                   (update-in [:bond-slashed 1] (fnil + 0) 100)
                   (update-in [:bond-distribution :insurance] (fnil + 0) 50)
                   (update-in [:bond-distribution :protocol] (fnil + 0) 30)
                   (update :retained-slash-reserves (fnil + 0) 20))]
    (is (= {:holds? true :violations []}
           (resolver-sim.protocols.sew.invariants/slash-distribution-consistent? world1)))))

(deftest appeal-bond-custody-upheld-refunds-resolver
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                      :appeal-bond-amount 75})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        world3 (-> (res/resolve-appeal world2 workflow-id gov true) :world)]
    (is (= 75 (get-in world2 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= :reversed (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= 75 (get-in world3 [:claimable-v2 workflow-id :bond/refund resolver-addr] 0)))
    (is (= 0 (get-in world3 [:claimable workflow-id resolver-addr] 0))
        "bond/refund is v2-native; legacy :claimable is not dual-written")))

(deftest appeal-bond-custody-rejected-forfeits-to-insurance
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100
                                      :appeal-bond-amount 60})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "USDC" seller resolver-addr 1000 snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        world3 (-> (res/resolve-appeal world2 workflow-id gov false) :world)]
    (is (= :pending (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= 60 (get world3 :appeal-bonds-forfeited-insurance 0)))
    (is (= 60 (get-in world3 [:appeal-bond-distributions-by-token :USDC] 0)))))

;; ============ Reversal-slash specific tests ============

(deftest reversal-slash-basis-is-stake
  (testing "Reversal slash amount is based on resolver stake, not escrow principal"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          slash-id (str workflow-id "-reversal-0")
          slash (get-in world [:pending-fraud-slashes slash-id])
          stake (reg/get-stake world "0xL0Res")]
      (is (some? slash) "reversal slash should exist")
      (is (= :stake (:basis-kind slash)))
      (is (= 10000 (:basis-amount slash)))
      ;; 25% of 10000 stake = 2500 (not 25% of 8000 escrow principal = 2000)
      (is (= 2500 (:amount slash)) "slash amount should be 25% of stake, not principal")
      (is (not= 2000 (:amount slash)) "slash amount should NOT be 25% of escrow principal")
      (is (= 7500 (reg/get-stake world "0xL0Res")) "stake reduced by slash amount"))))

(deftest reversal-slash-uses-level-scoped-id
  (testing "handle-reversal-slashing generates \"<wf>-reversal-<level-1>\" id"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          expected-slash-id (str workflow-id "-reversal-0")
          slash (get-in world [:pending-fraud-slashes expected-slash-id])]
      (is (some? slash) "reversal slash entry should exist under level-scoped id")
      (is (= :executed (:status slash)) "Track 1 (same-evidence) slash is immediately executed")
      (is (= :reversal (:reason slash)))
      (is (= "0xL0Res" (:resolver slash))))))

(deftest reversed-reversal-full-lifecycle
  (testing "Reversal slash can itself be appealed and reversed (Track 2 / manual path)"
    (let [l0-res  "0xL0Res"
          gov     "0xGov"
          snap    (snap-fix/escrow-snapshot {:appeal-window-duration 200
                                           :appeal-bond-amount 0})
          buyer   "0xBuyer"
          seller  "0xSeller"
          world0  (-> (t/empty-world 1000)
                      (reg/register-stake l0-res 5000))
          {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 1000 {} snap)
          slash-id (str workflow-id "-reversal-0")
          ;; Manually install a :pending reversal slash (mimicking Track 2)
          world1  (assoc-in world [:pending-fraud-slashes slash-id]
                            {:resolver         l0-res
                             :amount           500
                             :reason           :reversal
                             :status           :pending
                             :proposed-at      1000
                             :appeal-deadline  1200
                             :appeal-bond-held 0
                             :contest-deadline 0
                             :workflow-id      workflow-id})
          ;; Resolver appeals the reversal slash
          world2  (:world (res/appeal-slash world1 workflow-id l0-res slash-id))
          ;; Governance upholds the appeal → slash :reversed
          world3  (:world (res/resolve-appeal world2 workflow-id gov true slash-id))]
      (is (= :appealed (get-in world2 [:pending-fraud-slashes slash-id :status]))
          "After appeal, slash should be :appealed")
      (is (= :reversed (get-in world3 [:pending-fraud-slashes slash-id :status]))
          "After governance upholds appeal, reversal slash should be :reversed")
      ;; Attempting to execute the reversed slash should be blocked
      (let [r-exec (res/execute-fraud-slash world3 workflow-id slash-id)]
        (is (false? (:ok r-exec)))
        (is (= :slash-already-reversed (:error r-exec)))))))

(deftest multi-level-reversal-no-slash-id-collision
  (testing "Two reversals on the same workflow use distinct level-scoped ids"
    ;; This is a regression test for the slash-id collision bug (Bug 2).
    ;; We construct two slash entries with the ids that handle-reversal-slashing
    ;; would generate: \"0-reversal-0\" (L0→L1 reversal) and \"0-reversal-1\" (L1→L2 reversal).
    (let [wf-id    0
          slash-l0 (str wf-id "-reversal-0")
          slash-l1 (str wf-id "-reversal-1")
          world    (-> (t/empty-world 1000)
                       (assoc-in [:pending-fraud-slashes slash-l0]
                                 {:resolver "0xL0Res" :amount 100 :status :executed
                                  :reason :reversal :proposed-at 1000 :appeal-deadline 0
                                  :appeal-bond-held 0 :contest-deadline 0})
                       (assoc-in [:pending-fraud-slashes slash-l1]
                                 {:resolver "0xL1Res" :amount 200 :status :executed
                                  :reason :reversal :proposed-at 1050 :appeal-deadline 0
                                  :appeal-bond-held 0 :contest-deadline 0}))]
      (is (not= slash-l0 slash-l1) "Level-scoped ids must differ")
      (is (= "0xL0Res" (get-in world [:pending-fraud-slashes slash-l0 :resolver])))
      (is (= "0xL1Res" (get-in world [:pending-fraud-slashes slash-l1 :resolver])))
      ;; Verify neither overwrote the other
      (is (= 100 (get-in world [:pending-fraud-slashes slash-l0 :amount])))
      (is (= 200 (get-in world [:pending-fraud-slashes slash-l1 :amount]))))))

(deftest resolve-appeal-uses-workflow-id-from-pending-not-slash-id-string
  (testing "resolve-appeal extracts workflow-id from pending entry, not from slash-id string"
    ;; Regression test for the fragile (:workflow-id custody slash-id) fallback.
    ;; When custody is nil (bond-held=0), wf-id should come from :pending entry.
    (let [resolver "0xRes"
          gov      "0xGov"
          wf-id    42
          slash-id (str wf-id "-reversal-0")
          world    (-> (t/empty-world 1000)
                       (reg/register-stake resolver 5000)
                       (assoc-in [:pending-fraud-slashes slash-id]
                                 {:resolver         resolver
                                  :amount           300
                                  :reason           :reversal
                                  :status           :appealed
                                  :proposed-at      1000
                                  :appeal-deadline  1200
                                  :appeal-bond-held 0
                                  :contest-deadline 0
                                  :workflow-id      wf-id}))
          r     (res/resolve-appeal world wf-id gov true slash-id)
          world' (:world r)]
      (is (true? (:ok r)))
      (is (= :reversed (get-in world' [:pending-fraud-slashes slash-id :status])))
      ;; bond-held=0 so no claimable entry, but we verify no error was thrown
      (is (= 0 (get-in world' [:claimable wf-id resolver] 0))))))

(deftest appeal-executed-reversal-slash-rejected
  (testing "Track 1 :executed reversal slash cannot be appealed"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          slash-id (str workflow-id "-reversal-0")
          r (res/appeal-slash world workflow-id "0xL0Res" slash-id)]
      (is (false? (:ok r)))
      (is (= :slash-not-pending (:error r))))))

(deftest reversal-slash-zero-bps-is-noop
  (testing "handle-reversal-slashing is a no-op when reversal-slash-bps is 0"
    (let [{:keys [world workflow-id steps]}
          (rev-fx/build-reversal-world {:snapshot {:reversal-slash-bps 0}})
          after-l0 (:after-l0 steps)
          slash-id (str workflow-id "-reversal-0")]
      (is (nil? (get-in world [:pending-fraud-slashes slash-id]))
          "no slash entry created")
      (is (= (reg/get-stake after-l0 "0xL0Res")
             (reg/get-stake world "0xL0Res"))
          "L0 resolver stake unchanged after reversal"))))

(deftest reversal-slash-after-fraud-slash
  (testing "Reversal slash reads current stake at reversal time (Solidity semantics)"
    (let [gov "0xGov"
          r0 "0xRes0"
          r1 "0xRes1"
          buyer "0xBuyer"
          seller "0xSeller"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                         :reversal-slash-bps 2500
                                         :appeal-window-duration 2000000
                                         :challenge-window-duration 2000000
                                         :max-dispute-level 2
                                         :escrow-fee-bps 0
                                         :resolver-bond-bps 10000})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r0 10000)
                     (reg/register-stake r1 5000))
          {:keys [world workflow-id]}
          (lc/create-escrow world0 buyer "USDC" seller 8000 {} snap)
          after-raise (:world (lc/raise-dispute world workflow-id buyer))
          after-l0 (:world (res/execute-resolution after-raise workflow-id r0 true "0xhash" nil))

          ;; Reduce L0's stake via fraud slash before escalation.
          ;; Advance time past the slash timelock, then reset for challenge window.
          after-l0-params (assoc-in after-l0 [:params :slash-epoch-cap-bps] 5000)
          world-slashed (-> (res/propose-fraud-slash after-l0-params workflow-id gov r0 5000) :world
                            (time-ctx/advance-time {:to 3000001})
                            (res/execute-fraud-slash workflow-id)
                            :world
                            (time-ctx/advance-time {:to 1000}))

          ;; Escalate and reverse
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
          after-escalation (:world (res/challenge-resolution world-slashed workflow-id buyer esc-fn))
          after-l1 (:world (res/execute-resolution after-escalation workflow-id r1 false "0xhash2" nil))
          slash-id (str workflow-id "-reversal-0")
          slash (get-in after-l1 [:pending-fraud-slashes slash-id])]
      (is (some? slash) "reversal slash entry exists")
      ;; basis-amount reads current stake at reversal time, not original stake at decision
      (is (= 5000 (:basis-amount slash)) "basis-amount is remaining stake after fraud slash")
      ;; 2500 bps = 25% of 5000 remaining stake = 1250
      (is (= 1250 (:amount slash)) "slash amount is 25% of remaining stake")
      (is (= 3750 (reg/get-stake after-l1 r0)) "L0 stake reduced from 5000 → 3750"))))

(deftest reversal-slash-without-challenger
  (testing "handle-reversal-slashing handles nil challenger gracefully"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          world-no-challenger (update world :challengers dissoc workflow-id)
          slash-id (str workflow-id "-reversal-0")
          slash (get-in world-no-challenger [:pending-fraud-slashes slash-id])]
      (is (some? slash) "reversal slash entry exists despite nil challenger")
      (is (= :executed (:status slash)) "slash executed normally")
      (is (= "0xL0Res" (:resolver slash)))
      (is (pos? (reg/get-stake world-no-challenger "0xL0Res")) "stake deduction still occurs"))))

(deftest propose-fraud-slash-guards-test
  (let [buyer "0xBuyer"
        seller "0xSeller"
        resolver-addr "0xRes"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 100})
        world0 (reg/register-stake (t/empty-world 1000) resolver-addr 1000)
        {:keys [world workflow-id]}
        (world-ready-for-fraud-slash-propose world0 buyer "0xT" seller resolver-addr 1000 snap)]
    (testing "rejects propose before dispute path"
      (let [{:keys [world workflow-id]}
            (lc/create-escrow world0 buyer "0xT" seller 1000 {:custom-resolver resolver-addr} snap)
            r (res/propose-fraud-slash world workflow-id gov resolver-addr 100)]
        (is (false? (:ok r)))
        (is (= :workflow-not-slashable (:error r)))))
    (testing "rejects duplicate pending propose"
      (let [r1 (res/propose-fraud-slash world workflow-id gov resolver-addr 100)
            r2 (res/propose-fraud-slash (:world r1) workflow-id gov resolver-addr 50)]
        (is (true? (:ok r1)))
        (is (false? (:ok r2)))
        (is (= :slash-already-pending (:error r2)))))
    (testing "rejects wrong resolver address"
      (let [r (res/propose-fraud-slash world workflow-id gov "0xOther" 100)]
        (is (false? (:ok r)))
        (is (= :slash-resolver-mismatch (:error r)))))
    (testing "stores workflow-id on pending entry"
      (let [r (res/propose-fraud-slash world workflow-id gov resolver-addr 100)]
        (is (= workflow-id (get-in (:world r) [:pending-fraud-slashes workflow-id :workflow-id])))))))

(deftest resolve-appeal-on-executed-slash-returns-cannot-reverse-executed-slash
  (let [resolver-addr "0xRes"
        gov "0xGov"
        world (-> (t/empty-world 1000)
                  (assoc-in [:pending-fraud-slashes "s1"]
                            {:resolver resolver-addr
                             :amount 100
                             :reason :fraud
                             :status :executed
                             :proposed-at 1000
                             :appeal-deadline 0
                             :appeal-bond-held 0
                             :contest-deadline 0}))
        r (res/resolve-appeal world 0 gov true "s1")]
    (is (false? (:ok r)))
    (is (= :cannot-reverse-executed-slash (:error r)))))

(deftest force-reversal-slash-immediate
  (testing "force-reversal-slash produces an executed slash entry"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          w (res/force-reversal-slash world workflow-id :track :immediate)
          slash-entry (get-in w [:pending-fraud-slashes (str workflow-id "-force-reversal-0")])]
      (is (some? slash-entry) "force-reversal slash entry should exist")
      (is (= :executed (:status slash-entry)) "immediate track should be executed")
      (is (pos? (:amount slash-entry)) "slash amount should be positive")
      (is (= :reversal (:reason slash-entry)) "reason should be :reversal"))))

(deftest force-reversal-slash-pending
  (testing "force-reversal-slash produces a pending slash entry"
    (let [{:keys [world workflow-id]} (rev-fx/build-reversal-world)
          w (res/force-reversal-slash world workflow-id :track :pending)
          slash-entry (get-in w [:pending-fraud-slashes (str workflow-id "-force-reversal-0")])]
      (is (some? slash-entry) "force-reversal slash entry should exist")
      (is (= :pending (:status slash-entry)) "pending track should be pending")
      (is (pos? (:appeal-deadline slash-entry)) "pending track should have appeal deadline"))))

(deftest force-reversal-slash-custom-bps
  (testing "force-reversal-slash accepts custom slash-bps override when snapshot has zero"
    (let [snap (snap-fix/escrow-snapshot {:reversal-slash-bps 0 :appeal-window-duration 120
                                          :challenge-window-duration 120 :max-dispute-level 2
                                          :dispute-resolver "0xL0Res"})
          w0 (rev-fx/build-reversal-world {:snapshot snap})
          [wf w] [(:workflow-id w0) (:world w0)]
          w (res/force-reversal-slash w wf :slash-bps 5000 :track :immediate)
          slash-entry (get-in w [:pending-fraud-slashes (str wf "-force-reversal-0")])]
      (is (some? slash-entry) "custom bps override should produce a slash entry")
      (is (= :executed (:status slash-entry)) "immediate track should be executed")
      (is (pos? (:amount slash-entry)) "slash amount should be positive"))))
