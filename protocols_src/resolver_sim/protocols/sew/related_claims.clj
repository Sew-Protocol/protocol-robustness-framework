(ns resolver-sim.protocols.sew.related-claims
  "Related claims registry: explicit immutable relationship groups for
   audit, batch force-authorisation, and evidence linkage.

   A related-claims group is created once with an immutable member set.
   Membership cannot be mutated — versioned relationships must create a
   new relationship-id for any membership change.

   v1 semantics: #{:audit-only} — no settlement coupling.
   Future semantics may add :batch-force-authorisation, :shared-evidence.

   See docs/architecture/related-claims.md for full design rationale."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.util.attribution :as attr]))

(def ^:const related-claims-domain
  "related-claims.v1")

(def ^:const related-claims-version 1)

(def ^:const default-semantics
  "Default relationship semantics set for v1.
   :audit-only — no settlement coupling, purely descriptive."
  #{:audit-only})

(def ^:const allowed-relationship-types
  "Controlled vocabulary of relationship types."
  #{:same-incident
    :same-counterparty
    :same-evidence
    :governance-batch
    :force-authorisation-batch
    :resolver-batch
    :appeal-batch})

(def ^:const allowed-claim-kinds
  "Claim kinds that can appear in a relationship membership."
  #{:sew/workflow})

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn related-claims-hash
  "Compute the canonical hash for a related-claims relationship.
   Hashes over sorted members with their claim/kind, workflow/id, and
   claim/scope-hash to prevent membership mutation without
   re-authorization."
  [members]
  (hash/domain-hash related-claims-domain
                    (vec (sort-by (juxt :workflow/id :claim/kind)
                                 (for [m members]
                                   (select-keys m [:claim/kind :workflow/id :claim/scope-hash]))))))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn get-related-claims
  "Lookup a relationship record by id. Returns nil if not found."
  [world relationship-id]
  (get-in world [:related-claims relationship-id]))

