(ns resolver-sim.scenario.dispute-coverage
  "Dispute resolution coverage report.
   Produces a structured map of dispute-resolution scenarios, coverage
   breakdown, gaps, and researcher-readiness flags.
   Designed to be readable by a first-time researcher."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.definitions.registry :as defs]))

(def coverage-categories
  "Coverage category definitions with full scenario file paths."
  {:basic-lifecycle    {:label "Basic lifecycle"
                        :scenarios ["S-DR-001-basic-release-ruling"
                                    "S-DR-002-basic-refund-ruling"
                                    "S-DR-003-duplicate-dispute-rejected"
                                    "S-DR-004-timeout-default-resolution"]}
   :evidence           {:label "Evidence robustness"
                        :scenarios ["S-DR-010-missing-evidence"
                                    "S-DR-011-contradictory-evidence"]}
   :strategic          {:label "Strategic disputants"
                        :scenarios ["S-DR-020-false-claimant-slashed"
                                    "S-DR-021-griefing-claim-cost"
                                    "S-DR-022-lazy-counterparty-timeout"]}
   :resolver-integrity {:label "Resolver integrity"
                        :scenarios ["S-DR-030-biased-resolver-appealed"
                                    "S-DR-031-colluding-resolver-detected"
                                    "S-DR-032-resolver-insufficient-stake"]}
   :finality           {:label "Finality and payout correctness"
                        :scenarios ["S-DR-040-finality-blocked-during-appeal"
                                    "S-DR-041-finality-after-appeal-window"
                                    "S-DR-042-duplicate-claim-after-finality-rejected"
                                    "S-DR-043-payout-shortfall-deferred"
                                    "S-DR-044-slash-obligation-unmet-recorded"]}})

(def coverage-gaps
  "Known research gaps that cannot yet be tested because the model lacks
   a necessary concept.  These are TODO stubs, not silences."
  [{:coverage :evidence
    :gap :world-hash-linkage
    :status :resolved
    :resolution "evidence-summary.json now surfaces world/before-hash and world/after-hash for every evidence record. See resolver-sim.evidence.summary/write-evidence-summary!"}
   {:coverage :evidence
    :gap :evidence-deadline
    :reason "protocol-params has no evidence-window-duration; submit-evidence accepted anytime while :disputed"}
   {:coverage :strategic
    :gap :expected-value-appeal
    :reason "no game-theoretic EV model for appeal decisions; can only test structural behaviour"}
   {:coverage :resolver-integrity
    :gap :resolver-response-deadline
    :reason "no resolver-response-window param; lazy resolver detected only via max-dispute-duration timeout"}])

(defn- scenario-exists?
  "Check if a scenario file exists on disk."
  [scenario-id]
  (let [path (str "scenarios/" scenario-id ".json")]
    (.exists (io/file path))))

(defn- scenario-tags
  "Read tags from a scenario file."
  [scenario-id]
  (let [path (str "scenarios/" scenario-id ".json")]
    (when (.exists (io/file path))
      (try (let [sc (sc/load-scenario-file path)]
             (or (get sc :tags []) []))
           (catch Exception _ [])))))

(defn- has-todo-stub-tag?
  [scenario-id]
  (some #(= "status/todo-stub" %) (scenario-tags scenario-id)))

(defn dispute-resolution-coverage-report
  "Produce a structured coverage report for dispute resolution research.
   Returns a map with :suite, :total-scenarios, :by-coverage, :missing,
   and :researcher-readiness keys.
   Call via:
     (require '[resolver-sim.scenario.dispute-coverage :as dc])
     (dc/dispute-resolution-coverage-report)"
  []
  (let [all-defined (vec (mapcat :scenarios (vals coverage-categories)))
        existing (filter scenario-exists? all-defined)
        todo-stubs (filter has-todo-stub-tag? all-defined)
        by-coverage (into (sorted-map)
                          (map (fn [[k v]]
                                 [k (count (filter #(scenario-exists? %) (:scenarios v)))]))
                          (seq coverage-categories))
        missing (filter #(not (scenario-exists? %)) all-defined)]
    {:suite :dispute-resolution
     :total-scenarios (count existing)
     :defined-scenarios (count all-defined)
     :todo-stubs (count todo-stubs)
     :by-coverage by-coverage
      :missing (vec (for [m missing]
                     (let [cat (first (filter (fn [[_ v]] ((set (:scenarios v)) m)) coverage-categories))]
                       {:scenario-id m
                        :coverage (first cat)
                        :reason "scenario file not found on disk"})))
     :gaps coverage-gaps
     :researcher-readiness
     {:trace-summary? true
      :evidence-summary? true
      :evidence-world-hashes? true
      :financial-outcome? true
      :linked-evidence-group? true
      :invariant-results? true
      :dispute-summary? true}}))

(defn -main
  "CLI entrypoint: print dispute resolution coverage report as JSON.
   Usage: clojure -M:dispute-coverage-report"
  [& _args]
  (let [report (dispute-resolution-coverage-report)]
    (println (json/write-str report {:indent true}))))
