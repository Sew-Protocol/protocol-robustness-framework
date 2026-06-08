(require '[resolver-sim.protocols.sew :as sew]
(require '[resolver-sim.protocols.sew.replay-test :as rt])

(def sc #'resolver-sim.protocols.sew.replay-test/sc)
(def default-params #'resolver-sim.protocols.sew.replay-test/default-params)

(defn dump [label r]
  (println "\n===" label "outcome" (:outcome r))
  (doseq [t (:trace r)]
    (when (or (not= :ok (:result t)) (:violations t))
      (println " seq" (:seq t) (:action t) (:result t) (:error t)
               (when (:violations t) (str "violations=" (:violations t)))))))

(let [gov {:id "gov" :type "governance" :address "0xGov"}
      keeper {:id "keeper" :type "keeper" :address "0xKeeper"}
      upheld (sew/replay-with-sew-protocol
              (sc :agents [rt/alice rt/bob rt/resolver gov keeper]
                  :params (assoc default-params :appeal-window-duration 120 :appeal-bond-amount 70)
                  :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake" :params {:amount 10000}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1060 :agent "alice" :action "raise_dispute" :params {:workflow-id 0}}
                   {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                    :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                   {:seq 5 :time 1140 :agent "resolver" :action "appeal_slash" :params {:workflow-id 0}}
                   {:seq 6 :time 1160 :agent "gov" :action "resolve_appeal" :params {:workflow-id 0 :upheld? true}}
                   {:seq 7 :time 1200 :agent "gov" :action "execute_fraud_slash" :params {:workflow-id 0}}
                   {:seq 8 :time 1250 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}]))
      rejected (sew/replay-with-sew-protocol
                (sc :agents [rt/alice rt/bob rt/resolver gov keeper]
                    :params (assoc default-params :appeal-window-duration 120 :appeal-bond-amount 80)
                    :events
                    [{:seq 0 :time 1000 :agent "resolver" :action "register_stake" :params {:amount 10000}}
                     {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                      :params {:token "0xUSDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                     {:seq 2 :time 1060 :agent "alice" :action "raise_dispute" :params {:workflow-id 0}}
                     {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                      :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                     {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                      :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                     {:seq 5 :time 1140 :agent "resolver" :action "appeal_slash" :params {:workflow-id 0}}
                     {:seq 6 :time 1160 :agent "gov" :action "resolve_appeal" :params {:workflow-id 0 :upheld? false}}
                     {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}
                     {:seq 8 :time 1255 :agent "gov" :action "execute_fraud_slash" :params {:workflow-id 0}}]))
      s40 (sew/replay-with-sew-protocol
           (sc :agents [rt/alice rt/bob rt/resolver gov keeper]
               :params (assoc default-params :appeal-window-duration 120)
               :events
               [{:seq 0 :time 1000 :agent "resolver" :action "register_stake" :params {:amount 10000}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                {:seq 2 :time 1060 :agent "alice" :action "raise_dispute" :params {:workflow-id 0}}
                {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                 :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                 :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}
                {:seq 6 :time 1255 :agent "gov" :action "execute_fraud_slash" :params {:workflow-id 0}}]))]
  (dump "upheld" upheld)
  (dump "rejected" rejected)
  (dump "s40" s40)
  (println "upheld trace6 claimable" (get-in upheld [:trace 6 :world :claimable 0 "0xResolver"]))
  (println "rejected trace6 distributions" (get-in rejected [:trace 6 :world :appeal-bond-distributions-by-token]))))
