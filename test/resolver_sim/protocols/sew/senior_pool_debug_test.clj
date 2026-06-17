(ns resolver-sim.protocols.sew.senior-pool-debug-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.resolution :as res]))

(deftest debug-pending-slash
  (let [world (t/empty-world 1000)
        pending {:resolver "0xRes" :amount 100 :status :pending :proposed-at 1000 :appeal-deadline 1000}
        world' (assoc-in world [:pending-fraud-slashes 0] pending)]
    (println "DEBUG: pending exists?" (get-in world' [:pending-fraud-slashes 0]))
    (println "DEBUG: deadline" (:appeal-deadline (get-in world' [:pending-fraud-slashes 0])))
    (is (some? (get-in world' [:pending-fraud-slashes 0])))))
