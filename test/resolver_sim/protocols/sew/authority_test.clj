(ns resolver-sim.protocols.sew.authority-test
  "Tests for contract_model/authority.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types     :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.authority :as auth]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def carol    "0xCarol")
(def mod-addr "0xModule")

(def base-snapshot
  (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :max-dispute-duration 3600}))

(defn- world-with-escrow
  "World with one :disputed escrow, given optional settings overrides."
  ([] (world-with-escrow {}))
  ([settings-overrides]
   (let [snap (if (:resolution-module settings-overrides)
                (snap-fix/escrow-snapshot
                 (merge {:escrow-fee-bps 50 :max-dispute-duration 3600}
                        (select-keys settings-overrides [:resolution-module])))
                base-snapshot)
         sett (t/make-escrow-settings
               (dissoc settings-overrides :resolution-module))
         r    (lc/create-escrow
               (t/empty-world 1000)
               alice "0xUSDC" bob 1000 sett snap)
         w    (:world r)]
     (-> w
         (assoc-in [:escrow-transfers 0 :escrow-state]  :disputed)
         (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)))))

;; ---------------------------------------------------------------------------
;; Priority 1: customResolver is exclusive
;; ---------------------------------------------------------------------------

(deftest custom-resolver-exclusive
  (let [w (world-with-escrow {:custom-resolver "0xCustom"})]
    (testing "custom resolver is authorized"
      (is (true? (auth/authorized-resolver? w 0 "0xCustom" nil))))
    (testing "module resolver not authorized when custom is set"
      (let [mod-fn (auth/make-default-resolution-module resolver)]
        (is (false? (auth/authorized-resolver? w 0 resolver mod-fn)))))
    (testing "direct resolver not authorized when custom is set"
      (is (false? (auth/authorized-resolver? w 0 resolver nil))))))

;; ---------------------------------------------------------------------------
;; Priority 2: resolution module
;; ---------------------------------------------------------------------------

(deftest resolution-module-authorizes
  (let [w      (world-with-escrow {:resolution-module mod-addr})
        mod-fn (auth/make-default-resolution-module resolver)]
    (is (true?  (auth/authorized-resolver? w 0 resolver mod-fn))
        "module-authorized resolver should pass")
    (is (false? (auth/authorized-resolver? w 0 carol mod-fn))
        "carol not authorized by module")))

(deftest resolution-module-fallthrough-to-direct
  "When a resolution module declines, BaseEscrow falls through to check
   et.disputeResolver — mirroring the Solidity pattern
     if (authorized) return true;
     return disputeResolver == et.disputeResolver;
   This is safe because escalateDispute() always keeps et.disputeResolver
   in sync with the current round's resolver."
  (let [w      (world-with-escrow {:resolution-module mod-addr})
        mod-fn (fn [_wf _caller] {:authorized? false})]
    ;; Module says no — et.disputeResolver = resolver → still authorized via fallthrough
    (is (true?  (auth/authorized-resolver? w 0 resolver mod-fn)))
    ;; Carol is neither the module-authorized resolver nor et.disputeResolver
    (is (false? (auth/authorized-resolver? w 0 carol   mod-fn)))))

;; ---------------------------------------------------------------------------
;; Priority 3: direct resolver fallback
;; ---------------------------------------------------------------------------

(deftest direct-resolver-fallback
  (let [w (world-with-escrow)]
    (is (true?  (auth/authorized-resolver? w 0 resolver nil)))
    (is (false? (auth/authorized-resolver? w 0 carol nil)))))

;; ---------------------------------------------------------------------------
;; ModuleSnapshot immutability
;; ---------------------------------------------------------------------------

(deftest snapshot-frozen-after-governance-change
  "Governance changing the module address must NOT affect in-flight escrows."
  (let [w0       (world-with-escrow)
        original (t/get-snapshot w0 0)
        ;; Simulate governance changing a different escrow's snapshot
        w1       (assoc-in w0 [:module-snapshots 99]
                           (snap-fix/escrow-snapshot {:escrow-fee-bps 999}))]
    (is (auth/snapshot-frozen? w1 0 original)
        "escrow 0 snapshot unchanged by governance update to escrow 99")))

;; ---------------------------------------------------------------------------
;; ResolutionMode dispatch
;; ---------------------------------------------------------------------------

(deftest resolution-mode-custom
  (let [w (world-with-escrow {:custom-resolver "0xCustom"})]
    (is (= :custom-resolver (auth/resolution-mode w 0)))))

(deftest resolution-mode-module
  (let [w (world-with-escrow {:resolution-module mod-addr})]
    (is (= :resolution-module (auth/resolution-mode w 0)))))

(deftest resolution-mode-direct
  (let [w (world-with-escrow)]
    (is (= :direct (auth/resolution-mode w 0)))))

;; ---------------------------------------------------------------------------
;; DefaultResolutionModule stub
;; ---------------------------------------------------------------------------

(deftest default-module-authorizes-only-its-resolver
  (let [mod-fn (auth/make-default-resolution-module resolver)]
    (is (true?  (:authorized? (mod-fn 0 resolver))))
    (is (false? (:authorized? (mod-fn 0 carol))))))

;; ---------------------------------------------------------------------------
;; KlerosArbitrableProxy stub
;; ---------------------------------------------------------------------------

(deftest kleros-module-authorizes-by-level
  (let [levels   {0 "0xJuror1" 1 "0xJuror2"}
        level-fn (fn [wf] (get {0 0, 1 1} wf 0))
        mod-fn   (auth/make-kleros-module levels level-fn)]
    (is (true?  (:authorized? (mod-fn 0 "0xJuror1"))) "level 0 juror authorized for wf 0")
    (is (false? (:authorized? (mod-fn 0 "0xJuror2"))) "level 1 juror not authorized at level 0")
    (is (true?  (:authorized? (mod-fn 1 "0xJuror2"))) "level 1 juror authorized for wf 1")))
