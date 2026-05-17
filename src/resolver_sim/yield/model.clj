(ns resolver-sim.yield.model
  "Schemas and data shapes for the yield mechanism.")

;; Position accounting shape
;; {:position/id [:yield/position owner-id module-id token]
;;  :owner/id owner-id ; e.g., [:sew/escrow escrow-id]
;;  :module/id module-id
;;  :token token
;;  :principal 0
;;  :shares 0
;;  :entry-index 1.0
;;  :realized-yield 0
;;  :unrealized-yield 0
;;  :status :active} ; :active, :unwinding, :unwound, :withdrawn

(defn make-position
  [{oid :owner/id mid :module/id :keys [token principal shares entry-index]
    :or {principal 0 shares 0 entry-index 1.0}}]
  {:position/id [:yield/position oid mid token]
   :owner/id oid
   :module/id mid
   :token token
   :principal principal
   :shares shares
   :entry-index entry-index
   :realized-yield 0
   :unrealized-yield 0
   :status :active})
