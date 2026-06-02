(ns resolver-sim.sim.determinism-test
  "Proves that run-suite produces bit-identical results across two consecutive runs.

   A determinism failure here means the replay engine has a non-deterministic
   code path (e.g. unordered map iteration, mutable state, time-dependent logic).
   All golden reports depend on this property holding."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.sim.fixtures :as fixtures]))

(deftest all-invariants-suite-is-deterministic
  (let [result-1 (fixtures/run-suite :suites/all-invariants nil nil {:silent? true})
        result-2 (fixtures/run-suite :suites/all-invariants nil nil {:silent? true})]
    (is (= (dissoc result-1 :elapsed-ms)
           (dissoc result-2 :elapsed-ms))
        "Suite results differ between runs — replay engine has a non-deterministic code path")))
