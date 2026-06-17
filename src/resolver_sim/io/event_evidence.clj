(ns resolver-sim.io.event-evidence
  "System for capturing and persisting high-fidelity evidence for high-impact transitions.
   Integrates with with-attribution context to bind causal metadata.

   Phase 3 enhancements:
     - Pure builder layer in resolver-sim.evidence.capture (cap-field, cap-fields, require-fields, finalize-evidence)
     - Content-addressed before/after world hashes via stable-hash
     - Replay coordinates (seed, oracle mode/fixture/cursor) from attribution context
     - Causality links (caused-by/evidence-id, caused-by/rule, caused-by/action)
     - Evidence importance levels (:core, :diagnostic, :trace)
     - Composite evidence-hash chain for tamper evidence
     - event-evidence.v1 schema compliance"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.logging :as log]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn qualified-key
  "Serialize a Clojure keyword to a JSON key that preserves its namespace.
   :evidence/type → \"evidence/type\"
   Uses the full str representation (namespaced keyword → \"ns/name\") to
   ensure round-trip fidelity with (json/read-str ... :key-fn keyword)."
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (string? k) k
    :else (str k)))

(defn- read-evidence-json
  "Read an evidence artifact JSON file and return the map with keyword keys.
   Handles both qualified JSON keys (evidence/type) and legacy unqualified keys
   (type) by normalizing unqualified keys to their namespaced equivalents
   when the expected namespaced lookup is nil."
  [f]
  (let [data (json/read-str (slurp f) :key-fn keyword)]
    ;; Normalize: if unqualified keys were stored (legacy format), promote
    ;; :type → :evidence/type, :chain-seq → :evidence/chain-seq, etc.
    (reduce-kv (fn [m k v]
                 (if (and (keyword? k) (not (namespace k)))
                   ;; Try to find a matching namespaced key by checking
                   ;; common known prefixes for the unqualified name.
                   (let [qualified (keyword "evidence" (name k))]
                     (if (get data qualified)
                       m ;; already present, skip legacy fallback
                       (assoc m qualified v)))
                   m))
               data data)))

