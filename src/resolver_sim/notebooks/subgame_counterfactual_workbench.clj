^{:nextjournal.clerk/visibility {:code :show :result :show}}
(ns resolver-sim.notebooks.subgame-counterfactual-workbench
  "Workbench for exploring expand-strategic-tree — the dynamic tree expansion
   phase of the subgame perfect equilibrium (SPE) counterfactual evaluator."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.scenario.subgame-counterfactual :as spe]
            [resolver-sim.notebooks.nav :as nav]
            [resolver-sim.notebooks.common :as common]))

(nav/top-nav-bar "notebooks/subgame_counterfactual_workbench.clj")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
[:div {:class "workbench-container"}
 [:div {:class "wb-hero"}
  [:h1 "expand-strategic-tree Workbench"]
  [:p "Dynamic tree expansion from replay decision nodes. "
   "At each strategic decision point, the workbench queries the protocol "
   "for available actions, forks the simulation for each alternative, "
   "and compares terminal utilities against the chosen action."]]
 [:div {:style {:display "flex" :gap "12px" :alignItems "center"
                :marginBottom "24px" :padding "12px 16px" :background "#0f172a"
                :borderRadius "4px" :border "1px solid #004D59"
                :fontSize "0.85em" :color "#94a3b8"}}
  [:span {:style {:fontWeight "700" :color "#7ADDDC" :marginRight "4px"}} "Phase 2"]
  "expand-strategic-tree · "
  [:span {:style {:color "#64748b"}} "speds/subgame_counterfactual.clj"]]]

;; ──────────────────────────────────────────────────────────────────────────────
;;  State & initialization
;; ──────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defonce state (atom {:scenario nil :result nil :trace nil :protocol nil}))

(defn init!
  [path]
  (let [p (preg/get-protocol "sew-v1")
        sc (normalize/normalize-scenario (io-sc/load-scenario-file path))
        r (replay/replay-with-protocol p sc)]
    (swap! state assoc :protocol p :scenario sc :result r :trace (:trace r))
    :loaded))

(defn s
  ([k] (get @state k))
  ([k & ks] (get-in @state (into [k] ks))))

(defn load-and-replay [path] (init! path) (s :result))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Replay summary
;; ──────────────────────────────────────────────────────────────────────────────

(defn replay-summary
  ([]
   (let [result (s :result)
         trace (s :trace)]
     {:scenario-id (:scenario-id result)
      :outcome (:outcome result)
      :events-processed (:events-processed result)
      :trace-length (count trace)
      :strategic-nodes (count (filter #(contains? #{"raise_dispute" "escalate_dispute" "execute_resolution"} (:action %)) trace))})))

(defn decision-nodes
  [trace]
  (let [strategic #{"raise_dispute" "escalate_dispute" "execute_resolution" "challenge_resolution"}]
    (keep-indexed
      (fn [i entry]
        (when (contains? strategic (:action entry))
          {:seq (:seq entry) :action (:action entry) :agent (:agent entry)
           :time (:time entry) :result (:result entry) :error (:error entry)
           :pre-world (when (pos? i) (:world (nth trace (dec i))))
           :chosen-entry entry}))
      trace)))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Expand tree at a node
;; ──────────────────────────────────────────────────────────────────────────────

(defn expand-at-node
  [node]
  (let [trace (s :trace)
        remaining (drop (inc (long (:seq node))) trace)
        world (:pre-world node)
        actor (:agent node)]
    (if (and world (seq remaining))
      (spe/expand-strategic-tree
        (s :protocol) (:agents (s :scenario)) (:protocol-params (s :scenario))
        (:scenario-id (s :scenario)) world actor
        (map-indexed (fn [i e] (assoc e :seq (inc i))) remaining)
        {:enable-tree-expansion? true})
      (println "Cannot expand: seq" (:seq node) "pre-world:" (some? world)
               "has-trailing-events:" (some? (seq remaining))))))

(defn expand-all-nodes
  [nodes]
  (vec (for [node nodes :let [branches (expand-at-node node)] :when (seq branches)]
         {:node node :branches branches :branch-count (count branches)})))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Utility comparison
;; ──────────────────────────────────────────────────────────────────────────────

(defn utility-table
  [branches actor]
  (->> branches
       (mapv (fn [branch]
               (let [terminal (:terminal-world branch)]
                 {:action (:action (:action branch))
                  :outcome (:outcome branch)
                  :halt-reason (:halt-reason branch)
                  :terminal-state (some? terminal)
                  :utility (when terminal (:value (spe/compute-utility terminal actor {})))})))
       (sort-by #(if (:utility %) (- ##Inf) (or (:utility %) ##Inf)))))

(defn available-actions-at
  [node]
  (when-let [world (:pre-world node)]
    (proto/available-actions (s :protocol) world (:agent node))))

;; ──────────────────────────────────────────────────────────────────────────────
;;  Use in Clerk
;; ──────────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :show :result :show}}
;; 1. Load a scenario:
;;    (init! "scenarios/S26_forking-strategist-l1-reversal.json")
;;
;; 2. Get decision nodes:
;;    (def nodes (decision-nodes (s :trace)))
;;
;; 3. Expand tree at a node:
;;    (def branches (expand-at-node (first nodes)))
;;
;; 4. Compare utilities:
;;    (utility-table branches "0xChallenger")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
[:div {:class "card" :style {:marginTop "32px"}}
 [:div {:class "card-title"} "Caveats"]
 [:ul {:style {:color "#94a3b8" :fontSize "0.85em" :lineHeight "1.7" :margin "0" :paddingLeft "20px"}}
  [:li [:strong {:style {:color "#fbbf24"}} "expand-strategic-tree is false by default"]
   ". Set {:enable-tree-expansion? true} in SPE config."]
  [:li [:strong {:style {:color "#fbbf24"}} "Expense"] " — expanding every node "
   "can take minutes."]
  [:li "Stale continuations are tagged :fork/stale-continuation and excluded "
   "from SPE judgement."]
  [:li "Tree expansion supports 3 strategic actions: "
   "raise_dispute, escalate_dispute, execute_resolution."]]]
