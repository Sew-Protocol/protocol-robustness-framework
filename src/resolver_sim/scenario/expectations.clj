(ns resolver-sim.scenario.expectations
  "Expectation evaluator for CDRS v1.1 scenarios.

   Checks execution-level pass/fail criteria: named invariants, terminal world
   state, and metric assertions. This namespace is pure — no I/O, no DB.

   Depends on resolver-sim.scenario.theory for shared value helpers and
   evaluate-metric-op. The split is intentional: theory evaluation (claim
   falsification) and expectation evaluation (execution correctness) are
   separate concerns that may have different failure semantics."
  (:require [clojure.string :as str]
            [resolver-sim.scenario.theory :as theory]))

;; ---------------------------------------------------------------------------
;; Key-relaxed world lookup
;; ---------------------------------------------------------------------------

(defn- try-parse-int [k]
  (if (string? k)
    (try (Integer/parseInt k) (catch Exception _ nil))
    k))

(defn- get-relaxed
  "Get key k from map m, tolerating integer/string key type mismatch.
   Tries: exact key → integer parse → keyword parse → string of keyword."
  [m k]
  (let [int-k (try-parse-int k)
        kw-k  (when (string? k) (keyword k))]
    (cond
      (contains? m k)                          (get m k)
      (and int-k (contains? m int-k))          (get m int-k)
      (and kw-k (contains? m kw-k))            (get m kw-k)
      (and (keyword? k) (contains? m (name k))) (get m (name k))
      :else                                    (get m k))))

(defn- get-in-relaxed [m path]
  (reduce get-relaxed m path))

(defn- try-number [v]
  (cond
    (number? v) v
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    :else nil))

