(ns resolver-sim.definitions.registry-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.outcome-semantics :as ose]))

(deftest purpose-and-status-parity
  (is (defs/valid-purpose? :theory-falsification))
  (is (= "Theory Falsification" (ose/purpose-label :theory-falsification)))
  (is (defs/valid-status? :inconclusive))
  (is (= "Inconclusive" (ose/theory-label :inconclusive))))

(deftest stable-definitions-hash
  (is (string? (defs/definitions-hash)))
  (is (= (defs/definitions-hash) (defs/definitions-hash))))