(defn hash-world
  "Content hash of a world state for forensic anchoring.
   Uses pr-str directly (not canonicalize) because world state maps may have
   mixed key types (keyword, integer, string) that sorted-map cannot compare."
  [world]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (pr-str world) "UTF-8"))
    (apply str (map (partial format "%02x") (.digest digest)))))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- evidence-filename
  "Derive filename from evidence record metadata.
   Sanitize evidence-type to avoid directory traversal issues."
  [evidence]
  (let [etype (:evidence/type evidence)
        reason (clojure.string/replace (name (if (keyword? etype) etype "unknown")) #":" "-")
        reason (clojure.string/replace reason #"/" "-")
        sid    (name (if (keyword? (:scenario/id evidence)) (:scenario/id evidence) "unknown"))
        idx    (:event/seq evidence "unknown")]
    (str reason "-" sid "-" idx ".json")))

(defn- needs-internal-build?
  "Check if the call should use the legacy positional builder path.
   Positional calls have keyword :reason, map :pre, map :post, map :inputs."
  [reason pre post inputs]
  (and (keyword? reason) (map? pre) (map? post) (map? inputs)))

;; ── Capture Verification ──────────────────────────────────────────────────────
;;
;; Post-write verification confirms the artifact actually hit disk.
;; Mode is controlled by *write-verification*:
;;   :none     — skip verification
;;   :exists   — check file exists and is non-empty
;;   :readback — read file back and compare hashes
;;
;; Default :exists.  Set to :readback for research artifact runs.
;;   (binding [ev/*write-verification* :readback]
;;     (capture-event-evidence! ...))

(def ^:dynamic *write-verification* :exists)

(defn- verify-write!
  "Verify that an evidence artifact was written correctly.
   Throws on verification failure."
  [evidence path]
  (case *write-verification*
    :none
    nil

    :exists
    (when (or (not (.exists path)) (zero? (.length path)))
      (throw (ex-info "Evidence write verification failed"
                      {:path (str path)
                       :exists (.exists path)
                       :length (when (.exists path) (.length path))
                       :evidence-type (:evidence/type evidence)})))

    :readback
    (let [written (json/read-str (slurp path) :key-fn keyword)]
      (when (not= (dissoc evidence :persistence)
                  (dissoc written :persistence))
        (throw (ex-info "Evidence readback mismatch"
                        {:path (str path)
                         :evidence-type (:evidence/type evidence)
                         :expected-keys (keys (dissoc evidence :persistence))
                         :actual-keys (keys (dissoc written :persistence))}))))))

;; ── Performance Metrics ──────────────────────────────────────────────────────
;;
;; Phase 4: Latency tracking for evidence capture pipeline.
;; Metrics collected:
;;   - capture-latency-ms: wall-clock time per capture-event-evidence! call
;;   - hash-latency-ms: wall-clock time per stable-hash computation
;;   - serialize-latency-ms: wall-clock time per JSON serialization + disk write
;;
;; Access metrics from tests or production monitors:
;;   (resolver-sim.io.event-evidence/collect-and-reset-metrics!)
;; Returns a map of aggregate stats and resets the counters.

(def ^:private metrics (atom {:capture-count 0
                              :capture-total-ns 0
                              :hash-count 0
                              :hash-total-ns 0
                              :serialize-count 0
                              :serialize-total-ns 0}))

(defn collect-and-reset-metrics!
  "Return accumulated metrics and reset counters.
   Returns {:capture-count N :capture-latency-ms avg-ms
            :hash-count N :hash-latency-ms avg-ms
            :serialize-count N :serialize-latency-ms avg-ms}"
  []
  (let [m @metrics]
    (reset! metrics {:capture-count 0 :capture-total-ns 0
                     :hash-count 0 :hash-total-ns 0
                     :serialize-count 0 :serialize-total-ns 0})
    {:capture-count (:capture-count m)
     :capture-latency-ms (if (pos? (:capture-count m))
                           (/ (:capture-total-ns m) (:capture-count m) 1e6)
                           0)
     :hash-count (:hash-count m)
     :hash-latency-ms (if (pos? (:hash-count m))
                        (/ (:hash-total-ns m) (:hash-count m) 1e6)
                        0)
     :serialize-count (:serialize-count m)
     :serialize-latency-ms (if (pos? (:serialize-count m))
                             (/ (:serialize-total-ns m) (:serialize-count m) 1e6)
                             0)}))

(defn- record-capture-latency! [start-ns]
  (swap! metrics update :capture-count inc)
  (swap! metrics update :capture-total-ns + (- (System/nanoTime) start-ns)))

(defn- record-hash-latency! [start-ns]
  (swap! metrics update :hash-count inc)
  (swap! metrics update :hash-total-ns + (- (System/nanoTime) start-ns)))

(defn- record-serialize-latency! [start-ns]
  (swap! metrics update :serialize-count inc)
  (swap! metrics update :serialize-total-ns + (- (System/nanoTime) start-ns)))

;; ── Evidence Chain Cursor ─────────────────────────────────────────────────────
;;
;; Run-scoped evidence chain: every targeted artifact gets a sequence number,
;; the previous artifact's hash, and its own hash.  This enables a researcher
;; to detect missing or inserted artifacts using only the artifact directory.
;;
;; The cursor is an atom; call reset-chain-cursor! at the start of each run.
;; Example from dispatcher entry point:
;;   (event-evidence/reset-chain-cursor!)

(def ^:private chain-cursor
  (atom {:seq 0 :last-hash nil}))

(defn reset-chain-cursor!
  "Reset the evidence chain cursor for a new run."
  []
  (reset! chain-cursor {:seq 0 :last-hash nil}))

(defn- inject-chain-fields
  "Add :evidence/chain-seq, :evidence/chain-prev-hash, and
   :evidence/chain-self-hash to the evidence map using the cursor."
  [evidence]
  (let [{:keys [seq last-hash]} @chain-cursor
        seq-no (inc seq)
        self-hash (:evidence/hash evidence)]
    (swap! chain-cursor assoc :seq seq-no :last-hash self-hash)
    (assoc evidence
      :evidence/chain-seq seq-no
      :evidence/chain-prev-hash last-hash
      :evidence/chain-self-hash self-hash)))

;; ── Chain Registry Integration ────────────────────────────────────────────────

(defn- normalize-for-chain
  "Add chain-compatible keys from namespaced evidence fields."
  [evidence]
  (cond-> evidence
    (:evidence/hash evidence) (assoc :evidence-hash (:evidence/hash evidence))
    (:evidence/type evidence) (assoc :artifact-kind (:evidence/type evidence))))

(defn- validate-attribution!
  "Log a warning if required attribution keys are missing."
  [resolved-attr]
  (when (and (map? resolved-attr) (nil? (:ctx/run-id resolved-attr)))
    (log/warn! "capture-event-evidence! called without :ctx/run-id in attribution — evidence will be orphaned"
               {:resolved-attr (dissoc resolved-attr :ctx/scenario-id)})))

;; ── Primary Capture API ──────────────────────────────────────────────────────

(defn capture-event-evidence!
  "Persist a structured evidence record for a critical transition.

   Two calling conventions:

   A) Positional (legacy, backward-compatible):
      (capture-event-evidence! :reason pre post inputs)
      (capture-event-evidence! :reason pre post inputs calc)
      (capture-event-evidence! :reason pre post inputs calc ctx-or-opts)

      Where ctx-or-opts is nil, an attribution context map, or an opts map
      with optional keys :attribution-context, :importance (default :core).

   B) Pre-built evidence map (preferred for Phase 3):
      (capture-event-evidence! finalized-evidence)

      Where finalized-evidence was built with cap-field / cap-fields,
      validated with require-fields, and finalized with finalize-evidence."
  ([reason pre post inputs]
   (capture-event-evidence! reason pre post inputs nil nil))
  ([reason pre post inputs calc]
   (capture-event-evidence! reason pre post inputs calc nil))
  ([reason pre post inputs calc ctx-or-opts]
   (let [capture-start (System/nanoTime)]
     (if (needs-internal-build? reason pre post inputs)
        ;; Legacy positional path: build evidence internally
        (let [hash-opts? (and (map? ctx-or-opts)
                              (or (:world-before ctx-or-opts)
                                  (:world-after ctx-or-opts)))
              resolved-attr (cond
                              (and (map? ctx-or-opts) (:attribution-context ctx-or-opts))
                              (attr/get-attribution (:attribution-context ctx-or-opts))
                              (and (map? ctx-or-opts) (not hash-opts?))
                              ctx-or-opts
                              (map? reason)
                              (attr/get-attribution reason)
                              :else
                              (attr/current-attribution))
              _ (validate-attribution! resolved-attr)
              importance (if (map? ctx-or-opts) (or (:importance ctx-or-opts) :core) :core)
              hash-start (System/nanoTime)
              before-hash (cap/stable-hash pre)
              after-hash  (cap/stable-hash post)
              _ (record-hash-latency! hash-start)
              e (-> (cap/evidence-base {:type reason :importance importance
                                     :ctx resolved-attr})
                (cap/cap-fields {:scenario/id     (:ctx/scenario-id resolved-attr)
                                 :run/id          (:ctx/run-id resolved-attr)
                                 :trial/id        (:ctx/trial-id resolved-attr)
                                 :event/seq       (:ctx/event-index resolved-attr 0)
                                 :replay/seed     (:ctx/replay-seed resolved-attr)
                                 :oracle/cursor   (:ctx/oracle-cursor resolved-attr)
                                 :oracle/mode     (:ctx/oracle-mode resolved-attr)
                                 :oracle/fixture-id (:ctx/oracle-fixture-id resolved-attr)})
                (assoc :inputs inputs :pre-state pre :post-state post :calculation calc)
                (cap/cap-field :world/before-hash before-hash)
                (cap/cap-field :world/after-hash after-hash)
                (cond-> (and (map? ctx-or-opts) (:world-before ctx-or-opts))
                        (assoc :world/before-full-hash (hash-world (:world-before ctx-or-opts)))
                        (and (map? ctx-or-opts) (:world-after ctx-or-opts))
                        (assoc :world/after-full-hash (hash-world (:world-after ctx-or-opts)))
                        (:ctx/evidence-group-id resolved-attr)
                        (assoc :evidence/group-id (:ctx/evidence-group-id resolved-attr)
                               :evidence/layer :targeted-protocol))
                (cap/finalize-evidence)
                (inject-chain-fields))
              serialize-start (System/nanoTime)
              out-dir  (str (evcfg/artifact-dir) "/event-evidence")
              filename (evidence-filename e)
              f        (io/file out-dir filename)]
          (.mkdirs (io/file out-dir))
          (spit f (json/write-str e{:key-fn qualified-key :indent true}))
          (verify-write! e f)
          (record-serialize-latency! serialize-start)
          (record-capture-latency! capture-start)
          (println "Captured event evidence:" (:evidence/type e) "hash:" (:evidence/hash e))
          (chain/register-evidence! (normalize-for-chain e))
          e)
        ;; Single-arg convention: reason is actually a pre-built evidence map
       (do (record-capture-latency! capture-start)
           (capture-event-evidence! reason)))))
  ([evidence]
   (let [capture-start (System/nanoTime)]
     (when (map? evidence)
        (let [group-id (:ctx/evidence-group-id (attr/current-attribution))
              evidence (cond-> evidence
                         group-id (assoc :evidence/group-id group-id :evidence/layer :targeted-protocol))
              evidence (inject-chain-fields evidence)
              serialize-start (System/nanoTime)
             out-dir  (str (evcfg/artifact-dir) "/event-evidence")
             filename (evidence-filename evidence)
             f        (io/file out-dir filename)]
          (.mkdirs (io/file out-dir))
          (spit f (json/write-str evidence{:key-fn qualified-key :indent true}))
          (verify-write! evidence f)
          (record-serialize-latency! serialize-start)
          (record-capture-latency! capture-start)
          (println "Captured event evidence:" (:evidence/type evidence) "hash:" (:evidence/hash evidence))
          (chain/register-evidence! (normalize-for-chain evidence))
          evidence)))))

;; ── Evidence Links Index ──────────────────────────────────────────────────────
;;
;; Post-run index that groups generic-trace and targeted-protocol evidence by
;; :evidence/group-id.  Lets researchers navigate from a dispatcher trace event
;; to all targeted evidence captured during that same replay event.

(defn build-evidence-links-index
  "Scan the event-evidence directory and group artifacts by :evidence/group-id.
   Returns a map keyed by group-id, each entry listing both generic-trace and
   targeted-protocol artifacts with their paths and hashes."
  ([]
   (build-evidence-links-index (str (evcfg/artifact-dir) "/event-evidence")))
  ([dir]
   (let [files (filter #(.isFile %) (file-seq (io/file dir)))
          entries (keep (fn [f]
                          (try
                            (let [data (read-evidence-json f)
                                  gid (:evidence/group-id data)]
                              (when gid
                                {:group-id gid
                                 :layer (:evidence/layer data)
                                 :path (.getPath f)
                                 :hash (or (:evidence-hash data)
                                           (:evidence/hash data))
                                 :event-type (:evidence/type data)
                                 :artifact-kind (:artifact-kind data)}))
                            (catch Exception _ nil)))
                      files)]
     (reduce (fn [acc entry]
               (update-in acc [(:group-id entry) :artifacts]
                          (fnil conj [] entry)))
             {} entries))))

(comment
  ;; (build-evidence-links-index "results/test-artifacts/event-evidence")
  )

(defn write-evidence-links-index!
  "Build and persist the evidence links index to evidence-links.json.
   Returns the path to the written file."
  [& [dir]]
  (let [idx (build-evidence-links-index dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-links.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str idx{:key-fn qualified-key :indent true}))
    (println "Wrote evidence links index:" (.getPath f))
    (.getPath f)))

;; ── Evidence Type → Mechanism Mapping ─────────────────────────────────────────
;;
;; Maps each :evidence/type keyword to a higher-level mechanism group.
;; Used by find-evidence, mechanism index, and coverage report.
;; Update this map when new evidence types are added.

(def evidence-type->mechanism
  {:stake-registered :staking
   :stake-withdrawn :staking
   :escrow-created :escrow-lifecycle
   :escrow-released :escrow-lifecycle
   :escrow-refunded :escrow-lifecycle
   :dispute-raised :dispute-resolution
   :dispute-escalated :dispute-resolution
   :resolution-challenged :dispute-resolution
   :fraud-slash-proposed :slashing
   :fraud-slash-appealed :slashing
   :slash-appeal/reversed :slashing
   :slash-appeal/rejected :slashing
   :bond-posted :bonding
   :bond-slashed :bonding
   :bond-returned :bonding
   :yield-accrual :yield
   :settlement-fill :settlement
   :resolver-unfrozen :system
   :resolver-unavailability-changed :system})

(defn mechanism-for-type
  "Look up the mechanism group for an evidence type.
   Accepts keyword or string. Falls back to :uncategorized."
  [etype]
  (let [k (if (keyword? etype) etype (keyword etype))]
    (or (evidence-type->mechanism k) :uncategorized)))

;; ── Query API ─────────────────────────────────────────────────────────────────
;;
;; Researcher-facing discovery API for targeted evidence artifacts.
;; All filters are optional and AND-combined.
;;
;; Usage:
;;   (find-evidence :by-type :escrow-created)
;;   (find-evidence :by-workflow 42 :by-chain-seq {:from 1 :to 10})
;;   (find-evidence :by-group-id "S19-run:0:create_escrow" :include-body? true)

(defn- artifact-summary
  "Produce a lightweight summary of an evidence artifact.
   Does NOT include the full body — researcher must opt in via :include-body?."
  [data path]
  (let [etype (:evidence/type data)]
    (merge
      {:id (some-> (:evidence/chain-self-hash data)
                   (subs 0 16)
                   (str "ev-"))
       :evidence/type etype
       :evidence/mechanism (mechanism-for-type etype)
       :evidence/chain-seq (:evidence/chain-seq data)
       :evidence/group-id (:evidence/group-id data)
       :evidence/layer (:evidence/layer data)
       :evidence/hash (or (:evidence-hash data) (:evidence/hash data))
       :path path}
      ;; Include subject keys when present
      (when-let [sid (:subject/id data)] {:subject/id sid})
      (when-let [stype (:subject/type data)] {:subject/type stype})
      ;; Include any workflow-id from domain payload
      (some (fn [k] (when-let [v (get data k)] {k v}))
            [:escrow/workflow-id :finalize/workflow-id :bond/workflow-id
             :dispute/workflow-id :appeal/slash-id :appeal-resolution/slash-id
             :proposal/workflow-id :unfreeze/resolver
             :challenge/workflow-id :escalation/workflow-id]))))

(defn- matches-filter?
  "Check a single evidence artifact map against one filter."
  [data filter-type filter-val]
  (case filter-type
    :by-type
    (= (name filter-val) (:evidence/type data))

    :by-mechanism
    (= filter-val (mechanism-for-type (keyword (:evidence/type data))))

    :by-workflow
    (let [w (and (number? filter-val) filter-val)]
      (some (fn [k] (= w (get data k)))
            [:escrow/workflow-id :finalize/workflow-id :bond/workflow-id
             :dispute/workflow-id :appeal/slash-id :appeal-resolution/slash-id
             :proposal/workflow-id]))

    :by-group-id
    (= filter-val (:evidence/group-id data))

    :by-chain-seq
    (let [s (:evidence/chain-seq data)]
      (and (number? s)
           (or (nil? (:from filter-val)) (>= s (:from filter-val)))
           (or (nil? (:to filter-val)) (<= s (:to filter-val)))))

    :by-run-id
    (= filter-val (:run/id data))

    :by-scenario-id
    (= filter-val (:scenario/id data))

    true))

(defn find-evidence
  "Search targeted evidence artifacts matching all given filters.
   Filters are AND-combined. Returns a vector of lightweight summaries.
   
   Optional keyword arguments:
   :by-type        — evidence type keyword or string
   :by-mechanism   — mechanism keyword (e.g. :slashing, :escrow-lifecycle)
   :by-workflow    — workflow-id integer
   :by-group-id    — evidence group-id string
   :by-chain-seq   — {:from N :to M} map (inclusive)
   :by-run-id      — run-id string
   :by-scenario-id — scenario-id string
   :include-body?  — boolean, include full parsed artifact body
   :artifact-dir   — override artifact directory path
   
   Examples:
     (find-evidence :by-type :escrow-created)
     (find-evidence :by-workflow 42)
     (find-evidence :by-chain-seq {:from 1 :to 10} :include-body? true)"
  [& {:keys [by-type by-mechanism by-workflow by-group-id by-chain-seq
             by-run-id by-scenario-id include-body? artifact-dir]
      :or {artifact-dir (str (evcfg/artifact-dir) "/event-evidence")
           include-body? false}}]
  (let [filters (concat
                  (when by-type [:by-type by-type])
                  (when by-mechanism [:by-mechanism by-mechanism])
                  (when by-workflow [:by-workflow by-workflow])
                  (when by-group-id [:by-group-id by-group-id])
                  (when by-chain-seq [:by-chain-seq by-chain-seq])
                  (when by-run-id [:by-run-id by-run-id])
                  (when by-scenario-id [:by-scenario-id by-scenario-id]))]
    (if-let [dir (io/file artifact-dir)]
      (if (.isDirectory dir)
        (let [files (filter #(.isFile %) (file-seq dir))]
          (keep (fn [f]
                  (try
                    (let [data (read-evidence-json f)
                          matches (every? (fn [[ft fv]] (matches-filter? data ft fv))
                                          (partition 2 filters))]
                      (when matches
                        (if include-body?
                          (assoc data :path (.getPath f))
                          (artifact-summary data (.getPath f)))))
                    (catch Exception _ nil)))
                files))
        [])
      [])))

;; ── Mechanism Index ───────────────────────────────────────────────────────────
;;
;; Groups evidence artifacts by mechanism for quick researcher discovery.
;; Written post-run alongside evidence-links.json.

(defn build-mechanism-index
  "Group targeted evidence artifacts by mechanism.
   Each group entry includes count and artifact summaries.
   Mechanism is derived from :evidence/mechanism field if present,
   falling back to evidence-type->mechanism mapping."
  [& [dir]]
  (let [files (filter #(.isFile %) (file-seq (io/file (or dir (str (evcfg/artifact-dir) "/event-evidence")))))
        entries (keep (fn [f]
                        (try
                          (let [data (read-evidence-json f)
                                etype (:evidence/type data)
                                mech (or (:evidence/mechanism data)
                                         (mechanism-for-type (keyword etype)))]
                            (when mech
                              {:mechanism mech
                               :summary (artifact-summary data (.getPath f))}))
                          (catch Exception _ nil)))
                      files)]
    (reduce (fn [acc entry]
              (let [mech (:mechanism entry)
                    sum (:summary entry)]
                (-> acc
                    (update-in [mech]
                               (fnil #(update % :artifacts conj sum)
                                     {:mechanism mech
                                      :count 0
                                      :artifacts []}))
                    (update-in [mech :count] (fnil inc 0)))))
            {} entries)))

(defn write-mechanism-index!
  "Build and persist the mechanism index to evidence-mechanisms.json.
   Also registers the index in the chain registry for test-artifacts.json.
   Returns the path to the written file."
  [& [dir]]
  (let [idx (build-mechanism-index dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-mechanisms.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str (sort-by key idx){:key-fn qualified-key :indent true}))
    (println "Wrote mechanism index:" (.getPath f))
    (chain/register-additional-artifact!
      (chain/index-artifact-entry :evidence-mechanisms "evidence-mechanisms.json"
                                  "evidence-index.v1" "DIAGNOSTIC"))
    (.getPath f)))

;; ── Coverage Report ───────────────────────────────────────────────────────────
;;
;; Post-run report that compares generic trace events, targeted evidence,
;; and evidence links to identify coverage gaps.

(defn- read-evidence-links-index
  "Read evidence-links.json from the artifact directory."
  [dir]
  (let [f (io/file dir "evidence-links.json")]
    (when (.exists f)
      (try (json/read-str (slurp f) :key-fn keyword)
           (catch Exception _ nil)))))

(defn- generic-trace-event-count
  "Approximate count of generic trace events from evidence-registry.json.
   Uses :artifact-kind :transition entries in the registry."
  [dir]
  (let [f (io/file dir "evidence-registry.json")]
    (if (.exists f)
      (try
        (let [reg (json/read-str (slurp f) :key-fn keyword)]
          (count (:artifacts reg)))
        (catch Exception _ 0))
      0)))

(defn build-evidence-coverage-report
  "Analyze evidence artifacts in a run directory and produce a coverage report.
   
   The report includes:
   - Total events with generic trace evidence
   - Events with targeted protocol evidence
   - Mechanism counts (number of artifacts per mechanism)
   - Missing group-ids (events with generic trace but no targeted evidence)
   - Warnings about potential gaps
   
   Returns a structured map suitable for JSON serialization."
  [& [dir]]
  (let [artifact-dir (or dir (str (evcfg/artifact-dir)))
        ev-dir (str artifact-dir "/event-evidence")
        generic-count (generic-trace-event-count artifact-dir)
        links (read-evidence-links-index artifact-dir)
        targeted-events (when links (count links))
        group-ids (when links (keys links))
        ;; Count by mechanism using the mechanism index
        mech-idx (build-mechanism-index ev-dir)
        mechanism-counts (into {} (map (fn [[k v]] [k (:count v)]) mech-idx))
        ;; Build targeted type counts
        type-counts (let [files (filter #(.isFile %) (file-seq (io/file ev-dir)))]
                      (reduce (fn [acc f]
                                (try
                                  (let [data (read-evidence-json f)
                                        etype (:evidence/type data)]
                                    (if etype
                                      (update-in acc [(name (keyword etype))] (fnil inc 0))
                                      acc))
                                  (catch Exception _ acc)))
                              {} files))
        total-targeted (reduce + 0 (vals type-counts))]
    {:run-directory artifact-dir
     :generic-trace-event-count generic-count
     :targeted-evidence-count total-targeted
     :targeted-group-count (or targeted-events 0)
     :mechanism-counts mechanism-counts
     :type-counts type-counts
     :links-index-present? (some? links)
     :warnings
     (cond-> []
       (zero? generic-count)
       (conj "No generic trace artifacts found — evidence-registry.json may be missing")
       (zero? total-targeted)
       (conj "No targeted evidence artifacts found — event-evidence directory may be empty")
       (and links (zero? targeted-events))
        (conj "Evidence links index exists but contains no group-ids"))}))

(defn write-evidence-coverage-report!
  "Build and persist the evidence coverage report to evidence-coverage-report.json.
   Also registers the report in the chain registry for test-artifacts.json.
   Returns the path to the written file."
  [& [dir]]
  (let [report (build-evidence-coverage-report dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-coverage-report.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str report{:key-fn qualified-key :indent true}))
    (println "Wrote evidence coverage report:" (.getPath f))
    (chain/register-additional-artifact!
      (chain/index-artifact-entry :evidence-coverage-report "evidence-coverage-report.json"
                                  "evidence-index.v1" "DIAGNOSTIC"))
    (.getPath f)))

