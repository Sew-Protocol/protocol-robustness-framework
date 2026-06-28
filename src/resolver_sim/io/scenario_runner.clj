(ns resolver-sim.io.scenario-runner
  "CLI shell: load scenarios, run collections, print reports, exit codes.

   Does not judge pass/fail — delegates to `scenario.runner` and `sim.fixtures`.
   Table output via `scenario.report/print-report`; legacy fixture detail via
   `sim.reporter` when `:report-format :fixture`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.evidence.forensic-populate :as fp]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.forensic.provenance :as prov]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.io.serialization :as serialization]
            [resolver-sim.logging :as log]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-scenarios :as inv-sc]
            [resolver-sim.protocols.sew.narrative :as narrative]
            [resolver-sim.run.bundle-root :as br]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.sim.fixtures :as fixtures]
            [resolver-sim.validation.scenario-id :as sid]
            [resolver-sim.yield.invariant-catalog :as yield-inv-cat]))

(def ^:private supported-scenario-profiles
  #{:minimal :research :forensic :demo :debug-heavy})

(def ^:private known-scenario-metadata-keys
  #{:scenario/assumptions
    :scenario/model-scope
    :scenario/expected-outcome
    :scenario/claim-intents
    :scenario/evidence-profile
    :scenario/output-profile
    :scenario/output-overrides})

(defn- normalize-profile
  [profile]
  (cond
    (nil? profile) nil
    (keyword? profile) profile
    (string? profile) (keyword profile)
    :else profile))

(defn- normalize-claim-intents
  [claim-intents]
  (cond
    (nil? claim-intents) nil
    (vector? claim-intents) claim-intents
    (sequential? claim-intents) (vec claim-intents)
    :else [claim-intents]))

(defn- scenario-metadata-extra
  [scenario]
  (into {}
        (filter (fn [[k _]]
                  (and (keyword? k)
                       (= "scenario" (namespace k))
                       (not (contains? known-scenario-metadata-keys k)))))
        scenario))

(defn- warn-scenario-metadata-issues!
  [scenario metadata-extra]
  (let [scenario-id (:scenario-id scenario)]
    (when-not scenario-id
      (log/warn! :scenario-id-missing
                 {:path (:scenario-path scenario)
                  :message "Scenario ids should be explicit; path fallback is compatibility-only"}))
    (when (seq metadata-extra)
      (log/warn! :scenario-metadata-extra
                 {:scenario-id scenario-id
                  :keys (vec (sort (keys metadata-extra)))
                  :message "Preserving unknown :scenario/* metadata for registry validation"}))
    (when (and (nil? (:scenario/assumptions scenario))
               (nil? (:assumptions scenario))
               (seq (get-in scenario [:theory :assumptions])))
      (log/warn! :scenario-metadata-fallback
                 {:scenario-id scenario-id
                  :field :scenario/assumptions
                  :source :theory/assumptions
                  :message "Using :theory/:assumptions as compatibility fallback"}))
    (when (and (nil? (:scenario/claim-intents scenario))
               (nil? (:claim-intents scenario))
               (some? (get-in scenario [:theory :claim-id])))
      (log/warn! :scenario-metadata-fallback
                 {:scenario-id scenario-id
                  :field :scenario/claim-intents
                  :source :theory/claim-id
                  :message "Using :theory/:claim-id as compatibility fallback"}))))

