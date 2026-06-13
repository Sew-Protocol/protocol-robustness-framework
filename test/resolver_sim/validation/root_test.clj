(ns resolver-sim.validation.root-test
  (:require [clojure.test :refer :all]
            [resolver-sim.validation.root :as sut]
            [resolver-sim.validation.suite-result :as suite]))

(deftest test-root-reduction
  (let [s1 (suite/suite-result :s1 :test [{:check/id :c1 :status :passed :status-key :ok}])
        s2 (suite/suite-result :s2 :test [{:check/id :c2 :status :failed :error-key :e1 :message "Fail"}])
        root (sut/build-validation-root [s1 s2])]
    (is (= :failed (:status root)))
    (is (= #{:ok} (:status-keys root)))
    (is (= #{:e1} (:error-keys root)))
    (is (= 2 (get-in root [:metrics :checks])))
    (is (= 1 (get-in root [:metrics :passed])))
    (is (= 1 (get-in root [:metrics :failed])))))

(deftest test-derive-root-status
  (let [base (assoc sut/empty-validation-root :metrics {:checks 10 :passed 10})]
    (is (= :passed (sut/derive-root-status base)))
    (is (= :warning (sut/derive-root-status (assoc base :warning-keys #{:w1}))))
    (is (= :failed (sut/derive-root-status (assoc base :error-keys #{:e1}))))
    (is (= :failed-critical (sut/derive-root-status (assoc base :error-keys #{:invariant/broken}))))))

(deftest test-merge-metrics
  (is (= {:checks 2 :passed 1 :failed 1}
         (sut/merge-metrics {:checks 1 :passed 1} {:checks 1 :failed 1}))))

(deftest test-status-summary
  (let [root (assoc sut/empty-validation-root :status :passed :metrics {:checks 10 :passed 10})]
    (is (re-find #"Validation: passed" (sut/status-summary root)))))
