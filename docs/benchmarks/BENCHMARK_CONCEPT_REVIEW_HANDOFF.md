# Benchmark and Concepts Review Handoff

Audience: `gpt.5.4.mini`

Scope: investigate and fix the remaining benchmark/concept integration issues without broad refactors. Keep edits limited to benchmark runner/report/validator/tests unless a helper clearly belongs in an existing concept namespace.

## 1. Fix PRF alias fallback for old evidence

### Symptom

`resolver-sim.benchmark.report/scenario->outcome` still returns `nil` for older evidence rows that only contain `:file`.

Repro:

```clojure
(require '[resolver-sim.benchmark.report :as rpt])

(rpt/scenario->outcome
 "malicious-resolver-verdict-v1"
 [{:file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
   :outcome :pass
   :scenario/evidence-root "root"}])
;; currently nil
```

### Cause

`src/resolver_sim/benchmark/report.clj` resolves public ID to simulator path:

```clojure
(reference-validation-path-by-id "malicious-resolver-verdict-v1")
;; => "scenarios/S25_profit-maximizer-slash-lifecycle.json"
```

But `scenario->outcome` only compares that resolved path against `:simulator/scenario-path`, not `:file`.

Current match order at `src/resolver_sim/benchmark/report.clj`:

```clojure
(or (first (filter #(= scenario-id (:scenario/id %)) results))
    (when scenario-path
      (first (filter #(= scenario-path (:simulator/scenario-path %)) results)))
    (first (filter #(str/includes? (:file %) scenario-id) results)))
```

Old evidence has:

```clojure
{:file "scenarios/S25_profit-maximizer-slash-lifecycle.json"}
```

It does not have:

```clojure
{:simulator/scenario-path "..."}
```

The loose fallback also fails because the file path does not contain the public ID string.

### Required change

In `src/resolver_sim/benchmark/report.clj`, update `scenario->outcome` match order to:

1. exact `:scenario/id`
2. exact resolved manifest path against `:simulator/scenario-path`
3. exact resolved manifest path against `:file`
4. existing loose `:file` substring fallback

Suggested implementation:

```clojure
(let [scenario-path (reference-validation-path-by-id scenario-id)
      match (or (first (filter #(= scenario-id (:scenario/id %)) results))
                (when scenario-path
                  (first (filter #(= scenario-path (:simulator/scenario-path %)) results)))
                (when scenario-path
                  (first (filter #(= scenario-path (:file %)) results)))
                (first (filter #(str/includes? (:file %) scenario-id) results)))]
  ...)
```

Also guard the substring fallback so nil `:file` does not throw:

```clojure
(str/includes? (or (:file %) "") scenario-id)
```

### Tests to add

Extend `test/resolver_sim/benchmark/report_test.clj`:

```clojure
(deftest scenario-outcome-resolves-reference-validation-file-alias
  (is (= {:outcome :pass
          :halt-reason nil
          :file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
          :scenario/evidence-root "root"}
         (rpt/scenario->outcome
          "malicious-resolver-verdict-v1"
          [{:file "scenarios/S25_profit-maximizer-slash-lifecycle.json"
            :outcome :pass
            :halt-reason nil
            :scenario/evidence-root "root"}]))))
```

## 2. Fix benchmark-local concept enrichment shape

### Symptom

Direct PRF benchmark run now populates `:concept/section`, but benchmark-local concepts have nil fields:

```clojure
{:concept/id :robustness/resolver-accountability
 :concept/name nil
 :concept/summary "Whether resolver corruption or bias is economically bounded..."
 :concept/stakeholder-question nil
 :concept/assumptions nil
 :concept/out-of-scope nil}
```

### Cause

`src/resolver_sim/benchmark/runner.clj` loads benchmark-local concepts from:

```text
benchmarks/concepts/protocol-robustness-v0.edn
```

Those maps use benchmark-specific fields:

```clojure
{:concept/id :robustness/resolver-accountability
 :concept/title "Resolver accountability"
 :concept/summary "..."
 :concept/stakeholder-language "..."
 :concept/maps-to {...}
 :concept/why-it-matters "..."}
```

But runner passes them to `resolver-sim.concepts.reporting/enrich-report`, which expects global concept fields:

```clojure
:concept/name
:concept/stakeholder-question
:concept/assumptions
:concept/out-of-scope
:concept/failure-modes
```

That mismatch causes nil values in runtime evidence.

### Required change

Do not pass raw benchmark-local concept maps directly to `concepts-reporting/enrich-report`.

Best minimal fix:

1. Keep `concepts-reporting/enrich-report` for global `data/concepts` concepts.
2. Add a small private formatter in `src/resolver_sim/benchmark/runner.clj` for benchmark-local concepts.
3. Merge global summaries and benchmark-local summaries into the final `:concept/section`.