(defn- validate-scenario-metadata!
  [{:scenario/keys [assumptions claim-intents evidence-profile output-profile output-overrides]
    :as metadata}
   scenario-id]
  (when (and (some? assumptions) (not (vector? assumptions)))
    (throw (ex-info "Scenario assumptions must be a vector when present"
                    {:scenario-id scenario-id
                     :field :scenario/assumptions
                     :actual assumptions})))
  (when (and (some? claim-intents) (not (vector? claim-intents)))
    (throw (ex-info "Scenario claim intents must normalize to a vector"
                    {:scenario-id scenario-id
                     :field :scenario/claim-intents
                     :actual claim-intents})))
  (when (and evidence-profile
             (not (contains? supported-scenario-profiles evidence-profile)))
    (throw (ex-info "Unknown scenario evidence profile"
                    {:scenario-id scenario-id
                     :field :scenario/evidence-profile
                     :actual evidence-profile
                     :supported supported-scenario-profiles})))
  (when (and output-profile
             (not (contains? supported-scenario-profiles output-profile)))
    (throw (ex-info "Unknown scenario output profile"
                    {:scenario-id scenario-id
                     :field :scenario/output-profile
                     :actual output-profile
                     :supported supported-scenario-profiles})))
  (when (and (some? output-overrides) (not (map? output-overrides)))
    (throw (ex-info "Scenario output overrides must be a map when present"
                    {:scenario-id scenario-id
                     :field :scenario/output-overrides
                     :actual output-overrides})))
  metadata)

(defn- scenario-metadata
  [scenario]
  (let [metadata-extra (scenario-metadata-extra scenario)
        metadata {:scenario/assumptions      (or (:scenario/assumptions scenario)
                                                 (:assumptions scenario)
                                                 (get-in scenario [:theory :assumptions]))
                  :scenario/model-scope      (or (:scenario/model-scope scenario)
                                                 (:model-scope scenario))
                  :scenario/expected-outcome (or (:scenario/expected-outcome scenario)
                                                 (:expected-outcome scenario))
                  :scenario/claim-intents    (normalize-claim-intents
                                              (or (:scenario/claim-intents scenario)
                                                  (:claim-intents scenario)
                                                  (some-> scenario :theory :claim-id vector)))
                  :scenario/evidence-profile (normalize-profile
                                              (or (:scenario/evidence-profile scenario)
                                                  (:evidence-profile scenario)))
                  :scenario/output-profile   (normalize-profile
                                              (or (:scenario/output-profile scenario)
                                                  (:output-profile scenario)))
                  :scenario/output-overrides (or (:scenario/output-overrides scenario)
                                                 (:output-overrides scenario))}
        metadata* (cond-> metadata
                    (seq metadata-extra)
                    (assoc :scenario/metadata-extra metadata-extra))]
    (warn-scenario-metadata-issues! scenario metadata-extra)
    (when-let [scenario-id (:scenario-id scenario)]
      (sid/validate-scenario-id! scenario-id))
    (validate-scenario-metadata! metadata* (:scenario-id scenario))))

(defn- replay-fn-for-protocol
  [protocol-id]
  (let [protocol-id (or protocol-id preg/default-protocol-id)
        protocol    (preg/get-protocol protocol-id)]
    (when-not protocol
      (throw (ex-info "Unknown scenario protocol"
                      {:protocol protocol-id
                       :known-protocols (vec (preg/known-protocol-ids))})))
    (if (= "yield-v1" protocol-id)
      #(replay/replay-yield-scenario protocol %)
      (fn [scenario]
        (if (= "sew-v1" protocol-id)
          (sew/replay-with-sew-protocol scenario)
          (replay/replay-with-protocol protocol scenario))))))

(defn- scenario-file-details
  [scenario-path default-protocol-id]
  (let [scenario (-> scenario-path io-sc/load-scenario-file normalize/normalize-scenario)
        protocol-id (or (:protocol scenario) default-protocol-id)]
    {:scenario scenario
     :protocol protocol-id}))

