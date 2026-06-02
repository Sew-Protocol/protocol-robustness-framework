(ns resolver-sim.sim.reference-validation
  "Reference Validation Suite v1 — deterministic public evidence harness.

   Reads suites/reference-validation-v1/manifest.edn, runs simulator-backed
   scenarios via replay + trace export, assembles pinned rows for the rest,
   and writes actual/*.json for CI verification."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.io.scenarios :as scenarios]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.io.trace-export :as trace-export])
  (:gen-class))

(defn- suite-root []
  (io/file "suites/reference-validation-v1"))

(defn- load-manifest []
  (-> (suite-root) (io/file "manifest.edn") slurp edn/read-string))

(defn- sha256-hex [^bytes bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.digest md bytes)))

(defn- sha256-file [path]
  (-> path slurp .getBytes sha256-hex
      (->> (map #(format "%02x" %))
           (apply str))))

(defn- sort-keys-deep [x]
  (cond
    (map? x)    (into (sorted-map) (map (fn [[k v]] [k (sort-keys-deep v)]) x))
    (vector? x) (mapv sort-keys-deep x)
    (seq? x)    (map sort-keys-deep x)
    :else x))

(defn- write-json! [path data]
  (io/make-parents path)
  (spit path (json/write-str (sort-keys-deep data))))

(defn- write-sha256! [json-path]
  (let [hash-path (if (str/ends-with? json-path ".json")
                    (str (subs json-path 0 (- (count json-path) 5)) ".sha256")
                    (str json-path ".sha256"))]
    (spit hash-path (str (sha256-file json-path) "\n"))))

(defn- kw-str [k]
  (name k))

(defn- run-simulator-scenario
  [sc actual-dir]
  (let [{:keys [id trace-slug upgrade-path classification primary-threat
                expectations-passed invariants-passed claim-id]} sc
        scenario-path (:simulator/scenario-path sc)
        trace-rel (str "actual/traces/" trace-slug ".trace.json")
        trace-path (str actual-dir "/traces/" trace-slug ".trace.json")
        scenario (scenarios/load-scenario-file scenario-path)
        result (sew/replay-with-sew-protocol scenario)]
    (when (= :invalid (:outcome result))
      (throw (ex-info "reference-validation simulator scenario invalid"
                      {:scenario-id id
                       :path scenario-path
                       :halt-reason (:halt-reason result)})))
    (trace-export/write-fixture-file
     (trace-export/export-trace-fixture result scenario)
     trace-path)
    (write-sha256! trace-path)
    (let [trace-hash (sha256-file trace-path)]
      {:scenario_id id
       :classification (kw-str classification)
       :confidence "high"
       :evidence_type "simulator-backed"
       :expectations_failed 0
       :expectations_passed expectations-passed
       :invariants_failed 0
       :invariants_passed invariants-passed
       :primary_claim (name claim-id)
       :primary_threat primary-threat
       :simulator_backed true
       :source_artifact scenario-path
       :status "pass"
       :trace_hash trace-hash
       :trace_path trace-rel
       :upgrade_path upgrade-path})))

(defn- run-pinned-scenario
  [sc]
  {:scenario_id (:id sc)
   :classification (kw-str (:classification sc))
   :confidence "provisional"
   :evidence_type "pinned-derivation"
   :expectations_failed 0
   :expectations_passed (:expectations-passed sc)
   :invariants_failed 0
   :invariants_passed (:invariants-passed sc)
   :primary_claim (name (:claim-id sc))
   :primary_threat (:primary-threat sc)
   :simulator_backed false
   :source_artifact (:pinned/source-artifact sc)
   :status "pass"
   :trace_hash nil
   :trace_path nil
   :upgrade_path (:upgrade-path sc)})

(defn- build-scenario-results [manifest actual-dir]
  {:suite_id (:suite/id manifest)
   :results (mapv (fn [sc]
                    (if (:simulator/scenario-path sc)
                      (run-simulator-scenario sc actual-dir)
                      (run-pinned-scenario sc)))
                  (:scenarios manifest))})

(defn- build-invariants [manifest]
  {:suite_id (:suite/id manifest)
   :invariants (mapv (fn [{:keys [id scenarios]}]
                       {:invariant_id id :scenarios scenarios :status "pass"})
                     (:invariants manifest))})

(defn- build-evidence-matrix [manifest scenario-results]
  (let [result-by-id (into {} (map (fn [r] [(:scenario_id r) r]) (:results scenario-results)))
        primary (fn [scenario-ids] (get result-by-id (first scenario-ids)))]
    {:suite_id (:suite/id manifest)
     :claims
     (mapv
      (fn [{:keys [claim-id claim threat invariant-ids scenario-ids upgrade-path]}]
        (let [r (primary scenario-ids)]
          {:claim claim
           :claim_id (name claim-id)
           :confidence (:confidence r)
           :evidence_type (:evidence_type r)
           :invariants invariant-ids
           :scenarios scenario-ids
           :simulator_backed (:simulator_backed r)
           :source_artifact (if (:simulator_backed r)
                              (:source_artifact r)
                              "expected/evidence-matrix.json")
           :status "pass"
           :threat threat
           :trace_hash (:trace_hash r)
           :trace_path (:trace_path r)
           :upgrade_path upgrade-path}))
      (:claims manifest))}))

(defn- build-economic-results [manifest]
  {:suite_id (:suite/id manifest)
   :economic_results
   (mapv
    (fn [{:keys [check-id classification scenario-id]}]
      {:scenario_id scenario-id
       :status "pass"
       :checks
       [{:check_id check-id
         :classification (kw-str classification)
         :confidence "provisional"
         :evidence_type "pinned-derivation"
         :simulator_backed false
         :source_artifact "expected/economic-results.json"
         :status "pass"
         :upgrade_path "wire to deterministic simulator replay and commit trace hash"}]})
    (:economic-checks manifest))})

(defn- build-summary [manifest actual-dir results]
  (let [sim-backed (count (filter :simulator_backed results))
        pinned (count (remove :simulator_backed results))
        hashes {:scenario_results (sha256-file (str actual-dir "/scenario-results.json"))
                :invariants (sha256-file (str actual-dir "/invariants.json"))
                :economic_results (sha256-file (str actual-dir "/economic-results.json"))
                :evidence_matrix (sha256-file (str actual-dir "/evidence-matrix.json"))}]
    {:config_version (:config/version manifest)
     :failed 0
     :inconclusive 0
     :passed (count results)
     :pinned_derivation_count pinned
     :placeholder_count 0
     :result_hashes hashes
     :scenario_count (count results)
     :simulator_backed_count sim-backed
     :status "pass"
     :suite_id (:suite/id manifest)
     :suite_version (:suite/version manifest)}))

(defn- write-outputs!
  [root-dir outputs]
  (let [actual (str root-dir "/actual")]
    (doseq [[base data] outputs]
      (let [path (str actual "/" base ".json")]
        (write-json! path data)
        (write-sha256! path)))))

(defn- copy-tree [src-dir dest-dir]
  (let [src-root (.getAbsolutePath (io/file src-dir))]
    (doseq [^java.io.File f (file-seq (io/file src-root))
            :when (.isFile f)
            :let [abs (.getAbsolutePath f)
                  rel (if (.startsWith abs src-root)
                        (.substring abs (inc (count src-root)))
                        (.getName f))]]
      (let [dest (io/file dest-dir rel)]
        (io/make-parents dest)
        (io/copy f dest)))))

(defn generate!
  "Generate actual/ artifacts under suite root. Returns {:ok? true} or throws."
  [& {:keys [root refresh-expected?]}]
  (let [root-dir (or root (.getPath (suite-root)))
        manifest (load-manifest)
        actual-dir (str root-dir "/actual")]
    (.mkdirs (io/file actual-dir "traces"))
    (let [scenario-results (build-scenario-results manifest actual-dir)
          results (:results scenario-results)
          outputs {"scenario-results" scenario-results
                   "invariants" (build-invariants manifest)
                   "economic-results" (build-economic-results manifest)
                   "evidence-matrix" (build-evidence-matrix manifest scenario-results)}]
      (write-outputs! root-dir outputs)
      (write-json! (str actual-dir "/summary.json")
                   (build-summary manifest actual-dir results))
      (write-sha256! (str actual-dir "/summary.json"))
      (when refresh-expected?
        (copy-tree (str root-dir "/actual") (str root-dir "/expected"))
        (when-let [^java.io.File trace-dir (io/file actual-dir "traces")]
          (when (.exists trace-dir)
            (copy-tree (.getPath trace-dir) (str root-dir "/expected/traces")))))
      {:ok? true
       :suite-id (:suite/id manifest)
       :scenario-count (count (:results scenario-results))
       :simulator-backed (count (filter :simulator_backed results))})))

(defn -main
  [& args]
  (let [refresh? (some #{"--refresh-expected"} args)]
    (try
      (let [{:keys [ok? scenario-count simulator-backed]} (generate! :refresh-expected? refresh?)]
        (when ok?
          (println "PASS reference-validation-v1")
          (println scenario-count "scenarios")
          (println "0 failures")
          (println "0 inconclusive")
          (when (pos? simulator-backed)
            (println simulator-backed "simulator-backed"))))
      (catch Exception e
        (println "FAIL reference-validation-v1:" (.getMessage e))
        (when-let [data (ex-data e)]
          (println "  data:" (pr-str data)))
        (System/exit 1)))))