Suggested helper:

```clojure
(defn- benchmark-local-concept-section
  [concepts]
  (when (seq concepts)
    {:concept/summaries
     (mapv (fn [c]
             {:concept/id (:concept/id c)
              :concept/name (:concept/title c)
              :concept/title (:concept/title c)
              :concept/summary (:concept/summary c)
              :concept/stakeholder-language (:concept/stakeholder-language c)
              :concept/why-it-matters (:concept/why-it-matters c)
              :concept/maps-to (:concept/maps-to c)})
           concepts)}))
```

Suggested merge helper:

```clojure
(defn- merge-concept-sections
  [& sections]
  (let [sections (remove nil? sections)
        summaries (mapcat :concept/summaries sections)
        risks (mapcat :risk-annotations sections)]
    (when (or (seq summaries) (seq risks))
      (cond-> {:concept/summaries (vec summaries)}
        (seq risks) (assoc :risk-annotations (vec risks))))))
```

Then in runner concept enrichment:

```clojure
(let [global-section (when (seq relevant)
                       (:concept/section
                        (concepts-reporting/enrich-report nil relevant)))
      local-section (benchmark-local-concept-section local-relevant)]
  (merge-concept-sections global-section local-section))
```

Do not remove the report path in `resolver-sim.benchmark.report`; that already reads benchmark concepts directly for dimensions.

### Tests to add

Add a focused test in `test/resolver_sim/benchmark/runner_test.clj` or a smaller new test namespace if runner tests are too broad.

Use `with-redefs` for `concepts-registry/load-registry` and avoid executing real scenarios. Test the private helper only if project convention allows `#'resolver-sim.benchmark.runner/...`; otherwise test through a small public runner helper only if one already exists.

Expected assertion:

```clojure
(is (= "Resolver accountability"
       (get-in section [:concept/summaries 0 :concept/name])))
(is (= "Resolver accountability"
       (get-in section [:concept/summaries 0 :concept/title])))
(is (string? (get-in section [:concept/summaries 0 :concept/stakeholder-language])))
(is (map? (get-in section [:concept/summaries 0 :concept/maps-to])))
```

## 3. Strengthen validation for scenario dimensions

### Symptom

`bb benchmarks:validate` passes even when benchmark report dimensions would render nil concept fields.

Concrete current case:

`benchmarks/packs/prf-core/shortfall-allocation-v0.edn` declares:

```clojure
:benchmark/concepts
[:allocation/partial-fill
 :consensus/evidence
 :consensus/finality
 :verifiable-assurance/forensic-confidence]
```

But its scenario dimensions are:

```clojure
:allocation/partial-fill
:allocation/shortfall
:allocation/pro-rata-fairness
```

Only `:allocation/partial-fill` is declared in `:benchmark/concepts`. There are no definitions for `:allocation/shortfall` or `:allocation/pro-rata-fairness` in the benchmark-local concept file or global concept registry.

Report code looks up concept by scenario dimension:

```clojure
(dimension->concept concept-idx dim-id)
```

Missing dimension concepts produce nil values for:

```clojure
:concept/title
:concept/summary
:concept/stakeholder-language
:concept/why-it-matters
:concept/maps-to
```

### Cause

`scripts/benchmarks_validate.clj` validates only `:benchmark/concepts` entries:

```clojure
(doseq [concept-id concept-ids]
  (when-not (get concept-idx concept-id)
    ...))
```

It never validates `(:dimension scenario)` for entries in `:benchmark/scenarios`.

### Required change

In `scripts/benchmarks_validate.clj`, after building `concept-idx`, validate every scenario dimension.

Suggested logic:

```clojure
(doseq [scenario (:benchmark/scenarios benchmark)]
  (let [dimension (:dimension scenario)]
    (when (and dimension (not (get concept-idx dimension)))
      (swap! errors conj
             (str "missing concept definition for scenario dimension "
                  dimension " in " benchmark-path))
      (println "    FAIL missing scenario dimension concept" dimension))))
```

Also validate that dimensions used in `:benchmark/scenarios` are present in `:benchmark/concepts` when the benchmark declares `:benchmark/concepts`:

```clojure
(let [declared-concepts (set (:benchmark/concepts benchmark))]
  (doseq [scenario (:benchmark/scenarios benchmark)]
    (let [dimension (:dimension scenario)]
      (when (and dimension
                 (seq declared-concepts)
                 (not (contains? declared-concepts dimension)))
        ...))))
```

Expected failure messages for current data, unless concepts are added:

```text
missing scenario dimension concept :allocation/shortfall
missing scenario dimension concept :allocation/pro-rata-fairness
scenario dimension :allocation/shortfall is not declared in :benchmark/concepts
scenario dimension :allocation/pro-rata-fairness is not declared in :benchmark/concepts
```

