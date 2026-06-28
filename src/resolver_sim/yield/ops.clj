(ns resolver-sim.yield.ops
  "Operation dispatch and main entry points for yield transitions.")

(defn- get-module [world module-id]
  (get-in world [:yield/modules module-id]))

(defn apply-yield-op
  "Apply a yield operation to the world state.
   op: {:op/type :yield/deposit :owner/id id :module/id mid :amount a :token t ...}"
  [world op]
  (let [module-id (:module/id op)
        module    (get-module world module-id)
        op-type   (:op/type op)
        f         (get-in module [:ops op-type])]
    (if f
      (f world module op)
      (throw (ex-info "Unsupported yield op"
                      {:op op
                       :module module})))))

(defn accrue-module
  "Advance yield for a module. Usually triggered by time advance."
  [world module-id accrual-event]
  (apply-yield-op world (assoc accrual-event
                               :op/type :yield/accrue
                               :module/id module-id)))