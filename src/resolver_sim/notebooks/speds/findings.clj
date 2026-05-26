(ns resolver-sim.notebooks.speds.findings
  "Findings-envelope generation for SPEDS.
   Canonical evidence artifact from trial outputs."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.semantics :as sem]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.notebooks.speds.config :as config]))

(def findings-path "results/test-artifacts/findings.json")

(def required-finding-keys
  #{:finding_id :scenario_id :kind :severity :status_kind :title
    :summary :story :evidence_refs :provenance})

(def default-comparator-config
  {:strategy :nearest-baseline-by-id
   :enabled? true})

(defn- purpose->kind [purpose]
  (sem/purpose->kind purpose))

(defn- purpose->family [purpose]
  (sem/purpose->story-family-str purpose))

(defn- purpose-label [purpose]
  (or (get-in (defs/purpose-def (ose/normalize-purpose purpose)) [:label])
      (some-> purpose name)
      "Unclassified"))

(defn- transition-label [tr]
  (or (get-in (defs/transition-def tr) [:label])
      (some-> tr name (str/replace "_" " "))
      "unknown transition"))

(defn- one-line-description [scenario]
  (or (:description scenario)
      (:summary scenario)
      (let [sid (or (:id scenario) "unknown-scenario")
            p-label (purpose-label (:purpose scenario))
            tr (first (or (:transitions scenario) []))
            tr-label (when tr (transition-label tr))]
        (str p-label " scenario " sid
             (when tr-label (str " exercises " tr-label))
             "."))))

(defn- classification-for [scenario]
  (sem/classification-for-purpose (:purpose scenario)))

