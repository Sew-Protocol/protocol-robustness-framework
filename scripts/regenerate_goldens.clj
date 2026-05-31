(ns scripts.regenerate-goldens
  (:require [clojure.edn :as edn]
            [resolver-sim.sim.fixtures :as f]))

(def suite-ids
  [:suites/all-invariants
   :suites/baseline-safety
   :suites/equilibrium-validation
   :suites/spe-validation
   :suites/spe-regression
   :suites/equivalence-auth-paths
   :suites/equivalence-race-pairs
   :suites/equivalence-escalation-boundaries
   :suites/equivalence-accounting-min
   :suites/equivalence-money-path-integrity
   :suites/dr3-critical
   :suites/governance-decay
   :suites/same-block-ordering
   :suites/timelock-regression])

(defn- theory-fail-reason [r]
  (when (:theory r)
    (let [status (get-in r [:theory :status])
          mech   (get-in r [:theory :mechanism-status])
          eq     (get-in r [:theory :equilibrium-status])]
      (when-not (and (= :pass status)
                     (contains? #{:pass :not-checked :inconclusive} mech)
                     (contains? #{:pass :not-checked :inconclusive} eq))
        (select-keys (:theory r) [:status :mechanism-status :equilibrium-status])))))

(defn- trace-fail? [r]
  (or (not= :pass (:outcome r))
      (not (:ok? (:threshold-validation r)))
      (and (:expectations r) (not (:ok? (:expectations r))))
      (some? (theory-fail-reason r))
      (and (:golden-comparison r) (not (:ok? (:golden-comparison r))))
      (and (:expected-outcome r) (not= (:expected-outcome r) (:outcome r)))
      (and (:expected-halt-reason r) (not= (:expected-halt-reason r) (:halt-reason r)))))

(defn -main [& _]
  (let [manifest   (edn/read-string (slurp "data/fixtures/suites/manifest.edn"))
        all-suites (vec (set (concat suite-ids (keys manifest))))
        saved      (atom 0)
        failures   (atom [])]
    (doseq [suite-id all-suites]
      (try
        (let [{:keys [ok? results]} (f/run-suite suite-id :save)]
          (swap! saved + (count results))
          (when-not ok?
            (doseq [r results]
              (when (trace-fail? r)
                (swap! failures conj
                       {:suite suite-id
                        :trace (:trace-id r)
                        :outcome (:outcome r)
                        :halt (:halt-reason r)
                        :golden-drift? (and (:golden-comparison r)
                                            (not (:ok? (:golden-comparison r))))
                        :expectations (when (and (:expectations r)
                                                 (not (:ok? (:expectations r))))
                                        (:expectations r))
                        :theory (theory-fail-reason r)})))))
        (catch Exception e
          (swap! failures conj {:suite suite-id :error (.getMessage e)}))))
    (println (format "Regenerated golden reports for %d trace runs across %d suites."
                     @saved (count all-suites)))
    (if (seq @failures)
      (do
        (println "\nRemaining suite failures after golden save:")
        (doseq [f @failures]
          (println " " (pr-str f)))
        (System/exit 1))
      (println "All suites pass after regeneration."))))
