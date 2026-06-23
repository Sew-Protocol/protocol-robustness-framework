(ns resolver-sim.definitions.passive-registries
  "Semantic registries validated on namespace load.

   Registries validated at startup:
   - intent-registry
   - projection-definition-registry
   - claim-definition-registry
   - attestor-registry
   - execution-registry
   - evidence-policy-registry
   - hash-projection-registry (wraps canonical.clj hash-intents)
   - domain-tag-registry (wraps canonical.clj domain-tags)

   Validation throws on startup if any registry is invalid.
   This is a hard-fail — the system will not start with corrupt registries."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]))

(def ^:dynamic *startup-registry-validation-enabled*
  "When false, startup registry validation is skipped.
   Set to false only for test fixtures that need to load without valid registries.
   Defaults to true — corrupted registries are a hard-fail."
  true)

(defn- canonical-entry-hash
  [intent entry]
  (hc/hash-with-intent {:hash/intent intent} entry))

(defn- attach-hash
  [intent hash-key entry]
  (assoc entry hash-key (canonical-entry-hash intent entry)))

(defn- attach-registry-hash
  [intent registry]
  (assoc registry :registry-hash (canonical-entry-hash intent registry)))

(def intent-definitions
  "Passive INTENT_REGISTRY_SPEC_V1 entries.
   These describe semantic intents used by hash projections and additive
   projection-based pro-rata artifacts."
  (mapv (partial attach-hash :intent-registry-entry :canonical-hash)
        [{:id :identity/intent-dsl
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :intent-dsl-identity
          :scope {:protocols #{:framework}
                  :domains #{:identity}
                  :modules #{:intent-dsl}}
          :inputs #{:intent-object}
          :constraints #{:canonical-safe :domain-separated}
          :output {:type :canonical-hash
                   :hash/intent :intent-dsl}
          :extensions-policy {:allowed? true
                              :require-namespaced-keys? true}
          :description "Canonical identity for INTENT_DSL_SPEC_V1 intent objects."}
         {:id :identity/intent-registry-entry
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :intent-registry-entry-identity
          :scope {:protocols #{:framework}
                  :domains #{:identity}
                  :modules #{:intent-registry}}
          :inputs #{:intent-registry-entry}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :intent-registry-entry}
          :description "Canonical identity for one registered intent entry."}
         {:id :identity/projection-definition
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :projection-definition-identity
          :scope {:protocols #{:framework}
                  :domains #{:projection}
                  :modules #{:projection-registry}}
          :inputs #{:projection-definition}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :projection-definition}
          :description "Canonical identity for one projection definition."}
         {:id :identity/projection-artifact
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :projection-artifact-identity
          :scope {:protocols #{:framework}
                  :domains #{:projection}
                  :modules #{:projection-artifacts}}
          :inputs #{:projection-artifact}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :projection-artifact}
          :description "Canonical identity for one projection artifact."}
         {:id :identity/claim-definition
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :claim-definition-identity
          :scope {:protocols #{:framework}
                  :domains #{:claims}
                  :modules #{:claim-registry}}
          :inputs #{:claim-definition}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :claim-definition}
          :description "Canonical identity for one claim definition."}
         {:id :identity/attestor
          :version 1
          :intent/type :identity/hash-projection
          :intent/purpose :attestor-identity
          :scope {:protocols #{:framework}
                  :domains #{:attestation}
                  :modules #{:attestor-registry}}
          :inputs #{:attestor}
          :constraints #{:canonical-safe :domain-separated :self-hash-excluded}
          :output {:type :canonical-hash
                   :hash/intent :attestor}
          :description "Canonical identity for one attestor registry entry."}
         {:id :pro-rata/slash-obligation-allocation
          :version 1
          :intent/type :pro-rata/allocation
          :intent/purpose :slash-obligation-allocation
          :scope {:protocols #{:sew}
                  :domains #{:economic-allocation}
                  :modules #{:slashing}}
          :inputs #{:obligations :weights :caps :balances :eligible-participants}
          :constraints #{:conservation :non-negative :allocation-completeness
                         :rounding-bounded}
          :output {:type :allocation-vector
                   :unit :wei
                   :rounding :floor-with-largest-remainder}
          :input-policy :exact
          :constraint-policy :require-all
          :extensions-policy {:allowed? true
                              :require-namespaced-keys? true}
          :description "Projection-based pro-rata allocation intent for Sew slash obligations."}]))

(def intent-registry
  (attach-registry-hash
   :intent-registry
   {:registry-version 1
    :intents intent-definitions}))

(def projection-definitions
  "Passive PROJECTION_DEFINITION_REGISTRY_SPEC_V1 entries."
  (mapv (partial attach-hash :projection-definition :canonical-hash)
        [{:id :projection/world-structure
          :version 1
          :projection-type :world-structure
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:intent-dsl-identity
                             :intent-registry-entry-identity}
          :source {:type :registry-artifact}
          :include-paths [[:registry-version] [:intents]]
          :exclude-paths [[:registry-hash]]
          :transforms [:canonical-artifact-value]
          :output {:type :structure-view}
          :claims [{:claim-id :projection-deterministic}
                   {:claim-id :projection-canonical-safe}]}
         {:id :projection/projection-definition-registry
          :version 1
          :projection-type :registry-view
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:projection-definition-identity}
          :source {:type :projection-definition-registry}
          :include-paths [[:registry-version] [:projection-definitions]]
          :exclude-paths [[:registry-hash]]
          :transforms [:strip-self-hash-fields :canonical-artifact-value]
          :output {:type :definition-registry-view}
          :claims [{:claim-id :projection-deterministic}
                   {:claim-id :projection-canonical-safe}]}
         {:id :projection/projection-artifact
          :version 1
          :projection-type :projection-artifact
          :intent-types #{:identity/hash-projection}
          :intent-purposes #{:projection-artifact-identity}
          :source {:type :projection-artifact}
          :include-paths [[:schema-version] [:projection-id] [:projection]]
          :exclude-paths [[:projection-hash]]
          :transforms [:strip-self-hash-fields :canonical-artifact-value]
          :output {:type :projection-artifact-view}
          :claims [{:claim-id :projection-deterministic}
                   {:claim-id :projection-canonical-safe}]}
         {:id :projection/pro-rata-slash-obligation
          :version 1
          :projection-type :pro-rata-allocation
          :intent-types #{:pro-rata/allocation}
          :intent-purposes #{:slash-obligation-allocation}
          :source {:type :allocation-input}
          :include-paths [[:amount]
                          [:items]
                          [:rounding]
                          [:remainder-policy]
                          [:ordering-policy]]
          :exclude-paths [[:id-fn]
                          [:weight-fn]
                          [:cap-fn]
                          [:runtime]
                          [:debug]
                          [:telemetry]]
          :transforms [:canonical-artifact-value]
          :output {:type :allocation-frame
                   :unit :wei
                   :rounding :floor-with-largest-remainder
                   :remainder-policy :unallocated
                   :ordering-policy :input-order
                   :required-keys #{:participants
                                    :eligible-participants
                                    :weights
                                    :caps
                                    :total-obligation
                                    :constraints}}
          :claims [{:claim-id :projection-deterministic
                    :required? true}
                   {:claim-id :projection-canonical-safe
                    :required? true}
                   {:claim-id :allocation-complete
                    :required? true}
                   {:claim-id :non-negative
                    :required? true}
                   {:claim-id :conservation
                    :required? true}
                   {:claim-id :rounding-bounded
                    :required? true}
                   {:claim-id :ordering-independent
                    :required? true}]}]))

(def projection-definition-registry
  (attach-registry-hash
   :projection-definition-registry
   {:registry-version 1
    :projection-definitions projection-definitions}))

(def claim-definitions
  "Passive CLAIM_DEFINITION_REGISTRY_SPEC_V1 entries."
  (mapv (partial attach-hash :claim-definition :canonical-hash)
        [{:id :projection-deterministic
          :version 1
          :category :invariant
          :description "Projection output is deterministic for the same source input and definition."
          :inputs [:projection-definition :source-input :projection-artifact]
          :evaluation {:type :recompute-and-compare
                       :expected :same-projection-hash}
          :outputs [:passed? :expected-hash :actual-hash]}
         {:id :projection-canonical-safe
          :version 1
          :category :safety
          :description "Projection output contains only canonical hash-safe values."
          :inputs [:projection-artifact]
          :evaluation {:type :canonical-value-validation
                       :validator :resolver-sim.hash.canonical/validate-canonical-value!}
          :outputs [:passed? :violations]}
         {:id :registry-entry-hash-valid
          :version 1
          :category :audit
          :description "Registry entry self hash matches its registered canonical projection."
          :inputs [:registry-entry]
          :evaluation {:type :hash-recompute
                       :expected :entry-self-hash}
          :outputs [:passed? :expected-hash :actual-hash]}
         {:id :allocation-complete
          :version 1
          :category :invariant
          :description "Every eligible participant has a corresponding allocation row."
          :inputs [:projection-artifact :allocation-result]
          :evaluation {:type :set-coverage
                       :expected :eligible-participants-covered}
          :outputs [:passed? :missing-participants :extra-participants]}
         {:id :non-negative
          :version 1
          :category :invariant
          :description "Allocation, unmet, weight, and cap values are never negative."
          :inputs [:allocation-result]
          :evaluation {:type :numeric-predicate
                       :predicate :all-values-non-negative}
          :outputs [:passed? :violations]}
         {:id :conservation
          :version 1
          :category :invariant
          :description "Requested amount equals allocated plus unmet plus remainder."
          :inputs [:allocation-result]
          :evaluation {:type :arithmetic-equality
                       :left :total-requested
                       :right [:total-allocated :total-unmet :remainder]}
          :outputs [:passed? :difference]}
         {:id :rounding-bounded
          :version 1
          :category :invariant
          :description "Rounding behavior is bounded by the registered allocation policy."
          :inputs [:projection-artifact :allocation-result]
          :evaluation {:type :policy-check
                       :policy :floor-with-largest-remainder}
          :outputs [:passed? :violations]}
         {:id :ordering-independent
          :version 1
          :category :invariant
          :description "Allocation result is invariant under permutation of input items (multi-set equality)."
          :inputs [:allocation-input :allocation-result]
          :evaluation {:type :permutation-test
                       :policy :multi-set-equality}
          :outputs [:passed? :violations]}]))

(def claim-definition-registry
  {:registry-version 1
   :claim-definitions claim-definitions})

(def attestors
  "Passive ATTESTOR_REGISTRY_SPEC_V1 entries."
  (mapv (partial attach-hash :attestor :attestor-hash)
        [{:id :local-development
          :version 1
          :type :validator
          :display-name "Local development validator"
          :status :active
          :verification {:type :local-process
                         :trust-boundary :developer-workstation}
          :metadata {:intended-use #{:tests :local-replay}}}
         {:id :ci-validation
          :version 1
          :type :ci-runner
          :display-name "CI validation runner"
          :status :active
          :verification {:type :public-key
                         :algorithm :ed25519
                         :key-id "ci-validation-placeholder"
                         :public-key "ci-validation-placeholder-public-key"}
          :metadata {:intended-use #{:validation :attestation}}}]))

(def attestor-registry
  {:registry-version 1
   :attestors attestors})

;; ── Execution Registry ─────────────────────────────────────────────────

(def execution-registry-definitions
  "EXECUTION_REGISTRY_SPEC_V1 entries: registered execution modes."
  [{:id :execution/simulation
    :version 1
    :execution/type :simulation
    :execution/mode :full
    :description "Full Monte Carlo simulation from protocol params."
    :claims #{:deterministic-replay :evidence-completeness}}
   {:id :execution/replay
    :version 1
    :execution/type :replay
    :execution/mode :deterministic
    :description "Deterministic replay from recorded trace."
    :claims #{:deterministic-replay :trace-fidelity}}
   {:id :execution/server
    :version 1
    :execution/type :server
    :execution/mode :long-running
    :description "Long-running gRPC server for interactive simulation."
    :claims #{:evidence-completeness}}
   {:id :execution/batch
    :version 1
    :execution/type :batch
    :execution/mode :sweep
    :description "Parameter sweep or batch execution over multiple trials."
    :claims #{:deterministic-replay}}
   {:id :execution/diff
    :version 1
    :execution/type :diff
    :execution/mode :comparison
    :description "Trace diff between two simulation runs."
    :claims #{:trace-fidelity}}
   {:id :execution/validation
    :version 1
    :execution/type :validation
    :execution/mode :static
    :description "Static validation of registries, fixtures, or scenarios."
    :claims #{}}])

(def execution-registry
  {:registry-version 1
   :executions execution-registry-definitions})

;; ── Evidence Policy Registry ───────────────────────────────────────────

(def evidence-policy-registry-definitions
  "EVIDENCE_POLICY_REGISTRY_SPEC_V1 entries: registered evidence policies."
  [{:id :evidence-policy/in-band
    :version 1
    :evidence-policy/type :capture
    :evidence-policy/source :action
    :description "Evidence captured inline during action execution."
    :constraints #{:deterministic :replayable}}
   {:id :evidence-policy/deterministic
    :version 1
    :evidence-policy/type :capture
    :evidence-policy/source :state
    :description "Evidence computable deterministically from world state alone."
    :constraints #{:state-derived :recomputable}}
   {:id :evidence-policy/out-of-band
    :version 1
    :evidence-policy/type :attestation
    :evidence-policy/source :external
    :description "External evidence submitted via out-of-band channel."
    :constraints #{:externally-sourced}}
   {:id :evidence-policy/attested
    :version 1
    :evidence-policy/type :attestation
    :evidence-policy/source :attestor
    :description "Evidence requiring explicit attestation for verification."
    :constraints #{:attestor-signed}}
   {:id :evidence-policy/computed
    :version 1
    :evidence-policy/type :computed
    :evidence-policy/source :derived
    :description "Evidence derived from existing evidence via transformation."
    :constraints #{:deterministic :derived}}])

(def evidence-policy-registry
  {:registry-version 1
   :evidence-policies evidence-policy-registry-definitions})

;; ── Hash Projection Registry ───────────────────────────────────────────

(defn- hash-intent->registry-entry
  [kw intent]
  (assoc intent
         :id kw
         :version (:intent/version intent)
         :hash-projection/id kw))

(def hash-projection-registry-definitions
  "HASH_PROJECTION_REGISTRY_SPEC_V1 entries: registered hash projections
   from resolver-sim.hash.canonical/hash-intents.
   Note: entries contain :intent/projection-fn which is not canonical-safe,
   so entry validation omits canonical hash checks for this registry."
  (mapv (partial hash-intent->registry-entry)
        (keys hc/hash-intents)
        (vals hc/hash-intents)))

(def hash-projection-registry
  {:registry-version 1
   :projections hash-projection-registry-definitions})

;; ── Domain Tag Registry ────────────────────────────────────────────────

(defn- domain-tag->registry-entry
  [[kw tag-string]]
  {:id kw
   :tag/id kw
   :tag/domain-string tag-string
   :version 1})

(def domain-tag-registry-definitions
  "DOMAIN_TAG_REGISTRY_SPEC_V1 entries: registered domain tags
   from resolver-sim.hash.canonical/domain-tags."
  (mapv domain-tag->registry-entry (seq hc/domain-tags)))

(def domain-tag-registry
  {:registry-version 1
   :domain-tags domain-tag-registry-definitions})

(def ^:private registry-specs
  {:intent-registry
   {:registry intent-registry
    :entries-key :intents
    :required-registry-fields #{:registry-version :intents :registry-hash}
    :required-entry-fields #{:id :version :intent/type :intent/purpose
                             :scope :inputs :constraints :output
                             :description :canonical-hash}
    :hash-intent :intent-registry-entry
    :hash-key :canonical-hash}

   :projection-definition-registry
   {:registry projection-definition-registry
    :entries-key :projection-definitions
    :required-registry-fields #{:registry-version :projection-definitions :registry-hash}
    :required-entry-fields #{:id :version :projection-type :intent-types
                             :intent-purposes :source :output :claims
                             :canonical-hash}
    :hash-intent :projection-definition
    :hash-key :canonical-hash}

   :claim-definition-registry
   {:registry claim-definition-registry
    :entries-key :claim-definitions
    :required-registry-fields #{:registry-version :claim-definitions}
    :required-entry-fields #{:id :version :category :description
                             :inputs :evaluation :outputs :canonical-hash}
    :hash-intent :claim-definition
    :hash-key :canonical-hash}

   :attestor-registry
   {:registry attestor-registry
    :entries-key :attestors
    :required-registry-fields #{:registry-version :attestors}
    :required-entry-fields #{:id :type :display-name :status
                             :verification :attestor-hash}
    :hash-intent :attestor
    :hash-key :attestor-hash}

   :execution-registry
   {:registry execution-registry
    :entries-key :executions
    :required-registry-fields #{:registry-version :executions}
    :required-entry-fields #{:id :version :execution/type :execution/mode
                             :description :claims}}

   :evidence-policy-registry
   {:registry evidence-policy-registry
    :entries-key :evidence-policies
    :required-registry-fields #{:registry-version :evidence-policies}
    :required-entry-fields #{:id :version :evidence-policy/type
                             :evidence-policy/source :description :constraints}}

   :hash-projection-registry
   {:registry hash-projection-registry
    :entries-key :projections
    :required-registry-fields #{:registry-version :projections}
    :required-entry-fields #{:id :version :intent/name :intent/domain-tag
                             :intent/description :intent/includes :intent/excludes
                             :intent/projection-fn}}

   :domain-tag-registry
   {:registry domain-tag-registry
    :entries-key :domain-tags
    :required-registry-fields #{:registry-version :domain-tags}
    :required-entry-fields #{:tag/id :tag/domain-string :version}}})

(defn- missing-fields
  [required m]
  (seq (sort (set/difference required (set (keys m))))))

(defn- duplicate-ids
  [entries]
  (->> entries
       (map :id)
       frequencies
       (filter (fn [[_ n]] (< 1 n)))
       (map first)
       sort
       seq))

(defn- error
  [code data]
  (assoc data :error code))

(defn- validate-entry-hash
  [{:keys [hash-intent hash-key registry-name]} entry]
  (when-let [expected (get entry hash-key)]
    (let [actual (canonical-entry-hash hash-intent entry)]
      (when (not= expected actual)
        (error :entry/hash-mismatch
               {:registry registry-name
                :id (:id entry)
                :hash-key hash-key
                :expected expected
                :actual actual})))))

(defn validate-registry
  "Validate one passive registry map. Returns {:valid? bool :errors [...]}
   and never throws."
  [registry-name registry spec]
  (let [{:keys [entries-key required-registry-fields required-entry-fields]
         :as spec} (assoc spec :registry-name registry-name)
        entries (get registry entries-key)
        registry-missing (missing-fields required-registry-fields registry)
        registry-errors (cond-> []
                          registry-missing
                          (conj (error :registry/missing-fields
                                       {:registry registry-name
                                        :missing (vec registry-missing)}))
                          (not= 1 (:registry-version registry))
                          (conj (error :registry/unsupported-version
                                       {:registry registry-name
                                        :version (:registry-version registry)}))
                          (not (vector? entries))
                          (conj (error :registry/entries-not-vector
                                       {:registry registry-name
                                        :entries-key entries-key})))
        duplicate-errors (if-let [ids (and (vector? entries) (duplicate-ids entries))]
                           [(error :entry/duplicate-ids
                                   {:registry registry-name
                                    :ids (vec ids)})]
                           [])
        entry-errors (if (vector? entries)
                       (mapcat (fn [entry]
                                 (let [entry-missing (missing-fields required-entry-fields entry)
                                       hash-error (validate-entry-hash spec entry)]
                                   (cond-> []
                                     entry-missing
                                     (conj (error :entry/missing-fields
                                                  {:registry registry-name
                                                   :id (:id entry)
                                                   :missing (vec entry-missing)}))
                                     (not (pos-int? (:version entry)))
                                     (conj (error :entry/invalid-version
                                                  {:registry registry-name
                                                   :id (:id entry)
                                                   :version (:version entry)}))
                                     hash-error
                                     (conj hash-error))))
                               entries)
                       [])
        errors (vec (concat registry-errors duplicate-errors entry-errors))]
    {:registry registry-name
     :valid? (empty? errors)
     :errors errors}))

(defn- registry-result
  [registry-name]
  (let [{:keys [registry] :as spec} (get registry-specs registry-name)]
    (validate-registry registry-name registry spec)))

(defn validate-intent-registry [] (registry-result :intent-registry))
(defn validate-projection-definition-registry [] (registry-result :projection-definition-registry))
(defn validate-claim-definition-registry [] (registry-result :claim-definition-registry))
(defn validate-attestor-registry [] (registry-result :attestor-registry))
(defn validate-execution-registry [] (registry-result :execution-registry))
(defn validate-evidence-policy-registry [] (registry-result :evidence-policy-registry))
(defn validate-hash-projection-registry [] (registry-result :hash-projection-registry))
(defn validate-domain-tag-registry [] (registry-result :domain-tag-registry))

(defn validate-passive-registries
  "Validate all registries and return an aggregate result.
   This function is passive and never throws.
   Includes all 8 registered registry types."
  []
  (let [results (mapv registry-result (keys registry-specs))
        errors (vec (mapcat :errors results))]
    {:valid? (empty? errors)
     :results results
     :errors errors}))

(defn- registry-summary
  "Build a summary map of all registry validation results for startup evidence."
  [validation-result]
  {:registry-count (count (:results validation-result))
   :valid? (:valid? validation-result)
   :registries (mapv (fn [r]
                       {:name (name (:registry r))
                        :valid? (:valid? r)
                        :error-count (count (:errors r))})
                     (:results validation-result))
   :generated-at (str (java.time.Instant/now))
   :schema-version "startup-validation.v1"})

(defn emit-startup-evidence!
  "Register a startup validation evidence record in the chain.
   Best-effort — failures are logged but do not halt execution.
   Uses requiring-resolve to avoid a hard dependency on chain when
   no evidence context is active."
  [validation-result]
  (try
    (let [register! (requiring-resolve 'resolver-sim.evidence.chain/register-evidence!)
          summary (registry-summary validation-result)
          h (hc/hash-with-intent {:hash/intent :startup-validation} summary)
          evidence {:artifact-kind :startup-validation
                    :evidence-hash h
                    :startup/valid? (:valid? validation-result)
                    :startup/registry-count (:registry-count summary)
                    :startup/registries (:registries summary)
                    :startup/generated-at (:generated-at summary)
                    :startup/schema-version (:schema-version summary)}]
      (register! evidence))
    (catch Exception e
      (log/warn! :startup-evidence-failed
                 {:error (.getMessage e)
                  :valid? (:valid? validation-result)}))))

(defn validate-all-registries!
  "Validate all registries. Throws on any validation failure.
   Called at namespace load. This is a hard-fail — the system will not
   start with corrupt registries.

   Set *startup-registry-validation-enabled* to false to disable
   (for test fixtures that need to load without valid registries)."
  []
  (when *startup-registry-validation-enabled*
    (let [result (validate-passive-registries)]
      (when-not (:valid? result)
        (throw (ex-info "Registry validation failed — system cannot start with corrupt registries"
                        {:results (:results result)
                         :errors (:errors result)})))
      (hc/validate-registry!)
      (emit-startup-evidence! result)
      nil)))

(def validate-passive-registries!
  "Legacy alias for validate-all-registries!."
  (fn
    ([] (validate-all-registries!))
    ([{:keys [_strict?]}]
     (validate-all-registries!))))


;; ── Startup validation ─────────────────────────────────────────────────
;; Runs when this namespace is loaded.
(when *startup-registry-validation-enabled*
  (validate-all-registries!))
