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
        slash-id "wf0-reversal"
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
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "0xT" seller 1000 {} snap)
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
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "0xT" seller 1000 {} snap)
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
        {:keys [world workflow-id]} (lc/create-escrow world0 buyer "0xT" seller 1000 {} snap)
        world1 (-> (res/propose-fraud-slash world workflow-id gov resolver-addr 100) :world)
        world2 (-> (res/appeal-slash world1 workflow-id resolver-addr) :world)
        world3 (-> (res/resolve-appeal world2 workflow-id gov false) :world)]
    (is (= :pending (get-in world3 [:pending-fraud-slashes workflow-id :status])))
    (is (= 0 (get-in world3 [:pending-fraud-slashes workflow-id :appeal-bond-held])))
    (is (= 60 (get-in world3 [:bond-distribution :insurance] 0)))
    (is (= 60 (get world3 :appeal-bonds-forfeited-insurance 0)))))

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
