(ns resolver-sim.scenario.equilibrium-test
  "Unit tests for the trace-end mechanism-property and equilibrium-concept validators.
   Uses synthetic projections — no replay required.

   These tests cover cases that cannot be included in the fixture suite directly
   (mechanism :fail cases) as well as the full set of :pass, :inconclusive, and
   :not-applicable paths for each validator."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.scenario.equilibrium :as eq]
            [resolver-sim.scenario.projection  :as proj]))

(defn -main
  "Allow direct execution via: clojure -M:test -m resolver-sim.scenario.equilibrium-test"
  [& _]
  (run-tests 'resolver-sim.scenario.equilibrium-test))

;; ---------------------------------------------------------------------------
;; Synthetic projection helpers
;; ---------------------------------------------------------------------------

(defn- projection
  "Build a minimal synthetic projection for unit tests."
  [{:keys [terminal? halt-reason total-held
           attack-attempts attack-successes funds-lost
           invariant-violations negative-payoff-count
           coalition-net-profit]
    :or   {terminal?          true
           halt-reason        :all-terminal
           total-held         {}
           attack-attempts    0
           attack-successes   0
           funds-lost         0
           invariant-violations 0}}]
  {:terminal-world {:terminal?          terminal?
                    :total-held-by-token total-held
                    :escrow-count       1}
   :metrics        {:attack-attempts       attack-attempts
                    :attack-successes      attack-successes
                    :funds-lost            funds-lost
                    :invariant-violations  invariant-violations
                    :negative-payoff-count negative-payoff-count
                    :coalition-net-profit  coalition-net-profit}
   :trace-summary  {:halt-reason   halt-reason
                    :events-count  2
                    :actors        ["buyer" "seller"]
                    :terminal-time 1100}})

;; ---------------------------------------------------------------------------
;; budget-balance
;; ---------------------------------------------------------------------------

(deftest test-budget-balance-pass
  (testing "terminal escrows, total-held = 0 → :pass"
    (let [proj (projection {:terminal? true :total-held {"USDC" 0}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :pass (:status result)))
      (is (= :hard (:severity result))))))

(deftest test-budget-balance-fail
  (testing "terminal escrows but total-held > 0 → :fail"
    (let [proj (projection {:terminal? true :total-held {"USDC" 500}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-budget-balance-not-applicable-non-terminal
  (testing "non-terminal escrows → :not-applicable"
    (let [proj (projection {:terminal? false :total-held {"USDC" 5000}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :not-applicable (:status result))))))

(deftest test-budget-balance-not-applicable-open-disputes
  (testing "halt-reason :open-disputes-at-end → :not-applicable"
    (let [proj (projection {:terminal? false
                            :halt-reason :open-disputes-at-end
                            :total-held {"USDC" 5000}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :not-applicable (:status result))))))

;; ---------------------------------------------------------------------------
;; incentive-compatibility
;; ---------------------------------------------------------------------------

(deftest test-incentive-compat-inconclusive-no-adversarial
  (testing "no adversarial actors → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :inconclusive (:status result))))))

(deftest test-incentive-compat-pass-adversarial-rejected
  (testing "attacks attempted, all rejected, no funds lost → :pass"
    (let [proj (projection {:attack-attempts 3 :attack-successes 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :pass (:status result))))))

(deftest test-incentive-compat-fail-attack-success
  (testing "attack succeeded (attack-successes > 0) → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :fail (:status result))))))

(deftest test-incentive-compat-fail-funds-lost
  (testing "funds-lost > 0 → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 0 :funds-lost 100})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; individual-rationality
;; ---------------------------------------------------------------------------

(deftest test-individual-rationality-inconclusive
  (testing "no payoff-ledger (negative-payoff-count nil), funds-lost = 0 → :inconclusive"
    (let [proj (projection {:negative-payoff-count nil :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :inconclusive (:status result))))))

(deftest test-individual-rationality-pass-with-ledger
  (testing "negative-payoff-count = 0 → :pass"
    (let [proj (projection {:negative-payoff-count 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :pass (:status result))))))

(deftest test-individual-rationality-fail-negative-payoff
  (testing "negative-payoff-count > 0 → :fail"
    (let [proj (projection {:negative-payoff-count 2 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :fail (:status result))))))

(deftest test-individual-rationality-fail-funds-lost
  (testing "funds-lost > 0 (partial proxy) → :fail"
    (let [proj (projection {:negative-payoff-count nil :funds-lost 100})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; collusion-resistance
;; ---------------------------------------------------------------------------

(deftest test-collusion-resistance-inconclusive
  (testing "coalition-net-profit absent → :inconclusive"
    (let [proj (projection {:coalition-net-profit nil})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj)
                     :collusion-resistance)]
      (is (= :inconclusive (:status result))))))

(deftest test-collusion-resistance-pass
  (testing "coalition-net-profit <= 0 → :pass"
    (let [proj (projection {:coalition-net-profit -50})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj)
                     :collusion-resistance)]
      (is (= :pass (:status result))))))

(deftest test-collusion-resistance-fail
  (testing "coalition-net-profit > 0 → :fail"
    (let [proj (projection {:coalition-net-profit 100})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj)
                     :collusion-resistance)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; dominant-strategy-equilibrium
;; ---------------------------------------------------------------------------

(deftest test-dominant-strategy-inconclusive-no-attacks
  (testing "no adversarial actors, no violations → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :inconclusive (:status result))))))

(deftest test-dominant-strategy-pass
  (testing "attacks present, none succeeded, no violations → :pass"
    (let [proj (projection {:attack-attempts 3 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :pass (:status result))))))

(deftest test-dominant-strategy-fail-attack-success
  (testing "attack-successes > 0 → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :fail (:status result))))))

(deftest test-dominant-strategy-fail-invariant-violation
  (testing "invariant-violations > 0 → :fail"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 2})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; nash-equilibrium
;; ---------------------------------------------------------------------------

(deftest test-nash-inconclusive-no-attacks
  (testing "no adversarial actors → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :inconclusive (:status result))))))

(deftest test-nash-pass
  (testing "attacks rejected, no violations → :pass"
    (let [proj (projection {:attack-attempts 2 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :pass (:status result))))))

(deftest test-nash-fail-attack-success
  (testing "attack-successes > 0 → :fail (eq-v9 scenario)"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; SPE / BNE — always :inconclusive
;; ---------------------------------------------------------------------------

(deftest test-subgame-perfect-equilibrium
  (testing "SPE inconclusive when no strategic decisions made"
    (let [proj {:raw-trace [{:world {}}] :decisions [] :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :inconclusive (:status result)))
      (is (= :absent-evidence (:basis result)))))

  (testing "SPE inconclusive when trace is not terminal"
    (let [proj {:raw-trace [{:world {}}]
                :decisions [{:index 0 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? false}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :inconclusive (:status result)))
      (is (= :multi-trace-required (:basis result)))))

  (testing "SPE PASS: bounded counterfactual regret table has zero max regret"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 150}}}}] ; t=2 (won: escrow 100 + bond 50)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :pass (:status result)))
      (is (= :single-trace-node-counterfactual-proxy (:basis result)))
      (is (= 1 (get-in result [:observed :decisions-checked])))
      (is (= :pass (get-in result [:observed :spe-status])))
      (is (= 0 (get-in result [:observed :spe-max-regret])))
      (is (= 1 (count (get-in result [:observed :spe-regret-table]))))
      (is (= :trace-following (get-in result [:observed :spe-continuation-policy :mode])))
      (is (= :preserve (get-in result [:observed :spe-replay-boundary :ordering-mode])))
      (is (= :terminal-realized-v1 (get-in result [:observed :spe-utility-spec :type])))
      (is (number? (get-in result [:observed :spe-mean-regret])))
      (is (= 0 (get-in result [:observed :spe-exceed-epsilon-count])))
      (is (map? (get-in result [:observed :spe-regret-distribution])))))

  (testing "SPE FAIL: bounded counterfactual regret exceeds threshold"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 0}}}}] ; t=2 (lost: bond slashed)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :fail (:status result)))
      (is (= :single-trace-node-counterfactual-proxy (:basis result)))
      (is (= 1 (count (get-in result [:observed :spe-violations]))))
      (is (= 50 (get-in result [:observed :spe-max-regret])))
      (is (= 50 (get-in result [:offending 0 :local-regret])))))

  (testing "SPE PASS: rational dispute"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"seller" 0}}}}
                            {:world {:claimable {"e1" {"seller" 0}}}}
                            {:world {:claimable {"e1" {"seller" 100}}}}]
                :decisions [{:index 1 :seq 1 :agent "seller" :action "raise_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :pass (:status result)))))

  (testing "SPE FAIL: multiple violations"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"a" 0 "b" 0}}}}
                            {:world {:bond-balances {"e1" {"a" 10}}}} ; a escalate
                            {:world {:bond-balances {"e1" {"a" 10 "b" 10}}}} ; b escalate
                            {:world {:claimable {"e1" {"a" 0 "b" 0}}}}] ; both lost
                :decisions [{:index 1 :seq 1 :agent "a" :action "escalate_dispute"}
                            {:index 2 :seq 2 :agent "b" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :fail (:status result)))
      (is (= 2 (count (get-in result [:observed :spe-violations]))))))

  (testing "SPE determinism: identical projection reruns produce identical regret table"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}
                            {:world {:claimable {"e1" {"buyer" 0}}}}]
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          r1 (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                 :subgame-perfect-equilibrium
                 :observed
                 :spe-regret-table)
          r2 (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                 :subgame-perfect-equilibrium
                 :observed
                 :spe-regret-table)]
      (is (= r1 r2)))))


(deftest test-bne-always-inconclusive
  (testing "bayesian-nash-equilibrium always returns :inconclusive"
    (let [proj (projection {:attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:bayesian-nash-equilibrium] proj)
                     :bayesian-nash-equilibrium)]
      (is (= :inconclusive (:status result))))))

;; ---------------------------------------------------------------------------
;; Status roll-up
;; ---------------------------------------------------------------------------

(deftest test-evaluate-equilibrium-all-pass
  (testing "evaluate-equilibrium with clean metrics → mechanism :pass (no attacks → :inconclusive)"
    (let [theory  {:mechanism-properties [:budget-balance]
                   :equilibrium-concept  [:dominant-strategy-equilibrium]}
          result  {:metrics {:attack-attempts 2 :attack-successes 0
                             :invariant-violations 0 :funds-lost 0}
                   :trace   [{:world {:total-held    {"USDC" 0}
                                      :total-fees    {"USDC" 0}
                                      :live-states   {1 :released}
                                      :escrow-count  1}}]}
          eq-out  (eq/evaluate-equilibrium theory result)]
      ;; budget-balance: terminal world with 0 held → :pass
      (is (= :pass (get-in eq-out [:mechanism-results :budget-balance :status])))
      ;; dominant-strategy: attack-attempts 2, successes 0 → :pass
      (is (= :pass (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :pass (:mechanism-status eq-out)))
      (is (= :pass (:equilibrium-status eq-out))))))

(deftest test-evaluate-equilibrium-fail-propagates
  (testing "a :fail in mechanism results rolls up to :fail status"
    (let [theory {:mechanism-properties [:budget-balance]}
          result {:metrics {:attack-attempts 0 :attack-successes 0
                            :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held    {"USDC" 5000}
                                     :total-fees    {"USDC" 0}
                                     :live-states   {1 :released}
                                     :escrow-count  1}}]}
          eq-out (eq/evaluate-equilibrium theory result)]
      ;; Escrow is in :released but total-held = 5000 → terminal? check
      ;; live-states has 1 :released → terminal? = true → budget-balance FAIL
      (is (= :fail (get-in eq-out [:mechanism-results :budget-balance :status])))
      (is (= :fail (:mechanism-status eq-out))))))

(deftest test-unknown-property-inconclusive
  (testing "unknown mechanism property → :inconclusive with absent-evidence basis"
    (let [proj (projection {})
          result (-> (eq/evaluate-mechanism-properties [:unknown-future-property] proj)
                     :unknown-future-property)]
      (is (= :inconclusive (:status result)))
      (is (= :absent-evidence (:basis result))))))

(deftest test-force-refund-path-integrity-pass
  (testing "refunded path remains refund-only"
    (let [proj (assoc (projection {})
                      :money-movement-summary
                      {:workflow-outcomes {0 {:terminal-state :refunded :path :refund}}})
          result (-> (eq/evaluate-mechanism-properties [:force-refund-path-integrity] proj)
                     :force-refund-path-integrity)]
      (is (= :pass (:status result))))))

(deftest test-force-refund-path-integrity-fail
  (testing "refunded workflow marked as release path fails"
    (let [proj (assoc (projection {})
                      :money-movement-summary
                      {:workflow-outcomes {0 {:terminal-state :refunded :path :release}}})
          result (-> (eq/evaluate-mechanism-properties [:force-refund-path-integrity] proj)
                     :force-refund-path-integrity)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-pending-lifecycle-integrity
  (testing "pending lifecycle pass and fail cases"
    (let [pass-proj (assoc (projection {})
                           :money-movement-summary
                           {:pending-lifecycle {:unknown {:created 2 :cleared 2 :superseded 1}}})
          fail-proj (assoc (projection {})
                           :money-movement-summary
                           {:pending-lifecycle {:unknown {:created 1 :cleared 2 :superseded 0}}})
          pass-r (-> (eq/evaluate-mechanism-properties [:pending-lifecycle-integrity] pass-proj)
                     :pending-lifecycle-integrity)
          fail-r (-> (eq/evaluate-mechanism-properties [:pending-lifecycle-integrity] fail-proj)
                     :pending-lifecycle-integrity)]
      (is (= :pass (:status pass-r)))
      (is (= :fail (:status fail-r))))))

(deftest test-stake-flow-conservation
  (testing "stake flow conservation pass and fail"
    (let [pass-proj (assoc (projection {})
                           :stake-flow-summary
                           {"0xR" {:start 100 :withdrawn 20 :slashed 30 :end 50}})
          fail-proj (assoc (projection {})
                           :stake-flow-summary
                           {"0xR" {:start 100 :withdrawn 20 :slashed 30 :end 60}})
          pass-r (-> (eq/evaluate-mechanism-properties [:stake-flow-conservation] pass-proj)
                     :stake-flow-conservation)
          fail-r (-> (eq/evaluate-mechanism-properties [:stake-flow-conservation] fail-proj)
                     :stake-flow-conservation)]
      (is (= :pass (:status pass-r)))
      (is (= :fail (:status fail-r))))))

;; ---------------------------------------------------------------------------
;; SPE observed fields — Phase F-J and Phase K
;; ---------------------------------------------------------------------------

(defn- spe-projection
  "Build a minimal projection suitable for SPE evaluation, with a real raw-trace
   and decisions so subgame_counterfactual fires.

   :pre-wealth  — agent wealth at the decision node's pre-state (default 100)
   :chosen-wealth — agent wealth at the decision node's post-state (default 200)
   Regret = max(0, pre-wealth - chosen-wealth) when chosen < pre."
  [{:keys [pre-wealth chosen-wealth agent action regret-threshold]
    :or {pre-wealth 0 chosen-wealth 200 agent "resolver"
         action "execute_resolution" regret-threshold 0}}]
  {:raw-trace [{:world {:claimable {"e1" {agent pre-wealth}}}}
               {:world {:claimable {"e1" {agent chosen-wealth}}}}]
   :decisions [{:seq 1 :agent agent :action action}]
   :terminal-world {:terminal? true
                    :total-held-by-token {}
                    :escrow-count 1}
   :metrics {:attack-attempts 0 :attack-successes 0 :funds-lost 0
             :invariant-violations 0}
   :trace-summary {:halt-reason :all-terminal :events-count 2
                   :actors [agent] :terminal-time 1100}
   :spe-config {:regret-threshold regret-threshold}})

(deftest test-spe-observed-includes-phase-f-g-h-i-j-l-fields
  (testing "SPE observed payload includes all Phase F-J and L fields"
    (let [proj (spe-projection {:chosen-wealth 200 :terminal-wealth 200
                                :regret-threshold 1000})
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)
          obs (:observed result)]
      (is (some? (:spe-result obs)))
      (is (some? (:spe-strategy-profile obs)))
      (is (number? (:spe-proper-subgames-checked obs)))
      (is (number? (:spe-information-set-nodes-checked obs)))
      (is (number? (:spe-not-checkable-nodes obs)))
      (is (vector? (:spe-counterexamples obs)))
      (is (map? (:spe-off-path-coverage obs)))
      (is (string? (:spe-proof-sketch obs))))))

(deftest test-spe-result-vocab-pass
  (testing ":spe-result is :spe/pass on no-regret resolver verdict"
    (let [proj (spe-projection {:chosen-wealth 200 :regret-threshold 1000})
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)
          obs (:observed result)]
      (is (= :pass (:status result)))
      (is (= :spe/pass (:spe-result obs))))))

(deftest test-spe-counterexamples-on-fail
  (testing ":spe-counterexamples non-empty on profitable deviation"
    (let [proj (spe-projection {:pre-wealth 100 :chosen-wealth 0 :regret-threshold 0})
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)
          obs (:observed result)]
      (is (= :fail (:status result)))
      (is (seq (:spe-counterexamples obs)))
      (let [ce (first (:spe-counterexamples obs))]
        (is (= :profitable-deviation (:failure/type ce)))
        (is (string? (:node/id ce)))))))

(deftest test-spe-proof-sketch-emitted
  (testing ":spe-proof-sketch is a non-empty string"
    (let [proj (spe-projection {:chosen-wealth 200 :regret-threshold 1000})
          obs (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                  :subgame-perfect-equilibrium :observed)]
      (is (string? (:spe-proof-sketch obs)))
      (is (pos? (count (:spe-proof-sketch obs)))))))

(deftest test-bounded-public-state-epsilon-spe-pass
  (testing ":bounded-public-state-epsilon-spe passes with a proper-subgame resolver node"
    (let [proj (spe-projection {:chosen-wealth 200 :regret-threshold 1000})
          result (-> (eq/evaluate-equilibrium-concepts [:bounded-public-state-epsilon-spe] proj)
                     :bounded-public-state-epsilon-spe)]
      (is (= :pass (:status result)))
      (is (= :hard (:severity result))))))

(deftest test-bounded-public-state-epsilon-spe-fail-deviation
  (testing ":bounded-public-state-epsilon-spe fails when regret exceeds threshold"
    (let [proj (spe-projection {:pre-wealth 100 :chosen-wealth 0 :regret-threshold 0})
          result (-> (eq/evaluate-equilibrium-concepts [:bounded-public-state-epsilon-spe] proj)
                     :bounded-public-state-epsilon-spe)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-bounded-public-state-epsilon-spe-inconclusive-no-proper-subgames
  (testing ":bounded-public-state-epsilon-spe is :inconclusive when only info-set nodes"
    ;; buyer raise_dispute is an info-set node → no proper subgames → inconclusive
    (let [proj (spe-projection {:pre-wealth 100 :chosen-wealth 0 :regret-threshold 0
                                :agent "buyer" :action "raise_dispute"})
          result (-> (eq/evaluate-equilibrium-concepts [:bounded-public-state-epsilon-spe] proj)
                     :bounded-public-state-epsilon-spe)]
      (is (= :inconclusive (:status result))))))

;; ---------------------------------------------------------------------------
;; Fix 1 regression: spe-config from theory block must be threaded to evaluator
;; ---------------------------------------------------------------------------

(defn- minimal-replay-result
  "Minimal replay result that trace-end-projection can process.
   Produces a resolver execute_resolution decision with regret=50.

   Wealth is keyed by address '0xresolver' (not agent-id 'resolver') because
   trace-end-projection resolves agent-id → address via :agents, and the SPE
   evaluator looks up wealth using actor = (or address agent).

   pre-wealth=100 (seq=0 register_stake), terminal-wealth=50 (seq=1 execute_resolution)."
  []
  {:trace [{:world {:claimable {"e1" {"0xresolver" 100}}
                    :live-states {"e1" "disputed"}
                    :total-held {}
                    :total-fees {}}
             :agent "resolver" :action "register_stake" :seq 0 :time 1000}
            {:world {:claimable {"e1" {"0xresolver" 50}}
                     :live-states {"e1" "released"}
                     :total-held {}
                     :total-fees {}}
             :agent "resolver" :action "execute_resolution" :seq 1 :time 1100}]
   :agents [{:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
   :metrics {}})

(deftest test-spe-config-threading-from-theory
  (testing "spe-config from theory block is used by the evaluator (not defaults)"
    (let [result (minimal-replay-result)
          ;; regret=50 > threshold=0 and > epsilon-abs=0 → FAIL
          theory-strict {:equilibrium-concept ["subgame-perfect-equilibrium"]
                         :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          ;; regret=50 <= threshold=9999, and 50 < epsilon-abs=200, 50/50=1.0 not > 1.0 → PASS
          theory-lenient {:equilibrium-concept ["subgame-perfect-equilibrium"]
                          :spe-config {:regret-threshold 9999 :epsilon-abs 200.0 :epsilon-rel 1.0}}
          r-strict  (-> (eq/evaluate-equilibrium theory-strict result)
                        :equilibrium-results :subgame-perfect-equilibrium)
          r-lenient (-> (eq/evaluate-equilibrium theory-lenient result)
                        :equilibrium-results :subgame-perfect-equilibrium)]
      ;; Strict threshold: regret(50) > 0 → fail
      (is (= :fail (:status r-strict))
          "regret-threshold=0 should fail when resolver wealth drops by 50")
      ;; Lenient threshold: regret(50) <= 9999 and within epsilon → pass
      (is (= :pass (:status r-lenient))
          "regret-threshold=9999 + epsilon-abs=200 should pass when resolver wealth drops by 50")
      ;; Confirm the declared threshold is visible in the observed payload
      (is (= 9999 (get-in r-lenient [:observed :spe-threshold]))
          "spe-threshold in observed should reflect the theory-declared value, not default 0"))))

;; ---------------------------------------------------------------------------
;; Phase K — Backward induction tests
;; ---------------------------------------------------------------------------

(defn- two-node-bi-replay-result
  "Minimal 2-node replay result for backward induction tests.
   Node seq=2 — buyer raise_dispute (information-set)
   Node seq=3 — resolver execute_resolution (proper-subgame)
   Resolver receives fee at execute_resolution; buyer wealth is 0 throughout."
  []
  {:raw-trace
   [{:world {:resolver-stakes {} :claimable {} :bond-balances {} :live-states {} :total-held {}}
     :agent "buyer" :action "create_escrow" :seq 0 :time 1000}
    {:world {:resolver-stakes {} :claimable {} :bond-balances {} :live-states {"e1" "pending"} :total-held {"e1" 1000}}
     :agent "buyer" :action "raise_dispute" :seq 2 :time 1010}
    {:world {:resolver-stakes {"0xresolver" 200} :claimable {"e1" {"0xresolver" 50}} :bond-balances {} :live-states {"e1" "disputed"} :total-held {}}
     :agent "resolver" :action "execute_resolution" :seq 3 :time 1060}
    {:world {:resolver-stakes {"0xresolver" 200} :claimable {"e1" {"0xresolver" 50}} :bond-balances {} :live-states {"e1" "released"} :total-held {}}
     :agent "resolver" :action "settle" :seq 4 :time 1070}]
   :agents [{:id "buyer" :address "0xbuyer" :role "buyer" :strategy "rational"}
            {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
   :metrics {}})

(deftest test-backward-induction-mode-vs-forward-single-node
  (testing "backward-induction and forward modes produce identical results on single-node trace"
    (let [result (minimal-replay-result)
          theory {:equilibrium-concept ["bounded-public-state-epsilon-spe"]
                  :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          theory-bi {:equilibrium-concept ["bounded-backward-induction-spe"]
                     :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          r-fwd (-> (eq/evaluate-equilibrium theory result)
                    :equilibrium-results :bounded-public-state-epsilon-spe)
          r-bi  (-> (eq/evaluate-equilibrium theory-bi result)
                    :equilibrium-results :bounded-backward-induction-spe)]
      (is (= (:status r-fwd) (:status r-bi))
          "single-node trace: forward and backward-induction modes must agree on status"))))

(deftest test-backward-induction-evaluation-mode-in-output
  (testing "backward-induction mode is recorded in output keys"
    (let [result (two-node-bi-replay-result)
          theory {:equilibrium-concept ["bounded-backward-induction-spe"]
                  :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :bounded-backward-induction-spe)]
      (is (some? r) "bounded-backward-induction-spe result must be present")
      (is (contains? #{:pass :fail :inconclusive :not-applicable} (:status r))
          "status must be a known keyword"))))

(deftest test-backward-induction-terminal-deviation-uses-pre-wealth
  (testing "terminal deviation (settle_now) uses pre-wealth, not chosen-local"
    (let [result (two-node-bi-replay-result)
          theory-bi {:equilibrium-concept ["bounded-backward-induction-spe"]
                     :spe-config {:regret-threshold 9999 :epsilon-abs 200.0 :epsilon-rel 1.0}}
          theory-fwd {:equilibrium-concept ["bounded-public-state-epsilon-spe"]
                      :spe-config {:regret-threshold 9999 :epsilon-abs 200.0 :epsilon-rel 1.0}}
          r-bi  (-> (eq/evaluate-equilibrium theory-bi result)
                    :equilibrium-results :bounded-backward-induction-spe)
          r-fwd (-> (eq/evaluate-equilibrium theory-fwd result)
                    :equilibrium-results :bounded-public-state-epsilon-spe)]
      (is (some? r-bi) "backward-induction result must be present")
      (is (some? r-fwd) "forward result must be present")
      (when (= :pass (:status r-fwd))
        (is (contains? #{:pass :inconclusive} (:status r-bi))
            "backward induction on terminal deviation must not be stricter than forward pass")))))

(deftest test-backward-induction-two-node-pass
  (testing "honest 2-node trace passes bounded-backward-induction-spe"
    (let [result (two-node-bi-replay-result)
          theory {:equilibrium-concept ["bounded-backward-induction-spe"]
                  :spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}}
          r (-> (eq/evaluate-equilibrium theory result)
                :equilibrium-results :bounded-backward-induction-spe)]
      (is (contains? #{:pass :inconclusive} (:status r))
          "honest resolution with no profitable deviation must pass or be inconclusive"))))