(defn- normalize-path-segment [seg]
  (cond
    (and (vector? seg) (= 2 (count seg)))
    [(if (keyword? (first seg)) (first seg) (keyword (str (first seg))))
     (try-number (second seg))]

    (and (string? seg) (.contains ^String seg "/"))
    (let [parts (str/split seg #"/")]
      (if (and (= 3 (count parts))
               (= "sew" (first parts))
               (= "escrow" (second parts))
               (re-matches #"\d+" (nth parts 2)))
        [(keyword "sew/escrow") (Long/parseLong (nth parts 2))]
        (mapv keyword parts)))

    (string? seg) (keyword seg)
    :else seg))

(defn- normalize-path [path]
  (mapv normalize-path-segment path))

(defn- metric-within-slack? [actual target slack]
  (let [a (try-number actual)
        t (try-number target)
        s (long (or slack 0))]
    (and a t (<= (Math/abs (- a t)) s))))

(defn- terminal-check-ok? [actual spec]
  (let [op (or (:op spec) :=)]
    (if (:equals spec)
      (if (= op :=)
        (= (theory/normalize-val actual) (theory/normalize-val (:equals spec)))
        (theory/evaluate-metric-op op actual (:equals spec)))
      (theory/evaluate-metric-op op actual (:value spec)))))

;; ---------------------------------------------------------------------------
;; Invariant evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-invariants
  "Check named invariants from :expectations/:invariants against the replay result.

   Lookup strategy:
   1. Per-invariant map: result[:metrics :invariant-results] is a map of
      {inv-kw :fail} for any invariant that violated at least once.
      If the named invariant is present → :fail.
      If it is absent from the map AND violations = 0 → :pass.
      If it is absent AND violations > 0 → conservative fail (another
      invariant fired but this named one is unconfirmed).
   2. Aggregate fallback: if :invariant-violations > 0 and the named
      invariant is not in the per-invariant map, fail conservatively —
      we know something broke but can't confirm which invariant.
   3. Pass: :invariant-violations = 0 means no invariant fired; all
      named invariants pass.

   Returns {:ok? bool :violations [v-map]}"
  [result named-invariants]
  (if (empty? named-invariants)
    {:ok? true :violations []}
    (let [inv-map        (get-in result [:metrics :invariant-results] {})
          agg-violations (get-in result [:metrics :invariant-violations] 0)
          violations     (atom [])]
      (doseq [inv-name named-invariants]
        (let [inv-kw (if (keyword? inv-name) inv-name (keyword (str inv-name)))
              status (cond
                       ;; Named result available — precise
                       (contains? inv-map inv-kw)
                       (get inv-map inv-kw)

                       ;; Not in per-invariant map; use aggregate fallback
                       (pos? agg-violations)
                       :fail

                       :else :pass)]
          (when (= status :fail)
            (swap! violations conj
                   {:type      :invariant-failed
                    :invariant inv-kw
                    :note      (if (contains? inv-map inv-kw)
                                 "per-invariant result: fail"
                                 (str "aggregate fallback: "
                                      agg-violations " violation(s) in run"))}))))
      {:ok? (empty? @violations)
       :violations @violations})))

;; ---------------------------------------------------------------------------
;; Expectations evaluation
;; ---------------------------------------------------------------------------

(defn analyze-expected-outcomes
  "Validate per-step :expected-outcomes against the replay trace.

   Each entry: {:seq n :action \"...\" :expect \"ok\"|\"rejected\"}."
  [scenario trace]
  (let [expected (vec (or (:expected-outcomes scenario) []))
        by-seq   (into {} (map (juxt :seq identity) trace))]
    (if (empty? expected)
      {:ok? true :violations []}
      (let [violations
            (for [{:keys [seq action expect]} expected
                  :let [entry (get by-seq seq)
                        want  (keyword (or expect "ok"))
                        got   (:result entry)]
                  :when (or (nil? entry)
                            (not= (name got) (name want)))]
              {:type     :expected-outcome-mismatch
               :seq      seq
               :action   action
               :expected want
               :actual   got
               :error    (:error entry)})]
        {:ok? (empty? violations) :violations (vec violations)}))))

(defn evaluate-expectations
  "Evaluate execution-level pass/fail criteria from an :expectations block.

   Checks in order:
     0. :invariants — named invariant checks (aggregate fallback if no per-invariant map)
     1. :terminal   — world state at end of trace (path + :equals or :op/:value)
     2. :step-terminal — world snapshot after a given :seq
     3. :metrics    — named metric assertions (op + value, optional :slack)

   Returns {:ok? bool :violations [v-map]}"
  [result expectations]
  (let [metrics    (:metrics result)
        trace      (:trace result)
        last-world (get (last trace) :world)
        violations (atom [])]

    ;; 0. Named invariant checks
    (let [inv-res (evaluate-invariants result (:invariants expectations []))]
      (when-not (:ok? inv-res)
        (doseq [v (:violations inv-res)]
          (swap! violations conj v))))

    ;; 1. Terminal state checks
    (doseq [t (:terminal expectations)]
      (let [path   (normalize-path (:path t))
            actual (get-in-relaxed last-world path)]
        (when-not (terminal-check-ok? actual t)
          (swap! violations conj {:type     :terminal-mismatch
                                  :path     path
                                  :expected (or (:equals t) (:value t))
                                  :op       (:op t :=)
                                  :actual   actual}))))

    ;; 2. Step terminal checks
    (doseq [t (:step-terminal expectations)]
      (let [entry  (first (filter #(= (:seq t) (:seq %)) trace))
            world  (:world entry)
            path   (normalize-path (:path t))
            actual (get-in-relaxed world path)]
        (when-not (terminal-check-ok? actual t)
          (swap! violations conj {:type     :step-terminal-mismatch
                                  :seq      (:seq t)
                                  :path     path
                                  :expected (or (:equals t) (:value t))
                                  :op       (:op t :=)
                                  :actual   actual}))))

    ;; 3. Metric expectations
    (doseq [m (:metrics expectations)]
      (let [actual (get metrics (theory/metric-key (:name m)))
            target (:value m)
            slack  (:slack m)
            ok?    (if slack
                     (metric-within-slack? actual target slack)
                     (theory/evaluate-metric-op (:op m) actual target))]
        (when-not ok?
          (swap! violations conj {:type     :metric-violation
                                  :name     (:name m)
                                  :op       (:op m)
                                  :expected target
                                  :slack    slack
                                  :actual   actual}))))

    {:ok? (empty? @violations)
     :violations @violations}))
