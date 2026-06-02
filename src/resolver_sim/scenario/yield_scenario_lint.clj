(ns resolver-sim.scenario.yield-scenario-lint
  "Structural checks for yield-stress scenarios (especially negative-yield tags).

   Catches vacuous scenarios that pass expectations without configuring or
   asserting yield stress behavior."
  (:require [clojure.string :as str]))

(defn- threat-tags [scenario]
  (into #{}
        (map (fn [t] (if (keyword? t) (name t) (str t)))
             (concat (:threat-tags scenario) (:threat_tags scenario)))))

(defn negative-yield-tagged?
  [scenario]
  (contains? (threat-tags scenario) "negative-yield"))

(defn- failure-modes-include-negative-yield?
  [modes]
  (when (seq modes)
    (some #{:negative-yield "negative-yield"} modes)))

(defn- token-config-has-negative-yield? [token-cfg]
  (failure-modes-include-negative-yield?
   (or (:failure-modes token-cfg) (:failure_modes token-cfg))))

(defn- yield-config-has-negative-yield? [scenario]
  (some (fn [module-cfg]
          (some token-config-has-negative-yield?
                (vals (or (:tokens module-cfg) {}))))
        (vals (or (get-in scenario [:yield-config :modules])
                  (get-in scenario [:yield_config :modules])
                  {}))))

(defn- set-yield-risk-events [scenario]
  (filter #(= "set-yield-risk" (name (or (:action %) "")))
          (:events scenario)))

(defn- event-configures-negative-yield? [event]
  (let [p (:params event)]
    (or (failure-modes-include-negative-yield?
         (or (:failure-modes p) (:failure_modes p)))
        (when-let [shocks (:shocks p)]
          (some #(and (= :failure-mode (keyword (:type %)))
                    (= :negative-yield (keyword (:mode %))))
                shocks)))))

(defn scenario-configures-negative-yield?
  "True when yield-config or a set-yield-risk event enables :negative-yield."
  [scenario]
  (or (yield-config-has-negative-yield? scenario)
      (some event-configures-negative-yield? (set-yield-risk-events scenario))))

(defn- norm-op [op]
  (cond
    (keyword? op) (name op)
    (string? op) (str/replace op #"^:" "")
    :else (str op)))

(defn- metric-asserts-yield-stress? [m]
  (let [name-k (if (keyword? (:name m)) (name (:name m)) (:name m))
        op     (norm-op (:op m))]
    (or (= name-k "yield/escrow-unrealized")
        (and (= name-k "yield/escrow-gross")
             (contains? #{"<" "<="} op)))))

(defn- step-asserts-yield-stress? [step]
  (let [path   (vec (:path step))
        op     (norm-op (:op step))
        last-k (when (seq path) (if (keyword? (last path)) (name (last path)) (last path)))]
    (or (and (= last-k "unrealized-yield")
             (contains? #{"<" ">" "<=" ">="} op))
        (and (= last-k "deferred-amount")
             (= op "=")))))

(defn scenario-asserts-yield-stress?
  "True when :expectations include a metric or step check on yield stress."
  [scenario]
  (let [exp (or (:expectations scenario) {})]
    (or (some metric-asserts-yield-stress? (:metrics exp))
        (some step-asserts-yield-stress? (:step-terminal exp)))))

(defn lint-negative-yield-scenario
  "Return a vector of violation maps (empty when well-formed)."
  [scenario]
  (when (negative-yield-tagged? scenario)
    (cond-> []
      (not (scenario-configures-negative-yield? scenario))
      (conj {:type    :missing-negative-yield-config
             :message "negative-yield tag requires failure-modes or yield-config preset"})

      (not (scenario-asserts-yield-stress? scenario))
      (conj {:type    :missing-yield-stress-assertion
             :message "negative-yield tag requires yield metric or step-terminal stress assertion"}))))

(defn well-formed-negative-yield-scenario?
  [scenario]
  (empty? (lint-negative-yield-scenario scenario)))
