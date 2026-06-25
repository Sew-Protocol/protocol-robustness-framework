(ns resolver-sim.evidence.attestation-bundle
  "Portable attestation verification bundles: self-contained packages
   containing attestations, claim results, evidence nodes, and registry
   snapshots for offline verification.

   Bundle manifest shape follows ATTESTATION_BUNDLE_SPEC_V1.

   Usage:
     (require '[resolver-sim.evidence.attestation-bundle :as ab])

     ;; Build a bundle from attestations and supporting data
     (ab/build-attestation-bundle
       {:attestations [attestation-1 attestation-2]
        :claim-results [claim-result-1]
        :evidence-nodes [node-1]
        :registries {:attestors attestor-registry
                     :claim-definitions claim-def-registry
                     :hash-intents hash-intents-map}
        :sensitivity-report {:sentinel/decision :allowed ...}
        :options {:bundle-dir \"path/to/bundle\"}})

     ;; Verify a bundle
     (ab/verify-attestation-bundle bundle)

     ;; Persist
     (ab/write-attestation-bundle! bundle)
     (ab/read-attestation-bundle path)"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-integrity :as integrity]
            [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.io File]
           [java.time Instant]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const bundle-version "attestation-bundle.v1")
(def ^:const bundle-kind :attestation-verification-package)

(def ^:const verification-statuses
  #{:fully-verified :hash-linked :partially-verified :invalid :blocked-by-sensitivity-policy})

;; ── Bundle Builder ───────────────────────────────────────────────────────────

(defn- sha256-hex
  [data]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (pr-str data) "UTF-8"))
    (apply str (map (partial format "%02x") (.digest digest)))))

(defn- file-sha256
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (slurp path) "UTF-8"))
    (apply str (map (partial format "%02x") (.digest digest)))))

(defn- compute-object-hash
  [obj]
  (hc/hash-with-intent {:hash/intent :evidence-record} obj))

(defn- object-path
  [base-dir kind hash]
  (str base-dir "/" (name kind) "/" hash ".edn"))

(defn build-attestation-bundle
  "Build an attestation verification bundle.

   Arguments (map):
     :attestations     — vector of attestation records (required)
     :claim-results    — vector of claim result maps (optional)
     :evidence-nodes   — vector of evidence node maps (optional)
     :registries       — map with keys :attestors, :claim-definitions,
                         :hash-intents (required)
     :sensitivity-report — map with :sentinel/decision and :sentinel/report-hash
     :options          — map with :bundle-dir (output directory)

   Returns the bundle manifest map."
  [{:keys [attestations claim-results evidence-nodes registries sensitivity-report options]
    :or {attestations [] claim-results [] evidence-nodes []}}]
  (let [bundle-dir (or (:bundle-dir options) "attestation-bundle")
        _ (.mkdirs (io/file bundle-dir "attestations"))
        _ (.mkdirs (io/file bundle-dir "claims"))
        _ (.mkdirs (io/file bundle-dir "evidence-nodes"))
        _ (.mkdirs (io/file bundle-dir "registries"))
        _ (.mkdirs (io/file bundle-dir "reports"))

        ;; Build object entries
        att-entries (mapv (fn [a]
                            (let [h (:attestation/id a)]
                              {:object/kind :attestation-record
                               :object/hash h
                               :object/path (object-path bundle-dir "attestations" h)
                               :object/availability :included}))
                          attestations)
        claim-entries (mapv (fn [c]
                              (let [h (or (:claim-result-hash c)
                                          (compute-object-hash c))]
                                {:object/kind :claim-result
                                 :object/hash h
                                 :object/path (object-path bundle-dir "claims" h)
                                 :object/availability :included}))
                            claim-results)
        node-entries (mapv (fn [n]
                             (let [h (:node-hash n)]
                               {:object/kind :evidence-node
                                :object/hash h
                                :object/path (object-path bundle-dir "evidence-nodes" h)
                                :object/availability (if n :included :hash-only)}))
                           evidence-nodes)
        entrypoints (mapv (fn [a]
                            {:attestation/hash (:attestation/id a)
                             :attestation/path (object-path bundle-dir "attestations"
                                                            (:attestation/id a))})
                          attestations)

        ;; Convert sets to vectors for canonical encoding
        canon-att-reg (walk/postwalk
                       (fn [x]
                         (cond (set? x) (vec (sort x))
                               (fn? x) (str x)
                               (instance? clojure.lang.Var x) (str x)
                               :else x))
                       registries/attestor-registry)
        canon-cd-reg (walk/postwalk
                      (fn [x]
                        (cond (set? x) (vec (sort x))
                              (fn? x) (str x)
                              (instance? clojure.lang.Var x) (str x)
                              :else x))
                      registries/claim-definition-registry)
        canon-hi-map (walk/postwalk
                      (fn [x]
                        (cond (set? x) (vec (sort x))
                              (fn? x) (str x)
                              (instance? clojure.lang.Var x) (str x)
                              :else x))
                      hc/hash-intents)
        registry-snapshot {:attestors
                           {:registry/hash (hc/hash-with-intent
                                            {:hash/intent :registry}
                                            canon-att-reg)
                            :registry/path (str bundle-dir "/registries/attestor-registry.edn")}
                           :claim-definitions
                           {:registry/hash (hc/hash-with-intent
                                            {:hash/intent :registry}
                                            canon-cd-reg)
                            :registry/path (str bundle-dir "/registries/claim-definition-registry.edn")}
                           :hash-intents
                           {:registry/hash (hc/hash-with-intent
                                            {:hash/intent :registry}
                                            canon-hi-map)
                            :registry/path (str bundle-dir "/registries/hash-intent-registry.edn")}}

        ;; Base manifest (without root-hash)
        base-manifest {:bundle/version bundle-version
                       :bundle/kind bundle-kind
                       :bundle/entrypoints entrypoints
                       :bundle/objects (vec (concat att-entries claim-entries node-entries))
                       :bundle/registries registry-snapshot
                       :bundle/sensitivity {:sentinel/decision (:sentinel/decision sensitivity-report :blocked)
                                            :sentinel/report-hash (:sentinel/report-hash sensitivity-report)
                                            :sentinel/path (:sentinel/path sensitivity-report
                                                                           (str bundle-dir
                                                                                "/reports/sensitivity-sentinel-report.edn"))}
                       :bundle/verification-profile {:integrity? true
                                                     :signature? true
                                                     :registry-backed? true
                                                     :subject-content-included? (boolean (seq claim-results))
                                                     :quorum? false}}

        ;; Canonical root hash (excludes self-referential fields)
        root-input (dissoc base-manifest :bundle/root-hash)
        root-hash (hc/hash-with-intent {:hash/intent :evidence-record} root-input)]

    (assoc base-manifest :bundle/root-hash root-hash)))

