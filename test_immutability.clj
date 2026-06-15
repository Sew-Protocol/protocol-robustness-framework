(defn update-transfer
  "Apply f to the EscrowTransfer map for workflow-id."
  [world workflow-id f & args]
  (apply update-in world [:escrow-transfers workflow-id] f args))

(def initial-world {:escrow-transfers {0 {:state :pending}}})
(def world' (update-transfer initial-world 0 assoc :state :disputed))

(println "Initial world:" initial-world)
(println "New world:" world')
(println "Immutability check:" (not= initial-world world'))
