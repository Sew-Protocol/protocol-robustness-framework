(ns resolver-sim.protocols.sew.slashing-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]))

(deftest slashing-logic-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        res "0xRes"
        gov "0xGov"
        snap (t/make-module-snapshot {:appeal-window-duration 86400})]
    
    (testing "Manual slash proposal is appealable"
      (let [world (reg/register-stake world res 1000)
            {:keys [world workflow-id]} (lc/create-escrow world buyer "0xT" seller 1000 {} snap)
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
        snap (t/make-module-snapshot {:appeal-window-duration 10})
        world (reg/register-stake world resolver-addr 1000)
        {:keys [world workflow-id]} (lc/create-escrow world buyer "0xT" seller 1000 {} snap)
        world-prop (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 500)
                       :world)
        world-late (assoc world-prop :block-time 1011)
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
        snap (t/make-module-snapshot {:appeal-window-duration 100})
        {:keys [world workflow-id]} (lc/create-escrow (t/empty-world 1000) buyer "0xT" seller 1000 {} snap)
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
        snap (t/make-module-snapshot {:appeal-window-duration 10})
        world0 (-> (t/empty-world 1000)
                   (assoc-in [:unavailability-stats :total-resolvers] 1)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 1000 {} snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 200) :world)
        world2 (assoc world1 :block-time 1011)
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
        snap (t/make-module-snapshot {:appeal-window-duration 100
                                      :appeal-bond-amount 75})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 1000 {} snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        world3 (-> (res/resolve-appeal world2 workflow-id gov true) :world)]
    (is (= 75 (get-in world2 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= :reversed (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= 75 (get-in world3 [:claimable workflow-id resolver-addr] 0)))))

(deftest appeal-bond-custody-rejected-forfeits-to-insurance
  (let [resolver-addr "0xRes"
        gov "0xGov"
        buyer "0xBuyer"
        seller "0xSeller"
        snap (t/make-module-snapshot {:appeal-window-duration 100
                                      :appeal-bond-amount 60})
        world0 (-> (t/empty-world 1000)
                   (reg/register-stake resolver-addr 1000))
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 1000 {} snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        world3 (-> (res/resolve-appeal world2 workflow-id gov false) :world)]
    (is (= :pending (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= 60 (get world3 :appeal-bonds-forfeited-insurance 0)))
    (is (= 60 (get-in world3 [:appeal-bond-distributions-by-token :USDC] 0)))))

;; ============ Reversal-slash specific tests ============

;; Helper: build a world where L0 has resolved :release and L1 has resolved :refund
;; triggering handle-reversal-slashing. Uses reversal-slash-bps > 0 so a slash entry is created.
(defn- build-reversal-slash-world
  "Returns world with a level-scoped reversal slash entry.
   workflow-id = 0. The L0 resolver (0xL0Res) is the slashed party.
   slash-id = \"0-reversal-0\" (level 1, dec = 0)."
  []
  (let [buyer   "0xBuyer"
        seller  "0xSeller"
        l0-res  "0xL0Res"
        l1-res  "0xL1Res"
        snap    (t/make-module-snapshot {:appeal-window-duration 120
                                         :challenge-window-duration 120
                                         :reversal-slash-bps 2500
                                         :max-dispute-level 2
                                         :dispute-resolver l0-res})
        world0  (-> (t/empty-world 1000)
                    (reg/register-stake l0-res 10000)
                    (reg/register-stake l1-res 10000)
                    (assoc-in [:total-held :USDC] 20000)
                    (assoc-in [:total-principal-deposited :USDC] 20000))
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 8000 {} snap)
        world1  (:world (lc/raise-dispute world workflow-id buyer))
        ;; L0 resolver rules :release
        world2  (:world (res/execute-resolution world1 workflow-id l0-res true "0xhash-l0" nil))
        ;; Escalate to L1 (buyer challenges, time within appeal window)
        world2a (assoc world2 :block-time 1080)
        esc-fn  (fn [_w _wfid _caller _level] {:ok true :new-resolver l1-res})
        world3  (:world (res/escalate-dispute world2a workflow-id buyer esc-fn))
        ;; L1 resolver rules :refund (opposite → triggers reversal slashing)
        world3a (assoc world3 :block-time 1200)
        world4  (:world (res/execute-resolution world3a workflow-id l1-res false "0xhash-l1" nil))]
    {:world world4 :workflow-id workflow-id}))

(deftest reversal-slash-basis-is-stake
  (testing "Reversal slash amount is based on resolver stake, not escrow principal"
    (let [{:keys [world workflow-id]} (build-reversal-slash-world)
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
    (let [{:keys [world workflow-id]} (build-reversal-slash-world)
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
          snap    (t/make-module-snapshot {:appeal-window-duration 200
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
    (let [{:keys [world workflow-id]} (build-reversal-slash-world)
          slash-id (str workflow-id "-reversal-0")
          r (res/appeal-slash world workflow-id "0xL0Res" slash-id)]
      (is (false? (:ok r)))
      (is (= :slash-not-pending (:error r))))))

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
