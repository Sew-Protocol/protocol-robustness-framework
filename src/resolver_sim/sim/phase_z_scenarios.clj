(ns resolver-sim.sim.adversarial.phase-z-scenarios
  "Phase Z adversarial scenarios mapped to the contract-model replay format.

   Phase Z (resolver-sim.sim.adversarial.phase-z) tests legitimacy and reflexive
   participation collapse.  Its five tests identify system-level risks:

     TEST 1 Baseline           → resolver always present           → no liveness risk
     TEST 2 Market Shock       → 40% resolver exit at epoch 30     → resolver absent path
     TEST 3 Scam Wave          → high false-positive slash rate     → honest resolver slashed
     TEST 4 Combined Shocks    → exit + false positives            → cascade + slash
     TEST 5 Cascading Failures → low accuracy + slow resolution    → disputes time out

   This namespace translates those macro-level risks into concrete EVM
   action sequences that stress the contract layer.  Each scenario is a
   valid input to replay-with-sew-protocol and produces a scored, persistable trace.

   Mapping:
     TEST 2/4 → resolver absent: dispute raised, nobody resolves → auto-cancel after 90 days
     TEST 3   → false positive: resolver cancels a honest-release escrow → buyer wrongly refunded
     TEST 5   → liveness cascade: two escrows dispute; resolver resolves none; both auto-cancel

   Persistence:
     (persist-top-n! n) runs all Phase Z scenarios, scores them, persists top-n.
     (persist-top-percent! p) keeps the top p fraction (0.01 = 1%)."
  (:require [resolver-sim.contract-model.replay      :as replay]
            [resolver-sim.protocols.sew            :as sew]
            [resolver-sim.io.trace-score             :as ts]
            [resolver-sim.io.trace-store             :as store]))

;; ---------------------------------------------------------------------------
;; Shared protocol params (maps to vault's 90-day maxDisputeDuration)
;; ---------------------------------------------------------------------------

(def ^:private params-dr3
  {:resolver-fee-bps      100
   :appeal-window-duration 0
   :max-dispute-duration  7776000    ;; 90 days in seconds
   :resolution-module     "0x0000000000000000000000000000000000001234"})

;; ---------------------------------------------------------------------------
;; Scenario definitions
;; ---------------------------------------------------------------------------

(def scenario-z1-resolver-absent
  "TEST 2/4: resolver absent after market shock.
   Buyer creates, raises dispute. Resolver never acts.
   After 90 days the keeper (buyer) auto-cancels.
   Liveness failure score: +5."
  {:scenario-id     "phase-z-1-resolver-absent"
   :schema-version  "1.0"
   :initial-block-time 1000
   :seed            421
   :agents
   [{:id "buyer"  :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :role "resolver"}]
   :protocol-params params-dr3
   :events
   [{:seq 0 :time 1001 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}
     :save-id-as "wf0"}
    {:seq 1 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf0"}}
    ;; Resolver absent — no execute_resolution step.
    ;; After max-dispute-duration the dispute auto-cancels.
    {:seq 2 :time 7778001 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id "wf0"}}]})

(def scenario-z3-false-positive-slash
  "TEST 3: Scam wave / false positive.
   Resolver (acting as attacker) cancels an escrow that should be released.
   Attacker-type resolver exploits dispute resolution.
   Score: attack-success +1 × 1."
  {:scenario-id     "phase-z-3-false-positive"
   :schema-version  "1.0"
   :initial-block-time 1000
   :seed            422
   :agents
   [{:id "buyer"  :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :strategy "malicious" :role "resolver"}]
   :protocol-params params-dr3
   :events
   [{:seq 0 :time 1001 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}
     :save-id-as "wf0"}
    {:seq 1 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf0"}}
    ;; Resolver cancels (false positive: should have released)
    {:seq 2 :time 3000 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id "wf0" :is-release false}}
    {:seq 3 :time 175801 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id "wf0"}}]})

