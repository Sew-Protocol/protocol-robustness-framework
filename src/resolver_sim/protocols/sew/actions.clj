(ns resolver-sim.protocols.sew.actions
  "SEW Protocol action vocabulary.

   Provides a decoupling layer between gRPC action strings and
   internal behaviour-model keywords.

   Layering: may import protocols/protocol only.
   Must NOT import contract_model/*, sim/*, db/*, or io/*.")

(def action-map
  "Mapping of SEW implementation action strings to canonical identifiers."
  {"create_escrow"              :transfer/create-protected
   "raise_dispute"              :case/dispute-raised
   "execute_resolution"         :case/resolution-executed
   "escalate_dispute"           :case/escalation-triggered
   "execute_pending_settlement" :case/pending-executed
   "release"                    :transfer/released
   "sender_cancel"              :transfer/cancelled-sender
   "recipient_cancel"           :transfer/cancelled-recipient})

(defn to-canonical [impl-action]
  (get action-map impl-action :unknown/action))
