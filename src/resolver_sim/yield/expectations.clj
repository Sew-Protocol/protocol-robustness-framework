(ns resolver-sim.yield.expectations
  "World-level expectation checkers for yield scenarios."
  (:require [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.evidence :as yield-evi]
            [resolver-sim.protocols.sew.types :as t]))

(defn- check-yield-expectation [world exp terminal?]
  (let [total-yield (reduce + (vals (:total-yield-generated world {})))
        min-y (:min-accrued-yield exp 0)
        max-y (:max-accrued-yield exp Double/MAX_VALUE)]
    (cond
      ;; Max yield is an 'always' check
      (> total-yield max-y)
      {:holds? false :error :yield-too-high :actual total-yield :expected max-y}
      
      ;; Min yield is a 'finally' check
      (and terminal? (< total-yield min-y))
      {:holds? false :error :yield-too-low :actual total-yield :expected min-y}
      
      :else {:holds? true})))

(defn- check-partial-liquidity-expectation [world exp terminal?]
  (let [positions (:yield/positions world {})
        shortfalls (keep :shortfall (vals positions))
        reclaimed (reduce + (keep :reclaimed-amount (vals positions)))
        actual-shortfall (+ (reduce + (map :deferred-amount shortfalls))
                            reclaimed)
        expected-sf (:expected-shortfall exp {})
        min-sf (:min expected-sf 0)
        max-sf (:max expected-sf Double/MAX_VALUE)]
    (cond
      ;; Max shortfall is an 'always' check
      (> actual-shortfall max-sf)
      {:holds? false :error :shortfall-too-high :actual actual-shortfall :expected max-sf}
      
      ;; Min shortfall is a 'finally' check
      (and terminal? (< actual-shortfall min-sf))
      {:holds? false :error :shortfall-too-low :actual actual-shortfall :expected min-sf}
      
      :else {:holds? true})))

(defn- all-terminal?
  "Check if all entities in the world are in terminal states.
   A world is terminal if all escrows are terminal AND no yield positions are unwinding."
  [world]
  (let [transfers (:escrow-transfers world)
        positions (:yield/positions world)]
    (let [escrows-done? (if (seq transfers)
                          (every? #(contains? t/terminal-states (:escrow-state (val %))) transfers)
                          true)
          yields-done?  (if (seq positions)
                          (not-any? #(= (:status (val %)) :unwinding) positions)
                          true)]
      (and escrows-done? yields-done?))))

(defn- check-loss-expectation [world exp terminal?]
  (let [tokens (keys (:total-principal-deposited world {}))
        actual-loss (reduce + (map #(yield-evi/sum-recognized-losses world %) tokens))
        min-loss (:min-recognized-principal-loss exp 0)
        max-loss (:max-recognized-principal-loss exp Double/MAX_VALUE)]
    (cond
      (> actual-loss max-loss)
      {:holds? false :error :loss-too-high :actual actual-loss :expected max-loss}
      
      (and terminal? (< actual-loss min-loss))
      {:holds? false :error :loss-too-low :actual actual-loss :expected min-loss}
      
      :else {:holds? true})))

(defn check-expectations
  "Check all yield expectations for the current world state."
  [world]
  (let [exp (get-in world [:params :expectations])
        terminal? (all-terminal? world)]
    (if (nil? exp)
      {:ok? true}
      (let [y-res  (when (:yield exp) (check-yield-expectation world (:yield exp) terminal?))
            pl-res (when (:partial-liquidity exp) (check-partial-liquidity-expectation world (:partial-liquidity exp) terminal?))
            l-res  (when (:losses exp) (check-loss-expectation world (:losses exp) terminal?))
            results (cond-> {}
                      y-res (assoc :yield y-res)
                      pl-res (assoc :partial-liquidity pl-res)
                      l-res (assoc :losses l-res))]
        {:ok? (every? :holds? (vals results))
         :results results}))))
