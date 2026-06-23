(ns resolver-sim.economics.payoffs-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.hash.canonical :as hc]))

(deftest allocate-pro-rata-equal-weights
  (testing "equal weights split evenly"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 50
                   :items [{:id :a :weight 100}
                           {:id :b :weight 100}]})]
      (is (= [{:id :a :allocated 25 :unmet 0 :weight 100 :cap nil}
              {:id :b :allocated 25 :unmet 0 :weight 100 :cap nil}]
             (:allocations result)))
      (is (= 50 (:total-requested result)))
      (is (= 50 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-unequal-weights
  (testing "unequal weights allocate proportionally"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 400
                   :items [{:id :a :weight 1000}
                           {:id :b :weight 500}
                           {:id :c :weight 500}]})]
      (is (= [200 100 100] (mapv :allocated (:allocations result))))
      (is (= 400 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-zero-weight-items
  (testing "zero-weight items receive no allocation while positive-weight items can receive all funds"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 50
                   :items [{:id :a :weight 100}
                           {:id :b :weight 0}]})]
      (is (= [50 0] (mapv :allocated (:allocations result))))
      (is (= [100 0] (mapv :weight (:allocations result))))
      (is (= 50 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))))
  (testing "all zero weights leave the amount unallocated as remainder"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 100
                   :items [{:id :a :weight 0}
                           {:id :b :weight 0}]})]
      (is (= [0 0] (mapv :allocated (:allocations result))))
      (is (= 0 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 100 (:remainder result))))))

