(ns resolver-sim.notebooks.speds.issues
  "Issue-contract generation for SPEDS.
   Converts trial artifacts into deterministic issue bundles used by story rendering."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.notebooks.common :as common]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.findings :as findings]
            [resolver-sim.notebooks.speds.config :as config]))

(def issues-path "results/test-artifacts/issues.json")

(def required-issue-keys
  #{:issue/id :scenario/id :kind :severity :status-kind :title
    :story/family :priority :evidence/refs :provenance})

(defn- purpose->family [purpose]
  (case (str/lower-case (str purpose))
    "theory-falsification" :theory-falsification
    "adversarial-robustness" :threat-detected
    :scenario-deep-dive))

(defn- status-kind [scenario summary]
  (let [purpose (str/lower-case (str (:purpose scenario)))
        failed (or (:failing_scenarios summary)
                   (:failed_scenarios summary)
                   (:failed summary)
                   0)]
    (cond
      (= purpose "theory-falsification") :expected-negative
      (pos? (long (or failed 0))) :detected
      :else :observed)))

(defn- severity [scenario summary]
  (let [tags (set (map #(-> % name str/lower-case) (or (:threat-tags scenario) [])))
        failed (pos? (long (or (:failed summary) (:failing_scenarios summary) 0)))]
    (cond
      (or (contains? tags "reentrancy") (contains? tags "solvency") failed) :high
      (or (contains? tags "appeal-escalation") (contains? tags "timing-boundary")) :medium
      :else :low)))

(defn- priority [sev]
  (case sev :high 90 :medium 60 :low 30 10))

(defn- truncate-safe [s n]
  (let [v (str (or s ""))]
    (subs v 0 (min n (count v)))))

(def default-issue-policy
  {:open-issue-when [{:status-kinds #{"observed"} :min-severity :medium}
                     {:kinds #{"missing_evidence" "invariant_failure"}}]
   :do-not-open-for #{"robustness_confirmation" "expected_negative"}})

(defn- severity-rank [s]
  (case (str/lower-case (name s))
    ("critical" "high") 3
    "medium" 2
    "low" 1
    0))

(defn- should-open-issue? [finding policy]
  (let [kind (or (:kind finding) "")
        status-kind (or (:status_kind finding) "")
        sev (keyword (str/lower-case (str (:severity finding))))
        blocked? ((:do-not-open-for policy) kind)]
    (and (not blocked?)
         (or (some (fn [{:keys [kinds]}] (and kinds (contains? kinds kind))) (:open-issue-when policy))
             (some (fn [{:keys [status-kinds min-severity]}]
                     (and status-kinds
                          (contains? status-kinds status-kind)
                          (>= (severity-rank sev) (severity-rank (or min-severity :low)))))
                   (:open-issue-when policy))))))

(defn finding->issue [finding run-id]
  {:issue/id (str "ISSUE-" (or run-id (:run-id config/protocol-defaults)) "-" (truncate-safe (Math/abs (hash (:finding_id finding))) 6))
   :source-finding-ids [(:finding_id finding)]
   :scenario/id (:scenario_id finding)
   :kind :policy-projected
   :status-kind :open
   :type :protocol_risk
   :status :open
   :severity (keyword (str/lower-case (str (:severity finding))))
   :priority (or (get-in finding [:story :priority]) 50)
   :title (str "Investigate: " (:title finding))
   :summary (:summary finding)
   :story/family (keyword (str/lower-case (str (get-in finding [:story :family] "scenario_deep_dive"))))
   :evidence/refs (vec (map (fn [r] {:artifact (:artifact r) :path (:path r)}) (or (:evidence_refs finding) [])))
   :provenance {:run-id run-id
                :git-sha (get-in finding [:provenance :git_sha])
                :trace-digest (get-in finding [:provenance :trace_digest])}})

(defn generate-issues-bundle
  "Builds actionable issues from findings + issue policy."
  ([artifacts] (generate-issues-bundle artifacts default-issue-policy))
  ([artifacts policy]
   (let [findings-bundle (or (findings/load-findings)
                             (findings/generate-findings-bundle artifacts))
        run-id (get-in findings-bundle [:run :run_id])
        findings-list (or (:findings findings-bundle) [])
        issues (->> findings-list
                    (filter #(should-open-issue? % policy))
                    (map #(finding->issue % run-id))
                    (sort-by (juxt (comp - :priority) :scenario/id))
                    vec)]
    {:schema/version "speds-issues-v1"
     :run/id run-id
     :generated-at (str (java.time.Instant/now))
     :issue-count (count issues)
     :issues issues
     :policy policy})))

(defn save-issues!
  "Writes issues bundle to results/test-artifacts/issues.json"
  ([] (save-issues! (data/load-run-artifacts)))
  ([artifacts]
   (let [bundle (generate-issues-bundle artifacts)]
     (.mkdirs (java.io.File. "results/test-artifacts"))
     (spit issues-path (json/write-str bundle))
     bundle)))

(defn load-issues []
  (common/read-json issues-path))
