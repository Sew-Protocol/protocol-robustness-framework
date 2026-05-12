^{:nextjournal.clerk/doc-css "https://cdn.tailwindcss.com"}
(ns resolver-sim.report
  "Reference Validation Evidence Dashboard (read-only artifact view)."
  {:nextjournal.clerk/toc true}
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def suite-root "suites/reference-validation-v1")

(defn read-json [path]
  (let [f (io/file path)]
    (when (.exists f)
      (with-open [r (io/reader f)]
        (json/read r :key-fn keyword)))))

(def summary
  (or (read-json (str suite-root "/expected/summary.json"))
      (read-json (str suite-root "/actual/summary.json"))))

(def scenario-results
  (or (read-json (str suite-root "/expected/scenario-results.json"))
      (read-json (str suite-root "/actual/scenario-results.json"))))

(def evidence-matrix
  (or (read-json (str suite-root "/expected/evidence-matrix.json"))
      (read-json (str suite-root "/actual/evidence-matrix.json"))))

(def invariants
  (or (read-json (str suite-root "/expected/invariants.json"))
      (read-json (str suite-root "/actual/invariants.json"))))

(defn metric-card [label value sub]
  [:div {:class "rounded-xl border border-slate-200 bg-white p-4 shadow-sm"}
   [:div {:class "text-sm text-slate-500"} label]
   [:div {:class "mt-1 text-3xl font-semibold text-slate-900"} value]
   [:div {:class "mt-1 text-xs text-slate-500"} sub]])

(defn badge [text tone]
  [:span {:class (str "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium "
                      (case tone
                        :green "bg-emerald-100 text-emerald-800"
                        :blue "bg-blue-100 text-blue-800"
                        :amber "bg-amber-100 text-amber-900"
                        :slate "bg-slate-100 text-slate-800"
                        "bg-slate-100 text-slate-800"))}
   text])

(def scenarios (vec (:results scenario-results)))
(def claims (vec (:claims evidence-matrix)))
(def invariant-rows (vec (:invariants invariants)))

(defn count-where [pred xs]
  (count (filter pred xs)))

(defn status-tone [s]
  (case (name s)
    "pass" :green
    "fail" :amber
    "inconclusive" :slate
    :slate))