(def scenario-z4-combined-shock
  "TEST 4: Combined shock — two escrows dispute, resolver resolves one wrong,
   second auto-cancels (resolver exits mid-stream).
   Combines false positive + liveness failure.
   Score: attack-success +1, liveness-fail +5."
  {:scenario-id     "phase-z-4-combined-shock"
   :schema-version  "1.0"
   :initial-block-time 1000
   :seed            423
   :agents
   [{:id "buyer"  :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :strategy "malicious" :role "resolver"}]
   :protocol-params params-dr3
   :events
   ;; Escrow A
   [{:seq 0 :time 1001 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}
     :save-id-as "wf0"}
    {:seq 1 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf0"}}
    ;; Resolver acts on escrow A (false positive cancel)
    {:seq 2 :time 3000 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id "wf0" :is-release false}}
    {:seq 3 :time 175801 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id "wf0"}}
    ;; Escrow B — resolver exits; times out
    {:seq 4 :time 175900 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 80000}
     :save-id-as "wf1"}
    {:seq 5 :time 176000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf1"}}
    ;; Resolver absent for wf1; auto-cancel after 90 days from dispute raise
    {:seq 6 :time 7954001 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id "wf1"}}]})

(def scenario-z5-cascade-liveness
  "TEST 5: Cascading failures — three concurrent disputes, resolver resolves none.
   All three auto-cancel. Maximum liveness failure score: +5."
  {:scenario-id     "phase-z-5-cascade-liveness"
   :schema-version  "1.0"
   :initial-block-time 1000
   :seed            424
   :agents
   [{:id "buyer"  :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :strategy "honest"}]
   :protocol-params params-dr3
   :events
   ;; All three escrows created
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}
     :save-id-as "wf0"}
    {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 50000}
     :save-id-as "wf1"}
    {:seq 2 :time 1020 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 75000}
     :save-id-as "wf2"}
    ;; All three disputed
    {:seq 3 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf0"}}
    {:seq 4 :time 2010 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf1"}}
    {:seq 5 :time 2020 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id "wf2"}}
    ;; Resolver exits — no resolutions. Auto-cancel all three.
    ;; Earliest disputed = wf0 at t=2000; eligible at t=2000+7776000=7778000
    {:seq 6 :time 7778001 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id "wf0"}}
    {:seq 7 :time 7778002 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id "wf1"}}
    {:seq 8 :time 7778003 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id "wf2"}}]})

(def all-scenarios
  "All Phase Z adversarial scenarios, in ascending severity order."
  [scenario-z1-resolver-absent
   scenario-z3-false-positive-slash
   scenario-z4-combined-shock
   scenario-z5-cascade-liveness])

;; ---------------------------------------------------------------------------
;; Scoring and persistence
;; ---------------------------------------------------------------------------

(defn run-and-score
  "Run a Phase Z scenario through replay-with-sew-protocol and score the result.
   Returns a map with :scenario, :result, :scored-result."
  [scenario]
  (let [result        (sew/replay-with-sew-protocol scenario)
        scored-result (ts/score-result result)]
    {:scenario      scenario
     :result        result
     :scored-result scored-result
     :categories    (ts/score-category scored-result)}))

(defn run-all
  "Run all Phase Z scenarios and return scored results, sorted by score descending."
  []
  (->> all-scenarios
       (mapv run-and-score)
       (sort-by #(- (get-in % [:scored-result :trace-score] 0)))))

(defn persist-top-n!
  "Run all Phase Z scenarios, score them, and persist the top n.
   Returns a vector of trace-ids for persisted traces."
  [n & [{:keys [store-dir] :as opts}]]
  (->> (run-all)
       (take n)
       (mapv (fn [{:keys [scenario scored-result]}]
               (store/store-trace! scored-result scenario
                                   (merge {:force? true} opts))))))

(defn persist-top-percent!
  "Run all Phase Z scenarios, score them, and persist the top p fraction.
   p=0.01 keeps the top 1%.  Always persists at least 1 scenario."
  [p & [opts]]
  (let [scored (run-all)
        n      (max 1 (int (Math/ceil (* p (count scored)))))]
    (persist-top-n! n opts)))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Run Phase Z scenarios and persist top 1% to the trace store.

   Usage:
     clojure -M:phase-z-persist
     clojure -M:phase-z-persist <store-dir>"
  [& args]
  (let [store-dir (or (first args) store/default-store-dir)
        ids       (persist-top-percent! 0.01 {:store-dir store-dir})]
    (println (str "Phase Z: persisted " (count ids) " trace(s) → " store-dir))
    (doseq [id ids]
      (when id (println (str "  " id))))
    (System/exit 0)))
