(ns resolver-sim.protocols.sew.accounting
  "Pure Clojure port of EscrowVault balance and fee accounting, plus
   BondCollector fee deduction logic.

   Covers:
     - total-held-per-token tracking (add on create, sub on release/refund)
     - total-fees-per-token (monotonically increasing; withdraw-fees resets)
     - claimable-balances (pull-settlement entitlements; cleared on withdrawEscrow)
     - withdraw-fees
     - BondCollector appeal bond accounting

   All arithmetic uses integer division (uint256 truncation semantics)."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.hash.canonical :as hash]))

(declare sub-held record-fee record-claimable)

;; ---------------------------------------------------------------------------
;; total-held tracking
;; ---------------------------------------------------------------------------

(def ^:private exceptional-held-reasons
  #{:governance-authorised-correction
    :replay-fixture-setup
    :replay-migration
    :force-authorised-release
    :force-authorised-refund
    :partial-fill-principal-loss})

(def ^:private address-scoped-held-reasons
  "Reasons for which :owner/address MUST be explicitly provided.
   Fallback-derived ownership (actor, from, resolver, recipient) is not
   permitted — forensic ownership must be unambiguous."
  #{:escrow-principal-deposited
    :escrow-settlement-released
    :escrow-settlement-refunded
    :force-authorised-release
    :force-authorised-refund
    :partial-fill-principal-loss
    :yield-distributed
    :resolver-slash-custody-debited
    :governance-authorised-correction})

