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
      (:notes scenario)
      (assoc :notes (:notes scenario)))))

(def default-export-metadata
  {"s62-cross-token-isolation-under-dispute-load"
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
  "Write trace + scenarios JSON for one scenario map."
  [scenario & {:keys [metadata]}]
  (let [sid      (:scenario-id scenario)
        meta     (merge (get default-export-metadata sid {}) metadata)
        doc      (scenario->trace-document scenario meta)
        trace-p  (str "data/fixtures/traces/" sid ".trace.json")
        scen-p   (str "scenarios/" (str/replace sid #"^s62-" "S62_") ".json")]
    (write-json-file doc trace-p)
    (write-json-file doc scen-p)
    {:trace-path trace-p :scenario-path scen-p :scenario-id sid}))