### Data fix options

Choose one:

1. Add two benchmark-local concepts for `:allocation/shortfall` and `:allocation/pro-rata-fairness` to a new benchmark-local concepts file for shortfall allocation.
2. Temporarily remove `:benchmark/scenarios` from `shortfall-allocation-v0` if it is not intended to be report-rendered yet.
3. Add those dimensions to `:benchmark/concepts` and define them in a proper concept source.

Best minimal option: create a new file:

```text
benchmarks/concepts/shortfall-allocation-v0.edn
```

with concepts:

```clojure
:allocation/partial-fill
:allocation/shortfall
:allocation/pro-rata-fairness
```

Then teach the loader/validator to read all files under `benchmarks/concepts/*.edn`.

## 4. Replace hardcoded benchmark-local concept file loading

### Symptom

Both runner and validator assume there is only one benchmark-local concept file:

```text
benchmarks/concepts/protocol-robustness-v0.edn
```

This blocks proper support for `shortfall-allocation-v0` and any future benchmark pack concepts.

### Cause

Runner:

```clojure
(defn- load-benchmark-local-concepts
  []
  (try
    (some-> (slurp "benchmarks/concepts/protocol-robustness-v0.edn")
            edn/read-string
            :concepts)
    (catch Exception _ nil)))
```

Validator:

```clojure
(some-> (read-edn-file "benchmarks/concepts/protocol-robustness-v0.edn")
        :concepts)
```

Both ignore any other future file in `benchmarks/concepts/`.

### Required change

Implement a shared pattern in both places, or extract a small helper namespace if that fits the project style.

Minimal in-place runner helper:

```clojure
(defn- load-benchmark-local-concepts
  []
  (try
    (->> (file-seq (io/file "benchmarks/concepts"))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".edn"))
         (mapcat #(-> % slurp edn/read-string :concepts))
         vec)
    (catch Exception e
      (log/warn! "benchmark/local-concepts-load-failed"
                 {:error (.getMessage e)})
      [])))
```

Validator equivalent:

```clojure
(defn benchmark-concept-files []
  (->> (file-seq (io/file "benchmarks/concepts"))
       (filter #(.isFile %))
       (filter #(clojure.string/ends-with? (.getName %) ".edn"))))

(defn load-benchmark-local-concepts []
  (mapcat #(-> % slurp edn/read-string :concepts)
          (benchmark-concept-files)))
```

Add `clojure.string` require in `scripts/benchmarks_validate.clj` if using `str/ends-with?`.

Update `run-validation` to validate every `benchmarks/concepts/*.edn` file exists/parses, not just `protocol-robustness-v0.edn`.

### Tests/checks

After this change, `bb benchmarks:validate` should fail until the shortfall allocation dimension concepts are fixed, or until validation is scoped only to benchmarks that use report dimensions. Do not hide that failure unless the data decision is explicit.

## 5. Avoid duplicate concept IDs when merging global and local concepts

### Symptom

`runner.clj` currently uses:

```clojure
(vec (distinct (concat relevant local-relevant)))
```

`distinct` compares full maps, not `:concept/id`. If a global and local concept share the same ID but different shape, both can appear in `:concept/summaries`.

### Cause

The merge is sequence-based, not ID-based.

### Required change

Deduplicate by `:concept/id`. Prefer local benchmark concepts over global concepts for benchmark-local namespaces, and prefer global concepts for global namespaces.

Minimal helper:

```clojure
(defn- concepts-by-id
  [concepts]
  (into {} (map (fn [c] [(:concept/id c) c]) concepts)))
```

Then:

```clojure
(let [global-by-id (concepts-by-id relevant)
      local-by-id (concepts-by-id local-relevant)
      all-relevant (vals (merge global-by-id local-by-id))]
  ...)
```

This makes local concepts win on duplicate IDs.

## Required validation commands

Run these after changes:

```bash
bb fmt
clojure -M:test:with-sew -e "(require '[clojure.test :as t] '[resolver-sim.benchmark.report-test] :reload) (t/run-tests 'resolver-sim.benchmark.report-test)"
bb benchmarks:validate
clojure -M:with-sew -e "(require '[resolver-sim.benchmark.runner :as r]) (let [e (r/run-benchmark \"benchmarks/packs/prf-core/protocol-robustness-v0.edn\")] (prn (:metrics e)) (prn (:concept/section e)))"
```

Expected outcomes:

1. Report test includes old-evidence alias fallback and passes.
2. PRF direct run returns `{:total 3 :passed 3}`.
3. PRF `:concept/section` includes non-nil benchmark concept names/titles and stakeholder language.
4. `bb benchmarks:validate` either passes with fixed shortfall concepts or fails with explicit missing dimension concept errors.

