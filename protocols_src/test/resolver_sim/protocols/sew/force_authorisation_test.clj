(ns resolver-sim.protocols.sew.force-authorisation-test
  "Force-authorisation lifecycle tests.

   Covers four scenarios:
     1. grant -> execute -> consumed       (happy path)
     2. grant -> revoke -> execute         (rejected)
     3. grant -> expired -> execute        (rejected)
     4. grant -> execute -> execute again  (rejected by Gap 1 guard)"
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.accounting :as acct]
             [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
             [resolver-sim.run.bundle-root :as br]
             [resolver-sim.time.context :as time-ctx]
             [resolver-sim.hash.canonical :as hc]))

(def gov-addr "0xGov")
(def alice-addr "0xAlice")
(def bob-addr "0xBob")
(def resolver-addr "0xResolver")
(def usdc "0xUSDC")

(def gov-ctx
  "Context with a governance agent for grant/revoke actions."
  {:agent-index {"gov" {:id "gov" :address gov-addr :role "governance"}}})

(def exec-ctx
  "Context with any resolvable agent for execute actions."
  {:agent-index {"exec" {:address resolver-addr}}})

(defn- disputed-world
  "Create a world with one :disputed escrow at block-time 1000.
   The dispute-resolver is set to resolver-addr for resolution authorization."
  [& {:keys [appeal-dur amount] :or {appeal-dur 0 amount 10000}}]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps        50
                                        :max-dispute-duration  3600
                                        :appeal-window-duration appeal-dur})
        w0   (t/empty-world 1000)
        cr   (lc/create-escrow w0 alice-addr usdc bob-addr amount
                               (t/make-escrow-settings {}) snap)
        w    (:world cr)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver-addr)
        (assoc-in [:dispute-timestamps 0] 1000))))

(defn- grant-force-auth
  "Call apply-action to grant a force-authorisation and return the world + auth-id."
  [world & {:keys [workflow-id reason starts-at duration expires-at]
            :or {workflow-id 0 reason :resolver-overcapacity}}]
  (let [params (merge {:workflow-id workflow-id :reason reason}
                      (when starts-at {:starts-at starts-at})
                      (when duration {:duration duration})
                      (when expires-at {:expires-at expires-at}))
        event {:seq 0 :time 1000 :agent "gov" :action "grant-force-authorisation"
               :params params}
        result (sew/apply-action gov-ctx world event)]
    (if (:ok result)
      {:world (:world result)
       :auth-id (get-in result [:extra :authorization/id])}
      {:error (:error result)})))

(defn- revoke-force-auth
  "Call apply-action to revoke a force-authorisation."
  [world auth-id]
  (let [event {:seq 1 :time 1000 :agent "gov" :action "revoke-force-authorisation"
               :params {:authorization-id auth-id}}
        result (sew/apply-action gov-ctx world event)]
    (if (:ok result)
      {:world (:world result)
       :auth-id auth-id}
      {:error (:error result)})))

(defn- execute-force-auth
  "Call apply-action to execute a force-authorised resolution."
  [world auth-id & {:keys [workflow-id is-release]
                    :or {workflow-id 0 is-release true}}]
  (let [event {:seq 2 :time 1000 :agent "exec" :action "execute-force-authorised-action"
               :params {:workflow-id workflow-id
                        :authorization-id auth-id
                        :is-release is-release}}
        result (sew/apply-action exec-ctx world event)]
    (if (:ok result)
      {:world (:world result)
       :auth-id auth-id}
      {:error (:error result)})))

;; ── Scenario 1: grant -> execute -> consumed ─────────────────────────────────

(deftest force-auth-grant-execute-consumed
  (let [world0 (disputed-world)
        {:keys [world auth-id] :as grant-result} (grant-force-auth world0)]
    (is auth-id "force-authorisation should be granted with an auth-id")
    (is (nil? (:error grant-result)) "grant should succeed")
    (let [world1 world

          record (get-in world1 [:force-authorisations auth-id])]
      (is (= :active (:authorization/status record)) "auth should be active after grant")
      (is (false? (:consumed? record)) "auth should not be consumed after grant")

      (let [{:keys [world error] :as exec-result} (execute-force-auth world1 auth-id)]
        (is (nil? error) "force-authorised execution should succeed")
        (let [world2 world

              record (get-in world2 [:force-authorisations auth-id])]
          (is (= :consumed (:authorization/status record)) "auth should be consumed after execution")
          (is (true? (:consumed? record)) "auth :consumed? should be true")

          (let [consumed (get-in world2 [:force-authorisations/consumed auth-id])]
            (is consumed "consumed registry entry should exist")
            (is (true? (:consumed? consumed)) "consumed registry entry should indicate consumed")
            (is (= auth-id (:authorization/id consumed)) "consumed registry should reference auth-id"))

          (is (= :released (t/escrow-state world2 0)) "escrow should be released"))))))

