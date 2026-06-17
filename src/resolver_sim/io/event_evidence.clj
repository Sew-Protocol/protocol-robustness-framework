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
          (spit f (json/write-str e {:indent true}))
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
          (spit f (json/write-str evidence {:indent true}))
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
                           (let [data (json/read-str (slurp f) :key-fn keyword)
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
               (update-in acc [(:group-id entry) :artifacts] conj entry))
             {} entries))))

(defn write-evidence-links-index!
  "Build and persist the evidence links index to evidence-links.json.
   Returns the path to the written file."
  [& [dir]]
  (let [idx (build-evidence-links-index dir)
        out-dir (or dir (str (evcfg/artifact-dir)))
        f (io/file out-dir "evidence-links.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str idx {:indent true}))
    (println "Wrote evidence links index:" (.getPath f))
    (.getPath f)))

