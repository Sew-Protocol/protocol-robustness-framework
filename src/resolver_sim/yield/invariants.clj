(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism (provider + Sew)."
  (:require [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.invariant-catalog :as cat]))

(defn- inv-result [holds?]
  {:holds? (boolean holds?)})

(defn- token-name [token]
  (if (keyword? token) (name token) (str token)))

(defn- held-for-token [world token]
  (let [t (token-name token)]
    (long (or (get-in world [:yield/held-balances t])
              (get-in world [:yield/held-balances (keyword t)])
              0))))

(defn check-position-consistency
  "Principal/shares/realized >= 0; unrealized >= 0 unless :mark-to-market.
   Returns {:holds? bool :violations [{:owner-id :issues [...]}]}."
  [world]
  (let [violations
        (into []
          (keep
            (fn [[oid pos]]
              (let [mid (:module/id pos)
                    tok (:token pos)
                    risk (get-in world [:yield/risk mid tok] {})
                    sf   (:shortfall pos)
                    sf-model (get-in world [:yield/shortfall-models mid tok])
                    mtm? (= :mark-to-market (risk/effective-loss-mode risk))
                    
                    ;; If principal-loss model and recoverable=false, 
                    ;; we expect negative unrealized/principal.
                    authorized-impairment? (and (= (:type sf-model) :principal-loss)
                                                (not (:recoverable sf-model true)))
                    
                    issues (cond-> []
                            (and (not authorized-impairment?) (neg? (:principal pos 0))) (conj :negative-principal)
                            (neg? (:shares pos 0)) (conj :negative-shares)
                            (neg? (:realized-yield pos 0)) (conj :negative-realized-yield)
                            (and (not mtm?) (not authorized-impairment?) (neg? (:unrealized-yield pos 0))) (conj :negative-unrealized-yield))]
                (when (seq issues)
                  {:owner-id oid :issues issues})))
          (:yield/positions world {})))]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn check-realized-non-negative
  [world]
  (every? #(>= (:realized-yield % 0) 0) (vals (:yield/positions world {}))))

(defn check-status-fsm
  [world]
  (let [allowed #{:active :unwinding :withdrawn}]
    (every? #(contains? allowed (:status %)) (vals (:yield/positions world {})))))

(defn check-shortfall-splits
  "When :shortfall exists, fulfilled + deferred = basis."
  [world]
  (every? (fn [pos]
            (if-let [sf (:shortfall pos)]
              (let [f (long (or (:fulfilled-amount sf) 0))
                    d (long (or (:deferred-amount sf) 0))
                    b (long (or (:basis-amount sf) 0))]
                (= (+ f d) b))
              true))
          (vals (:yield/positions world {}))))

(defn check-partial-liquidity-principal
  "Under :partial-liquidity, unwinding positions must not haircut principal on the shortfall map."
  [world]
  (every? (fn [pos]
            (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
                  failures (risk/normalize-failure-modes (:failure-modes risk))
                  partial? (contains? failures :partial-liquidity)]
              (if (and partial? (= (:status pos) :unwinding) (:shortfall pos))
                (let [sf (:shortfall pos)
                      principal (:principal pos 0)
                      f (long (or (:fulfilled-amount sf) 0))
                      d (long (or (:deferred-amount sf) 0))
                      b (long (or (:basis-amount sf) 0))]
                  (and (pos? principal)
                       (zero? (long (or (:haircut-amount sf) 0)))
                       (= (+ f d) b)))
                true)))
          (vals (:yield/positions world {}))))


(defn check-value-conservation
  "Conservation invariant: shortfall components are non-negative and
   deferred-amount (when present) does not exceed the position expected
   residual value (principal + unrealized-yield).

   This is a simplified check until the full principal/yield split
   accounting (Phase 3) is complete — at which point this invariant
   will verify: total-value = claimable + deferred + loss.

   For now, verifies: deferred-amount + haircut-amount >= 0
   and (deferred-amount + haircut-amount) <= principal + unrealized-yield
   when shortfall exists."
  [world]
  (every? (fn [pos]
            (let [principal (long (:principal pos 0))
                  unrealized (long (:unrealized-yield pos 0))
                  sf (:shortfall pos)
                  deferred  (long (or (:deferred-amount sf) 0))
                  haircut   (long (or (:haircut-amount sf) 0))
                  fulfilled (long (or (:fulfilled-amount sf) 0))]
              (and (>= deferred 0) (>= haircut 0) (>= fulfilled 0)
                   (if sf
                     (<= (+ deferred haircut) (+ principal (max 0 unrealized)))
                     true))))
          (vals (:yield/positions world {}))))

(defn check-exact-ratio-consistency
  "Verifies that position principal and unrealized yield are consistent
   with the module index: principal + unrealized = shares * current-index."
  [world]
  (let [positions (get world :yield/positions {})
        indices (get world :yield/indices {})
        violations
        (into []
          (keep
            (fn [[oid pos]]
              (let [mid (:module/id pos)
                    tok (:token pos)
                    shares (m/ratio (:shares pos 0))
                    current-index (m/ratio (get-in indices [mid tok] 1))
                    principal (m/ratio (:principal pos 0))
                    unrealized (m/ratio (:unrealized-yield pos 0))
                    
                    expected-total-value (* shares current-index)
                    actual-total-value (+ principal unrealized)]
                
                (when (not= expected-total-value actual-total-value)
                  {:owner-id oid :issues [:drift-detected] 
                   :details {:expected expected-total-value 
                             :actual actual-total-value}}))))
            positions))]
    {:holds? (empty? violations) :violations (vec violations)}))

(defn check-deferred-reclaim
  "Withdrawn positions: no shortfall; reclaimed ≥ 0."
  [world]
  (every? (fn [pos]
            (if (= (:status pos) :withdrawn)
              (and (nil? (:shortfall pos))
                   (>= (long (or (:reclaimed-amount pos) 0)) 0))
              true))
          (vals (:yield/positions world {}))))

(defn position-custody-need
  [world pos]
  (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
        mtm? (or (= :mark-to-market (risk/effective-loss-mode risk))
               (neg? (:unrealized-yield pos 0)))]
    (if mtm?
      (max 0 (+ (:principal pos 0) (:unrealized-yield pos 0) (:realized-yield pos 0)))
      (+ (:principal pos 0) (:realized-yield pos 0)))))

(defn check-yield-exposure
  [world live-position-pred held-balance-fn]
  (let [positions (get world :yield/positions {})
        tokens    (into #{} (map :token (vals positions)))]
    (every? (fn [token]
              (let [held (held-balance-fn token)
                    total-needed (reduce (fn [acc [oid pos]]
                                           (if (and (= (:token pos) token)
                                                    (= (:status pos) :active)
                                                    (live-position-pred oid pos))
                                             (+ acc (position-custody-need world pos))
                                             acc))
                                         0
                                         positions)]
                (>= held total-needed)))
            tokens)))

(defn check-provider-exposure
  [world]
  (check-yield-exposure world
                        (fn [_ pos] (= (:status pos) :active))
                        #(held-for-token world %)))

(def ^:private check-fns
  {:yield/position-consistency check-position-consistency
   :yield/exposure             check-provider-exposure
   :yield/shortfall-splits     check-shortfall-splits
   :yield/status-fsm           check-status-fsm
   :yield/realized-non-negative check-realized-non-negative
   :yield/partial-liquidity-principal check-partial-liquidity-principal
   :yield/value-conservation   check-value-conservation
   :yield/deferred-reclaim     check-deferred-reclaim
   :yield/exact-ratio-consistency check-exact-ratio-consistency})

(defn registered-ids []
  (vec (keys check-fns)))

(defn- normalize-world-for-check
  "Replay trace snapshots use :yield-positions / :yield-held; expand to world paths."
  [world]
  (cond-> world
    (map? world)
    (cond-> (:yield-positions world)
      (assoc :yield/positions (:yield-positions world))
      (:yield-held world)
      (assoc :yield/held-balances (:yield-held world))
      (:yield-indices world)
      (assoc :yield/indices (:yield-indices world)))))

(defn holds?
  "Run a single invariant check; returns boolean.
   Handles functions that return {:holds? bool :violations [...]} as well as
   raw boolean (backward compatible)."
  [inv-id world]
  (if-let [f (get check-fns inv-id)]
    (let [result (f (normalize-world-for-check world))]
      (boolean (if (map? result) (:holds? result) result)))
    (throw (ex-info "Unknown yield invariant" {:invariant inv-id :known (registered-ids)}))))

(defn run-invariants
  "Run invariant checks; returns {inv-id {:holds? bool :violations [...]}}.
   If the invariant function returns structured {:holds? ... :violations ...},
   those violations are preserved; otherwise they default to nil."
  [world inv-ids]
  (let [world* (normalize-world-for-check world)]
    (into {}
          (for [id inv-ids]
            (let [f (get check-fns id)
                  raw (when f (f world*))
                  structured? (map? raw)]
              [id (if structured?
                    (assoc raw :holds? (boolean (:holds? raw)))
                    (inv-result raw))])))))

(defn check-all
  "Default runtime set for yield-v1 (see `invariant-catalog/default-runtime-invariant-ids`)."
  [world]
  (run-invariants world cat/default-runtime-invariant-ids))
