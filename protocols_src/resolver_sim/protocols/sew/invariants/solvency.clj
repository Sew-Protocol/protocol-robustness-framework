(ns resolver-sim.protocols.sew.invariants.solvency
  "Solvency-related invariant predicates for the Sew contract model."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.evidence :as yield-evi]))

(defn- get-escrow-afa-sum [world token live-states]
  (reduce (fn [acc [_ et]]
            (if (and (= (:token et) token)
                     (contains? live-states (:escrow-state et)))
              (+ acc (:amount-after-fee et))
              acc))
          0
          (:escrow-transfers world)))

(defn- get-bond-held-sum [world token]
  (reduce + 0 (for [[wf agents] (:bond-balances world)
                    [agent amt] agents
                    :let [et (get-in world [:escrow-transfers wf])]
                    :when (= (:token et) token)]
                amt)))

(defn- get-slash-appeal-bond-sum [world token]
  (reduce + 0 (for [[slash-id ev] (:pending-fraud-slashes world {})
                    :let [custody (get-in world [:appeal-bond-custody slash-id])
                          bond-token (or (:token custody)
                                         (get-in world [:escrow-transfers (:workflow-id custody) :token]))]
                    :when (= token bond-token)
                    :let [amt (:appeal-bond-held ev 0)]]
                amt)))

(defn get-yield-held-sum [world token live-states]
  (reduce (fn [acc [oid pos]]
            (let [et (when (vector? oid) (get-in world [:escrow-transfers (second oid)]))]
              (cond
                ;; Active yield position in a live escrow or a resolver-owned position
                (and (= (:token pos) token)
                     (= (:status pos) :active)
                     (or (and et (contains? live-states (:escrow-state et)))
                         (t/resolver-yield-owner-id? oid)))
                (+ acc (:unrealized-yield pos 0) (:realized-yield pos 0))
                ;; Escrow :unwinding — deferred remains in :total-held after finalize (live AFA gone).
                ;; Realized-yield is not added here because it is already accounted in :total-held via earlier steps.
                ;; Resolver :unwinding — deferred is still inside the stake already in :total-held;
                ;; do not add to yield-sum (would double-count with :resolver-stakes).
                (and (= (:token pos) token)
                     (= (:status pos) :unwinding)
                     (not (t/resolver-yield-owner-id? oid)))
                (+ acc (get-in pos [:shortfall :deferred-amount] 0))
                :else acc)))
          0
          (:yield/positions world {})))

(defn solvency-holds?
  "True when total-held[token] exactly equals the sum of all internal liabilities.
   Liabilities = [Live Escrow AFAs] + [Active Bonds] + [Slash Appeal Bonds]
                 + [Yield component on live positions] + [USDC Resolver Stakes]
   (protocol fees live in :total-fees, not :total-held).

   The internal invariant is STRICT EQUALITY (=)."
  [world token-balances]
  (let [all-tokens    (-> (set (keys (:total-held world)))
                          (into (map :token (vals (:escrow-transfers world))))
                          (into (keys (:total-bonds-posted world))))
        violations
        (for [token all-tokens
              :let  [held       (get (:total-held world) token 0)
                     escrow-sum (get-escrow-afa-sum world token t/live-states)
                     bond-sum   (get-bond-held-sum world token)
                     slash-bond-sum (get-slash-appeal-bond-sum world token)
                     yield-sum  (get-yield-held-sum world token t/live-states)
                      ;; Resolver stakes (:resolver-stakes) are NOT tracked in
                     ;; :total-held. They are a separate economic layer governed
                     ;; by slash-distribution-consistent? and the fraud-slash
                     ;; pipeline.  Excluding them here prevents false solvency
                     ;; violations from register-stake / withdraw-stake.
                     losses     (yield-evi/sum-recognized-losses world token)

                      liabilities (+ (+ escrow-sum bond-sum slash-bond-sum yield-sum)
                                     losses)

                      ext-bal    (when token-balances (get token-balances token 0))
                      internal-ok? (= liabilities held)
                      external-ok? (or (nil? ext-bal) (<= held ext-bal))]
               :when (not (and internal-ok? external-ok?))]
          {:token       token
           :liabilities liabilities
           :held        held
           :ext-bal     ext-bal
           :internal-ok? internal-ok?
           :external-ok? external-ok?})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