(defn related-claims-active?
  "True when the relationship exists and has :active status."
  [world relationship-id]
  (let [rel (get-related-claims world relationship-id)]
    (and rel (= :active (:relationship/status rel)))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- validate-relationship-type!
  [type]
  (when-not (contains? allowed-relationship-types type)
    (throw (ex-info "invalid relationship type"
                    {:type :invalid-related-claims
                     :relationship/type type
                     :allowed allowed-relationship-types}))))

(defn- validate-claim-kinds!
  [members]
  (doseq [m members]
    (when-not (contains? allowed-claim-kinds (:claim/kind m))
      (throw (ex-info "invalid claim kind in relationship member"
                      {:type :invalid-related-claims
                       :member m
                       :allowed allowed-claim-kinds})))))

(defn- validate-members-exist!
  [world members]
  (doseq [m members]
    (case (:claim/kind m)
      :sew/workflow
      (when-not (t/valid-workflow-id? world (:workflow/id m))
        (throw (ex-info "relationship member workflow does not exist"
                        {:type :invalid-related-claims
                         :member m})))
      nil)))

(defn- validate-no-duplicate-members!
  [world members]
  (let [wf-ids (set (for [m members
                          :when (= :sew/workflow (:claim/kind m))]
                      (:workflow/id m)))]
    (when (not= (count wf-ids) (count members))
      (throw (ex-info "duplicate workflow-id in relationship members"
                      {:type :invalid-related-claims
                       :members members})))
    ;; Check no workflow-id already in another active relationship of same type
    (doseq [[rel-id rel] (:related-claims world {})]
      (when (= :active (:relationship/status rel))
        (let [existing-wf-ids (set (for [m (:relationship/members rel)
                                         :when (= :sew/workflow (:claim/kind m))]
                                     (:workflow/id m)))]
          (doseq [wf-id wf-ids]
            (when (contains? existing-wf-ids wf-id)
              (throw (ex-info "workflow-id already in an active relationship"
                              {:type :invalid-related-claims
                               :workflow/id wf-id
                               :existing-relationship-id rel-id})))))))))

;; ---------------------------------------------------------------------------
;; Builder
;; ---------------------------------------------------------------------------

(defn- build-member-scope-hash
  "Compute a scope hash for a single claim member.
   Used to tie force-authorisation scopes to specific members."
  [member]
  (hash/domain-hash "related-claims-member"
                     (select-keys member [:claim/kind :workflow/id])))

(defn- build-related-claims-record
  "Construct a related-claims record map without storing it."
  [world type members semantics reason created-by created-at-step]
  (let [wf-members (for [m members]
                     (let [scope-hash (or (:claim/scope-hash m)
                                         (build-member-scope-hash m))]
                       (-> m
                           (assoc :claim/scope-hash scope-hash)
                           (update :workflow/id t/normalize-workflow-id))))
        relationship-id (get world :next-related-claim-id 0)
        rel-hash (related-claims-hash wf-members)]
    {:related-claims/version related-claims-version
     :relationship/id relationship-id
     :relationship/type type
     :relationship/status :active
     :relationship/members wf-members
     :relationship/semantics (or semantics default-semantics)
     :relationship/reason reason
     :created-by created-by
     :created-at-step created-at-step
     :relationship/hash rel-hash}))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn create-related-claims!
  "Create a new related-claims relationship.
   Validates members exist, no duplicates, type is allowed.
   Returns {:ok true :world world' :relationship-id N :relationship record}.

   opts:
     :type         — keyword from allowed-relationship-types
     :members      — [{:claim/kind :sew/workflow :workflow/id N, :claim/scope-hash optional}]
     :semantics    — set of semantics keywords (default #{:audit-only})
     :reason       — string describing why
     :created-by   — {:actor/type :governance :actor/address \"0x...\"}
     :created-at-step — integer step number"
  [world {:keys [type members semantics reason created-by created-at-step]
          :or {semantics default-semantics
               reason "unspecified"
               created-by {:actor/type :governance :actor/address "0xGovernance"}
               created-at-step 0}}]
  (try
    (validate-relationship-type! type)
    (validate-claim-kinds! members)
    (validate-members-exist! world members)
    (validate-no-duplicate-members! world members)
    (let [record (build-related-claims-record world type members semantics reason created-by created-at-step)
          rel-id (:relationship/id record)
          world' (-> world
                     (assoc-in [:related-claims rel-id] record)
                     (update :next-related-claim-id inc))]
      (attr/with-attribution {:subject/type :related-claims
                              :subject/id rel-id
                              :action/type :related-claims/create
                              :evidence/reason :related-claims-created}
        (cap/capture-event-evidence!
         :related-claims-created
         {:related-claims/before {:related-claims-count (count (:related-claims world {}))}}
         {:related-claims/after {:related-claims-count (count (:related-claims world' {}))}}
         {:related-claims/id rel-id
          :related-claims/type type
          :related-claims/members-count (count members)
          :related-claims/reason reason}))
      (assoc (t/ok world')
             :relationship-id rel-id
              :relationship record))
    (catch Exception e
      (t/fail (or (:type (ex-data e)) :related-claims-invalid)))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-related-claims-for-workflow
  "Find all active relationship IDs that contain the given workflow-id."
  [world workflow-id]
  (let [wf-id (t/normalize-workflow-id workflow-id)]
    (keep (fn [[rel-id rel]]
            (when (and (= :active (:relationship/status rel))
                       (some (fn [m]
                               (and (= :sew/workflow (:claim/kind m))
                                    (= wf-id (:workflow/id m))))
                             (:relationship/members rel)))
              rel-id))
          (:related-claims world {}))))

(defn find-related-claims-for-workflows
  "Find all active relationship IDs that contain any of the given workflow-ids."
  [world workflow-ids]
  (set (mapcat #(find-related-claims-for-workflow world %) workflow-ids)))
