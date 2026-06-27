(ns resolver-sim.evidence.node
  "Execution evidence nodes: immutable, content-addressed records describing
   registered execution workloads.

   The node hash is computed from a canonical projection that excludes:
   - :node-id
   - :node-hash
   - :timestamp
   - policy-filtered presentation output

   This keeps node identity stable under metadata-only changes while allowing
   policy-driven visible output to vary without affecting integrity checks."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.logging :as log]))

(def ^:const schema-version 1)
(def ^:const default-policy-id :evidence-policy/computed)

(declare canonical-hashable-value
         validate-node
         validate-node-dag)

(def ^:dynamic *node-registry*
  "In-memory registry of execution evidence nodes keyed by :node-hash."
  (atom {}))

(defn reset-node-registry!
  []
  (reset! *node-registry* {})
  nil)

(defn all-nodes
  []
  (->> @*node-registry* vals (sort-by :timestamp) vec))

(defn lookup-node
  [node-hash]
  (get @*node-registry* node-hash))

(defn register-node!
  [node]
  (swap! *node-registry* assoc (:node-hash node) node)
  node)

(defn- now-iso
  []
  (str (java.time.Instant/now)))

(defn- node-short-hash
  [node-hash]
  (subs node-hash 0 (min 12 (count node-hash))))

(defn- canonical-disk-value
  [value]
  (letfn [(walk [x]
            (cond
              (map? x) (into (sorted-map)
                             (map (fn [[k v]] [(walk k) (walk v)]) x))
              (set? x) (into (sorted-set) (map walk x))
              (vector? x) (mapv walk x)
              (sequential? x) (mapv walk x)
              :else x))]
    (walk value)))

(defn- node-artifact-dir
  []
  (str (evcfg/artifact-dir) "/evidence-nodes"))

(defn- node-artifact-filename
  [node-hash]
  (str "node-" (node-short-hash node-hash) ".edn"))

(defn- node-artifact-path
  [node]
  (str (node-artifact-dir) "/" (node-artifact-filename (:node-hash node))))

(defn- node-artifact-entry
  [node path]
  (let [f (io/file path)]
    {:id (str "node-" (node-short-hash (:node-hash node)))
     :kind :evidence-node
     :artifact/type :evidence-node
     :artifact/hash (:node-hash node)
     :artifact/path path
     :evidence/schema-version (:schema-version node)
     :evidence/node-id (:node-id node)
     :evidence/execution-id (get-in node [:execution :execution-id])
     :evidence/status (get-in node [:result :status])
     :evidence/parent-hashes (vec (:parent-hashes node))
     :evidence/bootstrap-root? (boolean (seq (:bootstrap-roots node)))
     :path path
     :sha256 (chain/compute-file-sha256 path)
     :bytes (.length f)
     :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified f)))}))

(defn- write-node-artifact!
  [node]
  (let [path (node-artifact-path node)
        f (io/file path)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str (canonical-disk-value node)))
    path))

(defn read-persisted-node
  [path]
  (edn/read-string (slurp path)))

(defn- registry-var-value
  [sym]
  @(requiring-resolve sym))

