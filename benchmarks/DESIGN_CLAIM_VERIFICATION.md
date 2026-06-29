# Design: Claim Verification for Benchmark Packs

## Status: Design — do not implement

This document describes how to wire claim verification into the benchmark
runner so that declared claims (`:benchmark/claims`) are actually checked
and reported. Do not implement until this design is reviewed and approved.

---

## 1. Claim verification maturity levels

Before writing any evaluator, classify the claim by verification level.
This prevents overbuilding and makes the scope of each evaluator explicit.

| Level | Name | What the evaluator checks | Example |
|-------|------|---------------------------|---------|
| 0 | Declared only | Nothing — claim is metadata | `:benchmark/claims` in the manifest, no evaluator exists |
| 1 | Mechanical | Required artifacts, hashes, evidence roots, or result fields exist and are internally consistent | `:scenario-hash-present`, `:evidence-root-present`, `:replay-result-present` |
| 2 | Invariant-backed | Named invariants passed; claim is linked to invariant results | `:no-invariant-errors`, `:slashable-liability-preserved-holds` |
| 3 | Semantic | Domain-specific reasoning over scenario results, world state, metrics, or evidence nodes | `:malicious-verdict-economically-bounded`, `:pull-first-settlement-safety` |

**Initial implementation must support Level 1 only**, with optional
Level 2 for already-existing invariant results (`check-all` post-hoc).
Level 3 claims require separate review before implementation.

---

## 2. Scope: per-scenario vs per-pack

A claim's evaluator receives either a single scenario result or the
full benchmark aggregate. This must be explicit in the claim definition
so the runner knows how to dispatch.

```clojure
:claim/scope :scenario     ;; evaluator runs once per scenario
:claim/scope :benchmark    ;; evaluator runs once for the full pack
```

Examples:

| Claim | Scope | Why |
|-------|-------|-----|
| `:scenario-hash-present` | `:scenario` | Each scenario produces its own evidence root hash |
| `:evidence-root-present` | `:scenario` | Same — one root per replay |
| `:replay-result-present` | `:scenario` | One outcome per scenario |
| `:no-invariant-errors` | `:scenario` | Invariant check is per-scenario |
| `:all-scenarios-pass` | `:benchmark` | Aggregate of all scenario outcomes |
| `:liveness-under-adversarial-load` | `:scenario` | Tested per scenario in the benchmark pack |
| `:malicious-verdict-economically-bounded` | `:scenario` | Tested per scenario |

The runner iterates over scenario results for `:scope :scenario` claims,
and invokes once for `:scope :benchmark` claims.

---

## 3. First claims to implement

**Start with Level 1 mechanical claims.** These are difficult to
misinterpret and establish the evaluator wiring without domain risk.

### Level 1 — scenario scope

```clojure
;; ── src/resolver_sim/benchmark/claims.clj (new file)

(def evaluator-registry
  {:scenario-hash-present
   {:scope :scenario
    :check (fn [ctx]
             (let [h (get-in ctx [:scenario/result :scenario/evidence-root])]
               {:holds? (boolean (and h (re-matches #"[0-9a-f]{64}" h)))
                :violations (when-not h [{:type :missing-evidence-root}])}))}

   :evidence-root-present
   {:scope :scenario
    :check (fn [ctx]
             (let [r (get-in ctx [:scenario/result :scenario/evidence-root])]
               {:holds? (boolean r)
                :violations (when-not r [{:type :missing-evidence-root}])}))}

   :replay-result-present
   {:scope :scenario
    :check (fn [ctx]
             (let [outcome (get-in ctx [:scenario/result :outcome])]
               {:holds? (boolean outcome)
                :violations (when-not outcome [{:type :missing-outcome}])}))}

   :no-invariant-errors
   {:scope :scenario
    :check (fn [ctx]
             (let [failures (get-in ctx [:scenario/result :invariant-fail-count] 0)]
               {:holds? (zero? failures)
                :violations (when (pos? failures)
                              [{:type :invariant-failures
                                :count failures}])}))}})
```

### Level 1 — benchmark scope

```clojure
   :all-scenarios-pass
   {:scope :benchmark
    :check (fn [{:keys [benchmark/results]}]
             (let [passed (count (filter #(= :pass (:outcome %)) results))
                   total (count results)]
               {:holds? (= passed total)
                :violations (when (< passed total)
                              [{:type :not-all-passed
                                :passed passed :total total}])}))}}
```

### Level 2 — invariant-backed (optional in initial implementation)

```clojure
   :slashable-liability-preserved-holds
   {:scope :scenario
    :check (fn [ctx]
             (let [inv (:slashable-liability-preserved
                        (get-in ctx [:scenario/result :invariant-results]))]
               {:holds? (= :pass (:result inv))
                :violations (when (not= :pass (:result inv))
                              [{:type :invariant-failed
                                :invariant-id :slashable-liability-preserved}])}))}}
```

### Level 3 — deferred for separate review

```clojure
   :malicious-verdict-economically-bounded    ;; deferred
   :liveness-under-adversarial-load           ;; deferred
   :pull-first-settlement-safety              ;; deferred
```

---

## 4. Evaluator interface

Each evaluator entry is a map:

```clojure
{<claim-kw>
 {:scope <:scenario | :benchmark>
  :check (fn [context]) -> {:holds? boolean
                            :violations [<map> ...]}}
```

