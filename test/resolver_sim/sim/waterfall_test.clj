(ns resolver-sim.sim.waterfall-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.sim.waterfall :as waterfall]))

(deftest test-apply-waterfall-loss
  (testing "Generic waterfall loss allocation"
    (let [world {:waterfall-layers 
                 {:junior-bond {:capacity 500}
                  :senior-coverage {:capacity 200}
                  :unmet-obligation {:capacity Double/MAX_VALUE}}}
          ;; Scenario 1: Loss fully absorbed by junior bond
          res1 (waterfall/apply-waterfall-loss world [:junior-bond :senior-coverage] 300)
          _ (is (= 300 (get-in res1 [:absorbed :junior-bond])))
          _ (is (= 0 (:drift res1)))
          
          ;; Scenario 2: Loss exhausts junior, partially senior
          res2 (waterfall/apply-waterfall-loss world [:junior-bond :senior-coverage] 600)
          _ (is (= 500 (get-in res2 [:absorbed :junior-bond])))
          _ (is (= 100 (get-in res2 [:absorbed :senior-coverage])))
          _ (is (= 0 (:drift res2)))

          ;; Scenario 3: Loss exhausts all, hits unmet (drift is 100)
          res3 (waterfall/apply-waterfall-loss world [:junior-bond :senior-coverage] 800)
          _ (is (= 500 (get-in res3 [:absorbed :junior-bond])))
          _ (is (= 200 (get-in res3 [:absorbed :senior-coverage])))
          _ (is (= 100 (:drift res3)))]
      
      (is (= 0 (:drift res1))))))

(defn -main [& _]
  (run-tests 'resolver-sim.sim.waterfall-test))