(deftest allocate-pro-rata-capped-allocation
  (testing "caps limit individual allocations and record unmet amount"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 400
                   :items [{:id :a :weight 1000 :cap 1000}
                           {:id :b :weight 500 :cap 60}
                           {:id :c :weight 500 :cap 500}]
                   :cap-fn :cap})]
      (is (= [200 60 100] (mapv :allocated (:allocations result))))
      (is (= [0 40 0] (mapv :unmet (:allocations result))))
      (is (= 360 (:total-allocated result)))
      (is (= 40 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-dust-and-remainder-behavior
  (testing "default floor rounding leaves integer dust in remainder"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 10
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]})]
      (is (= [3 3 3] (mapv :allocated (:allocations result))))
      (is (= 9 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 1 (:remainder result)))))
  (testing "largest-remainder rounding distributes dust deterministically by input order"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 10
                   :rounding :floor-with-largest-remainder
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]})]
      (is (= [4 3 3] (mapv :allocated (:allocations result))))
      (is (= 10 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-never-produces-negative-allocations
  (testing "negative amount, weights, and caps are clamped so allocations stay non-negative"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 100
                   :items [{:id :a :weight -10 :cap 50}
                           {:id :b :weight 10 :cap -1}]
                   :cap-fn :cap})]
      (is (every? #(<= 0 (:allocated %)) (:allocations result)))
      (is (every? #(<= 0 (:unmet %)) (:allocations result)))
      (is (= [0 0] (mapv :allocated (:allocations result))))
      (is (= 100 (:total-unmet result)))))
  (testing "negative requested amount becomes zero"
    (let [result (payoffs/allocate-pro-rata
                  {:amount -100
                   :items [{:id :a :weight 10}]})]
      (is (= 0 (:total-requested result)))
      (is (= 0 (:total-allocated result)))
      (is (= 0 (:total-unmet result))))))

(deftest allocate-pro-rata-validates-inputs-and-policies
  (testing "unsupported policies fail explicitly"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported pro-rata rounding policy"
                          (payoffs/allocate-pro-rata {:amount 10
                                                      :rounding :bankers
                                                      :items [{:id :a :weight 1}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported pro-rata remainder policy"
                          (payoffs/allocate-pro-rata {:amount 10
                                                      :remainder-policy :redistribute
                                                      :items [{:id :a :weight 1}]}))))
  (testing "fractional amounts and weights are rejected rather than truncated"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected an integer amount"
                          (payoffs/allocate-pro-rata {:amount 10.5
                                                      :items [{:id :a :weight 1}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected an integer amount"
                          (payoffs/allocate-pro-rata {:amount 10
                                                      :items [{:id :a :weight 1.5}]})))))

(deftest allocate-pro-rata-conservation
  (testing "requested = allocated + unmet + remainder"
    (doseq [spec [{:amount 400
                   :items [{:id :a :weight 1000 :cap 1000}
                           {:id :b :weight 500 :cap 60}
                           {:id :c :weight 500 :cap 500}]
                   :cap-fn :cap}
                  {:amount 10
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]}
                  {:amount 100
                   :items [{:id :a :weight 0}]}]]
      (let [result (payoffs/allocate-pro-rata spec)]
        (is (= (:total-requested result)
               (+ (:total-allocated result)
                  (:total-unmet result)
                  (:remainder result))))))))

(deftest build-projection-artifact-validates-phase-4-contract
  (testing "projection artifacts are stable, canonical-safe, registered, summarized, and complete"
    (let [input {:amount 10
                 :rounding :floor-with-largest-remainder
                 :items [{:id :a :weight 1}
                         {:id :b :weight 1}
                         {:id :c :weight 1}]}
          artifact (payoffs/build-projection-artifact
                    input
                    {:source {:source/type :unit-test
                              :world-hash "sha256:test-world"}})
          artifact-again (payoffs/build-projection-artifact
                          input
                          {:source {:source/type :unit-test
                                    :world-hash "sha256:test-world"}})]
      (is (= (:projection-hash artifact) (:projection-hash artifact-again)))
      (is (= (:projection-hash artifact)
             (hc/hash-with-intent {:hash/intent :projection-artifact}
                                  (dissoc artifact :projection-hash))))
      (is (nil? (hc/validate-canonical-value! artifact)))
      (is (payoffs/registered-intent (get-in artifact [:intent :id])))
      (is (payoffs/registered-projection-definition (:projection-definition-id artifact)))
      (is (= {:participant-count 3
              :eligible-count 3
              :total-weight 3N
              :total-obligation 10N}
             (:summary artifact)))
      (is (= [:a :b :c] (get-in artifact [:projection :participants])))
      (is (= [:a :b :c] (get-in artifact [:projection :eligible-participants])))
      (is (= {:a 1N :b 1N :c 1N} (get-in artifact [:projection :weights])))
      (is (= 10N (get-in artifact [:projection :total-obligation])))
      (is (= :floor-with-largest-remainder
             (get-in artifact [:projection :constraints :rounding])))
      (is (seq (:claims artifact)))
      (is (:source-hash (:source artifact))))))

(deftest calculate-prorata-from-projection-shadows-direct-allocation
  (testing "projection artifacts replay to the same generic pro-rata allocation as direct input"
    (doseq [input [{:amount 400
                    :items [{:id :a :weight 1000 :cap 1000}
                            {:id :b :weight 500 :cap 60}
                            {:id :c :weight 500 :cap 500}]
                    :cap-fn :cap
                    :rounding :floor-with-largest-remainder
                    :remainder-policy :unallocated
                    :ordering-policy :input-order}
                   {:amount 10
                    :items [{:id :a :weight 1}
                            {:id :b :weight 1}
                            {:id :c :weight 1}]
                    :rounding :floor-with-largest-remainder
                    :remainder-policy :unallocated
                    :ordering-policy :input-order}
                   {:amount 100
                    :items [{:id :a :weight 0}
                            {:id :b :weight 0}]
                    :rounding :floor-with-largest-remainder
                    :remainder-policy :unallocated
                    :ordering-policy :input-order}]]
      (let [artifact (payoffs/build-projection-artifact input)
            direct (payoffs/allocate-pro-rata input)
            from-projection (payoffs/calculate-prorata-from-projection artifact)]
        (is (= direct from-projection))))))

(deftest payoffs-namespace-remains-protocol-generic
  (testing "generic economics has no namespace dependency on resolver-sim.protocols.sew"
    (let [source (slurp "src/resolver_sim/economics/payoffs.clj")]
      (is (not (str/includes? source "resolver-sim.protocols.sew")))
      (doseq [forbidden ["escrow" "slashable-stake" "liable-parties" "workflow"
                         "appeal" "bond" "governance" "junior" "senior"
                         "fraud" "reversal" "timeout"]]
        (is (not (str/includes? (str/lower-case source) forbidden))
            (str "payoffs.clj should not contain protocol term: " forbidden))))))

(deftest generic-basis-point-accounting
  (testing "basis point amounts use integer-safe truncation"
    (is (= 15 (payoffs/calculate-bps-amount 1000 150)))
    (is (= 0 (payoffs/calculate-bps-amount 99 1)))))

(deftest generic-net-after-bps-fee
  (testing "fee and net are reported without domain-specific naming"
    (is (= {:fee 15 :net 985}
           (payoffs/calculate-net-after-bps-fee 1000 150)))))

(deftest generic-capacity-limit
  (testing "capacity limit is generic scalar multiplication"
    (is (= 1000.0 (payoffs/calculate-capacity-limit 1000)))
    (is (= 1500.0 (payoffs/calculate-capacity-limit 1000 1.5)))))