### Context map

| Key | Available for | Contents |
|-----|---------------|----------|
| `:scenario/result` | `:scenario` | One SewAdapter scenario result map |
| `:scenario/world` | `:scenario` | Terminal world state from replay |
| `:scenario/metrics` | `:scenario` | Replay metrics map |
| `:evidence-nodes` | both | Evidence nodes produced during replay |
| `:benchmark/results` | `:benchmark` | All scenario result vectors |
| `:benchmark/manifest` | both | The benchmark manifest map |

---

## 5. Runner integration

In `run-benchmark`, after collecting results and building `inv-summary`:

```clojure
;; runner.clj (conceptual — not production code)
(let [claim-defs benchmark-claims/evaluator-registry
      manifest-claims (:benchmark/claims manifest)]
  (doseq [claim-id manifest-claims]
    (when-let [{:keys [scope check]} (get claim-defs claim-id)]
      (case scope
        :scenario
        (mapv (fn [result]
                (let [ctx {:scenario/result result
                           :scenario/world (:world result)
                           :evidence-nodes […]}
                      {:keys [holds? violations]} (check ctx)]
                  {:claim/id claim-id
                   :claim/outcome (if holds? :pass :fail)
                   :claim/evidence […]})))

        :benchmark
        (let [ctx {:benchmark/results results
                   :benchmark/manifest manifest
                   :evidence-nodes […]}
              {:keys [holds? violations]} (check ctx)]
          [{:claim/id claim-id
            :claim/outcome (if holds? :pass :fail)
            :claim/evidence […]}])))))
```

The result is a flat vector of `{:claim/id :claim/outcome :claim/severity :claim/evidence}`
added to the evidence bundle as `:claim-results`.

---

## 6. Goal

Turn this (current):

```clojure
{:benchmark {:benchmark/claims [:scenario-hash-present …]}
 :metrics {:total 3 :passed 3}
 :claim/status :declared-not-verified}
```

Into this (after):

```clojure
{:benchmark {:benchmark/claims [:scenario-hash-present …]}
 :metrics {:total 3 :passed 3}
 :claim-results
 [{:claim/id :scenario-hash-present
   :claim/outcome :pass
   :claim/severity :low
   :claim/evidence [:scenario/evidence-root]}
  …]
 :claim/status :verified}
```

---

## 7. What exists already

The codebase already has most of the machinery:

| Component | Location | Ready? |
|-----------|----------|--------|
| Claim definition registry | `passive_registries.clj` | ✓ — supports `:evaluation` type `:code-reference` with `:entry` var |
| Evaluator dispatch map | `pro_rata_claims.clj` `evaluator-registry` | ✓ — pattern exists, needs extension |
| Claims engine | `claims/engine.clj` `evaluate-claims` | ✓ — accepts claim specs + evidence nodes + evaluator resolver |
| Benchmark result spec | `BENCHMARK_RESULT_SPEC_V1.md` | ✓ — defines `:claim-results` shape |
| Evidence bundle | `runner.clj` | Missing `:claim-results` — otherwise ready |

Prefer adapting to the existing claims engine. Do not modify it unless the runner integration reveals a clear mismatch.
---

## 8. Files to create/modify (when implemented)

| File | Action |
|------|--------|
| `src/resolver_sim/benchmark/claims.clj` | Create — evaluator registry + Level 1 check fns |
| `src/resolver_sim/benchmark/runner.clj` | Modify — wire claim evaluation into `run-benchmark`, respect `:claim/scope` |
| `src/resolver_sim/benchmark/report.clj` | Modify — thread `:claim-results` through `build-report` |
| `benchmarks/packs/prf-core/protocol-robustness-v0.edn` | Modify — replace claims with Level 1 claims (see section 3) |
| `notebooks/benchmark_protocol_robustness.clj` | Modify — display per-claim results |
| `benchmarks/DESIGN_CLAIM_VERIFICATION.md` | This file — delete when implemented |

---

## 9. Implementation order

| Step | What | Depends on |
|------|------|-----------|
| 1 | Create `benchmark/claims.clj` with 3 Level 1 `:scope :scenario` evaluators | Nothing |
| 2 | Create 1 Level 1 `:scope :benchmark` evaluator (`:all-scenarios-pass`) | Step 1 |
| 3 | Wire scope-dispatched evaluation into `run-benchmark` | Steps 1-2 |
| 4 | Thread `:claim-results` through `build-report` into the evidence bundle | Step 3 |
| 5 | Update protocol-robustness-v0 `:benchmark/claims` to use Level 1 claims | Step 4 |
| 6 | Add Level 2 invariant-backed evaluators (optional) | Steps 1-5 |
| 7 | Update notebook to display claim results | Step 5 |
| 8 | Level 3 semantic evaluators — separate review first | Everything above |

---

## 10. Risk

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Evaluator function logic is wrong | Low (Level 1) | Level 1 checks are mechanical — field exists, hash matches pattern, outcome is non-nil |
| Claim scope confusion | Low | Explicit `:claim/scope` field; runner dispatches on it |
| Evidence-node data not available post-replay | Low | Level 1 checks need only the scenario result map, which the adapter already produces |
| Report builder expects different claim shape | Low | `build-report` passes `:claim-results` through unchanged — shape is defined by the claim evaluators |
