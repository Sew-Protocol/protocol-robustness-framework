# Phase 8: Yield Canonical-Loop Migration

## Objective
Route yield-v1 through `execution/run-simulation-loop` instead of `run-yield-loop`, then remove the temporary `[:simple "yield-v1"]` profile adapter.

## Gap Analysis

### Known differences between `run-yield-loop` and `run-simulation-loop`

| Feature | `run-yield-loop` | `run-simulation-loop` | Impact |
|---------|-----------------|----------------------|--------|
| validate-dt-time-alignment | Called in validate-yield-scenario | Not called | Must add yield-specific validation hook |
| Evidence emission | None | Full (transition, invariant, projection, checkpoint) | Run-simulation-loop evidence is conditional on flags (evidence-mode :none for simple) |
| Temporal rule checks | None | Evaluates temporal; can reject events | temporal-enabled? false in minimal flags => skipped |
| Batch mode | Sequential only | sequential + deterministic-batch | Not needed; yield uses sequential only |
| Alias resolution | None | resolve-id-alias, created-id | Yield's resolve-id-alias is no-op |
| Checkpoints | None | secure-checkpoint-update | Flag-gated (world-checkpoint-policy :omit for simple) |
| Projections | None | AnalysisModule compute-projection | YieldAnalysisModule not implemented |
| Open entities check | None | proto/open-entities -> fail | Yield's open-entities returns [], no-op |
| Expected-error analysis | None | expected-errors-set matching | Flag-gated (evaluate-expectations?) |
| States tracking | None | states map (seq -> snapshot) | run-simulation-loop always does this |
| Diagnostics | None | Accumulated through checkpoints | Flag-gated |
| Metrics profile | :yield-provider hardcoded | :sew-integrated default | Must pass :metrics-profile :yield-provider |

### Protocol implementation gaps

`YieldProviderProtocol` implements `SimulationAdapter` but NOT:
- `EconomicModel` — needed for `metric-vocabulary`, `accum-protocol-metrics`, `classify-event`
- `AnalysisModule` — needed for `compute-projection`, `classify-transition`
- `BatchConflictModel` — needed for `event-conflict-domains`

For the generic path (`run-simulation-loop`), the code checks for these via `satisfies?`:

```clojure
;; In replay-events (line 160):
(if (satisfies? proto/EconomicModel protocol)
  (proto/metric-vocabulary protocol)
  #{})

;; In run-simulation-loop (lines 332-337):
(if (satisfies? proto/AnalysisModule protocol)
  (proto/compute-projection protocol final-world)
  [nil nil])

;; In process-step (line 328):
(if (satisfies? proto/EconomicModel protocol)
  (proto/classify-event protocol event result-kw error-kw)
  #{})
```

All `satisfies?` checks have fallback paths (empty set, nil). So yield can run through the generic loop WITHOUT implementing these protocols — it just won't have projections, event classification, or protocol-specific metrics accumulation.

## Migration Steps

### Step 1: Add missing protocol stubs to YieldProviderProtocol (low risk, optional)

Add EconomicModel to YieldProviderProtocol:

```clojureresolver-sim/protocols/yield.clj
;; In deftype YieldProviderProtocol []

proto/EconomicModel
(metric-vocabulary [_]
  ;; Yield-specific metric keys
  #{:yield/principal :yield/current-value :yield/realized :yield/unrealized
    :yield/gross :yield/haircut :yield/deferred :yield/reclaimed
    :yield/total-held :yield/total-principal :yield/total-realized
    :yield/total-unrealized :yield/total-deferred :yield/total-haircut
    :yield/total-reclaimed :yield/positions-count})

(adversarial-event? [_ _event _agent] false)

(classify-event [_ _event _result-kw _error-kw] #{})

(accum-protocol-metrics [_ metrics _ _ _ _ _ _] metrics)

(summarise-batch [_ outcomes]
  {:n (count outcomes)
   :by-outcome (->> outcomes
                    (group-by :trial/outcome)
                    (map (fn [[k vs]] [k (count vs)]))
                    (into {}))})

(advisory [_ _world _request-type _context]
  {:not-supported true})
```

### Step 2: Add yield-specific validation hook

Inject `validate-dt-time-alignment` into the replay pipeline. Options:

**Option A (preferred):** Add a `:yield-dt-validation?` flag to the replay flags. When true, `replay-events` or `run-simulation-loop` calls `validate-dt-time-alignment` after generic validation.

**Option B:** Override `validate-scenario` with a protocol-specific multimethod. More general but more invasive.

### Step 3: Route yield-v1 through replay-events in the profile adapter

Change `profile_adapter.clj` from:

```clojure
(defmethod run-simple-profile [:simple "yield-v1"]
  [profile protocol scenario replay-opts]
  (yield-replay/replay-yield-events protocol scenario replay-opts))
```

to:

```clojure
(defmethod run-simple-profile [:simple "yield-v1"]
  [profile protocol scenario replay-opts]
  (let [replay-events (requiring-resolve 'resolver-sim.contract-model.replay/replay-events)]
    (replay-events protocol scenario
                   (merge {:minimal true
                           :flags {:metrics-profile :yield-provider}}
                          replay-opts))))
```

### Step 4: Add yield scenarios to parity tests

Extend `replay_simple_parity_test.clj` with yield scenarios comparing:
- `simple-replay` yield path (OLD adapter) vs `replay-events` yield path (NEW routing)
- Outcome, halt-reason, events-processed, trace shape
- Metrics availability (yield-specific keys must survive)

### Step 5: Update Yield adapter removal condition

Once parity tests pass for all yield scenarios, the removal condition changes from:

> "yield validation, metrics, options, invariants and result contract achieve canonical-loop parity"

to:

> "the `[:simple \\"yield-v1\\"]` adapter method is redundant with the default implementation"

### Step 6 (deferred): Remove the adapter

Remove the `[:simple "yield-v1"]` method entirely. The default `run-simple-profile` method will handle yield-v1.

## Rollback plan

If migration causes regressions in yield scenarios:
1. Revert the profile adapter change (Step 3)
2. Keep the protocol stubs (Step 1) and validation hook (Step 2)
3. Add a failure-mode flag: `{:yield-use-canonical-loop? false}` defaulting to adapter path
4. Migrate scenario-by-scenario

## Test commands

```bash
# Before migration
clojure -M:test:with-sew -e '
(require (quote resolver-sim.contract-model.replay-yield-test) :reload)
(require (quote resolver-sim.contract-model.replay-simple-characterization-test) :reload)
(require (quote resolver-sim.contract-model.replay-simple-parity-test) :reload)
(require (quote clojure.test))
(run-tests (quote resolver-sim.contract-model.replay-yield-test))
(run-tests (quote resolver-sim.contract-model.replay-simple-characterization-test))
(run-tests (quote resolver-sim.contract-model.replay-simple-parity-test))
'

# After each step, rerun the same command
```

## Scope

**Include:**
- Adding EconomicModel stubs to YieldProviderProtocol
- Adding yield-dt-validation flag
- Routing yield through replay-events
- Extending parity tests
- Updating adapter removal condition

**Defer:**
- Full EconomicModel implementation with yield-specific metrics accumulation
- AnalysisModule implementation for yield projections
- Alias resolution improvements
- Deletion of `run-yield-loop` (keep as internal implementation detail)
- Deletion of `replay-yield-events` (keep as pure function even if redundant)
