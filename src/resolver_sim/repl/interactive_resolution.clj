(ns resolver-sim.repl.interactive-resolution
  "REPL-first interactive driver for Sew dispute resolution exploration."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.time.deadlines :as dl]))

(defn start-session
  "Create an interactive session from a scenario/fixture map."
  ([fixture] (start-session fixture nil))
  ([fixture initial-events]
   (let [proto   sew/protocol
         agents   (:agents fixture)
         p-params (:protocol-params fixture {})
         context  (proto/build-execution-context proto agents p-params)
         world    (proto/init-world proto fixture)]
     (loop [session {:world         world
                     :context       context
                     :protocol      proto
                     :agents        agents
                     :protocol-params p-params
                     :steps         []
                     :trace         []}
            events (or initial-events (:events fixture []))]
       (if-let [ev (first events)]
         (let [result (sew/apply-action (:context session) (:world session)
                                        {:action (:action ev)
                                         :params (:params ev)
                                         :agent  (:agent ev)})
               world' (:world result)]
           (if (:ok result)
             (recur (-> session
                        (assoc :world world')
                        (update :steps conj
                                {:seq    (count (:steps session))
                                 :action (:action ev)
                                 :params (:params ev)
                                 :actor  (:agent ev)
                                 :time   (:block-time world')
                                 :result :applied})
                        (update :trace conj result))
                    (rest events))
             (throw (ex-info "Initial event failed"
                             {:event ev :error (:error result)}))))
         session)))))

(defn- workflow-stuck?
  "True when a :disputed escrow has no termination mechanism — no resolver
   assigned, no resolution module, no timeout path, no pending settlement,
   and no active resolver overflow."
  [world wf]
  (when (= :disputed (t/escrow-state world wf))
    (let [et   (t/get-transfer world wf)
          snap (t/get-snapshot world wf)]
      (not (or (:dispute-resolver et)
               (:resolution-module snap)
               (pos? (get snap :max-dispute-duration 0))
               (:exists (t/get-pending world wf))
               (seq (:resolver-overflows world)))))))

(defn- next-keeper-deadline
  "Earliest future timestamp when a keeper action could fire for this workflow.
   Returns {:deadline-ts <int> :action <kw>} or nil if none is pending."
  [world wf]
  (let [now     (time-ctx/block-ts world)
        et      (t/get-transfer world wf)
        state   (:escrow-state et)
        results (atom [])]
    (when (= :disputed state)
      ;; Priority 1: pending settlement appeal deadline
      (let [pending (t/get-pending world wf)]
        (when (:exists pending)
          (let [dl (:appeal-deadline pending)]
            (when-not (dl/deadline-expired? now dl)
              (swap! results conj {:deadline-ts dl :action :execute-pending})))))
      ;; Priority 2: auto-cancel-time on disputed escrow
      (let [act (:auto-cancel-time et 0)]
        (when (pos? act)
          (when-not (dl/deadline-expired? now act)
            (swap! results conj {:deadline-ts act :action :auto-cancel-on-disputed}))))
      ;; Priority 3: max-dispute-duration timeout
      (let [snap    (t/get-snapshot world wf)
            ts      (get-in world [:dispute-timestamps wf] 0)
            max-dur (get snap :max-dispute-duration 0)]
        (when (and (pos? ts) (pos? max-dur))
          (let [dl (dl/deadline ts max-dur)]
            (when-not (dl/deadline-expired? now dl)
              (swap! results conj {:deadline-ts dl :action :auto-cancel-disputed})))))
      ;; Return the nearest deadline
      (when (seq @results)
        (apply min-key :deadline-ts @results)))))

(defn- workflow-prefix
  "Short status tag for a workflow, e.g. '[wf-0 :disputed L1/pending]'."
  [world wf]
  (let [et    (t/get-transfer world wf)
        level (t/dispute-level world wf)
        pend  (:exists (t/get-pending world wf))
        state (:escrow-state et)
        tag   (str (name state)
                   (when pend "/pending")
                   (when (pos? level) (str " L" level))
                   (when (workflow-stuck? world wf) " !"))]
    (str "[wf-" wf " " tag "]")))

(defn- pr-select-keys
  "Format a subset of params keys for a compact one-line summary."
  [m ks]
  (let [ks' (filter #(contains? m %) ks)]
    (if (seq ks')
      (into (empty m) (select-keys m ks'))
      "")))

(defn- keeper-choices
  "Return auto-action choices for workflows needing timed processing."
  [world]
  (keep (fn [wf]
          (let [result (res/automate-timed-actions world wf)]
            (when (and (:ok result) (not= :none (:action result)))
              (let [pfx (workflow-prefix world wf)]
                {:action  "automate-timed-actions"
                 :params  {:workflow-id wf}
                 :actor   :keeper
                 :summary (str pfx " keeper: " (name (:action result)))
                 :type    :keeper}))))
        (keys (:escrow-transfers world))))

(defn- agent-choices
  "Return available protocol actions for all agents in the session."
  [session]
  (mapcat (fn [agent]
            (map (fn [action]
                   (let [wf  (get (:params action) :workflow-id)
                         pfx (if (some? wf)
                               (workflow-prefix (:world session) wf)
                               "")]
                     (assoc action
                            :actor   (:id agent)
                            :summary (str (:id agent) ": " pfx " " (:action action)
                                          " " (pr-select-keys (:params action) [:workflow-id :is-release]))
                            :type    :agent)))
                 (proto/available-actions (:protocol session) (:world session)
                                          (:address agent))))
          (:agents session)))

(defn- print-world-summary
  "Print a compact one-line summary of every workflow."
  [world]
  (let [wfs (keys (:escrow-transfers world))]
    (when (seq wfs)
      (println (str "World: " (str/join "  " (map (partial workflow-prefix world) wfs)))))))

(defn- print-deadline-previews
  "Print a line showing next future keeper deadlines when no action is due."
  [world]
  (let [wfs     (keys (:escrow-transfers world))
        pending (keep #(next-keeper-deadline world %) wfs)]
    (when (seq pending)
      (let [nearest (apply min-key :deadline-ts pending)
            now     (time-ctx/block-ts world)]
        (println (str "  (keeper deadline at t=" (:deadline-ts nearest)
                      ": " (name (:action nearest))
                      " — advance-time to " (:deadline-ts nearest) " then auto-until-decision)"))))))

(defn available-choices
  "Return numbered choice maps for all currently available actions.
   Prints a world summary and the choice menu as a side effect."
  [session]
  (print-world-summary (:world session))
  (let [world   (:world session)
        keepers (keeper-choices world)
        agents  (agent-choices session)
        choices (vec (concat keepers agents))]
    (if (seq choices)
      (do (doseq [c choices]
            (println (str "  " (:n c) ": " (:summary c))))
          (when (and (empty? keepers) (seq agents))
            (print-deadline-previews world))
          (map-indexed (fn [i c] (assoc c :n i)) choices))
      (do (print-deadline-previews world)
          (println "  (no actions available)")
          []))))

(defn- format-escrow-state [world]
  (into {}
        (map (fn [[wf et]]
               [wf {:state (:escrow-state et)
                    :pend  (:exists (t/get-pending world wf))
                    :level (t/dispute-level world wf)}]))
        (:escrow-transfers world)))

(defn- apply-choice
  "Apply a choice action, return updated session."
  [session choice]
  (let [event  {:action (:action choice)
                :params (:params choice)
                :agent  (when (= :agent (:type choice)) (:actor choice))}
        result (sew/apply-action (:context session) (:world session) event)]
    (if (:ok result)
      (let [world' (:world result)
            step   {:seq    (count (:steps session))
                    :action (:action choice)
                    :params (:params choice)
                    :actor  (:actor choice)
                    :time   (:block-time world')
                    :result :applied
                    :type   (:type choice)}]
        (println (str "Step " (:seq step) ": " (:action choice)
                      (when (= :agent (:type choice))
                        (str " (" (:actor choice) ")"))
                      " -> ok"))
        (let [before (format-escrow-state (:world session))
              after  (format-escrow-state world')]
          (doseq [[wf m] after]
            (let [b (get before wf)]
              (when (not= b m)
                (println (str "  wf-" wf ": "
                              (:state b) (when (:pend b) "/pending")
                              " -> "
                              (:state m) (when (:pend m) "/pending")
                              (when (not= (:level b) (:level m))
                                (str " (level " (:level b) " -> " (:level m) ")"))))))))
        (-> session
            (assoc :world world')
            (update :steps conj step)
            (update :trace conj result)))
      (do (println (str "[FAIL] " (:action choice) " failed: " (:error result)))
          session))))

(defn pick
  "Apply a numbered choice from available-choices.
   Prints the world summary and available choices only when n is nil."
  [session n]
  (if-let [choice (first (filter #(= n (:n %))
                                 (map-indexed (fn [i c] (assoc c :n i))
                                              (vec (concat (keeper-choices (:world session))
                                                           (agent-choices session))))))]
    (apply-choice session choice)
    (do (println "No choice" n "available")
        session)))

(defn apply-event
  "Directly apply an action event without going through available-choices.
   Event: {:action \"...\" :params {...} :agent \"...\"}"
  [session event]
  (let [choice {:action (:action event)
                :params (:params event)
                :actor  (:agent event)
                :type   (if (:agent event) :agent :keeper)
                :summary (str "direct: " (:action event))}]
    (apply-choice session choice)))

(defn auto-until-decision
  "Run keeper/timed actions until no automated actions remain.
   When none are due, reports the next future deadline."
  [session]
  (loop [session session]
    (let [keepers (keeper-choices (:world session))]
      (if (seq keepers)
        (do (println (str "auto: " (:summary (first keepers))))
            (recur (apply-choice session (first keepers))))
        (let [wfs       (keys (:escrow-transfers (:world session)))
              deadlines (keep #(next-keeper-deadline (:world session) %) wfs)]
          (if (seq deadlines)
            (let [nearest (apply min-key :deadline-ts deadlines)]
              (println (str "auto: next deadline t=" (:deadline-ts nearest)
                            " (" (name (:action nearest))
                            ") — advance-time to " (:deadline-ts nearest)
                            " then auto-until-decision")))
            (println "auto: no keeper actions pending"))
          session)))))

(defn liveness-summary
  "Print a detailed liveness analysis for every workflow in the session."
  [session]
  (let [world (:world session)
        wfs   (keys (:escrow-transfers world))]
    (doseq [wf wfs]
      (let [et        (t/get-transfer world wf)
            snap      (t/get-snapshot world wf)
            pending   (t/get-pending world wf)
            state     (:escrow-state et)
            level     (t/dispute-level world wf)
            now       (time-ctx/block-ts world)
            deadline  (next-keeper-deadline world wf)
            stuck     (workflow-stuck? world wf)]
        (println (str "Workflow " wf " -- " (name state)
                      (when (pos? level) (str " / level " level))
                      (when (:exists pending) " / pending exists")))
        (println "  Paths to termination:")
        (if (t/terminal-state? world wf)
          (println "    (already terminal)"))
        (when (and (not (t/terminal-state? world wf)) (= :pending state))
          (println "    release/cancel  " (str "available via agent action")))
        (when (= :disputed state)
          (let [resolver (:dispute-resolver et)
                mod      (:resolution-module snap)
                max-dur  (get snap :max-dispute-duration 0)
                dispute-ts (get-in world [:dispute-timestamps wf] 0)
                overflows (vals (:resolver-overflows world))
                active-of (first (filter #(and (= :active (:status %))
                                               (<= (:starts-at % 0) now)
                                               (< now (:expires-at %))
                                               (< (count (:used-workflows % #{}))
                                                  (:max-workflows % 0))
                                               (= (:resolver %) resolver))
                                         overflows))]
            (println (str "    resolve         "
                          (if resolver "yes" "no")
                          (when resolver (str " (" resolver ")"))))
            (println (str "    resolution-mod   "
                          (if mod "yes" "no")))
            (println (str "    timeout         "
                          (if (pos? max-dur) "yes" "no")
                          (when (pos? max-dur)
                            (let [timeout-ts (dl/deadline dispute-ts max-dur)]
                              (str " at t=" timeout-ts
                                   (when (dl/deadline-expired? now timeout-ts)
                                     " (due)"))))))
            (println (str "    pending-settle  "
                          (if (:exists pending) "yes" "no")
                          (when (:exists pending)
                            (str " (appeal deadline t=" (:appeal-deadline pending)
                                 (when (dl/deadline-expired? now (:appeal-deadline pending))
                                   " EXECUTABLE")
                                 ")"))))
            (if active-of
              (println (str "    overflow        available via overflow-id "
                            (:overflow-id active-of) " until t=" (:expires-at active-of)
                            " (resolver " (:resolver active-of)
                            ", actors: " (str/join ", " (:failover-resolvers active-of)) ")"))
              (when resolver
                (println "    overflow        not authorized")))))
        (when deadline
          (println (str "  Next keeper action: " (name (:action deadline))
                        " at t=" (:deadline-ts deadline)
                        " (in " (- (:deadline-ts deadline) now) "s)")))
        (when stuck
          (println "  *** LIVENESS FAILURE: no termination path ***"))
        (println)))))

(defn advance-time
  "Advance the world's block time. Returns updated session."
  [session target-ts]
  (let [world' (time-ctx/advance-time (:world session) {:to target-ts})]
    (println (str "time: " (:block-time (:world session)) " -> " target-ts))
    (assoc session :world world')))

(defn- action-name-for-export
  "Convert canonical action name to scenario-file convention (underscore)."
  [action]
  (str/replace action "-" "_"))

(defn export-session
  "Write session steps as a replayable scenario EDN file."
  [session path]
  (let [scenario {:scenario-id       (str "interactive-" (java.time.Instant/now))
                  :schema-version    "1.0"
                  :initial-block-time (:block-time (:world session))
                  :agents            (:agents session)
                  :protocol-params   (:protocol-params session)
                  :events            (vec (map-indexed
                                           (fn [i step]
                                             {:seq    i
                                              :time   (or (:time step) (:block-time (:world session)))
                                              :agent  (:actor step)
                                              :action (action-name-for-export (:action step))
                                              :params (:params step)})
                                           (:steps session)))}]
    (io/make-parents path)
    (spit path (with-out-str (pp/pprint scenario)))
    (println (str "Exported " (count (:steps session)) " steps to " path))
    path))
