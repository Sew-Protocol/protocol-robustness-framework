(ns resolver-sim.sim.dispatcher
  "Central transition dispatcher that binds protocol dispatch to evidence emission.
   This is the preferred entry point for scenario execution in the simulation
   kernel. It captures before/after world states, validates attribution context,
   and produces content-hashed evidence records for every transition.

   Legacy proto/dispatch-action calls remain functional — this layer adds
   evidence without changing the protocol interface."
  (:require [resolver-sim.evidence.chain :as chain]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.util.evidence :as ev]))

(defn apply-action-with-evidence
  "Dispatch an action through the protocol layer and emit a content-hashed
   evidence record for the transition.

   Call shape mirrors proto/dispatch-action:
     (apply-action-with-evidence protocol context world event)
     => {:ok bool? :world world' :error kw? :extra map? :evidence map?}

   The :evidence key in the result contains the evidence record (or nil if
   attribution context was insufficient for evidence emission). The dispatch
   itself always proceeds — evidence is best-effort at this layer."
  [protocol context world event]
  (let [action (:action event)
        pre-world world
        result (proto/dispatch-action protocol context world event)
        post-world (:world result)
        evidence (when (map? post-world)
                   (try
                     (attr/with-attribution
                       {:ctx/step (:seq event)
                        :ctx/event-id (str (:seq event))}
                       (ev/emit-evidence!
                         {:artifact-kind (if (:error result) :transition-error :transition)
                          :block-time (:block-time pre-world)
                          :step (:seq event)
                          :before pre-world
                          :after post-world
                          :action action
                          :result (dissoc result :world)}))
                     (catch Exception e
                       nil)))
         _ (when evidence (chain/register-evidence! evidence))]
    (assoc result :evidence evidence)))