(defn- scenario-id-num [sid]
  (some->> sid (re-find #"S(\d+)") second parse-long))

(defn- nearest-baseline-scenario [scenarios scenario]
  (let [sid-num  (scenario-id-num (:id scenario))
        baselines (filter #(= :baseline (ose/normalize-purpose (:purpose %))) scenarios)]
    (or (first (sort-by #(Math/abs ^long (- (long (or (scenario-id-num (:id %)) 0))
                                            (long (or sid-num 0)))) baselines))
        (first baselines))))

(defn- baseline-by-purpose [scenarios scenario]
  (let [purpose (ose/normalize-purpose (:purpose scenario))]
    (or (first (filter #(= purpose (ose/normalize-purpose (:purpose %))) scenarios))
        (nearest-baseline-scenario scenarios scenario))))

(defn- baseline-by-tags [scenarios scenario]
  (let [scenario-tags (set (or (:threat-tags scenario) []))
        scored (->> scenarios
                    (map (fn [s]
                           (let [tags (set (or (:threat-tags s) []))
                                 overlap (count (set/intersection scenario-tags tags))]
                             [overlap s])))
                    (sort-by (fn [[overlap _]] (- overlap))))]
    (or (some (fn [[overlap s]] (when (pos? overlap) s)) scored)
        (nearest-baseline-scenario scenarios scenario))))

(defn- choose-baseline [scenarios scenario strategy]
  (case strategy
    :matched-by-purpose (baseline-by-purpose scenarios scenario)
    :matched-by-tags    (baseline-by-tags scenarios scenario)
    :nearest-baseline-by-id (nearest-baseline-scenario scenarios scenario)
    ;; Safe fallback to maintain behavior for unknown strategies.
    (nearest-baseline-scenario scenarios scenario)))

(defn- baseline-comparison [artifacts scenario comparator-config]
  (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
        strategy (or (:strategy comparator-config) :nearest-baseline-by-id)
        baseline (choose-baseline scenarios scenario strategy)
        scenario-tags (set (or (:threat-tags scenario) []))
        baseline-tags (set (or (:threat-tags baseline) []))
        scenario-tag-count (count scenario-tags)
        baseline-tag-count (count baseline-tags)
        overlap-count (count (set/intersection scenario-tags baseline-tags))
        enabled? (not= false (:enabled? comparator-config))]
    {:baseline_scenario_id (or (:id baseline) "unavailable")
     :comparator_kind (-> strategy name (str/replace "-" "_"))
     :enabled? enabled?
     :delta_summary (if (and enabled? baseline)
                      {:threat_tag_count_delta (- scenario-tag-count baseline-tag-count)
                       :threat_tag_overlap_count overlap-count
                       :purpose_delta {:scenario (name (ose/normalize-purpose (:purpose scenario)))
                                       :baseline (name (ose/normalize-purpose (:purpose baseline)))}
                       :narrative (str "Compared against baseline " (:id baseline)
                                       ": tag delta=" (- scenario-tag-count baseline-tag-count)
                                       ", overlap=" overlap-count ".")}
                      {:narrative "No baseline scenario available in coverage artifact for automatic delta extraction."})
     :available? (boolean baseline)}))

(defn- narrative-artifact-spec [artifacts scenario sid sev st-kind comparator-config]
  (let [canon (data/canonical-summary (:summary artifacts))
        classification (classification-for scenario)
        replay-label (:replay-match-label (data/narrative-metrics artifacts))]
    {:schema_version "speds.story-artifact.v1"
     :scenario_id sid
     :classification classification
     :confidence {:level "medium"
                  :basis "single-run-artifact-derived"}
     :provenance {:run_id (:run-id canon)
                  :git_sha (:git-sha canon)
                  :trace_digest_status "missing"
                  :definitions/hash (defs/definitions-hash)
                  :definitions_hash (defs/definitions-hash)}
     :claims [{:claim_id "scenario-kind"
               :value st-kind
               :severity sev
               :source_artifact "coverage"
               :source_path [:coverage :scenarios sid]}
              {:claim_id "replay-alignment"
               :value replay-label
               :source_artifact "summary"
               :source_path [:summary :replay_match_pct]}]
     :baseline_comparison (baseline-comparison artifacts scenario comparator-config)
     :visual_blocks [{:block "headline" :text (or (:title scenario) sid)}
                     {:block "what_happened" :text (str "Scenario " sid " produced status kind " st-kind ".")}
                     {:block "why_it_matters" :text (:rationale classification)}
                     {:block "action" :text "Classify as research finding vs regression before publication."}]}))

(defn- ->outcome-class [classification]
  (case (:label classification)
    "research_finding" :research-finding
    "regression" :regression
    "operational_signal" :operational-signal
    :inconclusive))

(defn- ->outcome-status [classification]
  (case (:status classification)
    "assumption_falsified" :assumption-falsified
    "unexpected_behavior" :unexpected-behavior
    "requires_triage" :requires-triage
    :unknown))

(defn- ->outcome-severity [sev]
  (case sev
    "critical" :critical
    "high" :high
    "medium" :medium
    "low" :low
    :low))

(defn- canonical-outcome [artifacts scenario sid sev st-kind classification story-spec]
  {:outcome-model/version "v0.1"
   :outcome
   {:class (->outcome-class classification)
    :status (->outcome-status classification)
    :severity (->outcome-severity sev)
    :confidence {:level (keyword (or (:confidence classification) "medium"))
                 :basis :single-run-artifact-derived
                 :rationale (:rationale classification)}
    :execution {:result (keyword (or st-kind "observed"))
                :halt-reason nil
                :scenario-id sid
                :scenario-purpose (ose/normalize-purpose (:purpose scenario))}
    :evidence {:claims (get story-spec :claims [])
               :provenance (get story-spec :provenance {})}
    :comparison {:comparator-id (get-in story-spec [:baseline_comparison :baseline_scenario_id])
                 :strategy (keyword (or (get-in story-spec [:baseline_comparison :comparator_kind])
                                        "nearest-baseline-by-id"))
                 :deltas (get-in story-spec [:baseline_comparison :delta_summary])
                 :narrative (get-in story-spec [:baseline_comparison :delta_summary :narrative])}
    :actionability {:owner :triage
                    :release-gate-impact :review-required
                    :next-step "Classify as research finding vs regression before publication."}
    :visual {:blocks (mapv (fn [b] {:block (keyword (str/replace (or (:block b) "") "_" "-"))
                                    :text (:text b)})
                          (get story-spec :visual_blocks []))}}})

(defn- severity [scenario]
  (let [tags (set (map #(-> % name str/lower-case) (or (:threat-tags scenario) [])))
        invariant-severities (->> (or (:invariant-results scenario) {})
                                  (keep (fn [[inv status]]
                                          (when (= status :fail)
                                            (get-in (defs/invariant-def inv) [:default-severity]))))
                                  set)]
    (cond
      (contains? invariant-severities :high) "high"
      (contains? invariant-severities :medium) "medium"
      (or (contains? tags "reentrancy") (contains? tags "solvency")) "high"
      (or (contains? tags "appeal-escalation") (contains? tags "timing-boundary")) "medium"
      :else "low")))

(defn- status-kind [scenario]
  (if (ose/negative-test-purpose? (:purpose scenario))
      "expected_negative"
      "observed"))

(defn- priority [severity]
  (case severity "high" 90 "medium" 60 "low" 30 10))

(defn- truncate-safe [s n]
  (let [v (str (or s ""))]
    (subs v 0 (min n (count v)))))

(defn- finding-id [run-id scenario-id]
  (str "FINDING-" run-id "-" (truncate-safe (Math/abs (hash (str scenario-id))) 6)))

(defn scenario->finding [artifacts scenario comparator-config]
  (let [summary (:summary artifacts)
        canon (data/canonical-summary summary)
        sid (or (:id scenario) "unknown-scenario")
        sev (severity scenario)
        st-kind (status-kind scenario)
        classif (classification-for scenario)
        story-spec (narrative-artifact-spec artifacts scenario sid sev st-kind comparator-config)]
    {:finding_id (finding-id (or (:run-id canon) (:run-id config/protocol-defaults)) sid)
     :scenario_id sid
     :kind (purpose->kind (:purpose scenario))
     :category (:finding-category config/profile)
     :severity sev
     :status_kind st-kind
     :one_line_description (one-line-description scenario)
      :confidence "medium"
      :classification classif
     :title (or (:title scenario) sid)
     :summary (one-line-description scenario)
     :metrics {:replay_success_pct (:replay-match-pct (data/narrative-metrics artifacts))}
     :evidence_refs [{:artifact "coverage"
                      :path [:scenarios sid]
                      :digest nil
                      :digest_status "missing"}]
     :story {:eligible true
             :priority (priority sev)
             :family (purpose->family (:purpose scenario))}
      :story_artifact_spec story-spec
      :outcome (canonical-outcome artifacts scenario sid sev st-kind classif story-spec)
     :provenance {:run_id (:run-id canon)
                  :git_sha (:git-sha canon)
                  :trace_digest nil
                  :trace_digest_status "missing"
                  :definitions/hash (defs/definitions-hash)
                  :definitions_hash (defs/definitions-hash)}}))

(defn- counts [findings]
  (let [by-sev (frequencies (map :severity findings))
        by-kind (frequencies (map :kind findings))]
    {:critical_findings 0
     :high_findings (get by-sev "high" 0)
     :medium_findings (get by-sev "medium" 0)
     :low_findings (get by-sev "low" 0)
     :missing_evidence (count (filter #(= "missing" (get-in % [:provenance :trace_digest_status])) findings))
     :inconclusive (get by-kind "inconclusive_result" 0)
     :robustness_confirmations (get by-kind "robustness_confirmation" 0)}))

(defn generate-findings-bundle
  ([artifacts] (generate-findings-bundle artifacts {:comparator-config default-comparator-config}))
  ([artifacts {:keys [comparator-config] :or {comparator-config default-comparator-config}}]
   (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
        summary (:summary artifacts)
        canon (data/canonical-summary summary)
        findings (->> scenarios
                      (map #(scenario->finding artifacts % comparator-config))
                      (sort-by (juxt (comp - :priority :story) :scenario_id))
                      vec)
        cnt (counts findings)]
    {:schema_version "speds.findings.v1"
     :comparator_config comparator-config
     :run {:run_id (:run-id canon)
           :generated_at (str (java.time.Instant/now))
           :git_sha (:git-sha canon)
           :suite_id (:suite-id config/profile)
           :artifact_root "results/test-artifacts"}
     :overall_status {:status (if (= "pass" (:overall-status canon)) "passed" "warning")
                      :status_kind (if (empty? findings) "no_findings_detected" "validated_with_gaps")
                      :confidence "medium"
                      :summary (if (empty? findings)
                                 "No negative findings were detected in this run, but this does not prove protocol safety."
                                 "Findings observed; review evidence and action queue for triage.")
                      :counts cnt}
     :findings findings
     :story_candidates (if (empty? findings)
                         [{:story_id "RUN-OVERVIEW-001"
                           :kind "run_overview"
                           :priority 50
                           :headline "No negative findings detected in this validation run"
                           :caveat "Absence of findings is not proof of absence."}]
                         [])
     :provenance {:source_artifacts [{:kind "summary" :path (:test-summary config/artifact-paths) :digest nil}
                                     {:kind "coverage" :path (:coverage config/artifact-paths) :digest nil}]
                  :definitions/hash (defs/definitions-hash)
                  :definitions_hash (defs/definitions-hash)
                  :claim_map_path "results/test-artifacts/claim-map.json"}})))

(defn save-findings!
  ([] (save-findings! (data/load-run-artifacts)))
  ([artifacts]
   (let [bundle (generate-findings-bundle artifacts)]
     (.mkdirs (java.io.File. "results/test-artifacts"))
     (spit findings-path (json/write-str bundle))
     bundle)))

(defn load-findings []
  (common/read-json findings-path))
