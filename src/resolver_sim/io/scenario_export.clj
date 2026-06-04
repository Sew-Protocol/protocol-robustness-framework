(ns resolver-sim.io.scenario-export
  "Export Clojure invariant scenario maps to trace.json / scenarios/*.json (pure)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- agent->trace-agent
  [{:keys [id address strategy role]}]
  {:id      id
   :address address
   :type    (or role strategy "honest")})

(defn- agent->public-json-agent
  [{:keys [id address strategy role]}]
  (cond-> {:id id :address address}
    (or strategy role) (assoc :strategy (or strategy role "honest"))
    role             (assoc :role role)))

(defn scenario-id->public-json-filename
  "Map s19-dr3-... → S19_dr3-....json (public scenarios/ naming)."
  [scenario-id]
  (when-let [[_ n rest] (re-matches #"^s(\d+[a-z]?)-(.+)$" scenario-id)]
    (str "S" n "_" rest ".json")))

(defn- stringify-escalation-keys
  [params]
  (if-let [ers (:escalation-resolvers params)]
    (assoc params :escalation-resolvers
           (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) ers)))
    params))

(defn- prepare-protocol-params [params]
  (-> params stringify-escalation-keys walk/stringify-keys))

(defn- json-value
  ([v]
   (cond
     (keyword? v) (name v)
     (symbol? v)  (str v)
     :else v))
  ([_k v] (json-value v)))

(defn scenario->trace-document
  "Build a schema 1.1 trace document from an invariant scenario map."
  [scenario {:keys [title purpose threat-tags]}]
  (let [sid (:scenario-id scenario)]
    (cond-> {:schema-version     "1.1"
             :id                 (str "scenarios/" sid)
             :title              (or title sid)
             :purpose            (or purpose "regression")
             :threat-tags        (vec (or threat-tags []))
             :scenario-id        sid
             :initial-block-time (:initial-block-time scenario 1000)
             :agents             (mapv agent->trace-agent (:agents scenario []))
             :protocol-params    (prepare-protocol-params (:protocol-params scenario {}))
             :events             (:events scenario [])
             :scenario-author    (:scenario-author scenario)}
      (:expected-errors scenario)
      (assoc :expected-errors
             (mapv #(update % :error json-value) (:expected-errors scenario)))
      (:strict-expected-errors? scenario)
      (assoc :strict-expected-errors? true)
      (:allow-open-disputes? scenario)
      (assoc :allow-open-disputes? true)
      (:notes scenario)
      (assoc :notes (:notes scenario)))))

(defn scenario->public-json-document
  "Build scenarios/*.json document (schema 1.0) from an invariant scenario map."
  [scenario]
  (let [sid (:scenario-id scenario)]
    (cond-> {:schema-version     "1.0"
             :scenario-id        sid
             :initial-block-time (:initial-block-time scenario 1000)
             :agents             (mapv agent->public-json-agent (:agents scenario []))
             :protocol-params    (prepare-protocol-params (:protocol-params scenario {}))
             :events             (:events scenario [])}
      (:scenario-author scenario) (assoc :scenario-author (:scenario-author scenario))
      (:expected-errors scenario)
      (assoc :expected-errors
             (mapv #(update % :error json-value) (:expected-errors scenario)))
      (:strict-expected-errors? scenario)
      (assoc :strict-expected-errors? true)
      (:allow-open-disputes? scenario)
      (assoc :allow-open-disputes? true)
      (:provenance scenario) (assoc :provenance (:provenance scenario)))))

(def default-export-metadata
  {"s18-dr3-kleros-l0-resolves"
   {:title "DR3 Kleros: L0 Resolver Resolves"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s19-dr3-kleros-escalation-rejected-l0-resolves"
   {:title "DR3 Kleros: Escalation Rejected, L0 Resolves"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s20-dr3-kleros-max-escalation-guard"
   {:title "DR3 Kleros: Max Escalation Guard"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s21-dr3-kleros-pending-cleared-on-escalation"
   {:title "DR3 Kleros: Pending Cleared On Escalation"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s23-preemptive-escalation-blocked"
   {:title "Preemptive Escalation Blocked (No Pending Settlement)"
    :purpose "adversarial-robustness"
    :threat-tags ["escalation-abuse"]}

   "s62-cross-token-isolation-under-dispute-load"
   {:title "Cross-Token Isolation Under Concurrent Disputes"
    :purpose "regression"
    :threat-tags ["cross-token-contamination" "accounting-integrity" "concurrent-disputes"]}

   "s62-cross-token-fee-on-transfer-under-dispute-load"
   {:title "Fee-on-Transfer Under Concurrent Disputes"
    :purpose "regression"
    :threat-tags ["fee-on-transfer" "accounting-integrity" "concurrent-disputes"]}

   "s62-cross-token-parallel-appeal-depths-under-dispute-load"
   {:title "Parallel Appeal Depths Under Concurrent Disputes"
    :purpose "regression"
    :threat-tags ["appeal-escalation" "cross-token-contamination" "concurrent-disputes"]}

   "s62-resolver-capacity-concurrent-dispute-load"
   {:title "Resolver Capacity Under Concurrent Dispute Load"
    :purpose "regression"
    :threat-tags ["resolver-capacity" "dispute-flooding" "concurrent-disputes"]}})

(defn write-json-file
  [doc path]
  (io/make-parents path)
  (with-open [w (io/writer path)]
    (json/write doc w :key-fn name :value-fn json-value)))

(defn export-scenario-files!
  "Write trace fixture and optional public scenarios/*.json for one scenario map."
  [scenario & {:keys [metadata write-public-json?]}]
  (let [sid         (:scenario-id scenario)
        meta        (merge (get default-export-metadata sid {}) metadata)
        trace-doc   (scenario->trace-document scenario meta)
        trace-p     (str "data/fixtures/traces/" sid ".trace.json")
        public-name (scenario-id->public-json-filename sid)
        scen-p      (when (and write-public-json? public-name)
                      (str "scenarios/" public-name))]
    (write-json-file trace-doc trace-p)
    (when scen-p
      (write-json-file (scenario->public-json-document scenario) scen-p))
    (cond-> {:trace-path trace-p :scenario-id sid}
      scen-p (assoc :scenario-path scen-p))))
