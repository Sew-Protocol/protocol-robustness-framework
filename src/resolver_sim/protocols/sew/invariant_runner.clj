(ns resolver-sim.protocols.sew.invariant-runner
  "In-process runner for the deterministic invariant scenarios (S01–S100).

   Runs every scenario in invariant-scenarios/all-scenarios against
   sew/replay-with-sew-protocol, reports pass/fail per entry, and returns a
   summary map suitable for CLI consumption.

   S12 is a paired scenario (vector of two maps); it passes only when
   both sub-scenarios pass.

   Each result entry is enriched with type metadata from
   invariant-scenarios/scenario-type-registry (:scenario/type,
   :adversary/type, :adversary/traits) for queryable output.

   File-backed scenario runs live in resolver-sim.io.invariant-runner."
  (:require [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.protocols.sew.narrative :as narrative]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- run-one
  "Run a single scenario map.  Returns {:pass? bool :expected-fail? bool :result result}.

   When :expected-fail? is true on the scenario, the test passes only when the
   replay outcome is :fail (the invariant is expected to fire)."
  [scenario]
  (let [result        (sew/replay-with-sew-protocol scenario)
        expected-fail (boolean (:expected-fail? scenario false))
        outcome       (:outcome result)
        pass?         (if expected-fail
                        (= :fail outcome)
                        (= :pass outcome))]
    {:pass? pass? :expected-fail? expected-fail :result result}))

(defn- run-entry
  "Run a registry entry (single scenario or [s12a s12b] pair).
   Returns {:pass? bool :expected-fail? bool :steps int :reverts int :violations int :details [...]}."
  [entry]
  (if (map? entry)
    (let [{:keys [pass? expected-fail? result]} (run-one entry)]
      (when (and (not pass?) (pos? (get-in result [:metrics :invariant-violations] 0)))
        (println (format "Violation details for %s: %s" (:scenario-id entry) (get-in result [:metrics :invariant-results]))))
      {:pass?          pass?
       :expected-fail? expected-fail?
       :steps          (:events-processed result 0)
       :reverts        (get-in result [:metrics :reverts] 0)
       ;; Expected-fail scenarios are supposed to trigger the invariant — don't count as a violation.
       :violations     (if expected-fail? 0 (get-in result [:metrics :invariant-violations] 0))
       :details        [result]})
    ;; Paired scenario: both must pass (expected-fail? propagates from the first sub-scenario)
    (let [results    (mapv run-one entry)
          all-ok     (every? :pass? results)
          any-xfail  (boolean (some :expected-fail? results))]
      {:pass?          all-ok
       :expected-fail? any-xfail
       :steps          (reduce + (map #(get-in % [:result :events-processed] 0) results))
       :reverts        (reduce + (map #(get-in % [:result :metrics :reverts] 0) results))
       :violations     (reduce + (map #(if (:expected-fail? %)
                                         0
                                         (get-in % [:result :metrics :invariant-violations] 0))
                                      results))
       :details        (mapv :result results)})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run-all
  "Run all deterministic invariant scenarios (S01–S100).  Returns a summary map:
     {:passed int :total int :elapsed-ms long :results [{entry-result}]}
   where each entry-result adds :name, :scenario/type, and optional
   :adversary/type / :adversary/traits from the scenario-type-registry."
  []
  (let [t0      (System/currentTimeMillis)
        results (mapv (fn [[name entry]]
                        (let [entry-result (run-entry entry)
                              sid          (if (map? entry)
                                             (:scenario-id entry)
                                             (:scenario-id (first entry)))
                              type-meta    (get sc/scenario-type-registry sid {})]
                          (merge {:name name} type-meta entry-result)))
                      sc/all-scenarios)
        elapsed (- (System/currentTimeMillis) t0)]
    {:passed    (count (filter :pass? results))
     :total     (count results)
     :elapsed-ms elapsed
     :results   results}))

(defn print-scenario-outline
  "Print a human-readable per-step narrative for one replay result.
   Called automatically for failing scenarios in print-report, and for all
   scenarios when verbose? is true."
  [display-name result]
  (let [{:keys [header lines footer separator]} (narrative/scenario-outline display-name result)]
    (println header)
    (doseq [line lines] (println line))
    (println footer)
    (println separator)))

(defn print-report
  "Print a human-readable report from run-all output.  Returns exit code (0/1).

   Options map (second arg):
     :verbose?       — print step-by-step outline for every scenario (default false)
     :show-failures? — print step-by-step outline for failing scenarios (default true)"
  ([summary] (print-report summary {}))
  ([{:keys [passed total elapsed-ms results]} opts]
   (let [verbose?       (:verbose? opts false)
         show-failures? (:show-failures? opts true)
         w              72]
     (println (apply str (repeat w "═")))
     (println "  Sew Invariant Suite — Deterministic Scenarios (Clojure in-process)")
     (println (apply str (repeat w "═")))
     (println (format "  %-47s %5s  %7s  %s" "Scenario" "steps" "reverts" "status"))
     (println (str "  " (apply str (repeat (- w 2) "─"))))
     (doseq [{:keys [name pass? expected-fail? steps reverts violations]} results]
       (let [status (cond
                      (and pass? expected-fail?) "✓ XFAIL"
                      pass?                      "✓ PASS"
                      :else                      "✗ FAIL")
             extra  (when (pos? violations) (format "  violations=%d" violations))]
         (println (format "  %s  %-45s %5d  %7d%s"
                          status name steps reverts (or extra "")))))
     (println (apply str (repeat w "─")))
     (println (format "  %d/%d passed  (%.1f s)" passed total (/ elapsed-ms 1000.0)))
     (println (apply str (repeat w "═")))

     ;; Narrative outlines: always for failures, optionally for all
     (when (or verbose? show-failures?)
       (doseq [{:keys [name pass? details]} results
               :when (or verbose? (not pass?))
               detail details]
         (print-scenario-outline name detail)))

     (if (= passed total) 0 1))))

(defn run-and-report
  "Convenience: run-all then print-report.  Returns exit code.
   Accepts an optional options map forwarded to print-report."
  ([] (run-and-report {}))
  ([opts] (print-report (run-all) opts)))
