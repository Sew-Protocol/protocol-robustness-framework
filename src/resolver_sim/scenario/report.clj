(ns resolver-sim.scenario.report
  "Human-readable reporting for scenario collection summaries (prints only).")

(defn- status-label
  [{:keys [pass? expected-fail?]}]
  (cond
    (and pass? expected-fail?) "✓ XFAIL"
    pass? "✓ PASS"
    :else "✗ FAIL"))

(defn- format-violation
  [v]
  (case (:type v)
    :metric-violation
    (format "metric %s %s %s (actual %s)"
            (:name v) (:op v) (:expected v) (:actual v))

    :terminal-mismatch
    (format "terminal %s expected %s (actual %s)" (:path v) (:expected v) (:actual v))

    :expected-outcome-mismatch
    (format "seq %s %s expected %s (actual %s)" (:seq v) (:action v) (:expected v) (:actual v))

    :invariant-failed
    (format "invariant %s (%s)" (:invariant v) (:note v))

    (str v)))

(defn format-check-failures
  "One-line summaries for expectation / expected-outcomes / theory failures."
  [entry]
  (let [checks (:checks entry)]
    (vec
     (concat
      (when-let [eo (:expected-outcomes checks)]
        (when-not (:ok? eo true)
          (map #(str "  expected-outcome: " (format-violation %)) (:violations eo))))
      (when-let [exp (:expectations checks)]
        (when-not (:ok? exp true)
          (map #(str "  expectation: " (format-violation %)) (:violations exp))))
      (when-let [th (:theory checks)]
        (when-not (:ok? th true)
          ["  theory: check failed"]))))))

(defn print-report
  "Print a human-readable report from a collection summary map.

   Returns exit code 0 when all entries passed, 1 otherwise.

   Options:
     :title          — header title string
     :verbose?       — print step outline for every scenario
     :show-failures? — print outline for failing scenarios (default true)
     :show-checks?   — print expectation/theory one-liners for failures (default true)
     :outline-printer — (fn [display-name replay-result]) for failure/verbose outlines"
  ([summary] (print-report summary {}))
  ([{:keys [passed total elapsed-ms suite-id results]} opts]
   (let [verbose?       (:verbose? opts false)
         show-failures? (:show-failures? opts true)
         show-checks?   (:show-checks? opts true)
         title          (or (:title opts)
                            (if suite-id
                              (format "Scenario suite: %s" (name suite-id))
                              "Deterministic scenario collection"))
         w              72]
     (println (apply str (repeat w "═")))
     (println (format "  %s" title))
     (println (apply str (repeat w "═")))
     (println (format "  %-47s %5s  %7s  %s" "Scenario" "steps" "reverts" "status"))
     (println (str "  " (apply str (repeat (- w 2) "─"))))
     (doseq [entry results]
       (let [status (status-label entry)
             extra  (when (pos? (:violations entry 0))
                      (format "  violations=%d" (:violations entry)))]
         (println (format "  %s  %-45s %5d  %7d%s"
                          status (:name entry) (:steps entry) (:reverts entry) (or extra "")))
         (when (and show-checks? (not (:pass? entry))
                    (seq (format-check-failures entry)))
           (doseq [line (format-check-failures entry)]
             (println line)))
         (when (and (not (:pass? entry)) (:halt-reason entry))
           (println (format "  halt: %s" (:halt-reason entry))))))
     (println (apply str (repeat w "─")))
     (println (format "  %d/%d passed  (%.1f s)" passed total (/ elapsed-ms 1000.0)))
     (println (apply str (repeat w "═")))

     (when-let [outline (:outline-printer opts)]
       (when (or verbose? show-failures?)
         (doseq [{:keys [name pass? details]} results
                 :when (or verbose? (not pass?))
                 detail details]
           (outline name detail))))

     (if (= passed total) 0 1))))