;; ── Scenario 2: grant -> revoke -> execute (rejected) ────────────────────────

(deftest force-auth-grant-revoke-execute-rejected
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)]
    (is auth-id "grant should succeed")
    (let [world1 world

          {:keys [error] :as revoke-result} (revoke-force-auth world1 auth-id)]
      (is (nil? error) "revoke should succeed")
      (let [world2 (:world revoke-result)

            {:keys [error] :as exec-result} (execute-force-auth world2 auth-id)]
        (is (= :force-authorisation-not-active error)
            "execution should be rejected after revoke")))))

;; ── Scenario 3: grant -> expired -> execute (rejected) ────────────────────────

(deftest force-auth-grant-expired-execute-rejected
  (let [world (disputed-world)
        now (time-ctx/block-ts world)
        {:keys [world auth-id]} (grant-force-auth world :expires-at (+ now 100))]
    (is auth-id "grant should succeed")

    (let [world (time-ctx/advance-time world {:to (+ now 200)})
          {:keys [error] :as exec-result} (execute-force-auth world auth-id)]
      (is (= :force-authorisation-expired error)
          "execution should be rejected after expiry"))))

;; ── Scenario 4: grant -> execute -> execute again (rejected) ──────────────────

(deftest force-auth-grant-execute-execute-again-rejected
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)]
    (is auth-id "grant should succeed")
    (let [world1 world

          {:keys [world]} (execute-force-auth world1 auth-id)]
      (is (= :released (t/escrow-state world 0)) "first execution should release escrow")
      (let [world2 world

            {:keys [error] :as exec-result} (execute-force-auth world2 auth-id)]
        (is (= :force-authorisation-not-active error)
            "second execution should be rejected (status is :consumed)")))))

;; ── Integration: protocol state flows into bundle root ───────────────────────

(deftest force-auth-protocol-state-hashes-in-bundle-root
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)]
    (is auth-id "grant should succeed")
    (let [world1 world
          {:keys [world]} (execute-force-auth world1 auth-id)]
      (is (= :released (t/escrow-state world 0)) "execution should release escrow")
      (let [world2 world
            fa (get world2 :force-authorisations)
            fa-consumed (get world2 :force-authorisations/consumed)]
        (is (map? fa) "force-authorisations should be a map in the world")
        (is (map? fa-consumed) "force-authorisations/consumed should be a map in the world")

        (let [result {:status :pass
                      :totals {:passed 1 :failed 0 :total 1}
                      :protocol/force-authorisations fa
                      :protocol/force-authorisations-consumed fa-consumed}
              request {:runner/backend :local-current
                       :runner-selection {:mode :pinned :runner-id :runner/local-bb}
                       :suite/key :test
                       :protocol/default-id "sew-v1"
                       :evidence/profile :standard
                       :output/profile :full}
              bundle (br/build-bundle-root request result)
              proto (get bundle :protocol/state-hashes)]
          (is (map? proto) ":protocol/state-hashes should be present in bundle root")
          (is (string? (:force-authorisations/hash proto))
              "force-authorisations/hash should be a string")
          (is (string? (:force-authorisations/consumed-hash proto))
              "force-authorisations/consumed-hash should be a string")
          (is (pos? (count (:force-authorisations/hash proto)))
              "force-authorisations/hash should be non-empty")
          (is (pos? (count (:force-authorisations/consumed-hash proto)))
              "force-authorisations/consumed-hash should be non-empty")

          ;; Verify determinism: same world state → same hashes
          (let [bundle2 (br/build-bundle-root request result)
                proto2 (get bundle2 :protocol/state-hashes)]
            (is (= (:force-authorisations/hash proto) (:force-authorisations/hash proto2))
                "force-authorisations/hash should be deterministic")
            (is (= (:force-authorisations/consumed-hash proto) (:force-authorisations/consumed-hash proto2))
                "force-authorisations/consumed-hash should be deterministic")))))))

(deftest force-auth-protocol-state-hashes-absent-when-no-force-auth
  (let [world (disputed-world)
        result {:status :pass
                :totals {:passed 1 :failed 0 :total 1}
                :protocol/force-authorisations nil
                :protocol/force-authorisations-consumed nil}
        request {:runner/backend :local-current
                 :runner-selection {:mode :pinned :runner-id :runner/local-bb}
                 :suite/key :test
                 :protocol/default-id "sew-v1"
                 :evidence/profile :standard
                 :output/profile :full}
        bundle (br/build-bundle-root request result)
        proto (get bundle :protocol/state-hashes)]
    (is (nil? proto) ":protocol/state-hashes should be absent when no force-auth state")))
