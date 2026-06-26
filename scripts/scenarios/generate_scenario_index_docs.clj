(ns scripts.generate-scenario-index-docs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.protocols.sew.invariant-scenarios :as scenarios]
            [resolver-sim.protocols.sew.invariant-scenarios.doc-summaries :as doc]))

(def ^:private s01-s23-display-order
  [["S01" "s01-baseline-happy-path"]
   ["S02" "s02-dr3-dispute-release"]
   ["S03" "s03-dr3-dispute-refund"]
   ["S04" "s04-dispute-timeout-autocancel"]
   ["S05" "s05-pending-settlement-execute"]
   ["S06" "s06-mutual-cancel"]
   ["S07" "s07-unauthorized-resolver-rejected"]
   ["S08" "s08-state-machine-attack-gauntlet"]
   ["S09" "s09-multi-escrow-solvency"]
   ["S10" "s10-double-finalize-rejected"]
   ["S11" "s11-zero-fee-edge-case"]
   ["S12" "s12a-snapshot-isolation-fee-zero / s12b-snapshot-isolation-fee-500"]
   ["S13" "s13-pending-settlement-refund"]
   ["S14" "s14-dr3-module-authorized"]
   ["S15" "s15-dr3-module-unauthorized-rejected"]
   ["S16" "s16-ieo-create-release"]
   ["S17" "s17-ieo-dispute-no-resolver-timeout"]
   ["S18" "s18-dr3-kleros-l0-resolves"]
   ["S19" "s19-dr3-kleros-escalation-rejected-l0-resolves"]
   ["S20" "s20-dr3-kleros-max-escalation-guard"]
   ["S21" "s21-dr3-kleros-pending-cleared-on-escalation"]
   ["S22" "s22-status-leak-agree-cancel-over-dispute"]
   ["S23" "s23-preemptive-escalation-blocked"]])

(defn- short-scenario-name [sid]
  (when-let [[_ rest] (re-matches #"^s\d+[a-z]?-(.+)$" sid)]
    rest))

(defn- generated-s01-s23-rows []
  (let [sb (StringBuilder.)]
    (doseq [[label sid] s01-s23-display-order]
      (let [summary (if (str/includes? sid " / ")
                      (str (doc/require-s01-s23-summary! "s12a-snapshot-isolation-fee-zero")
                           " / "
                           (doc/require-s01-s23-summary! "s12b-snapshot-isolation-fee-500"))
                      (doc/require-s01-s23-summary! sid))
            name    (if (str/includes? sid " / ")
                      "governance-snapshot-isolation (s12a+s12b)"
                      (short-scenario-name sid))]
        (.append sb (format "| %s | %s | %s |\n" label name summary))))
    (.toString sb)))

(defn- replace-between-markers [content start-marker end-marker replacement]
  (let [start (.indexOf content start-marker)
        end   (.indexOf content end-marker)]
    (if (and (neg? start) (neg? end))
      content
      (str (subs content 0 (+ start (count start-marker)))
           "\n"
           replacement
           "\n"
           (subs content end)))))

(defn -main [& _]
  (let [table     (generated-s01-s23-rows)
        scenarios (slurp "docs/scenarios.md")
        robust    (slurp "docs/ROBUSTNESS_FRAMEWORK.md")
        edge-rows (str/join "\n"
                            (map (fn [[sid summary]]
                                   (format "| `%s` | %s |" sid summary))
                                 (sort-by key doc/robustness-edge-case-summaries)))
        scenarios* (replace-between-markers scenarios
                                            "<!-- GENERATED-S01-S23-START -->"
                                            "<!-- GENERATED-S01-S23-END -->"
                                            table)
        robust*    (replace-between-markers robust
                                              "<!-- GENERATED-EDGE-CASE-SUMMARIES-START -->"
                                              "<!-- GENERATED-EDGE-CASE-SUMMARIES-END -->"
                                              edge-rows)]
    (when (= scenarios scenarios*)
      (println "WARN: docs/scenarios.md missing GENERATED-S01-S23 markers — patch manually")
      (spit "docs/scenarios-S01-S23.generated.md" table))
    (when (= robust robust*)
      (println "WARN: docs/ROBUSTNESS_FRAMEWORK.md missing GENERATED-EDGE-CASE markers")
      (spit "docs/robustness-edge-case.generated.md" edge-rows))
    (when (not= scenarios scenarios*)
      (spit "docs/scenarios.md" scenarios*)
      (println "Updated docs/scenarios.md S01–S23 table"))
    (when (not= robust robust*)
      (spit "docs/ROBUSTNESS_FRAMEWORK.md" robust*)
      (println "Updated docs/ROBUSTNESS_FRAMEWORK.md edge-case summaries"))
    (println "Done.")))
