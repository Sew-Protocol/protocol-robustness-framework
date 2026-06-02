(ns resolver-sim.protocols.sew.reversal-fixtures
  "Shared builders for L0 release → escalation → L1 refund reversal-slash worlds."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry :as reg]))

(defn- require-ok!
  [result step context]
  (when-not (:ok result)
    (throw (ex-info (str context " failed at " step)
                    {:error (:error result) :step step :context context})))
  result)

(defn- at-block-time
  [world block-times step-key]
  (if-let [t (get block-times step-key)]
    (assoc world :block-time t)
    world))

(defn- escalate!
  [world workflow-id caller escalation-via l1-resolver block-times context]
  (let [esc-fn (fn [_w _wfid _caller _level] {:ok true :new-resolver l1-resolver})
        world' (at-block-time world block-times :escalation)]
    (case escalation-via
      :challenge (require-ok!
                  (res/challenge-resolution world' workflow-id caller esc-fn)
                  "challenge-resolution"
                  context)
      :escalate (require-ok!
                 (res/escalate-dispute world' workflow-id caller esc-fn)
                 "escalate-dispute"
                 context))))

(def ^:private base-opts
  {:initial-block-time 1000
   :buyer "0xBuyer"
   :seller "0xSeller"
   :l0-resolver "0xL0Res"
   :l1-resolver "0xL1Res"
   :challenger "0xChallenger"
   :escalation-caller nil
   :token "USDC"
   :escrow-amount 8000
   :l0-stake 10000
   :l1-stake 10000
   :escalation-via :challenge
   :l0-is-release true
   :l1-is-release false
   :l0-hash "0xhash-l0"
   :l1-hash "0xhash-l1"
   :snapshot {}
   :block-times {}})

;; Invariant S101: L0 at 1120, third-party challenge at 1130, L1 at 1200.
(def s101-track1-reversal
  {:escalation-via :challenge
   :block-times {:l0 1120 :escalation 1130 :l1 1200}})

;; Phase K: participant escalation on a long appeal window, same block throughout.
(def participant-escalation-reversal
  {:escalation-via :escalate
   :l0-resolver "0xRes0"
   :l1-resolver "0xRes1"
   :token "0xToken"
   :escrow-amount 5000
   :l0-stake 5000
   :l1-stake 5000
   :escalation-caller "0xSeller"
   :l0-hash "hash0"
   :l1-hash "hash1"
   :snapshot {:escrow-fee-bps 0
              :appeal-window-duration 3600
              :appeal-bond-bps 1000
              :reversal-slash-bps 2500}
   :block-times {}})

(defn build-reversal-world
  "Runs create → dispute → L0 release → escalation → L1 refund (reversal slash).

  opts — merged over [[base-opts]]; see [[s101-track1-reversal]] and
  [[participant-escalation-reversal]] for common presets.

  Returns {:world <after L1> :workflow-id n
           :steps {:after-raise :after-l0 :after-escalation}}."
  ([] (build-reversal-world {}))
  ([opts]
   (let [{:keys [initial-block-time buyer seller l0-resolver l1-resolver challenger
                 escalation-caller token escrow-amount l0-stake l1-stake escalation-via
                 l0-is-release l1-is-release l0-hash l1-hash snapshot block-times]}
         (merge base-opts s101-track1-reversal opts)
         context "build-reversal-world"
         snap (t/make-module-snapshot
               (merge {:appeal-window-duration 120
                       :challenge-window-duration 120
                       :reversal-slash-bps 2500
                       :max-dispute-level 2
                       :dispute-resolver l0-resolver}
                      snapshot))
         esc-caller (or escalation-caller
                        (case escalation-via
                          :escalate seller
                          :challenge challenger))
         world0 (-> (t/empty-world initial-block-time)
                     (reg/register-stake l0-resolver l0-stake)
                     (reg/register-stake l1-resolver l1-stake))
         create-r (require-ok! (lc/create-escrow world0 buyer token seller escrow-amount {} snap)
                               "create-escrow"
                               context)
         workflow-id (:workflow-id create-r)
         after-raise (:world (require-ok! (lc/raise-dispute (:world create-r) workflow-id buyer)
                                          "raise-dispute"
                                          context))
         after-l0 (:world (require-ok!
                           (res/execute-resolution (at-block-time after-raise block-times :l0)
                                                   workflow-id l0-resolver l0-is-release
                                                   l0-hash nil)
                           "l0-execute-resolution"
                           context))
         esc-r (escalate! after-l0 workflow-id esc-caller escalation-via l1-resolver
                          block-times context)
         after-escalation (:world esc-r)
         after-l1 (:world (require-ok!
                           (res/execute-resolution (at-block-time after-escalation block-times :l1)
                                                   workflow-id l1-resolver l1-is-release
                                                   l1-hash nil)
                           "l1-execute-resolution"
                           context))]
     {:world after-l1
      :workflow-id workflow-id
      :steps {:after-raise after-raise
              :after-l0 after-l0
              :after-escalation after-escalation}})))