(def status-counts
  {:pass (count-where #(= "pass" (name (:status %))) scenarios)
   :fail (count-where #(= "fail" (name (:status %))) scenarios)
   :inconclusive (count-where #(= "inconclusive" (name (:status %))) scenarios)})

(def sim-backed-count
  (or (:simulator_backed_count summary)
      (count-where :simulator_backed scenarios)))

(def pinned-count
  (or (:pinned_derivation_count summary)
      (count-where #(= "pinned-derivation" (:evidence_type %)) scenarios)))

(def placeholder-count
  (or (:placeholder_count summary)
      (count-where #(= "placeholder" (:evidence_type %)) scenarios)))

;; # Reference Validation Evidence Dashboard

;; This notebook is a **read-only artifact-driven evidence dashboard**.
;;
;; It is not the validation system itself. The source of truth is the CLI suite:
;;
;; - `make reference-validation-v1`
;; - `make verify-reference-validation-v1`

;; ## 1) Overview

[:div {:class "mb-4 rounded-xl border border-blue-200 bg-blue-50 p-4"}
 [:div {:class "text-sm font-semibold text-blue-900"} "Start here (first-time visitors)"]
 [:ul {:class "mt-2 list-disc pl-5 text-sm text-blue-900"}
  [:li "This page is a read-only evidence dashboard over committed artifacts."]
  [:li "Run canonical validation via: make reference-validation-v1 && make verify-reference-validation-v1"]
  [:li "Use bb help:first for the most useful newcomer commands."]]]

[:div {:class "grid grid-cols-1 gap-4 md:grid-cols-4"}
 (metric-card "Suite" (or (:suite_id summary) "reference-validation-v1") "Canonical reference suite")
 (metric-card "Version" (or (:suite_version summary) "1.1.0") "Current artifact version")
 (metric-card "Status" (str/upper-case (name (or (:status summary) :pass))) "Expected: PASS")
 (metric-card "Scenarios" (or (:scenario_count summary) (count scenarios)) "Curated high-value set")]

;; ## 2) Evidence composition

[:div {:class "mt-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm"}
 [:div {:class "mb-2 text-sm font-medium text-slate-700"}
  "Reference Validation Suite v1.1 posture: honest hybrid"]
 [:div {:class "grid grid-cols-1 gap-2 md:grid-cols-3"}
  [:div (badge "simulator-backed" :green) [:span {:class "ml-2 text-sm"} sim-backed-count]]
  [:div (badge "pinned-derivation" :blue) [:span {:class "ml-2 text-sm"} pinned-count]]
  [:div (badge "placeholder" :amber) [:span {:class "ml-2 text-sm"} placeholder-count]]]
 [:div {:class "mt-3 text-xs text-slate-500"}
  "No scenario should be marked simulator-backed unless it is derived from simulator execution in run.sh."]]

;; ## 2.1) Quick outcome view

[:div {:class "mt-4 grid grid-cols-1 gap-3 md:grid-cols-3"}
 (metric-card "PASS" (:pass status-counts) "Scenarios passing")
 (metric-card "FAIL" (:fail status-counts) "Scenarios failing")
 (metric-card "INCONCLUSIVE" (:inconclusive status-counts) "Scenarios needing deeper evidence")]

;; ## 2.2) Scenario selector (read-only jump list)

[:div {:class "mt-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm"}
 [:div {:class "mb-2 text-sm font-medium text-slate-700"} "Scenario selector"]
 [:div {:class "text-xs text-slate-500"}
  "Click any scenario below to jump to its card in this notebook."]
 [:div {:class "mt-3 flex flex-wrap gap-2"}
  (for [{:keys [scenario_id status]} scenarios]
    ^{:key scenario_id}
    [:a {:href (str "#" scenario_id)
         :class "rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 hover:bg-slate-50"}
     (str scenario_id " · " (str/upper-case (name status)))])]]

;; ## 3) What this proves / doesn’t prove

{:helps_show
 ["Reproducible outputs"
  "Claim-to-scenario mapping"
  "Deterministic evidence packaging"
  "Explicit assumptions and limits"]
 :does_not_prove
 ["Complete protocol correctness"
  "Exhaustive adversarial coverage"
  "Objective dispute truth"
  "Audit replacement"]}

;; ## 4) Claim → Threat → Invariant → Scenario matrix

(mapv (fn [{:keys [claim_id threat invariants scenarios status evidence_type simulator_backed confidence trace_hash]}]
        {:claim-id claim_id
         :threat threat
         :invariants invariants
         :scenarios scenarios
         :status status
         :evidence-type evidence_type
         :simulator-backed simulator_backed
         :confidence confidence
         :trace-hash (or trace_hash "-")})
      claims)

;; ## 5) Scenario cards (artifact-derived)

(mapv (fn [{:keys [scenario_id status primary_claim primary_threat evidence_type confidence simulator_backed trace_hash trace_path source_artifact upgrade_path]}]
        [:div {:id scenario_id
               :class "mb-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm"}
         [:div {:class "flex items-center justify-between"}
          [:div {:class "text-sm font-semibold text-slate-900"} scenario_id]
          (badge (str/upper-case (name status)) (status-tone status))]
         [:div {:class "mt-2 grid grid-cols-1 gap-1 text-xs text-slate-700 md:grid-cols-2"}
          [:div [:span {:class "font-medium"} "Claim: "] (str primary_claim)]
          [:div [:span {:class "font-medium"} "Threat: "] (str primary_threat)]
          [:div [:span {:class "font-medium"} "Evidence: "] (str evidence_type)]
          [:div [:span {:class "font-medium"} "Confidence: "] (str confidence)]
          [:div [:span {:class "font-medium"} "Simulator-backed: "] (str simulator_backed)]
          [:div [:span {:class "font-medium"} "Trace hash: "] (or trace_hash "-")]
          [:div [:span {:class "font-medium"} "Trace path: "] (or trace_path "-")]
          [:div [:span {:class "font-medium"} "Source: "] (str source_artifact)]
          [:div [:span {:class "font-medium"} "Upgrade path: "] (str upgrade_path)]]])
      scenarios)

;; ## 6) Invariant summary

(mapv (fn [{:keys [invariant_id status scenarios]}]
        {:invariant invariant_id
         :status status
         :scenarios scenarios})
      invariant-rows)

;; ## 7) Reproducibility block (source of truth)

{:commands ["make clean-reference-validation-v1"
            "make reference-validation-v1"
            "make verify-reference-validation-v1"]
 :expected_result ["PASS reference-validation-v1"
                   "7 scenarios"
                   "0 failures"
                   "0 inconclusive"]}

;; ## 8) Current limitations

[:div {:class "rounded-xl border border-amber-300 bg-amber-50 p-4"}
 [:div {:class "mb-2 text-sm font-semibold text-amber-900"} "Current limitations"]
 [:ul {:class "list-disc pl-5 text-sm text-amber-900"}
  [:li "Not all scenarios are simulator-backed yet."]
  [:li "Some evidence remains pinned deterministic derivations."]
  [:li "The suite does not exhaust all adversarial strategies."]
  [:li "The suite does not replace audits."]
  [:li "The suite validates modeled scenarios under stated assumptions."]]]

;; ## 9) Upgrade roadmap

{:roadmap [{:version "v1.0" :meaning "deterministic scaffold"}
           {:version "v1.1" :meaning "hybrid reproducibility suite"}
           {:version "v1.2" :meaning "partial simulator-backed suite (4+ scenarios target)"}
           {:version "v2.0" :meaning "fully simulator-backed canonical suite"}]}