(defn execution-registry
  []
  (registry-var-value 'resolver-sim.definitions.passive-registries/execution-registry))

(defn evidence-policy-registry
  []
  (registry-var-value 'resolver-sim.definitions.passive-registries/evidence-policy-registry))

(defn execution-entry
  [execution-id]
  (some #(when (= execution-id (:id %)) %) (:executions (execution-registry))))

(defn evidence-policy-entry
  [policy-id]
  (some #(when (= policy-id (:id %)) %) (:evidence-policies (evidence-policy-registry))))

(defn execution-registry-hash
  []
  (hc/hash-with-intent {:hash/intent :registry}
                       (canonical-hashable-value (execution-registry))))

(defn evidence-policy-hash
  ([] (evidence-policy-hash default-policy-id))
  ([policy-id]
   (let [policy (or (evidence-policy-entry policy-id)
                    (throw (ex-info "Unknown evidence policy" {:policy-id policy-id})))]
     (hc/hash-with-intent {:hash/intent :registry}
                          (canonical-hashable-value
                           {:policy-version schema-version
                            :policy policy})))))

(defn- stable-symbol-name
  [x]
  (cond
    (symbol? x) (str x)
    (var? x) (str (.-sym ^clojure.lang.Var x))
    (fn? x) (or (some-> x meta :name str)
                (.getName (class x)))
    :else (str x)))

(defn- canonical-hashable-value
  [value]
  (letfn [(walk [x]
            (cond
              (nil? x) nil
              (boolean? x) x
              (integer? x) x
              (string? x) x
              (keyword? x) x
              (symbol? x) {:type :symbol :value (stable-symbol-name x)}
              (instance? java.time.Instant x) (.toString x)
              (instance? Double x) {:type :float64 :value-str (format "%.17g" (double x))}
              (instance? Float x) {:type :float64 :value-str (format "%.17g" (float x))}
              (instance? clojure.lang.Ratio x) {:type :ratio :value-str (format "%.17g" (double x))}
              (fn? x) {:type :fn :name (stable-symbol-name x)}
              (var? x) {:type :var :value (stable-symbol-name x)}
              (vector? x) (mapv walk x)
              (map? x) (persistent!
                        (reduce-kv (fn [m k v]
                                     (assoc! m (walk k) (walk v)))
                                   (transient {})
                                   x))
              (set? x) (vec (sort-by pr-str (map walk x)))
              (sequential? x) (mapv walk x)
              :else (str x)))]
    (walk value)))

(defn- hash-content
  [value]
  (hc/hash-with-intent {:hash/intent :evidence-content}
                       (canonical-hashable-value value)))

(defn- normalize-failure
  [failure]
  {:failure-type (or (:failure-type failure) :unexpected)
   :message (or (:message failure) "")
   :expected? (boolean (:expected? failure))
   :class (or (:class failure) (:failure-type failure) :unexpected)})

(defn apply-evidence-policy
  "Apply a policy to execution output.

   Policy fields are intentionally additive and optional:
   - :classes                 — visible output classes to retain
   - :failure-policy/include-expected-failures? — default true
   - :failure-policy/exclude-classes            — set of failure classes to omit

   Returns a map with:
   - :summary         — canonical counts safe to include in the hashed node
   - :visible-output  — policy-filtered presentation data (excluded from node hash)
   - :visible-failures / :filtered-failures — for diagnostics and tests"
  [policy {:keys [status outputs failure-details]}]
  (let [classes (set (or (:classes policy) #{:result :outputs :failures}))
        failure-policy (:failure-policy policy {})
        include-expected? (get failure-policy :include-expected-failures? true)
        exclude-classes (set (or (:exclude-classes failure-policy) #{}))
        normalized-failures (mapv normalize-failure (or failure-details []))
        visible-failures (->> normalized-failures
                              (remove #(and (not include-expected?) (:expected? %)))
                              (remove #(contains? exclude-classes (:class %)))
                              vec)
        visible-output (cond-> {}
                         (contains? classes :result)
                         (assoc :status status)

                         (contains? classes :outputs)
                         (assoc :outputs outputs)

                         (and (contains? classes :failures) (seq visible-failures))
                         (assoc :failures visible-failures))
        expected-failure-count (count (filter :expected? normalized-failures))
        unexpected-failure-count (- (count normalized-failures) expected-failure-count)]
    {:summary {:failure-count (count normalized-failures)
               :expected-failure-count expected-failure-count
               :unexpected-failure-count unexpected-failure-count
               :visible-failure-count (count visible-failures)
               :filtered-failure-count (- (count normalized-failures)
                                          (count visible-failures))}
     :visible-output visible-output
     :visible-failures visible-failures
     :filtered-failures (vec (remove (set visible-failures) normalized-failures))
     :excluded-classes exclude-classes}))

(defn compute-node-hash
  [node]
  (hc/hash-with-intent {:hash/intent :evidence-node} node))

(defn build-execution-node
  [{:keys [execution-id policy-id parent-hashes bootstrap-roots timestamp
           status inputs outputs failure-details attestations extensions
           execution-kind runner]
    :or {policy-id default-policy-id
         parent-hashes []
         bootstrap-roots []
         timestamp (now-iso)
         attestations []
         extensions {}}}]
  (let [execution-entry' (or (execution-entry execution-id)
                             (throw (ex-info "Unknown execution id" {:execution-id execution-id})))
        policy-entry' (or (evidence-policy-entry policy-id)
                          (throw (ex-info "Unknown evidence policy id"
                                          {:policy-id policy-id
                                           :execution-id execution-id})))
        {:keys [summary visible-output excluded-classes]} (apply-evidence-policy
                                                           policy-entry'
                                                           {:status status
                                                            :outputs outputs
                                                            :failure-details failure-details})
        base {:schema-version schema-version
              :parent-hashes (vec parent-hashes)
              :bootstrap-roots (vec bootstrap-roots)
              :timestamp timestamp
              :execution {:execution-id execution-id
                          :execution-kind (or execution-kind (:kind execution-entry'))
                          :runner (or runner (:runner execution-entry'))
                          :registry-hash (execution-registry-hash)
                          :policy-id policy-id
                          :policy-hash (evidence-policy-hash policy-id)}
              :result {:status status
                       :summary summary}
              :evidence {:inputs-hash (hash-content inputs)
                         :outputs-hash (hash-content outputs)}
              :attestations (vec attestations)
              :extensions extensions
              :policy-output {:visible visible-output
                              :excluded-classes excluded-classes}}
        node-hash (compute-node-hash base)]
    (assoc base
           :node-id node-hash
           :node-hash node-hash)))

(defn- validate-node-or-throw!
  ([node]
   (validate-node-or-throw! node (set (keys @*node-registry*))))
  ([node known-parent-hashes]
   (let [{:keys [valid? errors checks]} (validate-node node
                                                       :known-parent-hashes known-parent-hashes)]
     (when-not valid?
       (throw (ex-info "Cannot persist invalid execution evidence node"
                       {:node-hash (:node-hash node)
                        :errors errors
                        :checks checks})))
     node)))

(defn persist-execution-node!
  ([node]
   (persist-execution-node! node (set (keys @*node-registry*))))
  ([node known-parent-hashes]
   (validate-node-or-throw! node known-parent-hashes)
   (let [path (write-node-artifact! node)
         entry (node-artifact-entry node path)]
     (chain/register-additional-artifact! entry)
     {:node node
      :artifact-entry entry
      :path path})))

(defn verify-persisted-node-artifact!
  ([path]
   (verify-persisted-node-artifact! path nil))
  ([path artifact-entry]
   (verify-persisted-node-artifact! path artifact-entry {}))
  ([path artifact-entry {:keys [known-parent-hashes]
                         :or {known-parent-hashes #{}}}]
   (let [node (read-persisted-node path)
         validation (validate-node node :known-parent-hashes known-parent-hashes)
         recomputed (compute-node-hash node)
         recorded-path (or (:artifact/path artifact-entry)
                           (:path artifact-entry)
                           path)
         file-hash (chain/compute-file-sha256 path)
         hash-valid? (= recomputed (:node-hash node))
         artifact-hash-valid? (or (nil? artifact-entry)
                                  (= (:artifact/hash artifact-entry) (:node-hash node)))
         artifact-file-hash-valid? (or (nil? artifact-entry)
                                       (= (:sha256 artifact-entry) file-hash))
         path-valid? (= recorded-path path)
         errors (vec (concat (:errors validation)
                             (when-not hash-valid?
                               [{:error :node/hash-mismatch
                                 :recorded (:node-hash node)
                                 :computed recomputed}])
                             (when-not path-valid?
                               [{:error :artifact/path-mismatch
                                 :recorded recorded-path
                                 :actual path}])
                             (when-not artifact-hash-valid?
                               [{:error :artifact/hash-mismatch
                                 :recorded (:artifact/hash artifact-entry)
                                 :computed (:node-hash node)}])
                             (when-not artifact-file-hash-valid?
                               [{:error :artifact/file-hash-mismatch
                                 :recorded (:sha256 artifact-entry)
                                 :computed file-hash}])))]
     {:valid? (empty? errors)
      :path path
      :node node
      :artifact-entry artifact-entry
      :errors errors
      :checks {:node-valid? (:valid? validation)
               :node-hash-valid? hash-valid?
               :artifact-hash-valid? artifact-hash-valid?
               :artifact-file-hash-valid? artifact-file-hash-valid?
               :path-valid? path-valid?}})))

(defn- persisted-node-artifact-paths
  [dir]
  (let [root (io/file dir)
        node-dir (if (= "evidence-nodes" (.getName root))
                   root
                   (io/file root "evidence-nodes"))]
    (when (.exists node-dir)
      (->> (file-seq node-dir)
           (filter #(.isFile ^java.io.File %))
           (map #(.getPath ^java.io.File %))
           (filter #(.endsWith ^String % ".edn"))
           sort
           vec))))

(defn verify-persisted-node-artifacts!
  ([dir]
   (verify-persisted-node-artifacts! dir nil))
  ([dir artifact-entries]
   (let [paths (persisted-node-artifact-paths dir)
         node-results (mapv (fn [path]
                              (let [artifact-entry (some #(when (= (:artifact/path %) path) %) artifact-entries)
                                    known-parent-hashes (->> paths
                                                             (map (fn [candidate]
                                                                    (-> candidate read-persisted-node :node-hash)))
                                                             set)]
                                (verify-persisted-node-artifact! path
                                                                 artifact-entry
                                                                 {:known-parent-hashes known-parent-hashes})))
                            paths)
         nodes (mapv :node node-results)
         dag (validate-node-dag nodes)
         artifact-matches? (if (seq artifact-entries)
                             (every? (fn [{:keys [path node artifact-entry]}]
                                       (and artifact-entry
                                            (= (:artifact/path artifact-entry) path)
                                            (= (:artifact/hash artifact-entry)
                                               (:node-hash node))
                                            (= (:sha256 artifact-entry)
                                               (chain/compute-file-sha256 path))))
                                     node-results)
                             true)
         errors (vec (concat (mapcat :errors node-results)
                             (:errors dag)))]
     {:valid? (and (empty? errors) (:valid? dag) artifact-matches?)
      :paths paths
      :node-results node-results
      :dag dag
      :errors errors
      :checks {:dag-valid? (:valid? dag)
               :paths-found (count paths)
               :artifacts-matched? artifact-matches?}})))

(defn emit-execution-node!
  "Build, persist, and register an execution node.
   Returns the node map (including :node-hash)."
  [node-spec]
  (let [node (build-execution-node node-spec)]
    (persist-execution-node! node)
    (register-node! node)
    node))

(defn- cycle-path
  [graph node]
  (letfn [(visit [n stack seen]
            (cond
              (some #{n} stack)
              (conj (vec (drop-while #(not= n %) stack)) n)

              (seen n)
              nil

              :else
              (some #(visit % (conj stack n) (conj seen n)) (get graph n))))]
    (visit node [] #{})))

(defn validate-node
  "Validate one node against canonical hash integrity and local shape rules.
   Returns {:valid? .. :errors [...] :checks {...}}."
  [node & {:keys [known-parent-hashes]
           :or {known-parent-hashes #{}}}]
  (let [required-top-level [:schema-version :node-id :node-hash :parent-hashes
                            :execution :result :evidence]
        missing-top-level (vec (remove #(contains? node %) required-top-level))
        valid-status? (contains? #{:pass :fail :error} (get-in node [:result :status]))
        recomputed (compute-node-hash node)
        hash-valid? (= recomputed (:node-hash node))
        parent-set (set (:parent-hashes node))
        bootstrap-set (set (:bootstrap-roots node))
        missing-parents (vec (remove #(or (contains? known-parent-hashes %)
                                          (contains? bootstrap-set %))
                                     parent-set))
        errors (cond-> []
                 (seq missing-top-level)
                 (conj {:error :node/missing-fields :missing missing-top-level})

                 (not= schema-version (:schema-version node))
                 (conj {:error :node/unsupported-schema-version
                        :schema-version (:schema-version node)})

                 (not valid-status?)
                 (conj {:error :node/invalid-status
                        :status (get-in node [:result :status])})

                 (not hash-valid?)
                 (conj {:error :node/hash-mismatch
                        :recorded (:node-hash node)
                        :computed recomputed})

                 (seq missing-parents)
                 (conj {:error :node/missing-parents
                        :missing-parents missing-parents}))]
    {:valid? (empty? errors)
     :errors errors
     :checks {:hash-valid? hash-valid?
              :valid-status? valid-status?
              :missing-parents missing-parents}}))

(defn validate-node-dag
  "Validate a node collection as a DAG.

   Checks:
   - every node hash matches its canonical projection
   - every parent hash exists in the collection or in explicit bootstrap roots
   - cycles are rejected when :strict-dag? is true"
  [nodes & {:keys [strict-dag?]
            :or {strict-dag? true}}]
  (let [node-map (into {} (map (juxt :node-hash identity) nodes))
        known-hashes (set (keys node-map))
        per-node (mapv #(validate-node % :known-parent-hashes known-hashes) nodes)
        graph (into {}
                    (map (fn [{:keys [node-hash parent-hashes bootstrap-roots]}]
                           [node-hash (vec (remove (set bootstrap-roots) parent-hashes))])
                         nodes))
        cycle (when strict-dag?
                (some #(cycle-path graph %) (keys graph)))
        errors (vec (concat (mapcat :errors per-node)
                            (when cycle
                              [{:error :node/cycle
                                :cycle cycle}])))]
    {:valid? (empty? errors)
     :errors errors
     :node-count (count nodes)
     :checks {:hashes-valid? (every? #(get-in % [:checks :hash-valid?]) per-node)
              :parents-valid? (every? empty? (map #(get-in % [:checks :missing-parents]) per-node))
              :cycle-free? (nil? cycle)}}))

;; ── Detailed validation ──────────────────────────────────────────────────────

(defn- check-node-id-equals-hash
  "Identity check: :node-id must equal :node-hash."
  [node]
  (let [node-id (:node-id node)
        node-hash (:node-hash node)]
    (if (= node-id node-hash)
      {:check/id :node-id-equals-hash :check/status :pass}
      {:check/id :node-id-equals-hash :check/status :fail
       :reason (str "node-id " node-id " does not match node-hash " node-hash)})))

(defn- check-node-inputs-present
  "Link check: :evidence :inputs-hash and :outputs-hash must be present and non-empty."
  [node]
  (let [inputs-hash (get-in node [:evidence :inputs-hash])
        outputs-hash (get-in node [:evidence :outputs-hash])
        missing (cond-> []
                  (not (and (string? inputs-hash) (pos? (count inputs-hash))))
                  (conj :inputs-hash)
                  (not (and (string? outputs-hash) (pos? (count outputs-hash))))
                  (conj :outputs-hash))]
    (if (seq missing)
      {:check/id :node-inputs-present :check/status :fail
       :missing missing}
      {:check/id :node-inputs-present :check/status :pass})))

(defn- check-node-attestations-present
  "Link check: :attestations must be a vector (may be empty)."
  [node]
  (let [attestations (:attestations node)]
    (if (vector? attestations)
      {:check/id :node-attestations-present :check/status :pass}
      {:check/id :node-attestations-present :check/status :fail
       :reason (str "Expected vector for :attestations, got " (type attestations))})))

(defn- check-node-parents-resolvable
  "Link check: all :parent-hashes must be resolvable from bootstrap-roots
   or from known-parent-hashes (passed via opts)."
  [node known-parent-hashes]
  (let [parent-hashes (set (:parent-hashes node))
        bootstrap-roots (set (:bootstrap-roots node))
        missing (vec (remove #(or (contains? known-parent-hashes %)
                                  (contains? bootstrap-roots %))
                             parent-hashes))]
    (if (seq missing)
      {:check/id :node-parents-resolvable :check/status :fail
       :missing missing}
      {:check/id :node-parents-resolvable :check/status :pass})))

(defn validate-node-detailed
  "Validate one node with extended checks including identity, links,
   and input/output presence.

   In addition to all checks from validate-node, runs:
   - node-id equals node-hash
   - inputs-hash and outputs-hash present
   - attestations is a vector
   - parent hashes are resolvable

   Returns {:valid? ... :errors [...] :checks [...] :summary {...}}."
  [node & {:keys [known-parent-hashes]
           :or {known-parent-hashes #{}}}]
  (let [detailed-checks [(check-node-attestations-present node)]
        pre-failures (filter #(= :fail (:check/status %)) detailed-checks)]
    (if (seq pre-failures)
      {:valid? false
       :errors (mapv (fn [c] {:check/id (:check/id c) :reason (:reason c)}) pre-failures)
       :checks detailed-checks
       :summary {:base-valid? false
                 :detailed-checks-total (count detailed-checks)
                 :detailed-checks-passed (- (count detailed-checks) (count pre-failures))
                 :detailed-checks-failed (count pre-failures)}}
      (let [base (validate-node node :known-parent-hashes known-parent-hashes)
            deeper-checks [(check-node-id-equals-hash node)
                           (check-node-inputs-present node)
                           (check-node-parents-resolvable node known-parent-hashes)]
            all-checks (vec (concat detailed-checks deeper-checks))
            failures (filter #(= :fail (:check/status %)) all-checks)
            all-errors (vec (concat (:errors base)
                                    (mapv (fn [f] {:check/id (:check/id f) :reason (:reason f)})
                                          failures)))
            all-pass? (and (:valid? base) (empty? failures))]
        {:valid? all-pass?
         :errors all-errors
         :checks all-checks
         :summary {:base-valid? (:valid? base)
                   :detailed-checks-total (count all-checks)
                   :detailed-checks-passed (- (count all-checks) (count failures))
                   :detailed-checks-failed (count failures)}}))))

;; ── Detailed DAG validation ──────────────────────────────────────────────────

(defn- check-dag-single-root
  "Acyclicity check: the DAG must have exactly one root node
   (a node with no :parent-hashes that are not bootstrap-roots)."
  [nodes]
  (let [all-parents (set (mapcat :parent-hashes nodes))
        all-bootstrap (set (mapcat :bootstrap-roots nodes))
        effective-parents (apply disj all-parents all-bootstrap)
        roots (remove #(contains? effective-parents (:node-hash %)) nodes)
        root-count (count roots)]
    (if (= 1 root-count)
      {:check/id :dag-single-root :check/status :pass}
      {:check/id :dag-single-root :check/status :fail
       :reason (str "Expected 1 root node, found " root-count)
       :root-count root-count})))

(defn- check-dag-no-duplicate-hashes
  "Completeness check: no two nodes may share the same :node-hash."
  [nodes]
  (let [hashes (map :node-hash nodes)
        duplicates (vec (keys (filter (fn [[_ v]] (> v 1)) (frequencies hashes))))]
    (if (seq duplicates)
      {:check/id :dag-no-duplicate-hashes :check/status :fail
       :duplicates duplicates}
      {:check/id :dag-no-duplicate-hashes :check/status :pass})))

(defn- check-dag-all-nodes-valid
  "Aggregate check: every node in the DAG passes validate-node-detailed."
  [nodes known-parent-hashes]
  (let [results (mapv #(validate-node-detailed % :known-parent-hashes known-parent-hashes) nodes)
        failures (filter #(not (:valid? %)) results)]
    (if (seq failures)
      {:check/id :dag-all-nodes-valid :check/status :fail
       :failed-count (count failures)
       :node-count (count nodes)}
      {:check/id :dag-all-nodes-valid :check/status :pass
       :node-count (count nodes)})))

(defn validate-dag-detailed
  "Validate a node collection as a DAG with extended checks.

   In addition to all checks from validate-node-dag, runs:
   - single root node
   - no duplicate node hashes
   - per-node detailed validation (identity, links, inputs)

   Returns {:valid? ... :errors [...] :checks [...] :summary {...}}."
  [nodes & {:keys [strict-dag?]
            :or {strict-dag? true}}]
  (let [known-hashes (set (map :node-hash nodes))
        base (validate-node-dag nodes :strict-dag? strict-dag?)
        detailed-checks [(check-dag-single-root nodes)
                         (check-dag-no-duplicate-hashes nodes)
                         (check-dag-all-nodes-valid nodes known-hashes)]
        failures (filter #(= :fail (:check/status %)) detailed-checks)
        all-errors (vec (concat (:errors base)
                                (mapv (fn [f] {:check/id (:check/id f) :reason (:reason f)})
                                      failures)))
        all-pass? (and (:valid? base) (empty? failures))]
    {:valid? all-pass?
     :errors all-errors
     :checks (concat (:checks base) detailed-checks)
     :summary {:base-valid? (:valid? base)
               :detailed-checks-total (count detailed-checks)
               :detailed-checks-passed (- (count detailed-checks) (count failures))
               :detailed-checks-failed (count failures)}}))

(defn with-execution-node
  "Run thunk, emit an execution node for pass/fail/error, return thunk's value.

   Options:
   - :execution-id     required registry execution id
   - :policy-id        evidence policy id, default :evidence-policy/computed
   - :inputs           canonicalizable input summary
   - :parent-hashes    parent node hashes
   - :bootstrap-roots  explicit parent hashes allowed without local nodes
   - :runner           runner keyword (default nil — resolved from execution registry)
   - :status-fn        maps successful return value -> :pass | :fail | :error
   - :outputs-fn       maps successful return value -> canonicalizable output summary
   - :failure-details-fn maps successful return value -> failure vector
   - :extensions-fn    maps successful return value -> unhashed extension metadata

   On exceptions, emits :error and rethrows."
  [{:keys [execution-id policy-id inputs parent-hashes bootstrap-roots runner
           status-fn outputs-fn failure-details-fn extensions-fn]
    :or {policy-id default-policy-id
         parent-hashes []
         bootstrap-roots []
         runner nil
         status-fn (constantly :pass)
         outputs-fn identity
         failure-details-fn (constantly [])
         extensions-fn (constantly {})}}
   thunk]
  (let [timestamp (now-iso)]
    (try
      (let [value (thunk)
            status (status-fn value)]
        (emit-execution-node!
         {:execution-id execution-id
          :policy-id policy-id
          :runner runner
          :timestamp timestamp
          :parent-hashes parent-hashes
          :bootstrap-roots bootstrap-roots
          :status status
          :inputs inputs
          :outputs (outputs-fn value)
          :failure-details (failure-details-fn value)
          :extensions (extensions-fn value)})
        value)
      (catch Throwable t
        (try
          (emit-execution-node!
           {:execution-id execution-id
            :policy-id policy-id
            :runner runner
            :timestamp timestamp
            :parent-hashes parent-hashes
            :bootstrap-roots bootstrap-roots
            :status :error
            :inputs inputs
            :outputs {:error (.getMessage t)
                      :exception (str (class t))}
            :failure-details [{:failure-type :exception
                               :class :exception
                               :message (.getMessage t)
                               :expected? false}]
            :extensions {:exception-class (str (class t))}})
          (catch Throwable node-error
            (log/warn! :execution-node-emission-failed
                       {:execution-id execution-id
                        :error (.getMessage node-error)
                        :original-error (.getMessage t)})))
        (throw t)))))

(defn with-execution-node+
  "Like with-execution-node but returns {:result <thunk-value> :execution-node <node-map>}.
   Callers that need the execution node hash use this instead."
  [{:keys [execution-id policy-id inputs parent-hashes bootstrap-roots runner
           status-fn outputs-fn failure-details-fn extensions-fn]
    :or {policy-id default-policy-id
         parent-hashes []
         bootstrap-roots []
         runner nil
         status-fn (constantly :pass)
         outputs-fn identity
         failure-details-fn (constantly [])
         extensions-fn (constantly {})}}
   thunk]
  (let [timestamp (now-iso)]
    (try
      (let [value (thunk)
            status (status-fn value)
            node (emit-execution-node!
                  {:execution-id execution-id
                   :policy-id policy-id
                   :runner runner
                   :timestamp timestamp
                   :parent-hashes parent-hashes
                   :bootstrap-roots bootstrap-roots
                   :status status
                   :inputs inputs
                   :outputs (outputs-fn value)
                   :failure-details (failure-details-fn value)
                   :extensions (extensions-fn value)})]
        {:result value :execution-node node})
      (catch Throwable t
        (try
          (let [node (emit-execution-node!
                      {:execution-id execution-id
                       :policy-id policy-id
                       :runner runner
                       :timestamp timestamp
                       :parent-hashes parent-hashes
                       :bootstrap-roots bootstrap-roots
                       :status :error
                       :inputs inputs
                       :outputs {:error (.getMessage t)
                                 :exception (str (class t))}
                       :failure-details [{:failure-type :exception
                                          :class :exception
                                          :message (.getMessage t)
                                          :expected? false}]
                       :extensions {:exception-class (str (class t))}})]
            {:result nil :execution-node node :error t})
          (catch Throwable node-error
            (log/warn! :execution-node-emission-failed
                       {:execution-id execution-id
                        :error (.getMessage node-error)
                        :original-error (.getMessage t)})
            {:result nil :execution-node nil :error t
             :node-error node-error}))))))
