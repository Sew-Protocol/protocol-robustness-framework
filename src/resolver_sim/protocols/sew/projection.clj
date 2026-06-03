(ns resolver-sim.protocols.sew.projection
  "Sew-specific terminal trace projection.

   Produces the :terminal-world / :metrics / :trace-summary map expected by
   evaluate-mechanism-properties and evaluate-equilibrium-concepts when the
   Sew DisputeProtocol implementation is in use.

   This is the Sew reference implementation of the trace-projection protocol
   method.  The generic projection utilities (map-delta, nested-sum-by-actor,
   classify-coalition-actor?) live in resolver-sim.scenario.projection and are
   re-used here.

   This namespace is pure — no I/O, no DB, no side effects."
  (:require [resolver-sim.scenario.projection :as proj]
            [resolver-sim.protocols.sew.claimable-outcome :as claim-outcome]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.yield.evidence :as ye]))

;; ---------------------------------------------------------------------------
;; Sew terminal-state vocabulary
;; ---------------------------------------------------------------------------

(def ^:private terminal-escrow-states
  "Sew escrow states from which no further transitions are possible."
  #{:released :refunded :cancelled :timeout})

(defn- terminal-state? [state]
  (contains? terminal-escrow-states (keyword (or state ""))))

(defn- escalation-event? [action]
  (boolean (re-find #"escalat" (str (or action "")))))

(defn- withdrawal-event? [action]
  (boolean (re-find #"withdraw" (str (or action "")))))

(defn- slash-event? [action]
  (boolean (re-find #"slash|auto_cancel_disputed" (str (or action "")))))

(defn- strategic-action? [action]
  (contains? #{"create_escrow" "raise_dispute" "escalate_dispute" "execute_resolution"} action))

(defn terminal-world-from-result
  "Canonical terminal-world accessor for replay result payloads.
   Returns the world snapshot from the final trace entry, or nil when absent."
  [result]
  (get-in result [:trace (dec (count (:trace result))) :world]))

(defn- build-trace-context
  [result]
  (let [trace      (:trace result [])
        world      (terminal-world-from-result result)]
    (when world
      {:trace trace
       :world world
       :protocol (:protocol result)
       :metrics (:metrics result {})
       :agents (:agents result [])
       :halt-reason (:halt-reason result)})))

(declare funds-ledger-view)

(defn- build-module-snapshot-topology
  [world]
  (into {}
        (for [[wf snap] (get world :module-snapshots {})]
          [wf {:resolution-module         (:resolution-module snap)
               :release-strategy          (:release-strategy snap)
               :cancellation-strategy     (:cancellation-strategy snap)
               :yield-generation-module   (:yield-generation-module snap)
               :yield-distribution-module (:yield-distribution-module snap)
               :incentive-module          (:incentive-module snap)}])))

(defn- build-yield-routing-view
  [world]
  (into {}
        (for [[wf snap] (get world :module-snapshots {})]
          [wf {:escrow/modules           (:escrow/modules snap)
               :yield-module-id          (:yield-module-id snap)
               :yield-profile            (:yield-profile snap)
               :yield-archetype          (:yield-archetype snap)
               :yield-generation-module  (:yield-generation-module snap)}])))

(defn- build-yield-evidence-summary
  [world]
  (ye/canonical-yield-evidence
   {:routing-by-workflow (build-yield-routing-view world)}))

(defn- build-evidence
  [world]
  {:funds-ledger-summary (funds-ledger-view world)
   :yield-evidence       (build-yield-evidence-summary world)})

(defn- initial-stake-flow
  [trace world]
  (let [first-stakes (get-in (first trace) [:world :resolver-stakes] {})
        last-stakes  (get world :resolver-stakes {})
        keys-all     (into #{} (concat (keys first-stakes) (keys last-stakes)))]
    (reduce (fn [m r]
              (assoc m r {:start     (long (get first-stakes r 0))
                          :withdrawn 0
                          :slashed   0
                          :end       (long (get last-stakes r 0))}))
            {}
            keys-all)))

(defn- accumulate-transition
  [{:keys [token-deltas pending-lifecycle stake-flow] :as acc} [a b]]
  (let [action (str (or (:action b) ""))
        held-d (proj/map-delta (get-in a [:world :total-held] {}) (get-in b [:world :total-held] {}))
        fee-d  (proj/map-delta (get-in a [:world :total-fees] {}) (get-in b [:world :total-fees] {}))
        toks   (into #{} (concat (keys held-d) (keys fee-d)))
        token-deltas'
        (reduce (fn [m t]
                  (-> m
                      (update-in [t :held-delta] (fnil + 0) (long (get held-d t 0)))
                      (update-in [t :fee-delta] (fnil + 0) (long (get fee-d t 0)))
                      (update-in [t :claimable-delta] (fnil + 0) 0)))
                token-deltas
                toks)
        p0 (long (get-in a [:world :pending-count] 0))
        p1 (long (get-in b [:world :pending-count] 0))
        pending-lifecycle'
        (cond
          (> p1 p0) (update pending-lifecycle :created + (- p1 p0))
          (< p1 p0) (-> pending-lifecycle
                        (update :cleared + (- p0 p1))
                        (update :superseded + (if (escalation-event? action) (- p0 p1) 0)))
          :else pending-lifecycle)
        before (get-in a [:world :resolver-stakes] {})
        after  (get-in b [:world :resolver-stakes] {})
        deltas (proj/map-delta before after)
        stake-flow'
        (reduce (fn [m [resolver d]]
                  (if (neg? d)
                    (cond
                      (withdrawal-event? action) (update-in m [resolver :withdrawn] + (- d))
                      (slash-event? action) (update-in m [resolver :slashed] + (- d))
                      :else m)
                    m))
                stake-flow
                deltas)]
    (assoc acc
           :token-deltas token-deltas'
           :pending-lifecycle pending-lifecycle'
           :stake-flow stake-flow')))

(defn- derive-transition-summaries
  [trace world]
  (let [transitions (map vector trace (rest trace))
        init {:token-deltas {}
              :pending-lifecycle {:created 0 :cleared 0 :superseded 0}
              :stake-flow (initial-stake-flow trace world)}]
    (assoc (reduce accumulate-transition init transitions)
           :transitions transitions)))

(defn- derive-shortfall-summary
  [world]
  (let [positions (vals (get world :yield/positions {}))
        rows      (keep :shortfall positions)
        reasons   (->> rows (keep :reason) (map str) set sort vec)]
    {:entry-count (count rows)
     :shortfall-explicit? true
     :has-shortfall? (pos? (count rows))
     :fulfilled-total (reduce + 0 (map #(long (get % :fulfilled-amount 0)) rows))
     :deferred-total (reduce + 0 (map #(long (get % :deferred-amount 0)) rows))
     :haircut-total (reduce + 0 (map #(long (get % :haircut-amount 0)) rows))
     :reasons reasons
     :rounding-policy "floor-to-asset-decimals.v1"}))

;; ---------------------------------------------------------------------------
;; Sew trace-end projection
;; ---------------------------------------------------------------------------

(defn trace-end-projection
  "Produce a stable, minimal projection of a Sew replay result for use by
   mechanism-property and equilibrium-concept validators.

   Returns a map with keys:
     :protocol                — the protocol implementation instance
     :agents                  — the agents involved in the scenario
     :protocol-params         — the protocol parameters
     :scenario-id             — the scenario identifier
     :terminal-world          — world state at end of trace
     :metrics                 — accumulated metrics
     :trace-summary           — high-level trace statistics
     :money-movement-summary  — workflow outcomes, pending lifecycle, token deltas
     :payoff-ledger-summary   — per-actor payoff ledger
     :stake-flow-summary      — per-resolver stake flow
     :decisions               — strategic decision nodes in trace
     :raw-trace               — the full trace vector

   Returns nil when result has no trace (e.g. :outcome :invalid with 0 events).

   1-arity form is provided for backward compatibility; protocol defaults to nil."
  ([result] (trace-end-projection nil result))
  ([protocol result]
  (when-let [{:keys [trace world metrics agents halt-reason]} (build-trace-context result)]
      (let [live-states  (get world :live-states {})
            scenario-id  (get-in world [:params :scenario-id] "unknown")
            p-params     (get world :params {})
            escrows      (into {} (map (fn [[id s]] [id (keyword (or s ""))]) live-states))
            all-terminal (every? (fn [[_ s]] (terminal-state? s)) escrows)

            agents-by-id (reduce (fn [m a]
                                   (let [id   (or (:id a) "")
                                         addr (or (:address a) id)]
                                     (assoc m id addr)))
                                 {}
                                 agents)

            escalation-levels
            (into #{} (keep (fn [entry]
                              (let [action (get entry :action "")]
                                (when (escalation-event? action)
                                  (get-in entry [:extra :level]))))
                            trace))

            actors
            (into #{} (keep :agent trace))

            ;; Strategic decision nodes — Sew-specific action vocabulary.
            decisions (vec (keep (fn [entry]
                                   (when (strategic-action? (:action entry))
                                     (let [agent-id (:agent entry)
                                           addr     (get agents-by-id agent-id agent-id)]
                                       (assoc (select-keys entry [:seq :time :agent :action :extra])
                                              :address addr))))
                                  trace))

            {:keys [transitions token-deltas pending-lifecycle stake-flow]}
            (derive-transition-summaries trace world)
            shortfall-summary (derive-shortfall-summary world)
            escrow-yield-outcomes (claim-outcome/outcomes-by-workflow world)

            coalition-addrs
            (into #{}
                  (keep (fn [a]
                          (when (proj/classify-coalition-actor? a)
                            (or (:address a) (:id a)))))
                  agents)

            payoff-ledger
            (reduce (fn [acc [a b]]
                      (let [action      (str (or (:action b) ""))
                            actor       (or (:agent b) "unknown")
                            addr        (get agents-by-id actor actor)

                            held-d      (proj/map-delta (get-in a [:world :total-held] {}) (get-in b [:world :total-held] {}))
                            held-neg    (reduce + 0 (map (fn [[_ d]] (if (neg? d) (- d) 0)) held-d))
                            held-pos    (reduce + 0 (map (fn [[_ d]] (if (pos? d) d 0)) held-d))

                            fee-d       (proj/map-delta (get-in a [:world :total-fees] {}) (get-in b [:world :total-fees] {}))
                            fee-inc     (reduce + 0 (map (fn [[_ d]] (if (pos? d) d 0)) fee-d))

                            stakes-d    (proj/map-delta (get-in a [:world :resolver-stakes] {}) (get-in b [:world :resolver-stakes] {}))
                            stake-delta (long (get stakes-d addr 0))
                            slash?      (and (slash-event? action) (neg? stake-delta))

                            bonds-a     (proj/nested-sum-by-actor (get-in a [:world :bond-balances] {}))
                            bonds-b     (proj/nested-sum-by-actor (get-in b [:world :bond-balances] {}))
                            bond-delta  (- (long (get bonds-b addr 0)) (long (get bonds-a addr 0)))

                            claim-a     (proj/nested-sum-by-actor (get-in a [:world :claimable] {}))
                            claim-b     (proj/nested-sum-by-actor (get-in b [:world :claimable] {}))
                            claim-delta (- (long (get claim-b addr 0)) (long (get claim-a addr 0)))

                            inflow      (+ (max 0 claim-delta) held-neg)
                            outflow     (+ (max 0 (- claim-delta)) held-pos)

                            fee-paid    (if (pos? fee-inc) fee-inc 0)
                            fee-recv    0
                            slash-loss  (if (and slash? (neg? stake-delta)) (- stake-delta) 0)
                            bond-lock   (max 0 bond-delta)
                            bond-release (max 0 (- bond-delta))]
                        (-> acc
                            (update-in [addr :inflows] (fnil + 0) inflow)
                            (update-in [addr :outflows] (fnil + 0) outflow)
                            (update-in [addr :fees-paid] (fnil + 0) fee-paid)
                            (update-in [addr :fees-received] (fnil + 0) fee-recv)
                            (update-in [addr :slash-penalties] (fnil + 0) slash-loss)
                            (update-in [addr :bond-lock-delta] (fnil + 0) bond-lock)
                            (update-in [addr :bond-release-delta] (fnil + 0) bond-release))))
                    {}
                    transitions)

            payoff-ledger
            (reduce-kv (fn [m addr row]
                         (let [net (- (+ (:inflows row 0)
                                         (:fees-received row 0)
                                         (:bond-release-delta row 0))
                                      (+ (:outflows row 0)
                                         (:fees-paid row 0)
                                         (:slash-penalties row 0)
                                         (:bond-lock-delta row 0)))]
                           (assoc m addr (assoc row :net-payoff net))))
                       {}
                       payoff-ledger)

            negative-payoff-count
            (count (filter (fn [[_ row]] (neg? (long (:net-payoff row 0)))) payoff-ledger))

            coalition-net-profit
            (when (seq coalition-addrs)
              (reduce + 0
                      (for [addr coalition-addrs]
                        (long (get-in payoff-ledger [addr :net-payoff] 0)))))

            workflow-outcomes
            (into {}
                  (map (fn [[wf st]]
                         [wf {:terminal-state st
                              :path (case st
                                      :released :release
                                      :refunded :refund
                                      :cancelled :cancel
                                      :timeout :timeout
                                      :non-terminal)}])
                       escrows))]

        (let [snapshot-routing (build-yield-routing-view world)
              snapshot-topology (build-module-snapshot-topology world)
              {:keys [funds-ledger-summary yield-evidence]} (build-evidence world)
              funds-ledger funds-ledger-summary]
          {:terminal-world
         {:escrows             escrows
          :total-held-by-token (get world :total-held {})
          :total-fees-by-token (get world :total-fees {})
          :escrow-amounts      (get world :escrow-amounts {})
          :dispute-resolvers   (get world :dispute-resolvers {})
          :dispute-levels      (get world :dispute-levels {})
           :module-topology-by-workflow snapshot-topology
           :yield-routing-by-workflow snapshot-routing
          :pending-count       (get world :pending-count 0)
          :resolver-stakes     (get world :resolver-stakes {})
          :terminal?           all-terminal
          :all-terminal-states (into #{} (filter terminal-state? (vals escrows)))}

         :metrics
         {:total-escrows               (get metrics :total-escrows 0)
          :total-volume                (get metrics :total-volume 0)
          :disputes-triggered          (get metrics :disputes-triggered 0)
          :resolutions-executed        (get metrics :resolutions-executed 0)
          :pending-settlements-executed (get metrics :pending-settlements-executed 0)
          :attack-attempts             (get metrics :attack-attempts 0)
          :attack-successes            (get metrics :attack-successes 0)
          :rejected-attacks            (get metrics :rejected-attacks 0)
          :reverts                     (get metrics :reverts 0)
          :invariant-violations        (get metrics :invariant-violations 0)
          :double-settlements          (get metrics :double-settlements 0)
          :invalid-state-transitions   (get metrics :invalid-state-transitions 0)
          :funds-lost                  (get metrics :funds-lost 0)
          :coalition-net-profit        (let [mval (get metrics :coalition-net-profit)]
                                          (if (some? mval) mval coalition-net-profit))
          :negative-payoff-count       (let [mval (get metrics :negative-payoff-count)]
                                          (if (some? mval) mval negative-payoff-count))}

         :trace-summary
         {:events-count      (count trace)
          :actors            actors
          :dispute-count     (get metrics :disputes-triggered 0)
          :escalation-levels escalation-levels
          :terminal-time     (get world :block-time 0)
          :halt-reason       halt-reason
          :funds-conservation-holds? (get-in funds-ledger [:conservation :holds?])
          :funds-drift-total         (get-in funds-ledger [:conservation :drift-total])
          :funds-drift-by-token      (get-in funds-ledger [:conservation :drift-by-token])}

         :story-facts
         {:scenario-classification
          (cond
            (pos? (get metrics :attack-successes 0)) :adversarial-success
            (pos? (get metrics :rejected-attacks 0)) :adversarial-deflected
            :else :neutral)
          :attack-attempts (get metrics :attack-attempts 0)
          :attack-successes (get metrics :attack-successes 0)
          :rejected-attacks (get metrics :rejected-attacks 0)
          :funds-conservation-holds? (get-in funds-ledger [:conservation :holds?])
          :funds-drift-total (get-in funds-ledger [:conservation :drift-total])
          :coalition-net-profit (let [mval (get metrics :coalition-net-profit)]
                                  (if (some? mval) mval coalition-net-profit))
          :negative-payoff-count (let [mval (get metrics :negative-payoff-count)]
                                   (if (some? mval) mval negative-payoff-count))
          :terminal-state-counts (frequencies (vals escrows))
          :terminal-time (get world :block-time 0)}

         :money-movement-summary
         {:workflow-outcomes workflow-outcomes
          :post-dispute-transfers {:unknown {:to-sender 0 :to-recipient 0 :fees 0}}
          :pending-lifecycle {:unknown pending-lifecycle}

         :shortfall-summary shortfall-summary
          :token-deltas token-deltas}

         :payoff-ledger-summary
         {:per-actor payoff-ledger
          :negative-payoff-count negative-payoff-count
          :coalition-net-profit coalition-net-profit}

         :stake-flow-summary stake-flow

         :protocol protocol
         :agents agents
         :protocol-params p-params
         :scenario-id scenario-id
         :decisions decisions
         :raw-trace trace
         :funds-ledger-summary funds-ledger
         :yield-evidence yield-evidence
         :escrow-yield-outcomes escrow-yield-outcomes})))))

;; ---------------------------------------------------------------------------
;; Read-only use-of-funds projection
;; ---------------------------------------------------------------------------

(defn funds-ledger-view
  "Return a read-only use-of-funds ledger projection for a Sew world.

   Output is intentionally accounting-centric and protocol-consumer friendly:
   - token-level buckets (held/released/refunded/withdrawn/bond flows)
   - global custody buckets (claimable, bond-locked, bond-fees, distribution)
   - conservation verdict and explicit drift summaries."
  [world]
  (let [held         (:total-held world {})
        released     (:total-released world {})
        refunded     (:total-refunded world {})
        withdrawn    (:total-withdrawn world {})
        posted       (:total-bonds-posted world {})
        slashed      (:bond-slashed world {})
        escrows      (:escrow-transfers world {})
        tokens       (-> #{}
                         (into (keys held))
                         (into (keys released))
                         (into (keys refunded))
                         (into (keys withdrawn))
                         (into (keys posted))
                         (into (map :token (vals escrows))))
        slashed-by-token
        (reduce (fn [acc [wf amt]]
                  (if-let [token (get-in escrows [wf :token])]
                    (update acc token (fnil + 0) amt)
                    acc))
                {}
                slashed)
        by-token
        (into {}
              (for [token tokens]
                [token {:held         (long (get held token 0))
                        :released     (long (get released token 0))
                        :refunded     (long (get refunded token 0))
                        :withdrawn    (long (get withdrawn token 0))
                        :bond-posted  (long (get posted token 0))
                        :bond-slashed (long (get slashed-by-token token 0))}]))
        claimable-total
        (reduce + 0 (for [wf (vals (:claimable world {}))
                          amt (vals wf)]
                      (long amt)))
        bond-locked-total
        (reduce + 0 (for [wf (vals (:bond-balances world {}))
                          amt (vals wf)]
                      (long amt)))
        bond-fees-total (reduce + 0 (map long (vals (:bond-fees world {}))))
        bond-distribution-total
        (let [d (:bond-distribution world {:insurance 0 :protocol 0 :burned 0})]
          (+ (long (:insurance d 0))
             (long (:protocol d 0))
             (long (:burned d 0))))
        retained-total (long (:retained-slash-reserves world 0))
        conservation   (inv/conservation-of-funds? world)
        drift-by-token (into {}
                             (for [{:keys [token accounted inflow]} (:violations conservation [])]
                               [token (- (long accounted) (long (or inflow 0)))]))
        drift-total    (reduce + 0 (vals drift-by-token))]
    {:as-of-block-time (:block-time world)
     :by-token by-token
     :global {:claimable-total          claimable-total
              :bond-locked-total        bond-locked-total
              :bond-fees-total          bond-fees-total
              :bond-distribution-total  bond-distribution-total
              :retained-slash-reserves  retained-total}
     :conservation {:holds?        (:holds? conservation)
                    :drift-total   drift-total
                    :drift-by-token drift-by-token
                    :violations    (:violations conservation [])}}))
