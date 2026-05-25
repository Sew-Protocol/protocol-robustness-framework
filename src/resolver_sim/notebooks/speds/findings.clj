(ns resolver-sim.notebooks.speds.findings
  "Findings-envelope generation for SPEDS.
   Canonical evidence artifact from trial outputs."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.config :as config]))

(def findings-path "results/test-artifacts/findings.json")

(def required-finding-keys
  #{:finding_id :scenario_id :kind :severity :status_kind :title
    :summary :story :evidence_refs :provenance})

(defn- purpose->kind [purpose]
  (case (str/lower-case (str purpose))
    "theory-falsification" "expected_negative"
    "adversarial-robustness" "liveness_risk"
    "regression" "regression"
    "inconclusive_result"))

(defn- purpose->family [purpose]
  (case (str/lower-case (str purpose))
    "theory-falsification" "theory_falsification"
    "adversarial-robustness" "threat_detected"
    "scenario_deep_dive"))

(defn- classification-for [scenario]
  (let [purpose (str/lower-case (str (:purpose scenario)))]
    (cond
      (= purpose "theory-falsification")
      {:label "research_finding"
       :status "assumption_falsified"
       :confidence "high"
       :rationale "Scenario is explicitly tagged theory-falsification; negative outcomes are expected evidence, not regressions."}

      (= purpose "regression")
      {:label "regression"
       :status "unexpected_behavior"
       :confidence "high"
       :rationale "Scenario is explicitly tagged regression and should be treated as engineering defect signal."}

      :else
      {:label "operational_signal"
       :status "requires_triage"
       :confidence "medium"
       :rationale "Scenario does not explicitly declare falsification/regression semantics; manual triage recommended."})))

(defn- scenario-id-num [sid]
  (some->> sid (re-find #"S(\d+)") second parse-long))

(defn- nearest-baseline-scenario [scenarios scenario]
  (let [sid-num  (scenario-id-num (:id scenario))
        baselines (filter #(= "baseline" (str/lower-case (str (:purpose %)))) scenarios)]
    (or (first (sort-by #(Math/abs ^long (- (long (or (scenario-id-num (:id %)) 0))
                                            (long (or sid-num 0)))) baselines))
        (first baselines))))

(defn- baseline-comparison [artifacts scenario]
  (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
        baseline (nearest-baseline-scenario scenarios scenario)
        scenario-tags (set (or (:threat-tags scenario) []))
        baseline-tags (set (or (:threat-tags baseline) []))
        scenario-tag-count (count scenario-tags)
        baseline-tag-count (count baseline-tags)
        overlap-count (count (set/intersection scenario-tags baseline-tags))]
    {:baseline_scenario_id (or (:id baseline) "unavailable")
     :comparator_kind "nearest_baseline_by_id"
     :delta_summary (if baseline
                      {:threat_tag_count_delta (- scenario-tag-count baseline-tag-count)
                       :threat_tag_overlap_count overlap-count
                       :purpose_delta {:scenario (str/lower-case (str (:purpose scenario)))
                                       :baseline (str/lower-case (str (:purpose baseline)))}
                       :narrative (str "Compared against baseline " (:id baseline)
                                       ": tag delta=" (- scenario-tag-count baseline-tag-count)
                                       ", overlap=" overlap-count ".")}
                      {:narrative "No baseline scenario available in coverage artifact for automatic delta extraction."})
     :available? (boolean baseline)}))

(defn- narrative-artifact-spec [artifacts scenario sid sev st-kind]
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
                  :trace_digest_status "missing"}
     :claims [{:claim_id "scenario-kind"
               :value st-kind
               :severity sev
               :source_artifact "coverage"
               :source_path [:coverage :scenarios sid]}
              {:claim_id "replay-alignment"
               :value replay-label
               :source_artifact "summary"
               :source_path [:summary :replay_match_pct]}]
     :baseline_comparison (baseline-comparison artifacts scenario)
     :visual_blocks [{:block "headline" :text (or (:title scenario) sid)}
                     {:block "what_happened" :text (str "Scenario " sid " produced status kind " st-kind ".")}
                     {:block "why_it_matters" :text (:rationale classification)}
                     {:block "action" :text "Classify as research finding vs regression before publication."}]}))

(defn- severity [scenario]
  (let [tags (set (map #(-> % name str/lower-case) (or (:threat-tags scenario) [])))]
    (cond
      (or (contains? tags "reentrancy") (contains? tags "solvency")) "high"
      (or (contains? tags "appeal-escalation") (contains? tags "timing-boundary")) "medium"
      :else "low")))

(defn- status-kind [scenario]
  (let [purpose (str/lower-case (str (:purpose scenario)))]
    (if (= purpose "theory-falsification")
      "expected_negative"
      "observed")))

(defn- priority [severity]
  (case severity "high" 90 "medium" 60 "low" 30 10))

(defn- truncate-safe [s n]
  (let [v (str (or s ""))]
    (subs v 0 (min n (count v)))))

(defn- finding-id [run-id scenario-id]
  (str "FINDING-" run-id "-" (truncate-safe (Math/abs (hash (str scenario-id))) 6)))

(defn scenario->finding [artifacts scenario]
  (let [summary (:summary artifacts)
        canon (data/canonical-summary summary)
        sid (or (:id scenario) "unknown-scenario")
        sev (severity scenario)
        st-kind (status-kind scenario)
        classif (classification-for scenario)]
    {:finding_id (finding-id (or (:run-id canon) (:run-id config/protocol-defaults)) sid)
     :scenario_id sid
     :kind (purpose->kind (:purpose scenario))
     :category "dispute_resolution"
     :severity sev
     :status_kind st-kind
      :confidence "medium"
      :classification classif
     :title (or (:title scenario) sid)
     :summary (str "Finding derived from scenario " sid ".")
     :metrics {:replay_success_pct (:replay-match-pct (data/narrative-metrics artifacts))}
     :evidence_refs [{:artifact "coverage"
                      :path [:scenarios sid]
                      :digest nil
                      :digest_status "missing"}]
     :story {:eligible true
             :priority (priority sev)
             :family (purpose->family (:purpose scenario))}
      :story_artifact_spec (narrative-artifact-spec artifacts scenario sid sev st-kind)
     :provenance {:run_id (:run-id canon)
                  :git_sha (:git-sha canon)
                  :trace_digest nil
                  :trace_digest_status "missing"}}))

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

(defn generate-findings-bundle [artifacts]
  (let [scenarios (or (get-in artifacts [:coverage :scenarios]) [])
        summary (:summary artifacts)
        canon (data/canonical-summary summary)
        findings (->> scenarios
                      (map #(scenario->finding artifacts %))
                      (sort-by (juxt (comp - :priority :story) :scenario_id))
                      vec)
        cnt (counts findings)]
    {:schema_version "speds.findings.v1"
     :run {:run_id (:run-id canon)
           :generated_at (str (java.time.Instant/now))
           :git_sha (:git-sha canon)
           :suite_id "dispute-resolution-validation-v1"
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
                  :claim_map_path "results/test-artifacts/claim-map.json"}}))

(defn save-findings!
  ([] (save-findings! (data/load-run-artifacts)))
  ([artifacts]
   (let [bundle (generate-findings-bundle artifacts)]
     (.mkdirs (java.io.File. "results/test-artifacts"))
     (spit findings-path (json/write-str bundle))
     bundle)))

(defn load-findings []
  (common/read-json findings-path))