(defn- scenario-entry-for-path
  [path default-protocol-id]
  (let [{:keys [scenario protocol]} (scenario-file-details path default-protocol-id)
        scenario* (if (= "yield-v1" protocol)
                    (yield-inv-cat/enrich-expectations scenario)
                    scenario)
        scenario-with-path (assoc scenario* :scenario-path path)
        explicit-scenario-id (:scenario-id scenario-with-path)
        scenario-id (or explicit-scenario-id path)
        dispatcher-id (keyword "protocol" protocol)]
    (when-not explicit-scenario-id
      (log/warn! :scenario-id-compatibility-fallback
                 {:path path
                  :scenario-id scenario-id
                  :message "Scenario id fell back to the file path; add an explicit :scenario-id before registry validation"}))
    {:name              (str scenario-id "  [" path "]")
     :scenario          scenario-with-path
     :source            :file
     :protocol          protocol
     :scenario-id       scenario-id
     :dispatcher-id     dispatcher-id
     :scenario-path     path
     :scenario-metadata (scenario-metadata scenario-with-path)}))

(def ^:private default-runner-selection
  "Default pinned runner selection for canonical suite runs."
  {:mode :pinned
   :runner-id :runner/local-bb
   :description "Default pinned local Babashka runner"})

(defn resolve-path-run-request
  "Prepare a normalized request envelope for one or more file-backed scenarios.

   This is a boundary helper only: it performs file loading, normalization,
   protocol inference, and per-protocol expectation enrichment without running
   replay. Returns a map under `:scenario-run/request` suitable for reuse by
   direct scenario runs, named suites, registry validation, and future
   comparison-oriented entrypoints."
  [paths opts]
  (let [default-protocol-id (or (:protocol opts) preg/default-protocol-id)
        runner-selection (or (:runner-selection opts) default-runner-selection)
        entries (mapv #(scenario-entry-for-path % default-protocol-id) paths)]
    {:scenario-run/request
     {:runner/backend :local-current
      :runner-selection runner-selection
      :suite/key (:suite-id opts)
      :protocol/default-id default-protocol-id
      :evidence/profile (or (:evidence-profile opts)
                            (when (= 1 (count entries))
                              (get-in (first entries) [:scenario-metadata :scenario/evidence-profile])))
      :output/profile (or (:output-profile opts)
                          (when (= 1 (count entries))
                            (get-in (first entries) [:scenario-metadata :scenario/output-profile])))
      :entries entries
      :entry-count (count entries)}}))

(defn- execute-path-run-request
  [{:scenario-run/keys [request]} opts]
  (let [entries (:entries request)]
    (doseq [{:keys [name protocol]} entries]
      (println (format "[run:scenario] %s -> protocol %s" name protocol)))
    (runner/run-collection
     {:entries   entries
      :replay-fn (fn [scenario]
                   ((replay-fn-for-protocol (or (:protocol scenario)
                                                (:protocol/default-id request)
                                                preg/default-protocol-id))
                    scenario))}
     (merge {:normalize? false :suite-id (:suite/key request)} opts))))

(defn- enrich-summary-results
  [summary request]
  (let [entry-by-id (into {}
                          (map (juxt :scenario-id identity))
                          (:entries request))]
    (update summary :results
            (fn [results]
              (mapv (fn [result]
                      (if-let [entry (get entry-by-id (:scenario-id result))]
                        (assoc result
                               :scenario-path (:scenario-path entry)
                               :dispatcher-id (:dispatcher-id entry)
                               :scenario-metadata (:scenario-metadata entry)
                               :runner {:backend (:runner/backend request)
                                        :protocol-id (:protocol entry)
                                        :dispatcher-id (:dispatcher-id entry)
                                        :runner-selection (:runner-selection request)}
                               :execution/raw (:replay-result result))
                        result))
                    results)))))

(defn normalize-run-result
  [request summary]
  (let [summary* (enrich-summary-results summary request)
        results (:results summary*)
        totals {:passed (count (filter :pass? results))
                :failed (count (remove :pass? results))
                :total (count results)}]
    {:scenario-run/request request
     :scenario-run/result
     {:status (if (:ok? summary*) :pass :fail)
      :suite/key (:suite-id summary*)
      :evidence/profile (:evidence/profile request)
      :output/profile (:output/profile request)
      :runner-selection (:runner-selection request)
      :totals totals
      :results results
      :summary summary*
      :diagnostics {:elapsed-ms (:elapsed-ms summary* 0)
                    :suite-id (:suite-id summary*)}}}))

(defn- sew-replay-fn []
  (replay-fn-for-protocol "sew-v1"))

(defn- default-report-opts [opts]
  (merge {:outline-printer (fn [name result]
                             (let [{:keys [header lines footer separator]}
                                   (narrative/scenario-outline name result)]
                               (println header)
                               (doseq [line lines] (println line))
                               (println footer)
                               (println separator)))}
         opts))

(declare run-paths)

(def ^:private registry-suite-runners
  "Protocol ID → (fn [opts] → summary-map).
   sew-v1:   in-process invariant registry (protocols_src/.../invariant_scenarios.clj)
   yield-v1: file-backed suite (scenarios/Y*.json via :yield-provider-scenarios)"
  {"sew-v1"   (fn [{:keys [suite-id] :as opts}]
                (runner/run-collection
                 {:entries     inv-sc/all-scenarios
                  :replay-fn   (sew-replay-fn)
                  :type-meta-fn (fn [sid] (get inv-sc/scenario-type-registry sid {}))}
                 (merge {:suite-id (or suite-id :sew-invariants)} opts)))
   "yield-v1" (fn [opts]
                (run-paths (suites/suite-paths :yield-provider-scenarios)
                           (assoc opts :protocol "yield-v1")))})

(defn run-registry-suite
  "Run the protocol registry suite. Returns summary map.
   Protocol is read from :protocol in opts (default sew-v1).
   sew-v1:   in-process invariant registry (S01–S107+)
   yield-v1: file-backed yield provider suite (Y01–Y05)"
  ([] (run-registry-suite {}))
  ([opts]
   (let [protocol-id (or (:protocol opts) preg/default-protocol-id)
         runner (get registry-suite-runners protocol-id)]
     (if runner
       (runner opts)
       (throw (ex-info "No registry suite runner for protocol"
                       {:protocol protocol-id
                        :known (keys registry-suite-runners)}))))))

(defn run-paths
  "Run file-backed scenarios from `paths`. Each path becomes one entry.
   Files are normalized before replay. Returns summary map.

   opts may include `:protocol` (registry id, default sew-v1)."
  [paths opts]
  (let [{request :scenario-run/request :as envelope}
        (resolve-path-run-request paths opts)
        summary (execute-path-run-request envelope opts)
        normalized (normalize-run-result request summary)]
    (assoc (:summary (:scenario-run/result normalized))
           :scenario-run/request request)))

(defn run-scenario-file
  "Run a single scenario file. Returns summary map with one entry."
  [scenario-path opts]
  (run-paths [scenario-path] opts))

(defn run-registry-scenario
  "Run a single scenario from the in-process invariant registry.

   Takes a scenario map (as found in e.g. inv-sc/all-scenarios) and runs it
   through the same replay pipeline used by run-registry-suite.  Returns the
   entry result map from scenario.runner/build-entry-result.

   This is the public entry point for dev REPL helpers that need to run one
   in-process scenario without going through file-backed dispatch."
  [scenario replay-fn]
  (let [summary (runner/run-collection
                 {:entries [(if (map? scenario) scenario {:pair scenario})]
                  :replay-fn replay-fn}
                 {:normalize? false})]
    (first (:results summary))))

(defn- preserve-ns-key [k]
  "JSON key function that preserves Clojure keyword namespaces.
   :bundle/hash → \"bundle/hash\", not \"hash\"."
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn write-result-json
  "Write result map as JSON, preserving keyword namespaces in keys.
   Uses json/write-str directly (bundle roots and replay results are plain maps,
   not records — no protocol dispatch needed)."
  [output-path result]
  (when (and output-path (not= output-path "-"))
    (io/make-parents output-path)
    (spit output-path (json/write-str result :key-fn preserve-ns-key :indent true))))

(defn run-registry-suite-and-report
  "Run protocol registry suite, print report, return exit code.
   Protocol is read from :protocol in opts (default sew-v1)."
  ([] (run-registry-suite-and-report {}))
  ([opts]
   (let [protocol-id (or (:protocol opts) preg/default-protocol-id)
         suite-id (case protocol-id
                    "sew-v1"   :sew-invariants
                    "yield-v1" :yield-provider-scenarios
                    nil)
         title (case protocol-id
                 "sew-v1"   "Sew Invariant Suite — Deterministic Scenarios (Clojure in-process)"
                 "yield-v1" "Yield Provider Suite — File-backed scenarios (Y01–Y05)"
                 (str "Invariant suite for protocol: " protocol-id))
         summary (run-registry-suite opts)
         code    (report/print-report (cond-> summary
                                        suite-id (assoc :suite-id suite-id))
                                      (default-report-opts
                                       (assoc opts :title title)))]
     code)))

(defn run-paths-and-report
  "Run path list, print report, return exit code."
  [paths opts]
  (let [summary (run-paths paths opts)
        suite-id (:suite-id opts)
        title    (when suite-id (format "Scenario suite: %s" (name suite-id)))
        code     (report/print-report summary
                                      (default-report-opts
                                       (cond-> opts
                                         title (assoc :title title))))]
    code))

(defn run-scenario-file-and-report
  "Run one scenario file; optional JSON output. Returns exit code."
  [scenario-path output-path opts]
  (try
    (let [summary (run-scenario-file scenario-path opts)
          entry   (first (:results summary))
          code    (report/print-report summary (default-report-opts opts))]
      (when output-path
        (write-result-json output-path (:replay-result entry)))
      code)
    (catch Exception e
      (println (format "Error running scenario %s: %s" scenario-path (.getMessage e)))
      1)))

(defn run-named-suite-and-report
  "Run a registered suite keyword (e.g. :yield-provider-scenarios). Returns exit code."
  [suite-key opts]
  (if-let [paths (suites/suite-paths suite-key)]
    (run-paths-and-report paths
                          (assoc opts
                                 :suite-id suite-key
                                 :protocol (or (:protocol opts)
                                               (suites/suite-protocol-id suite-key))))
    (do
      (println (str "Unknown suite: " suite-key
                    ". Known: " (str/join ", " (map name (suites/known-suite-keys)))))
      1)))

(defn suite-scenario-details
  "Extract scenario metadata from a scenario path using the same normalization
   and protocol-resolution path used for execution.

   Returns {:scenario/id string :scenario/path string :scenario/protocol keyword}.

   NOTE: Transitional display-only helper.  Loads every file to extract metadata.
   Call sparingly — prefer suite-level protocol-id from suites/suite-definition
   for display purposes."
  [path]
  (let [{request :scenario-run/request}
        (resolve-path-run-request [path] {})
        entry (first (:entries request))]
    {:scenario/id       (:scenario-id entry)
     :scenario/path     (:scenario-path entry)
     :scenario/protocol (keyword (:protocol entry))
     :scenario/source   (:source entry)
     :scenario/dispatcher-id (:dispatcher-id entry)}))

(defn known-suite-summaries
  "Return suite-level metadata for all named scenario suites.
   Per-scenario details use filename-derived IDs and suite-level protocol.
   Avoids loading individual scenario files.

   NOTE: Display-only metadata.  For authoritative per-scenario metadata
   use suite-scenario-details (transitional, loads files)."
  []
  (mapv (fn [suite-key]
          (let [{:keys [title description kind ci-tier protocol-id paths]}
                (suites/suite-definition suite-key)
                scenario-path->id (fn [path]
                                    (-> (java.io.File. path)
                                        (.getName)
                                        (clojure.string/replace #"\.(json|edn)$" "")))]
            {:suite/key           suite-key
             :suite/type          kind
             :suite/protocols     #{protocol-id}
             :suite/scenario-count (count paths)
             :suite/scenarios     (mapv (fn [path]
                                          {:scenario/id (scenario-path->id path)
                                           :scenario/path path
                                           :scenario/protocol (keyword protocol-id)})
                                        paths)
             :suite/title         title
             :suite/description   description
             :suite/ci-tier       ci-tier}))
        (suites/known-suite-keys)))

(defn run-fixture-suite
  "Run a composed EDN fixture suite (e.g. :suites/all-invariants).
   Returns the unified summary map; does not print unless opts request it."
  [suite-key mode opts]
  (fixtures/run-suite suite-key mode nil (assoc opts :silent? true)))

(defn run-fixture-suite-and-report
  "Run fixture suite with the invariant-style table report. Returns exit code.

   opts:
     :report-format — :table (default) uses scenario.report; :fixture uses sim.reporter
     Other opts forwarded to fixtures/run-suite (e.g. :result-display-level for :fixture)."
  [suite-key mode opts]
  (let [report-format (or (:report-format opts) :table)
        silent?       (= :table report-format)
        summary       (fixtures/run-suite suite-key mode nil
                                          (assoc opts :silent? silent?))]
    (if (= :table report-format)
      (report/print-report summary
                           (default-report-opts
                            (assoc opts
                                   :title (format "Fixture suite: %s" (name suite-key)))))
      (if (:ok? summary) 0 1))))

(defn run-and-report
  "CLI dispatcher: full invariant suite, named suite, or single file.

   `dispatch` is a map with optional keys:
     :suite          — path-list keyword (e.g. :yield-provider-scenarios, :sew-yield-scenarios)
     :fixture-suite  — EDN fixture keyword (e.g. :suites/all-invariants)
     :scenario       — file path
     :output-file    — JSON path when running a single scenario
     :protocol       — protocol id (default sew-v1)
     opts are forwarded to report/runner.

   Returns exit code: 0 on full pass, 1 on any failure."
  [dispatch opts]
  (let [default-protocol-id (or (:protocol dispatch) preg/default-protocol-id)
        inferred-protocol-id (when (:scenario dispatch)
                               (get-in (resolve-path-run-request [(:scenario dispatch)]
                                                                 {:protocol default-protocol-id})
                                       [:scenario-run/request :entries 0 :protocol]))
        suite-protocol-id (when (:suite dispatch)
                            (suites/suite-protocol-id (:suite dispatch)))
        protocol-id (or inferred-protocol-id suite-protocol-id default-protocol-id)
        runner-selection (or (:runner-selection dispatch) default-runner-selection)
        canonical? (and (nil? (:scenario dispatch))
                        (nil? (:fixture-suite dispatch))
                        (not= :dev (:mode dispatch)))
        non-canonical-reason (cond
                               (:scenario dispatch) :single-scenario-selected
                               (:fixture-suite dispatch) :fixture-suite-selected
                               :else nil)]
    (when (and (not canonical?) non-canonical-reason)
      (log/warn! :non-canonical-run
                 {:reason non-canonical-reason
                  :message "Run is non-canonical; bundle will be marked accordingly"}))
    ;; Bind TSA URL from environment if set (for RFC 3161 timestamp anchoring)
    (let [tsa-url (System/getenv "PRF_TSA_URL")]
      (when tsa-url
        (.println *err* (str "  TSA URL configured: " tsa-url))))
    (binding [ts/*tsa-url* (or (System/getenv "PRF_TSA_URL") ts/*tsa-url*)]
      (let [result (ev-node/with-execution-node+
                   {:execution-id :execution/replay
                    :runner :scenario-runner
                    :inputs (merge {:dispatch dispatch
                                    :runner-selection runner-selection
                                    :canonical? canonical?
                                    :non-canonical-reason non-canonical-reason
                                    :opts {:protocol protocol-id
                                           :report-format (:report-format opts)
                                           :suite-id (:suite-id opts)}}
                                   (prov/source-provenance))
                    :status-fn (fn [{:keys [exit-code]}]
                                 (cond (zero? exit-code) :pass
                                       (integer? exit-code) :fail
                                       :else :error))
                    :outputs-fn (fn [{:keys [exit-code dispatch-key canonical? bundle-root]}]
                                  {:exit-code exit-code
                                   :dispatch dispatch
                                   :dispatch-key dispatch-key
                                   :canonical? canonical?
                                   :non-canonical-reason non-canonical-reason
                                   :bundle/root bundle-root
                                   :bundle/root-hash (some-> bundle-root :bundle/hash)})
                    :failure-details-fn (fn [{:keys [exit-code dispatch-key]}]
                                          (if (zero? exit-code)
                                            []
                                            [{:failure-type :replay-failed
                                              :class :unexpected
                                              :message (str "Replay exited with code " exit-code
                                                            " (" (name dispatch-key) ")")
                                              :expected? false}]))}
                   (fn []
                     (let [dispatch-key (cond (:fixture-suite dispatch) :fixture-suite
                                              (:suite dispatch) :suite
                                              (:scenario dispatch) :scenario
                                              :else :registry)
                           exit-code (cond
                                       (:fixture-suite dispatch)
                                       (run-fixture-suite-and-report (:fixture-suite dispatch) nil opts)

                                       (:suite dispatch)
                                       (run-named-suite-and-report (:suite dispatch) opts)

                                       (:scenario dispatch)
                                       (run-scenario-file-and-report (:scenario dispatch)
                                                                     (:output-file dispatch)
                                                                     (assoc opts :protocol protocol-id))

                                       :else
                                       (run-registry-suite-and-report (assoc opts :protocol protocol-id)))
                           run-request {:runner/backend :local-current
                                        :runner-selection runner-selection
                                        :suite/key (:suite dispatch)
                                        :protocol/default-id protocol-id
                                        :evidence/profile (:evidence-profile opts)
                                        :output/profile (:output-profile opts)}
                           run-result {:status (if (zero? exit-code) :pass :fail)
                                       :suite/key (:suite dispatch)
                                       :totals {:total 0
                                                :passed (if (zero? exit-code) 0 0)
                                                :failed (if (zero? exit-code) 0 1)}
                                       :results []}
                           bundle-root (br/build-bundle-root run-request run-result)]
                       {:exit-code exit-code
                        :dispatch-key dispatch-key
                        :canonical? canonical?
                        :bundle-root bundle-root})))]

      ;; Populate claims/ and attestations/ with forensic claim evaluation results
      (.println *err* "\n--- Forensic Claims & Attestations ---")
      (let [bundle-root (:bundle-root (:result result))
            run-id (or (some-> (prov/provenance-map) :bundle/id) "unknown")
            bundle-hash (or (:bundle/hash bundle-root) "unknown")]
        (try
          (let [pop-result (fp/populate-claims-and-attestations! run-id bundle-hash)]
            (.println *err* (format "  claims: %d, attestations: %d, all-pass?: %s"
                                    (:claim-count pop-result)
                                    (:attestation-count pop-result)
                                    (:all-pass? pop-result))))
          (catch Exception e
            (.println *err* (str "  warning: forensic populate failed: " (.getMessage e))))))

      ;; Enrich bundle root with source provenance + execution node hash,
      ;; then write to --output-file and return
      (let [thunk-result (:result result)
            execution-node (:execution-node result)
            bundle-root (:bundle-root thunk-result)
            enriched-root (merge bundle-root
                                 (prov/source-provenance)
                                 (when execution-node
                                   {:execution/node-hash (:node-hash execution-node)
                                    :execution/content-hash (:content-hash execution-node)
                                    :execution/record-hash (:record-hash execution-node)}))]
        (when-let [output-path (:output-file dispatch)]
          (write-result-json output-path enriched-root))
        {:exit-code (:exit-code thunk-result)
         :bundle-root enriched-root
         :execution-node execution-node})))))
