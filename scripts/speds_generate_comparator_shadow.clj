(ns speds-generate-comparator-shadow
  (:require [clojure.string :as str]
            [resolver-sim.notebooks.speds.data :as data]
            [resolver-sim.notebooks.speds.issues :as issues]))

(defn- parse-strategy [s]
  (keyword (str/trim s)))

(defn -main [& args]
  (let [strategies-arg (first args)
        strategies (if (seq strategies-arg)
                     (->> (str/split strategies-arg #",")
                          (map parse-strategy)
                          vec)
                     [:nearest-baseline-by-id :matched-by-purpose :matched-by-tags])
        artifacts (data/load-run-artifacts)
        report (issues/save-comparator-shadow-report!
                artifacts
                {:strategies strategies
                 :enabled? true})]
    (println "Generated comparator shadow report:")
    (println "  path: results/test-artifacts/comparator-shadow.json")
    (println "  strategies:" (pr-str (:strategies report)))
    (doseq [r (:runs report)]
      (println "  -" (name (:strategy r))
               "findings=" (:finding-count r)
               "issues=" (:issue-count r)))))
