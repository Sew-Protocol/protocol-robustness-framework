(ns resolver-sim.scenario.projection
  "Terminal world-state projection for trace-end mechanism-property validation.

   Produces a stable, minimal projection of a replay result. Validators in
   resolver-sim.scenario.equilibrium receive only this projection — never raw
   world internals. This decouples validators from implementation details and
   keeps tests, docs, and TraceEquivalence integration stable.

   This namespace is pure — no I/O, no DB, no side effects.
   It currently reads SEW-specific world-snapshot fields and metrics; 
   this structure provides a template for future protocol integration.")

;; ---------------------------------------------------------------------------
;; Terminal-state helpers
;; ---------------------------------------------------------------------------

(def ^:private terminal-escrow-states
  "Escrow states from which no further transitions are possible."
  #{:released :refunded :cancelled :timeout})

(defn- terminal-state? [state]
  (contains? terminal-escrow-states (keyword (or state ""))))

;; ---------------------------------------------------------------------------
;; Projection
;; ---------------------------------------------------------------------------

(defn trace-end-projection
  "Produce a stable, minimal projection of a replay result for use by
   mechanism-property and equilibrium-concept validators.

   Returns a map with three keys:
     :terminal-world   — world state at the end of the trace
     :metrics          — accumulated metrics (plus nil slots for absent data)
     :trace-summary    — high-level trace statistics

   The caller (evaluate-equilibrium) passes this projection to every validator
   instead of the raw result, providing a stable, documented surface area.

   Returns nil when result has no trace (e.g. :outcome :invalid with 0 events)."
  [result]
  (let [trace       (:trace result [])
        last-entry  (last trace)
        world       (when last-entry (:world last-entry))]
    (when world
      (let [live-states  (get world :live-states {})
            escrows      (into {} (map (fn [[id s]] [id (keyword (or s ""))]) live-states))
            all-terminal (every? (fn [[_ s]] (terminal-state? s)) escrows)

            metrics      (:metrics result {})

            ;; Build agent-id -> address map from the scenario agents list.
            ;; Propagated by replay-scenario for SPE wealth lookup.
            agents-by-id (reduce (fn [m a]
                                   (let [id   (or (:id a) "")
                                         addr (or (:address a) id)]
                                     (assoc m id addr)))
                                 {}
                                 (:agents result []))

            ;; Derive escalation levels actually observed in trace
            escalation-levels
            (into #{} (keep (fn [entry]
                              (let [action (get entry :action "")]
                                (when (re-find #"escalat" action)
                                  (get-in entry [:extra :level]))))
                            trace))

            ;; Collect actors from trace entries
            actors
            (into #{} (keep :agent trace))

            ;; Distill decisions for SPE validation.
            ;; :address resolves agent-id -> protocol address so SPE can look up
            ;; resolver-stakes (keyed by address, not agent ID).
            decisions (vec (keep (fn [entry]
                                   (when (contains? #{"raise_dispute" "escalate_dispute" "release"
                                                      "sender_cancel" "recipient_cancel" "execute_resolution"}
                                                    (:action entry))
                                     (let [agent-id (:agent entry)
                                           addr     (get agents-by-id agent-id agent-id)]
                                       (assoc (select-keys entry [:seq :time :agent :action :extra])
                                              :address addr))))
                                 trace))]

        {:terminal-world
         {:escrows             escrows
          :total-held-by-token (get world :total-held {})
          :total-fees-by-token (get world :total-fees {})
          :escrow-amounts      (get world :escrow-amounts {})
          :dispute-resolvers   (get world :dispute-resolvers {})
          :dispute-levels      (get world :dispute-levels {})
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
          ;; nil when not populated by multi-epoch runner or payoff-ledger
          :coalition-net-profit        (get metrics :coalition-net-profit nil)
          :negative-payoff-count       (get metrics :negative-payoff-count nil)}

         :trace-summary
         {:events-count      (count trace)
          :actors            actors
          :dispute-count     (get metrics :disputes-triggered 0)
          :escalation-levels escalation-levels
          :terminal-time     (get world :block-time 0)
          :halt-reason       (:halt-reason result nil)}

         :decisions decisions
         :raw-trace trace}))))
