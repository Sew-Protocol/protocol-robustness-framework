(ns resolver-sim.community.attestation
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.community.task :as task]
            [clojure.java.io :as io]))

(def ^:const schema-version "community-attestation.v0")
(def ^:const domain-tag "COMMUNITY_ATTESTATION_V0")
(def ^:const attestation-ref-prefix "attestation:sha256:")

(def ^:const supported-predicates
  #{:runner/execution-attested :runner/result-reproduced :runner/result-challenged})

(def ^:const reproduction-statuses
  #{:matched :semantically-matched :mismatched :inconclusive})

(defn attestation-ref [hash] (str attestation-ref-prefix hash))
(defn attestation-ref? [s] (and (string? s) (.startsWith s attestation-ref-prefix)))
(defn parse-attestation-ref [s] (when (attestation-ref? s) (subs s (count attestation-ref-prefix))))

(defn- normalize-for-hash [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (integer? x) x
    (string? x) x
    (keyword? x) x
    (instance? java.time.Instant x) (str x)
    (vector? x) (mapv normalize-for-hash x)
    (map? x) (persistent!
              (reduce-kv (fn [m k v] (assoc! m (normalize-for-hash k) (normalize-for-hash v)))
                         (transient {}) x))
    (set? x) (vec (sort (map normalize-for-hash x)))
    (sequential? x) (mapv normalize-for-hash x)
    :else (str x)))

(defn- canonical-body [m]
  (dissoc m :attestation/id :attestation/hash :attestation/ref :attestation/signature))

(defn- compute-hash [body]
  (hc/domain-hash domain-tag (normalize-for-hash (canonical-body body))))

(defn build-execution-attestation
  [m]
  (let [task-ref (:task/ref m)
        runner-id (:runner/id m)
        exec-node-hash (:execution-node-hash m)
        _ (assert (task/task-ref? task-ref))
        _ (assert (string? runner-id))
        _ (assert (string? exec-node-hash))
        body {:schema-version schema-version
              :attestation/predicate :runner/execution-attested
              :subject {:kind :research-task :reference task-ref :hash (task/parse-task-ref task-ref)}
              :assertion {:runner/id runner-id
                          :code-hash (:code-hash m)
                          :env-hash (:env-hash m)
                          :bundle-root (:bundle-root m)
                          :execution-node-hash exec-node-hash
                          :result-projection-hash (:result-projection-hash m)}
              :context {:registry-snapshot/hash (:registry-snapshot-hash m)}
              :issued-at (or (:issued-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :attestation/id hash :attestation/hash hash :attestation/ref (attestation-ref hash))))

(defn build-reproduction-attestation
  [m]
  (let [task-ref (:task/ref m)
        orig-att-ref (:original-attestation-ref m)
        comparison-status (:comparison-status m)
        _ (assert (task/task-ref? task-ref))
        _ (assert (attestation-ref? orig-att-ref))
        _ (assert (contains? reproduction-statuses comparison-status))
        body {:schema-version schema-version
              :attestation/predicate :runner/result-reproduced
              :subject {:kind :research-task :reference task-ref :hash (task/parse-task-ref task-ref)}
              :assertion {:runner/id (:runner/id m)
                          :code-hash (:code-hash m)
                          :env-hash (:env-hash m)
                          :original-attestation-ref orig-att-ref
                          :original-result-projection-hash (:original-result-projection-hash m)
                          :reproduction-execution-node-hash (:reproduction-execution-node-hash m)
                          :reproduction-result-projection-hash (:reproduction-result-projection-hash m)
                          :comparison-policy (:comparison-policy m)
                          :comparison-status comparison-status
                          :mismatch-artifact-refs (vec (or (:mismatch-artifact-refs m) []))}
              :context {:registry-snapshot/hash nil}
              :issued-at (or (:issued-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :attestation/id hash :attestation/hash hash :attestation/ref (attestation-ref hash))))

(defn build-challenge-attestation
  [m]
  (let [task-ref (:task/ref m)
        challenged-ref (:challenged-attestation-ref m)
        _ (assert (task/task-ref? task-ref))
        _ (assert (attestation-ref? challenged-ref))
        body {:schema-version schema-version
              :attestation/predicate :runner/result-challenged
              :subject {:kind :research-task :reference task-ref :hash (task/parse-task-ref task-ref)}
              :assertion {:runner/id (:runner/id m)
                          :challenged-attestation-ref challenged-ref
                          :challenge-type (or (:challenge-type m) :evidence-unresolvable)
                          :reason (:reason m)
                          :challenge-evidence-refs (vec (or (:challenge-evidence-refs m) []))}
              :context {:registry-snapshot/hash nil}
              :issued-at (or (:issued-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :attestation/id hash :attestation/hash hash :attestation/ref (attestation-ref hash))))

(defn sign-attestation!
  [attestation private-key-path]
  (let [hash (:attestation/hash attestation)
        sig (signing/sign-hash hash private-key-path nil)]
    (assoc attestation :attestation/signature sig)))

(defn verify-attestation-signature
  [attestation public-key-path]
  (let [sig (:attestation/signature attestation)
        hash (:attestation/hash attestation)]
    (if (and sig hash public-key-path (.exists (io/file public-key-path)))
      (let [valid? (signing/verify-signature hash sig public-key-path)]
        {:valid? valid?
         :errors (when-not valid? ["Signature verification failed"])})
      {:valid? false
       :errors [(cond
                  (nil? sig) "No signature"
                  (nil? hash) "No hash"
                  :else (str "Public key not found: " public-key-path))]})))

(defn persist-attestation!
  [attestation dir]
  (let [hash (:attestation/hash attestation)
        f (io/file dir "community-attestations" (str "att-" (subs hash 0 12) ".edn"))]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str attestation))
    {:path (.getPath f) :hash hash}))

(defn resolve-attestation
  [dir hash-or-ref]
  (let [hash (if (attestation-ref? hash-or-ref)
               (parse-attestation-ref hash-or-ref)
               hash-or-ref)
        f (io/file dir "community-attestations" (str "att-" (subs hash 0 12) ".edn"))]
    (when (.exists f)
      (try (read-string (slurp f))
           (catch Exception _ nil)))))

(defn valid-attestation?
  [attestation]
  (and (map? attestation)
       (= schema-version (:schema-version attestation))
       (contains? supported-predicates (:attestation/predicate attestation))
       (string? (:attestation/hash attestation))
       (= (:attestation/id attestation) (:attestation/hash attestation))
       (= (compute-hash attestation) (:attestation/hash attestation))))
