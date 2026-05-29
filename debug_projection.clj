(require '[resolver-sim.protocols.sew :as sew]
         '[resolver-sim.protocols.protocol :as proto])

(def scenario {:scenario-id "test-spe-debug"
               :schema-version "1.0"
               :agents [{:id "buyer" :address "0xbuyer" :role "buyer" :strategy "honest"}
                        {:id "seller" :address "0xseller" :role "seller" :strategy "honest"}
                        {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
               :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow" 
                         :params {:token "USDC" :to "0xseller" :amount 1000 :custom-resolver "0xresolver"}}]
               :protocol-params {:resolver-fee-bps 0}})
(def result (sew/replay-with-sew-protocol scenario))
(def projection (proto/trace-projection sew/protocol result))
(println "Decisions in projection:" (count (:decisions projection)))