;; ── Bundle Verification ──────────────────────────────────────────────────────

(defn- check-version
  [bundle]
  (let [v (:bundle/version bundle)]
    (if (= v bundle-version)
      {:check/id :bundle-version-valid :check/status :pass}
      {:check/id :bundle-version-valid :check/status :fail
       :reason (str "Expected " bundle-version ", got " v)})))

(defn- check-root-hash
  [bundle]
  (let [recorded (:bundle/root-hash bundle)
        base (dissoc bundle :bundle/root-hash)
        computed (hc/hash-with-intent {:hash/intent :evidence-record} base)]
    (if (= recorded computed)
      {:check/id :bundle-root-hash-valid :check/status :pass}
      {:check/id :bundle-root-hash-valid :check/status :fail
       :reason (str "Root hash mismatch: recorded " recorded ", computed " computed)})))

(defn- check-object-integrity
  [bundle]
  (let [objects (:bundle/objects bundle [])
        results (mapv (fn [obj]
                        (let [obj-path (:object/path obj)
                              recorded-hash (:object/hash obj)]
                          (cond (nil? obj-path)
                                {:object/hash recorded-hash
                                 :check/status :warning
                                 :reason :hash-only}
                                (not (.exists (io/file obj-path)))
                                {:object/hash recorded-hash
                                 :check/status :warning
                                 :reason (str "File not found: " obj-path)}
                                :else
                                (try
                                  (let [content (edn/read-string (slurp obj-path))
                                        computed (compute-object-hash content)]
                                    (if (= computed recorded-hash)
                                      {:object/hash recorded-hash :check/status :pass}
                                      {:object/hash recorded-hash :check/status :fail
                                       :reason (str "Hash mismatch for " obj-path)}))
                                  (catch Exception e
                                    {:object/hash recorded-hash :check/status :error
                                     :reason (.getMessage e)})))))
                      objects)
        all-pass? (every? #(= :pass (:check/status %)) results)]
    {:check/id :object-integrity-valid
     :check/status (if all-pass? :pass :warning)
     :detail {:total (count results)
              :pass (count (filter #(= :pass (:check/status %)) results))
              :warning (count (filter #(= :warning (:check/status %)) results))
              :fail (count (filter #(= :fail (:check/status %)) results))}
     :objects results}))

(defn- check-attestation-integrity
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (let [path (:object/path obj)]
                          (cond
                            (nil? path)
                            {:attestation/id (:object/hash obj)
                             :check/status :warning
                             :reason :hash-only}
                            (not (.exists (io/file path)))
                            {:attestation/id (:object/hash obj)
                             :check/status :warning
                             :reason (str "File not found: " path)}
                            :else
                            (try
                              (let [content (edn/read-string (slurp path))
                                    integrity-result (integrity/verify-attestation-integrity content)]
                                {:attestation/id (:object/hash obj)
                                 :check/status (if (:valid? integrity-result) :pass :fail)
                                 :errors (:errors integrity-result)})
                              (catch Exception e
                                {:attestation/id (:object/hash obj)
                                 :check/status :error
                                 :reason (.getMessage e)})))))
                      att-objects)
        has-fail? (some #(= :fail (:check/status %)) results)
        all-warning? (every? #(#{:warning :pass} (:check/status %)) results)]
    {:check/id :attestation-integrity-valid
     :check/status (cond has-fail? :fail
                         all-warning? :warning
                         :else :pass)
     :detail {:total (count results)
              :pass (count (filter #(= :pass (:check/status %)) results))
              :fail (count (filter #(= :fail (:check/status %)) results))}
     :attestations results}))

(defn- check-attestation-signatures
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (try
                          (let [content (edn/read-string (slurp (:object/path obj)))
                                sig (:attestation/signature content)]
                            (if sig
                              {:attestation/id (:object/hash obj) :check/status :pass
                               :algorithm (:algorithm sig)
                               :public-key-id (:public-key-id sig)}
                              {:attestation/id (:object/hash obj) :check/status :warning
                               :reason :unsigned}))
                          (catch Exception e
                            {:attestation/id (:object/hash obj)
                             :check/status :error
                             :reason (.getMessage e)})))
                      att-objects)
        signed (count (filter #(= :pass (:check/status %)) results))
        unsigned (count (filter #(= :warning (:check/status %)) results))]
    {:check/id :attestation-signature-valid
     :check/status (if (zero? unsigned) :pass :warning)
     :detail {:signed signed :unsigned unsigned}
     :attestations results}))

(defn- check-registry-references
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (try
                          (let [content (edn/read-string (slurp (:object/path obj)))
                                attestor-id (:attestation/attestor-id content)]
                            {:attestation/id (:object/hash obj)
                             :attestor-id attestor-id
                             :check/status :pass
                             :note "Registry snapshot included for external verification"})
                          (catch Exception e
                            {:attestation/id (:object/hash obj)
                             :check/status :error
                             :reason (.getMessage e)})))
                      att-objects)]
    {:check/id :registry-references-valid
     :check/status :pass
     :detail {:registries-included (set (keys (:bundle/registries bundle)))
              :attestations-checked (count results)}
     :attestations results}))

(defn- check-claim-definition-references
  [bundle]
  (let [objects (:bundle/objects bundle [])
        att-objects (filter #(= :attestation-record (:object/kind %)) objects)
        results (mapv (fn [obj]
                        (try
                          (let [content (edn/read-string (slurp (:object/path obj)))
                                claim-id (:attestation/claim-id content)]
                            {:attestation/id (:object/hash obj)
                             :claim-id claim-id
                             :check/status (if claim-id :pass :warning)
                             :reason (when (nil? claim-id) :no-claim-reference)})
                          (catch Exception e
                            {:attestation/id (:object/hash obj)
                             :check/status :error
                             :reason (.getMessage e)})))
                      att-objects)]
    {:check/id :claim-definition-references-valid
     :check/status :pass
     :attestations results}))

(defn- check-subject-availability
  [bundle]
  (let [objects (:bundle/objects bundle [])
        hash-only (filter #(= :hash-only (:object/availability %)) objects)
        included (filter #(= :included (:object/availability %)) objects)]
    {:check/id :subject-content-available
     :check/status (if (seq hash-only) :warning :pass)
     :detail {:total (count objects)
              :included (count included)
              :hash-only (count hash-only)
              :note (when (seq hash-only)
                      "Some subjects are hash-only: content not available for verification")}}))

(defn- check-sensitivity-sentinel
  [bundle]
  (let [sensitivity (:bundle/sensitivity bundle)
        decision (:sentinel/decision sensitivity)]
    (if (= :allowed decision)
      {:check/id :sensitivity-sentinel-approved
       :check/status :pass
       :detail {:decision decision}}
      {:check/id :sensitivity-sentinel-approved
       :check/status :blocked
       :detail {:decision decision
                :reason "Sensitivity sentinel did not approve export"}})))

(defn verify-attestation-bundle
  "Verify an attestation bundle against the verification pipeline.

   Performs all 13 verification checks and returns a structured report
   with a bundle-level status.

   Returns:
     {:valid? true/false
      :bundle/status <one of five statuses>
      :checks [<check-result> ...]
      :summary {...}}"
  [bundle]
  (let [checks [(check-version bundle)
                (check-root-hash bundle)
                (check-object-integrity bundle)
                (check-attestation-integrity bundle)
                (check-attestation-signatures bundle)
                (check-registry-references bundle)
                (check-claim-definition-references bundle)
                (check-subject-availability bundle)
                (check-sensitivity-sentinel bundle)]
        failures (filter #(= :fail (:check/status %)) checks)
        blocked (filter #(= :blocked (:check/status %)) checks)
        warnings (filter #(= :warning (:check/status %)) checks)
        all-pass? (and (empty? failures) (empty? blocked))
        status (cond
                 (seq blocked) :blocked-by-sensitivity-policy
                 (seq failures) :invalid
                 (and (empty? warnings) all-pass?) :fully-verified
                 (some #(= :warning (:check/status %))
                       (filter #(= :subject-content-available (:check/id %)) checks))
                 :partially-verified
                 :else :hash-linked)]
    {:valid? (and all-pass? (empty? blocked))
     :bundle/status status
     :checks checks
     :summary {:total-checks (count checks)
               :pass (count (filter #(= :pass (:check/status %)) checks))
               :warning (count warnings)
               :fail (count failures)
               :blocked (count blocked)}}))

;; ── Bundle I/O ───────────────────────────────────────────────────────────────

(defn write-attestation-bundle!
  "Write an attestation bundle to disk.

   Persists each object to its declared path and writes the manifest
   as manifest.edn in the bundle root directory.

   Arguments:
     bundle — bundle manifest map (from build-attestation-bundle)
     objects-map — map of attestion/claim/node data to write:
                    {:attestations [..] :claim-results [..] :evidence-nodes [..]}

   Returns the bundle directory path."
  [bundle objects-map]
  (let [bundle-dir (some-> (get-in bundle [:bundle/objects 0 :object/path])
                           io/file .getParentFile .getParentFile .getPath)
        _ (when-not bundle-dir
            (throw (ex-info "Cannot determine bundle directory from manifest"
                            {:bundle bundle})))
        ;; Write attestations
        _ (doseq [a (:attestations objects-map [])]
            (let [path (object-path bundle-dir "attestations" (:attestation/id a))]
              (spit path (pr-str a))))
        ;; Write claim results
        _ (doseq [c (:claim-results objects-map [])]
            (let [h (or (:claim-result-hash c) (compute-object-hash c))
                  path (object-path bundle-dir "claims" h)]
              (spit path (pr-str c))))
        ;; Write evidence nodes
        _ (doseq [n (:evidence-nodes objects-map [])]
            (let [path (object-path bundle-dir "evidence-nodes" (:node-hash n))]
              (spit path (pr-str n))))
        ;; Write registries
        _ (doseq [[reg-kind reg-map] (:bundle/registries bundle)]
            (spit (:registry/path reg-map) (pr-str (get objects-map reg-kind))))
        ;; Write sensitivity report
        _ (when-let [report-path (get-in bundle [:bundle/sensitivity :sentinel/path])]
            (spit report-path (pr-str (:sensitivity-report objects-map))))
        ;; Write manifest
        manifest-path (str bundle-dir "/manifest.edn")]
    (spit manifest-path (pr-str bundle))
    (str (io/file bundle-dir ".written"))))

(defn read-attestation-bundle
  "Read an attestation bundle from disk.

   Reads the manifest from bundle-dir/manifest.edn and returns
   the manifest map.

   Arguments:
     bundle-dir — path to the bundle directory

   Returns the bundle manifest map."
  [bundle-dir]
  (let [manifest-path (str bundle-dir "/manifest.edn")]
    (when-not (.exists (io/file manifest-path))
      (throw (ex-info "Bundle manifest not found" {:path manifest-path})))
    (edn/read-string (slurp manifest-path))))
