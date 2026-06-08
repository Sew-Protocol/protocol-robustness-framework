(require '[clojure.test :as t]
         '[resolver-sim.protocols.sew.replay-test :as rt])

(defn run-one [sym]
  (let [r (t/test-var sym)]
    (println sym (if (zero? (:fail r 0)) "PASS" "FAIL"))
    r))

;; Run tests in same order as clojure.test (alphabetical by var name)
(let [vars (->> (ns-publics 'resolver-sim.protocols.sew.replay-test)
                keys
                (filter #(re-matches #"test-.*" (name %)))
                sort
                vec)]
  (doseq [v vars]
    (when (= 'test-withdraw-stake-allows-at-unfreeze-boundary v)
      (println ">>> about to run allows-at-unfreeze")))
    (run-one (symbol "resolver-sim.protocols.sew.replay-test" v))))
