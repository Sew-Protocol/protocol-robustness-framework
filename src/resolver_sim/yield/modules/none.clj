(ns resolver-sim.yield.modules.none
  "Zero-yield module implementation (default/none).")

(defn none-deposit [world _ _] world)
(defn none-accrue [world _ _] world)
(defn none-withdraw [world _ _] world)

(def zero-yield-module
  {:module/id :none
   :module/type :none
   :module/capabilities #{}
   :ops {:yield/deposit none-deposit
         :yield/withdraw none-withdraw
         :yield/accrue none-accrue}})
