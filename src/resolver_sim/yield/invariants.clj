(ns resolver-sim.yield.invariants
  "Generic accounting invariants for yield mechanism (provider + Sew)."
  (:require [resolver-sim.yield.risk :as risk]
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
  "Principal/shares/realized ≥ 0; unrealized ≥ 0 unless :mark-to-market."
  [world]
  (let [positions (:yield/positions world {})]
    (every? (fn [pos]
              (let [risk (get-in world [:yield/risk (:module/id pos) (:token pos)] {})
                    mtm? (= :mark-to-market (risk/effective-loss-mode risk))]
                (and (>= (:principal pos 0) 0)
                     (>= (:shares pos 0) 0)
                     (>= (:realized-yield pos 0) 0)
                     (or mtm?
                         (>= (:unrealized-yield pos 0) 0)))))
            positions)))

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
   :yield/deferred-reclaim     check-deferred-reclaim})

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
  "Run a single invariant check; returns boolean."
  [inv-id world]
  (if-let [f (get check-fns inv-id)]
    (boolean (f (normalize-world-for-check world)))
    (throw (ex-info "Unknown yield invariant" {:invariant inv-id :known (registered-ids)}))))

(defn run-invariants
  "Run invariant checks; returns {inv-id {:holds? bool}}."
  [world inv-ids]
  (let [world* (normalize-world-for-check world)]
    (into {}
          (for [id inv-ids]
            [id (inv-result (holds? id world*))]))))

(defn check-all
  "Default runtime set for yield-v1 (see `invariant-catalog/default-runtime-invariant-ids`)."
  [world]
  (run-invariants world cat/default-runtime-invariant-ids))
