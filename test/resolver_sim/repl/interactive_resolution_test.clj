(ns resolver-sim.repl.interactive-resolution-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.repl.interactive-resolution :as ir]
            [resolver-sim.protocols.sew.types :as t]))

(def sample-fixture
  "DR3 with 2-level escalation (0xresolver → 0xl1-resolver), 120s appeal window."
  {:initial-block-time 1000
   :agents             [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                        {:id "seller"      :address "0xseller"      :strategy "honest"}
                        {:id "resolver"    :address "0xresolver"    :role "resolver"}
                        {:id "l1-resolver" :address "0xl1-resolver" :role "resolver"}]
   :protocol-params
   {:resolver-fee-bps 150
    :appeal-window-duration 120
    :max-dispute-duration 2592000
    :dispute-resolver "0xresolver"
    :escalation-resolvers {"0" "0xresolver" "1" "0xl1-resolver"}}})

(def initial-events
  "Create escrow, then raise dispute to reach a :disputed starting state."
  [{:seq 0 :time 1000 :agent "buyer"  :action "create-escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000}}
   {:seq 1 :time 1060 :agent "buyer"  :action "raise-dispute"
    :params {:workflow-id 0}}])

(deftest smoke-test
  (let [;; Start session from fixture with initial events
        session (ir/start-session sample-fixture initial-events)
        world   (:world session)]
    (is (= :disputed (t/escrow-state world 0)))
    (is (zero? (t/dispute-level world 0)))

    ;; Step 1: resolver executes resolution (release) → creates pending settlement
    (let [choices (ir/available-choices session)
          _ (is (seq choices) "expected available choices after dispute")
          ;; n=0 should be resolver execute-resolution release
          session (ir/pick session 0)
          world   (:world session)]
      (is (= :disputed (t/escrow-state world 0)))
      (is (:exists (t/get-pending world 0)) "pending settlement exists")

      ;; Step 2: buyer escalates dispute → level 0→1, new resolver
      (let [session (ir/pick session 0)
            world   (:world session)]
        (is (= :disputed (t/escrow-state world 0)))
        (is (= 1 (t/dispute-level world 0)) "escalated to level 1")
        (is (= "0xl1-resolver"
               (:dispute-resolver (t/get-transfer world 0)))
            "resolver updated to L1")

        ;; Step 3: L1 resolver executes resolution (release)
        ;; available-actions doesn't expose this when a pending exists,
        ;; so we apply directly.
        (let [session (ir/apply-event session
                                      {:action  "execute-resolution"
                                       :params  {:workflow-id 0 :is-release true
                                                 :resolution-hash "0xl1release"}
                                       :agent   "l1-resolver"})
              world   (:world session)]
          (is (= :disputed (t/escrow-state world 0)))
          (is (:exists (t/get-pending world 0)) "new pending after L1 resolution")

          ;; Step 4: advance time past appeal window (1000 + 120 = 1120)
          (let [session (ir/advance-time session 1500)]
            (is (= 1500 (:block-time (:world session))))

            ;; Step 5: auto-until-decision → execute-pending-settlement
            (let [session (ir/auto-until-decision session)
                  world   (:world session)]
              (is (t/terminal-state? world 0)
                  "escrow reached terminal state after pending settlement")
              (is (= 6 (count (:steps session)))
                  "6 steps: 2 initial + resolution + escalation + L1 resolution + auto/pending-exec")

              ;; Export smoke test
              (let [path (ir/export-session session
                                            "results/interactive-smoke-test.edn")]
                (is (.exists (java.io.File. path)))
                (println "Exported to" path)))))))))

(deftest governance-override-test
  (let [session (ir/start-session sample-fixture initial-events)
        ;; No governance agent — normal denial
        session-fail (ir/apply-event session
                                     {:action "execute-resolution"
                                      :params {:workflow-id 0 :is-release true}
                                      :agent "buyer"})
        _ (is (= :disputed (t/escrow-state (:world session-fail) 0)))
        _ (is (not (:exists (t/get-pending (:world session-fail) 0)))
              "buyer not authorized without governance")
        ;; Governance agent is available but resolver is healthy — override accepted
        ;; (:governed-by is the auth, resolver-unavailable-reason is just informational)
        session-override (ir/apply-event session
                                         {:action "execute-resolution"
                                          :params {:workflow-id 0 :is-release true}
                                          :agent "buyer"}
                                         {:governed-by "resolver"})
        world (:world session-override)]
    (is (= :disputed (t/escrow-state world 0)))
    (is (:exists (t/get-pending world 0)) "pending exists after governance override")
    ;; Step should record which agent approved the override
    (is (= "0xresolver" (-> session-override :steps last :governance-override))
        "governance agent address recorded in step")))