(def ^:private held-position-policy
  {:escrow-principal-deposited {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :escrow-settlement-released {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :escrow-settlement-refunded {:held/account :escrow-principal
                                :scope-keys [:held/workflow-id]}
   :force-authorised-release {:held/account :escrow-principal
                              :scope-keys [:held/workflow-id]}
   :force-authorised-refund {:held/account :escrow-principal
                             :scope-keys [:held/workflow-id]}
   :appeal-bond-posted {:held/account :appeal-bond
                        :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :appeal-bond-returned {:held/account :appeal-bond
                          :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :appeal-bond-slashed {:held/account :appeal-bond
                         :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :appeal-bond-forfeited {:held/account :appeal-bond
                           :scope-keys [:held/slash-id :held/bond-id :held/workflow-id :held/actor]}
   :yield-distributed {:held/account :yield-custody
                       :scope-keys [:held/workflow-id]}
   :deferred-yield-claimed {:held/account :yield-custody
                            :scope-keys [:held/owner-id :held/workflow-id]}
   :resolver-yield-withdrawn {:held/account :resolver-yield
                              :scope-keys [:held/owner-id :held/resolver]}
   :resolver-slash-custody-debited {:held/account :resolver-slash-custody
                                     :scope-keys [:held/resolver :held/workflow-id]}
   :partial-fill-principal-loss {:held/account :escrow-principal
                                 :scope-keys [:held/workflow-id]}})

(defn- validate-held-inputs!
  [token amount]
  (when (nil? token)
    (throw (ex-info "held adjustment requires token"
                    {:type :invalid-held-adjustment
                     :reason :missing-token})))
  (when (or (nil? amount) (neg? amount))
    (throw (ex-info "held adjustment requires non-negative amount"
                    {:type :invalid-held-adjustment
                     :reason :invalid-amount
                     :amount amount}))))

(defn- force-authorisation-scope-hash
  [scope-map]
  (hash/domain-hash "force-authorisation-scope" scope-map))

(defn- force-authorisation-expired?
  "True when the scope hash in auth-provenance does not match the scope-map
   derived from the actual held adjustment fields.  Prevents scope drift between
   authorization and execution."
  [auth-provenance scope-map]
  (let [expected (:authorization/scope-hash auth-provenance)
        actual (force-authorisation-scope-hash scope-map)]
    (not= expected actual)))

(defn- ensure-force-authorisation-usable!
  "Guard: check authorization not consumed and scope hash matches.
   Also rejects cross-direction reuse when direction is stored on the
   consumed entry.
   Throws on already-consumed or scope mismatch.
   Does NOT short-circuit for idempotent replay — that is handled at the
   outer command layer (dispatch-action / force-authorized entry point)."
  [world auth-provenance scope-map direction]
  (let [auth-id (:authorization/id auth-provenance)]
    (when-let [consumed (get-in world [:force-authorisations/consumed auth-id])]
      (throw (ex-info "force-authorisation already consumed"
                      {:type :authorization/already-consumed
                       :authorization/id auth-id
                       :consumed consumed
                       :attempt scope-map})))
    (when (force-authorisation-expired? auth-provenance scope-map)
      (throw (ex-info "force-authorisation scope mismatch"
                      {:type :authorization/scope-mismatch
                       :authorization/id auth-id
                       :authorization/scope-hash (:authorization/scope-hash auth-provenance)
                       :derived-scope-hash (force-authorisation-scope-hash scope-map)
                       :scope-map scope-map})))))

(defn- mark-force-authorisation-consumed
  [world auth-provenance adjustment]
  (let [auth-id (:authorization/id auth-provenance)]
    (assoc-in world [:force-authorisations/consumed auth-id]
              (merge {:consumed? true
                      :authorization/id auth-id
                      :authorization/type (:authorization/type auth-provenance)
                      :authorization/scope-hash (:authorization/scope-hash auth-provenance)
                      :held-adjustment/id (:held-adjustment/id adjustment)
                      :token (:token adjustment)
                      :amount (:amount adjustment)
                      :owner/address (:owner/address adjustment)
                      :workflow-id (:held/workflow-id adjustment)
                      :held/reason (:held/reason adjustment)
                      :consumed/action (:held/action adjustment)}
                     (when-let [d (:held/direction adjustment)]
                       {:held/direction d})))))

(defn- next-held-adjustment-id
  [world]
  (str "held-adjustment-" (count (:held-adjustments world []))))

(defn- preferred-held-value
  [m preferred-key fallback-key]
  (or (get m preferred-key)
      (get m fallback-key)))

(defn- held-position-components
  [token reason extra]
  (let [scope (merge {:held/workflow-id (preferred-held-value extra :held/workflow-id :workflow-id)
                      :held/bond-id (preferred-held-value extra :held/bond-id :bond-id)
                      :held/slash-id (preferred-held-value extra :held/slash-id :slash-id)
                      :held/actor (preferred-held-value extra :held/actor :actor)
                      :held/resolver (preferred-held-value extra :held/resolver :resolver)
                      :held/owner-id (preferred-held-value extra :held/owner-id :owner-id)
                      :held/from (preferred-held-value extra :held/from :from)
                      :held/to (preferred-held-value extra :held/to :to)
                      :held/recipient (preferred-held-value extra :held/recipient :recipient)
                      :owner/address (preferred-held-value extra :owner/address :address)}
                     extra)
        account-override (:held/account scope)
        position-override (:held/position-id scope)
        owner-address-override (:owner/address scope)
        policy (get held-position-policy reason)
        account (or account-override (:held/account policy))
        scope-values (cond
                       position-override nil
                       (seq (:scope-keys policy))
                       (->> (:scope-keys policy)
                            (keep #(get scope %))
                            vec)
                       :else nil)
        position-id (or position-override
                        (when (and account (seq scope-values))
                          (into [:held/position token account] scope-values)))
        owner-address (or owner-address-override
                          (:held/actor scope)
                          (:held/from scope)
                          (:held/resolver scope)
                          (:held/recipient scope))]
    (cond-> {:held/account account
             :held/position-id position-id}
      owner-address
      (assoc :owner/address owner-address))))

(defn- update-ledger-index
  [world adjustment]
  (let [{direction :held/direction
         token :token
         amount :amount
         position-id :held/position-id
         held-account :held/account
         owner-address :owner/address} adjustment
        workflow-id (:held/workflow-id adjustment)
        step-fn (case direction
                  :in +
                  :out -)
        world* (-> world
                   (update :held-ledger/index
                           (fn [idx]
                             (merge {:by-token (:total-held world {})
                                     :by-position (:held/positions world {})
                                     :by-account {}
                                     :by-owner {}
                                     :by-workflow {}}
                                    idx))))]
    (let [world' (cond-> (update-in world* [:held-ledger/index :by-token token] (fnil step-fn 0) amount)
                   position-id
                   (update-in [:held-ledger/index :by-position position-id] (fnil step-fn 0) amount)

                   held-account
                   (update-in [:held-ledger/index :by-account held-account] (fnil step-fn 0) amount)

                   owner-address
                   (update-in [:held-ledger/index :by-owner owner-address] (fnil step-fn 0) amount)

                   workflow-id
                   (update-in [:held-ledger/index :by-workflow workflow-id] (fnil step-fn 0) amount))]
      (-> world'
          (assoc :total-held (get-in world' [:held-ledger/index :by-token] {}))
          (assoc :held/positions (get-in world' [:held-ledger/index :by-position] {}))))))

(defn- build-held-adjustment
  [world token amount direction action reason authorization-provenance extra]
  (let [before (get-in world [:total-held token] 0)
        after  (case direction
                 :in  (+ before amount)
                 :out (- before amount))
        position-fields (held-position-components token reason extra)]
    (merge {:held-adjustment/id (next-held-adjustment-id world)
            :held/direction direction
            :token token
            :amount amount
            :held/before before
            :held/after after
            :held/reason (or reason :held/unspecified)
            :held/action action}
           position-fields
           (when authorization-provenance
             {:authorization/provenance authorization-provenance})
           extra)))

(defn- append-held-adjustment
  [world adjustment]
  (update world :held-adjustments (fnil conj []) adjustment))

(defn- adjust-held
  [world token amount direction {:keys [action reason authorization-provenance extra]
                                 :or {action "adjust-held"}}]
  (validate-held-inputs! token amount)
  (when (and (contains? exceptional-held-reasons reason)
             (nil? authorization-provenance))
    (throw (ex-info "exceptional held adjustment requires authorization provenance"
                    {:type :invalid-held-adjustment
                     :reason :missing-authorization-provenance
                     :held/reason reason})))
  (when (and (contains? address-scoped-held-reasons reason)
             (nil? (:owner/address (held-position-components token reason (or extra {})))))
    (attr/log-with-attr
      :warn "address-scoped held reason missing :owner/address — owner may be misattributed"
      {:held/reason reason
       :extra extra}))
  (let [is-force-auth? (= :force-authorisation (:authorization/type authorization-provenance))]
    (when is-force-auth?
      (let [components (held-position-components token reason (or extra {}))
            scope-map (merge {:authorization/id (:authorization/id authorization-provenance)
                              :authorization/type :force-authorisation
                              :held/direction direction
                              :token token
                              :held/account (:held/account components)
                              :owner/address (:owner/address components)
                              :held/reason reason}
                             (select-keys (or extra {}) [:held/workflow-id]))]
        (ensure-force-authorisation-usable! world authorization-provenance scope-map direction)))
    (let [current (get-in world [:total-held token] 0)]
      (when (and (= direction :out) (< current amount))
        (throw (ex-info "sub-held underflow"
                        {:type   :sub-held-underflow
                         :token  token
                         :held   current
                         :amount amount})))
      (let [adjustment (build-held-adjustment world
                                              token
                                              amount
                                              direction
                                              action
                                              reason
                                              authorization-provenance
                                              extra)
            world' (update-ledger-index world adjustment)
            world'' (append-held-adjustment world' adjustment)]
        (if is-force-auth?
          (mark-force-authorisation-consumed world'' authorization-provenance adjustment)
          world'')))))

(defn replay-held-adjustment-state
  "Replay a held-adjustment ledger into replay-verified materialized custody
   views. The ledger is canonical; returned indexes and balances are derived."
  ([adjustments] (replay-held-adjustment-state {} adjustments))
  ([initial-held adjustments]
   (let [initial-state {:held-ledger/index {:by-token initial-held
                                            :by-position {}
                                            :by-account {}
                                            :by-owner {}
                                            :by-workflow {}}
                        :total-held initial-held
                        :held/positions {}}]
     (reduce (fn [{total-held :total-held
                   index :held-ledger/index
                   positions :held/positions} adjustment]
             (let [{direction :held/direction
                    token :token
                    amount :amount
                    before :held/before
                    after :held/after
                    position-id :held/position-id
                    held-account :held/account
                    owner-address :owner/address} adjustment
                   workflow-id (:held/workflow-id adjustment)
                   current (get total-held token 0)
                   expected-after (case direction
                                    :in  (+ current amount)
                                    :out (- current amount)
                                    (throw (ex-info "invalid held direction"
                                                    {:type :invalid-held-adjustment
                                                     :direction direction
                                                     :adjustment adjustment})))]
               (when (nil? token)
                 (throw (ex-info "held adjustment missing token"
                                 {:type :invalid-held-adjustment
                                  :adjustment adjustment})))
               (when (or (nil? amount) (neg? amount))
                 (throw (ex-info "held adjustment amount must be non-negative"
                                 {:type :invalid-held-adjustment
                                  :adjustment adjustment})))
               (when (neg? expected-after)
                 (throw (ex-info "held adjustment replay underflow"
                                 {:type :invalid-held-adjustment
                                  :adjustment adjustment
                                  :current current})))
               (when (not= current before)
                 (throw (ex-info "held adjustment before mismatch"
                                 {:type :invalid-held-adjustment
                                  :adjustment adjustment
                                  :current current})))
               (when (not= expected-after after)
                 (throw (ex-info "held adjustment after mismatch"
                                 {:type :invalid-held-adjustment
                                  :adjustment adjustment
                                  :expected-after expected-after})))
               (let [step-fn (case direction
                               :in +
                               :out -)
                     index' (cond-> (update-in index [:by-token token] (fnil step-fn 0) amount)
                              position-id
                              (update-in [:by-position position-id] (fnil step-fn 0) amount)

                              held-account
                              (update-in [:by-account held-account] (fnil step-fn 0) amount)

                              owner-address
                              (update-in [:by-owner owner-address] (fnil step-fn 0) amount)

                              workflow-id
                              (update-in [:by-workflow workflow-id] (fnil step-fn 0) amount))]
                 {:held-ledger/index index'
                  :total-held (:by-token index')
                  :held/positions (:by-position index')})))
           initial-state
           adjustments))))

(defn replay-held-adjustments
  "Replay a held-adjustment ledger back into a token=>amount map.
   Used for forensic reconstruction when a world declares that its held
   adjustments are complete."
  ([adjustments] (replay-held-adjustments {} adjustments))
  ([initial-held adjustments]
   (:total-held (replay-held-adjustment-state initial-held adjustments))))

(defn add-held
  "Increase protocol-held custody balance for token.

   Use only when assets enter the escrow/bond custody pool.
   Do not use for resolver stake, which is tracked separately in
   :resolver-stakes.

  Optional opts:
   - :action                    logical mutation action string
   - :reason                    economic custody reason keyword
   - :authorization-provenance  structured authorization provenance
   - :extra                     extra machine-readable held-adjustment metadata"
  ([world token amount opts]
   (adjust-held world token amount :in (merge {:action "add-held"} opts))))

(defn sub-held
  "Decrease total-held for token by amount. Called on release/refund.
   Callers must have validated state. Throws a catchable ex-info on underflow
   so process-step's (catch Exception) handler converts it to :dispatch-exception
   rather than propagating an AssertionError past the catch boundary."
  ([world token amount opts]
   (adjust-held world token amount :out (merge {:action "sub-held"} opts))))

;; ---------------------------------------------------------------------------
;; total-fees tracking
;; ---------------------------------------------------------------------------

(defn record-fee
  "Accumulate fee into total-fees. Monotonically increasing.
   Mirrors FeeRecordingLibrary.recordFee in EscrowVault."
  [world token amount]
  (update-in world [:total-fees token] (fnil + 0) amount))

(defn withdraw-fees
  "Withdraw all accumulated fees for token.
   Sets total-fees[token] = 0 and returns {:ok true :world world' :amount amount}.
   Mirrors EscrowVault.withdrawFees.

   Guard: amount must be > 0.
   Guard: token must not be in a liquidity-crunch."
  [world token]
  (let [amount (get-in world [:total-fees token] 0)]
    (cond
      (zero? amount)
      (t/fail :no-fees-to-withdraw)

      (contains? (:token-liquidity-crunch world #{}) token)
      (t/fail :liquidity-insufficient)

      :else
      (let [world' (-> world
                       (assoc-in [:total-fees token] 0)
                       (update-in [:total-withdrawn token] (fnil + 0) amount))]
        (attr/with-attribution {:subject/type :token
                                :subject/id token
                                :action/type :fees/withdraw
                                :evidence/reason :fees-withdrawn}
          (cap/capture-event-evidence!
           :fees-withdrawn
           {:fee/before {:total-fees amount}}
           {:fee/after {:total-fees 0
                        :total-withdrawn (get-in world' [:total-withdrawn token])}}
           {:fee/token token
            :fee/amount amount}))
        (assoc (t/ok world') :amount amount)))))

;; ---------------------------------------------------------------------------
;; Claimable balances (pull-settlement model)
;;
;; Settlement creates claimableBalances[workflowId][addr] entitlements.
;; Funds are delivered explicitly via withdrawEscrow().
;; ---------------------------------------------------------------------------

(defn record-released
  "Track amount released to recipient. Called alongside sub-held on finalize-release."
  [world token amount]
  (update-in world [:total-released token] (fnil + 0) amount))

(defn record-refunded
  "Track amount refunded to sender. Called alongside sub-held on finalize-refund."
  [world token amount]
  (update-in world [:total-refunded token] (fnil + 0) amount))

;; ---------------------------------------------------------------------------
;; Claimable balances (pull-settlement model)
;;
;; Settlement creates claimableBalances[workflowId][addr] entitlements.
;; Funds are delivered explicitly via withdrawEscrow().
;; ---------------------------------------------------------------------------

(defn record-claimable
  "Record amount as claimable by addr for workflow-id.
   Depreciated: use record-claimable-v2 instead.
   Mirrors: claimableBalances[workflowId][recipient] += amount"
  [world workflow-id addr amount]
  (let [;; Fallback domain for legacy calls: settle to settlement/principal
        world' (update-in world [:claimable workflow-id addr] (fnil + 0) amount)]
    (update-in world' [:claimable-v2 workflow-id :settlement/principal addr] (fnil + 0) amount)))

(defn record-claimable-v2
  "Record amount as claimable by addr for workflow-id in a specific domain.
   Also updates the legacy :claimable map for temporary backward compatibility."
  [world workflow-id domain addr amount]
  (let [world' (update-in world [:claimable-v2 workflow-id domain addr] (fnil + 0) amount)]
    ;; Maintain dual-write to legacy map for settlement claimables (withdraw path)
    (if (#{:settlement/principal :settlement/yield} domain)
      (update-in world' [:claimable workflow-id addr] (fnil + 0) amount)
      world')))

(defn clear-claimable-v2-kind
  "Clear all v2 claimables for a workflow + kind.
   Idempotent by construction (dissoc-based), so repeated calls do not create negatives.
   For :settlement/principal, clears legacy :claimable for backward-compat parity.
   This function never infers claimants and never creates nil claimant keys."
  [world workflow-id kind]
  (let [world' (update-in world [:claimable-v2 workflow-id] dissoc kind)]
    (if (= kind :settlement/principal)
      (update world' :claimable dissoc workflow-id)
      world')))

(defn clear-claimable-v2-domain
  "Backward-compatible alias for clear-claimable-v2-kind."
  [world workflow-id domain]
  (clear-claimable-v2-kind world workflow-id domain))

(defn- clear-claimable-v2-for-addr
  "Remove claimable-v2 entries for addr on workflow-id (all domains).
   Dissocs addr from each domain; cleans up empty domain and workflow maps."
  [world wf-id addr]
  (if-let [domains (get-in world [:claimable-v2 wf-id])]
    (let [cleaned (reduce-kv (fn [m domain addr-map]
                              (let [without-addr (dissoc addr-map addr)]
                                (if (seq without-addr)
                                  (assoc m domain without-addr)
                                  m)))
                            {}
                            domains)]
      (if (seq cleaned)
        (assoc-in world [:claimable-v2 wf-id] cleaned)
        (update world :claimable-v2 dissoc wf-id)))
    world))

(defn withdraw-escrow
  "Claim claimable balance for addr on workflow-id.
   Mirrors: BaseEscrow.withdrawEscrow.

   Guard: escrow must be in terminal state (:released/:refunded/:resolved).
   Guard: claimable balance must be > 0.
   Guard: token must not be in a liquidity-crunch."
  [world workflow-id addr]
  (if (nil? workflow-id)
    (t/fail :invalid-workflow-id)
    (let [wf-id (t/normalize-workflow-id workflow-id)]
      (cond
        (not (t/valid-workflow-id? world wf-id))
        (t/fail :invalid-workflow-id)

        (not (t/terminal-state? world wf-id))
        (t/fail :transfer-not-finalized)

        :else
         (let [legacy-amount (get-in world [:claimable wf-id addr] 0)
               bond-refund   (get-in world [:claimable-v2 wf-id :bond/refund addr] 0)
               bounty        (get-in world [:claimable-v2 wf-id :liability/challenge-bounty addr] 0)
               amount        (+ legacy-amount bond-refund bounty)
              et     (t/get-transfer world wf-id)
              token  (:token et)]
          (cond
            (zero? amount)
            (t/fail :no-claimable-balance)

            (contains? (:token-liquidity-crunch world #{}) token)
            (t/fail :liquidity-insufficient)

            :else
            (let [world' (-> world
                             (assoc-in [:claimable wf-id addr] 0)
                             (clear-claimable-v2-for-addr wf-id addr)
                             (update-in [:total-withdrawn token] (fnil + 0) amount))]
              (attr/with-attribution {:subject/type :escrow
                                      :subject/id wf-id
                                      :action/type :escrow/withdraw
                                      :evidence/reason :escrow-withdrawn}
                (cap/capture-event-evidence!
                 :escrow-withdrawn
                 {:withdraw/before {:claimable amount
                                    :workflow-id wf-id
                                    :recipient addr}}
                 {:withdraw/after {:claimable (get-in world' [:claimable wf-id addr])
                                   :total-withdrawn (get-in world' [:total-withdrawn token])}}
                 {:withdraw/workflow-id wf-id
                  :withdraw/recipient addr
                  :withdraw/token token
                  :withdraw/amount amount}))
              (assoc (t/ok world') :amount amount))))))))

;; ---------------------------------------------------------------------------
;; BondCollector appeal bond accounting
;;
;; When an appeal is raised, the appellant posts a bond.
;; Protocol fee is deducted: bond * appeal-bond-protocol-fee-bps / 10000
;; Remainder goes to the incentive module.
;;
;; BondCollector storage (modelled in world):
;;   :bond-balances {workflow-id {addr amount}}   ; posted bonds per escrow/poster
;;   :bond-fees     {token amount}                 ; accumulated protocol fees from bonds
;; ---------------------------------------------------------------------------

(defn post-appeal-bond
  "Record an appeal bond posted by appellant for workflow-id.
   Deducts protocol fee into :bond-fees; records net in :bond-balances.
   Also updates :total-held and :total-bonds-posted (cumulative).

   NOTE: Bond inflow is tracked exclusively via :total-bonds-posted.
   Do NOT also increment :total-principal-deposited — that double-counts
   inflow in the conservation-of-funds and held-delta-accounted invariants.

   SIMULATION GAP: No caller-solvency check.  In a real deployment the
   appellant must have sufficient external balance to post the bond.
   The simulation does not model external wallets, so this enforcement
   is absent.  The invariant :challenge-bond-proportional flags cases
   where the configured bond exceeds the escrow value, which would make
   challenge uneconomic even if the caller had the funds."
  [world workflow-id appellant snap token amount]
  (let [fee-bps (or (:appeal-bond-protocol-fee-bps snap) 0)
        {:keys [fee net]} (sew-econ/calculate-appeal-bond-fee amount fee-bps)
        world' (-> world
                   (update-in [:bond-balances workflow-id appellant] (fnil + 0) net)
                   (update-in [:bond-fees token] (fnil + 0) fee)
                   (update-in [:total-bonds-posted token] (fnil + 0) amount)
                   (add-held token
                             net
                             {:action "post-appeal-bond"
                              :reason :appeal-bond-posted
                              :extra {:held/action "post-appeal-bond"
                                      :held/workflow-id workflow-id
                                      :held/bond-id (str workflow-id "-" appellant)
                                      :held/actor appellant}}))]
    (attr/with-attribution {:subject/type :bond
                            :subject/id (str workflow-id "-" appellant)
                            :action/type :bond/post
                            :evidence/reason :bond-posted}
      (cap/capture-event-evidence!
       :bond-posted
       {:bond/before {:bond-balance (get-in world [:bond-balances workflow-id appellant] 0)
                      :total-held (get-in world [:total-held token])}}
       {:bond/after  {:bond-balance (get-in world' [:bond-balances workflow-id appellant] 0)
                      :total-held (get-in world' [:total-held token])}}
       {:bond/workflow-id workflow-id
        :bond/appellant appellant
        :bond/amount amount
        :bond/fee fee
        :bond/net net
        :bond/token token}
       nil
       {:world-before world
        :world-after world'}))
    world'))

(defn distribute-slashed-funds
  "Internal: distribute slashed funds according to configurable split.
   Default split (50/30/20) can be overridden via :insurance-cut-bps and
   :protocol-retained-bps in world params (basis points).
   If a challenger is provided (Phase L), they receive a bounty from the slashed amount.
   Bounty is subtracted from the 'insurance' and 'protocol' portions.
   Returns updated world."
  ([world amount] (distribute-slashed-funds world amount nil 0 nil))
  ([world amount challenger bounty-bps]
   (distribute-slashed-funds world amount challenger bounty-bps nil))
  ([world amount challenger bounty-bps workflow-id]
   (let [bounty (sew-econ/calculate-bounty amount bounty-bps)
         split-opts (select-keys (:params world) [:insurance-cut-bps :protocol-retained-bps])
         dist   (sew-econ/calculate-slashing-distribution amount bounty split-opts)
         world' (-> world
                    (update-in [:bond-distribution :insurance] (fnil + 0) (:insurance dist))
                    (update-in [:bond-distribution :protocol]  (fnil + 0) (:protocol dist))
                    (update-in [:retained-slash-reserves]      (fnil + 0) (:retained dist))
                    (cond-> (and challenger (pos? bounty) (some? workflow-id))
                      (record-claimable-v2 workflow-id :liability/challenge-bounty challenger bounty)))]

     (when (and challenger (pos? bounty))
       (attr/with-attribution
         {:subject/type :challenger
          :subject/id   challenger
          :action/type  :reward-bounty
          :evidence/reason :incentive-payout}
         (cap/capture-event-evidence! :incentive-payout
                                      {:bounty-claimable 0}
                                      {:bounty-claimable bounty}
                                      {:slash-amount amount :bounty-bps bounty-bps}
                                      {:formula "sew-econ/calculate-bounty"}
                                      {:world-before world
                                       :world-after world'})))

     world')))

(defn- reject-bond-evidence!
  "Capture evidence for a rejected bond operation."
  [world token workflow-id appellant amount error-kw action-type evidence-type]
  (attr/with-attribution {:subject/type :bond
                          :subject/id (str workflow-id "-" appellant)
                          :action/type action-type
                          :evidence/reason error-kw}
    (cap/capture-event-evidence!
     evidence-type
     {:bond/before {:bond-balance (get-in world [:bond-balances workflow-id appellant] 0)
                    :bond-status :active}}
     {:bond/after  {:bond-balance (get-in world [:bond-balances workflow-id appellant] 0)
                    :bond-status :unchanged}}
     {:bond/workflow-id workflow-id
      :bond/appellant appellant
      :bond/amount amount
      :bond/error error-kw}
     nil
     {:world-before world
      :world-after world})))

(defn slash-bond
  "Slash the posted bond for a losing appellant.
   Moves balance from :bond-balances to :bond-slashed (for incentive distribution)
   and applies the 50/30/20 split logic.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)
        et     (t/get-transfer world workflow-id)
        token  (:token et)]
    (if (zero? amount)
      (do (reject-bond-evidence! world token workflow-id appellant amount :no-bond-to-slash :bond/slash-rejected :bond-slash-rejected)
          (t/fail :no-bond-to-slash))
      (let [world' (-> world
                       (sub-held token
                                 amount
                                 {:action "slash-bond"
                                  :reason :appeal-bond-slashed
                                  :extra {:held/action "slash-bond"
                                          :held/workflow-id workflow-id
                                          :held/bond-id (str workflow-id "-" appellant)
                                          :held/actor appellant}})
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (update-in [:bond-slashed workflow-id] (fnil + 0) amount)
                       (distribute-slashed-funds amount))]
        (attr/with-attribution {:subject/type :bond
                                :subject/id (str workflow-id "-" appellant)
                                :action/type :bond/slash
                                :evidence/reason :bond-slashed}
          (cap/capture-event-evidence!
           :bond-slashed
           {:bond/before {:bond-balance amount
                          :bond-status :active}}
           {:bond/after  {:bond-balance 0
                          :bond-status :slashed}}
           {:bond/workflow-id workflow-id
            :bond/appellant appellant
            :bond/amount amount
            :bond/token token}
           nil
           {:world-before world
            :world-after world'}))
        (assoc (t/ok world') :slashed amount)))))

(defn return-bond
  "Return the posted bond to a winning appellant.
   Clears :bond-balances entry and credits :claimable.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)
        et     (t/get-transfer world workflow-id)
        token  (:token et)]
    (if (zero? amount)
      (do (reject-bond-evidence! world token workflow-id appellant amount :no-bond-to-return :bond/return-rejected :bond-return-rejected)
          (t/fail :no-bond-to-return))
      (let [world' (-> world
                       (sub-held token
                                 amount
                                 {:action "return-bond"
                                  :reason :appeal-bond-returned
                                  :extra {:held/action "return-bond"
                                          :held/workflow-id workflow-id
                                          :held/bond-id (str workflow-id "-" appellant)
                                          :held/actor appellant}})
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (record-claimable workflow-id appellant amount))]
        (attr/with-attribution {:subject/type :bond
                                :subject/id (str workflow-id "-" appellant)
                                :action/type :bond/return
                                :evidence/reason :bond-returned}
          (cap/capture-event-evidence!
           :bond-returned
           {:bond/before {:bond-balance amount
                          :bond-status :active}}
           {:bond/after  {:bond-balance 0
                          :bond-status :returned}}
           {:bond/workflow-id workflow-id
            :bond/appellant appellant
            :bond/amount amount
            :bond/token token}
           nil
           {:world-before world
            :world-after world'}))
        (assoc (t/ok world') :returned amount)))))

(defn return-all-bonds-for-workflow
  "Return all posted appeal/challenge bonds for a workflow-id on finalization.
   Prevents bonds from leaking/accumulating indefinitely.
   Bonds are returned as claimable to the appellant."
  [world workflow-id]
  (let [wf-bonds (get-in world [:bond-balances workflow-id])]
    (if (seq wf-bonds)
      (let [et    (t/get-transfer world workflow-id)
            token (:token et)]
        (reduce-kv (fn [w appellant amount]
                     (if (pos? amount)
                       (-> w
                           (sub-held token
                                     amount
                                     {:action "return-all-bonds-for-workflow"
                                      :reason :appeal-bond-returned
                                      :extra {:held/action "return-all-bonds-for-workflow"
                                              :held/workflow-id workflow-id
                                              :held/bond-id (str workflow-id "-" appellant)
                                              :held/actor appellant}})
                           (assoc-in [:bond-balances workflow-id appellant] 0)
                           (record-claimable workflow-id appellant amount))
                       w))
                   world
                   wf-bonds))
      world)))
