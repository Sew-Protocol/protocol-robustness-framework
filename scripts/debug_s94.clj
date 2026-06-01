(ns debug-s94
  (:require [resolver-sim.protocols.sew :as sew]
            [resolver-sim.contract-model.replay :as replay]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

(defn -main []
  (let [scenario (json/read (io/reader "scenarios/S94_dispute-timeout-auto-refund.json") :key-fn keyword)
        result (sew/replay-with-sew-protocol scenario)]
    (if (= (:outcome result) :fail)
      (println "S94 FAILED as expected, investigating violations...")
      (println "S94 PASSED unexpectedly!"))))
