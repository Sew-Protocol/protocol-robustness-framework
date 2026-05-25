(ns resolver-sim.sim.reporter
  "Result reporting for schema-profile-driven suite execution."
  (:require [clojure.string :as str]
            [resolver-sim.scenario.schema-profile :as schema-profile]
            [resolver-sim.scenario.outcome-semantics :as ose]))

(defn print-expectations [expectations]
  (if (nil? expectations)
    (println "    - Expectations: Not evaluated")
    (if (:ok? expectations)
      (println "    ✓ Expectations: Pass")
      (do
        (println "    ✗ Expectations: Fail")
        (doseq [v (:violations expectations)]
          (case (:type v)
            :terminal-mismatch (println "      - Terminal mismatch at" (:path v) ": expected" (:expected v) "got" (:actual v))
            :metric-violation  (println "      - Metric violation:" (:name v) (:op v) (:expected v) "got" (:actual v))
            :invariant-failed  (println "      - Invariant failed:" (:invariant v) (when (:note v) (str "(" (:note v) ")")))
            (println "      - Unknown violation:" v)))))))

(defn print-theory [theory purpose]
  (let [status (or (:status theory) :not-evaluated)
        label  (ose/theory-label status)
        ok?    (ose/theory-expected? status purpose)
        marker (case status
                 :inconclusive  "?"
                 :not-evaluated "-"
                 (if ok? "✓" "✗"))]
    (println (str "    " marker " Theory: " label))
    (when (and (= status :falsified) (seq (:evidence theory)))
      (doseq [e (:evidence theory)]
        (println "      - Evidence:" (:metric e) (:op e) (:value e) "→ actual" (:actual e))))
    (when (= status :inconclusive)
      (println "      - No tracked metrics matched the falsifies-if conditions"))))

(defn print-equilibrium [theory]
  (let [status (get theory :equilibrium-status :not-checked)
        results (get theory :equilibrium-results {})]
    (when (not= status :not-checked)
      (println (str "    " (case status
                             :pass "✓"
                             :fail "✗"
                             "?") " Equilibrium: " (name status)))
      (doseq [[k v] results]
        (println (str "      - " (name k) ": " (name (:status v))))
        (when (:note v) (println "        Note:" (:note v)))))))

(defn print-suite-results [suite-result]
  (println (str "\nSuite: " (:suite-id suite-result)))
  (println (str "Overall Status: " (if (:ok? suite-result) "PASS" "FAIL")))
  (println "----------------------------------------------------------------------")
  (doseq [r (:results suite-result)]
    (println (str "[" (:outcome r) "] " (:trace-id r)))
    (print-expectations (:expectations r))
    (print-theory (:theory r) (:purpose r))
    (print-equilibrium (:theory r))))

;; ---------------------------------------------------------------------------
;; Coverage reporting
;; ---------------------------------------------------------------------------

(defn- purpose-label [p]
  (ose/purpose-label p))

(defn print-coverage
  "Print a human-readable coverage report from a map returned by
   resolver-sim.scenario.coverage/coverage-report."
  [report]
  (let [total     (:total report 0)
        versions  (:schema-versions report {})
        by-purpose (:by-purpose report {})
        tag-freq  (:threat-tag-freq report {})
        transition-freq (:transition-hit-freq report {})
        transition-by-purpose (:transition-by-purpose-hit-freq report {})
        guard-freq (:guard-hit-freq report {})
        guard-by-purpose (:guard-by-purpose-hit-freq report {})
        unhit-transitions (:unhit-transitions report [])
        uncl      (:unclassified-count report 0)
        enriched-version (or (:enriched-version report) (schema-profile/enriched-version))
        enriched-count (get versions enriched-version 0)]
    (println "\n╔══════════════════════════════════════════════════════════════════╗")
    (println "║  Scenario Coverage Report                                        ║")
    (println "╚══════════════════════════════════════════════════════════════════╝")
    (println (str "\n  Scanned: " (:scanned-dir report)))
    (println (str "  Total scenarios: " total))
    (println)

    ;; Schema version breakdown
    (println "  Schema versions:")
    (doseq [[v cnt] (sort-by key versions)]
      (let [bar (str/join "" (repeat cnt "█"))]
        (println (str "    v" v "  " (format "%3d" cnt) "  " bar))))
    (println)

    ;; By purpose
    (println "  By purpose:")
    (let [purpose-order [:regression :adversarial-robustness :theory-falsification :unclassified]
          all-purposes  (distinct (concat purpose-order (keys by-purpose)))]
      (doseq [p all-purposes
              :let [ids (get by-purpose p [])]
              :when (seq ids)]
        (println (str "    " (format "%-28s" (purpose-label p)) (count ids) " scenario(s)"))
        (doseq [id ids]
          (println (str "      · " (if (keyword? id) (name id) id))))))
    (println)

    ;; Threat tags
    (if (seq tag-freq)
      (do
        (println "  Threat tags (by frequency):")
        (doseq [[tag cnt] (sort-by (comp - val) tag-freq)]
          (println (str "    " (format "%-32s" (name tag)) cnt " scenario(s)"))))
      (println "  Threat tags: none tagged"))
    (println)

    ;; Transition coverage
    (if (seq transition-freq)
      (do
        (println "  Transition hits (all scenarios):")
        (doseq [[tr cnt] (sort-by (comp - val) transition-freq)]
          (println (str "    " (format "%-32s" (name tr)) cnt " hit(s)"))))
      (println "  Transition hits: none"))
    (println)

    ;; Transition coverage by purpose
    (when (seq transition-by-purpose)
      (println "  Transition hits by purpose:")
      (doseq [[p tf] (sort-by (comp str key) transition-by-purpose)
              :when (seq tf)]
        (println (str "    " (format "%-28s" (purpose-label p))))
        (doseq [[tr cnt] (sort-by (comp - val) tf)]
          (println (str "      · " (format "%-28s" (name tr)) cnt)))))
    (println)

    ;; Guard coverage
    (if (seq guard-freq)
      (do
        (println "  Guard hits (all scenarios):")
        (doseq [[g cnt] (sort-by (comp - val) guard-freq)]
          (println (str "    " (format "%-32s" (name g)) cnt " hit(s)"))))
      (println "  Guard hits: none"))
    (println)

    (when (seq guard-by-purpose)
      (println "  Guard hits by purpose:")
      (doseq [[p gf] (sort-by (comp str key) guard-by-purpose)
              :when (seq gf)]
        (println (str "    " (format "%-28s" (purpose-label p))))
        (doseq [[g cnt] (sort-by (comp - val) gf)]
          (println (str "      · " (format "%-28s" (name g)) cnt)))))
    (println)

    ;; Explicit release backlog
    (println "  Unhit transitions backlog (release gate):")
    (if (seq unhit-transitions)
      (doseq [tr unhit-transitions]
        (println (str "    · " (name tr))))
      (println "    none (all canonical transitions covered)"))
    (println)

    ;; Summary
    (println (str "  v" enriched-version " enriched:      " enriched-count " / " total " scenarios"))
    (println (str "  Unclassified (pre-v" enriched-version "): " uncl " scenarios without :purpose or :threat-tags"))
    (when (pos? uncl)
      (println (str "  → " uncl " scenarios could be enriched with :id, :title, :purpose, :threat-tags")))
    (println)))
