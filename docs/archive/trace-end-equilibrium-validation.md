# Trace-End Mechanism-Property Validation

**Status:** Design — ready for implementation  
**Branch:** `cdrs-v0.2`  
**Relates to:** `docs/CDRS-v1.1-THEORY-SCHEMA.md`, `scenario/theory.clj`, `scenario/expectations.clj`

---

## Summary

CDRS v1.1 theory blocks support `:equilibrium-concept` and `:mechanism-properties` fields but both are currently **RESERVED** — accepted, stored, never evaluated. This document specifies the implementation of a lightweight falsification layer called **trace-end mechanism-property validation** that activates these fields.

**This feature is correctly described as:**

> A lightweight falsification layer that checks whether realised terminal outcomes are
> consistent with claimed economic properties. These are terminal trace proxy validations.
> They falsify obvious violations but do not prove full equilibrium properties.

The output should always say **"trace-consistent with claimed property"**, never **"equilibrium proven"**. A Nash equilibrium requires comparing deviations across many traces; a single replay can only check that no observed attack succeeded and no invariant was violated.

---

## Claim-Strength Taxonomy

Every result carries a `:basis` field that declares the strength of the check:

| `:basis` value | Meaning |
|---|---|
| `:single-trace-terminal-proxy` | Terminal world state only; no deviation comparison |
| `:single-trace-metric-proxy` | Accumulated metrics from one trace |
| `:absent-evidence` | Required evidence fields not present → inconclusive |
| `:not-applicable` | Property logically cannot apply in this scenario context |
| `:multi-trace-required` | Only meaningful across N traces; single-trace always inconclusive |
| `:multi-epoch-required` | Only meaningful across epochs; single-trace always inconclusive |

---

## Phase 0 — Terminal World-State Projection

**File:** `src/resolver_sim/scenario/projection.clj` (new, pure)

Before validators run, produce a **stable minimal projection** of the replay result. Validators receive only this projection — never raw world internals. This gives stable tests, easier docs, and clean TraceEquivalence integration.

### Function signature

```clojure
(defn trace-end-projection
  "Produce a stable, minimal projection of a replay result for use by
   mechanism-property validators.

   Validators must not depend on the full world shape or raw trace structure.
   This projection is the only input they receive.

   Returns a map with three keys: :terminal-world, :metrics, :trace-summary."
  [result])
```

### Output shape

```clojure
{:terminal-world
 {:escrows              <map {id → state-kw}>            ; live-states
  :total-held-by-token  <map {token → amount}>           ; total-held
  :total-fees-by-token  <map {token → amount}>           ; total-fees
  :escrow-amounts       <map {id → amount}>              ; escrow-amounts
  :dispute-resolvers    <map {id → resolver-addr}>       ; dispute-resolvers
  :dispute-levels       <map {id → level-int}>           ; dispute-levels
  :pending-count        <int>                            ; pending-count
  :resolver-stakes      <map>                            ; resolver-stakes
  :terminal?            <bool>                           ; true iff no escrow in :created/:disputed/:pending
  :all-terminal-states  #{:released :refunded :cancelled :timeout}} ; terminal escrow states observed

 :metrics
 {:total-escrows            <int>
  :total-volume             <int>
  :disputes-triggered       <int>
  :resolutions-executed     <int>
  :pending-settlements-executed <int>
  :attack-attempts          <int>
  :attack-successes         <int>
  :invariant-violations     <int>
  :funds-lost               <int>
  :double-settlements       <int>
  ;; payoff tracking — nil when not available
  :coalition-net-profit     <num|nil>   ; populated only by multi-epoch runner
  :negative-payoff-count    <int|nil>}  ; populated only when payoff-ledger present

 :trace-summary
 {:events-count         <int>
  :actors               <set of actor-ids>
  :dispute-count        <int>
  :escalation-levels    <set of ints observed>
  :terminal-time        <block-time of last event>
  :halt-reason          <keyword>}}
```

### Implementation notes

- Extract from `result` as: last trace entry `.world` for terminal state; `result :metrics` for accumulated metrics; `result :trace` count and actors for summary
- `:terminal?` = `(every? #(terminal-state? %) (vals escrows))`; terminal states are `#{:released :refunded :cancelled :timeout}`  
- `:coalition-net-profit` is nil unless present in `result :metrics` (multi-epoch runner populates it)  
- `:negative-payoff-count` is nil unless present (payoff-ledger tracking not yet implemented)

---

## Phase 1 — Validator Registry

**File:** `src/resolver_sim/scenario/equilibrium.clj` (new, pure)

### Validator contract

Every validator is a pure function `[projection] → result-map`.

Each validator declares its **requirements** upfront and returns early with `:not-applicable` or `:inconclusive` when required evidence is absent:

```clojure
;; Validator result shape
{:property   :budget-balance          ; property keyword
 :status     :pass                    ; :pass | :fail | :inconclusive | :not-applicable
 :severity   :hard                    ; :hard | :soft
 :basis      :single-trace-terminal-proxy
 :observed   {:USDC 0}               ; what was actually seen
 :expected   "all total-held-by-token values zero when all escrows terminal"
 :offending  []                       ; populated on fail: ids, tokens, etc.
 :requires   [:terminal-world/total-held-by-token
              :terminal-world/terminal?]}
```

### Per-property validator specification

---

#### `:budget-balance`

**Claim:** No residual protocol-held funds remain after all relevant escrows reach terminal states, excluding explicitly retained fees.

**Requires:** `:terminal-world/total-held-by-token`, `:terminal-world/terminal?`

**Applicability:**  
- `:not-applicable` when `(not terminal?)` — open escrows legitimately hold funds  
- `:not-applicable` when `halt-reason == :open-disputes-at-end` — scenario explicitly allows open disputes  

**Check:**  
For each token: `total-held-by-token[token] == 0`

**Failure evidence:**  
`{:token :USDC :total-held 100 :offending-escrows [:e-12]}` (derive offending escrows from `:escrow-amounts` where `state != terminal`)

**Severity:** `:hard`  
**Basis:** `:single-trace-terminal-proxy`

---

#### `:incentive-compatibility`

**Claim:** No actor obtains higher realised payoff through labelled adversarial action than through honest baseline, when a baseline is present.

**Requires:** `:metrics/attack-successes`, `:metrics/funds-lost`

**Applicability:**  
- `:inconclusive` when `:metrics/attack-attempts == 0` — no adversarial actors in trace; property vacuously holds but cannot be tested  
- `:inconclusive` when no payoff-ledger present (`:metrics/negative-payoff-count == nil`)  

**Check:**  
`attack-successes == 0` AND `funds-lost == 0`

This is a **negative falsification proxy** only: it does not prove compatibility, only that no observed adversarial action succeeded.

**Failure evidence:**  
`{:attack-successes 2 :funds-lost 150 :basis :adversarial-action-succeeded}`

**Severity:** `:hard`  
**Basis:** `:single-trace-metric-proxy`

---

#### `:individual-rationality`

**Claim:** No required honest participant has a negative net payoff, excluding voluntary costs explicitly modelled.

**Requires:** payoff-ledger (`:metrics/negative-payoff-count`)

**Applicability:**  
- `:inconclusive` when `negative-payoff-count == nil` — payoff ledger not tracked; cannot evaluate  
- `:inconclusive` when `total-fees-by-token` is empty and no resolution occurred  

**Check (when data available):**  
`negative-payoff-count == 0`  

**Partial proxy (when full ledger absent):**  
`funds-lost == 0` — does not prove IR but falsifies obvious violations

**Failure evidence:**  
`{:negative-payoff-count 3 :basis :negative-payoff-ledger}` or `{:funds-lost 200 :basis :partial-proxy}`

**Severity:** `:soft` (inconclusive is expected until payoff-ledger is implemented)  
**Basis:** `:single-trace-metric-proxy` or `:absent-evidence`

---

#### `:collusion-resistance`

**Claim:** Labelled coalition does not profit relative to non-collusive baseline.

**Requires:** `:metrics/coalition-net-profit`, coalition actor labels

**Applicability:**  
- `:inconclusive` when `coalition-net-profit == nil` — multi-epoch runner not used; single trace cannot evaluate  
- `:inconclusive` when no actors have `:coalition` label in scenario  

**Check (when data available):**  
`coalition-net-profit <= 0`

**Severity:** `:hard` when evidence present, `:soft` when inconclusive  
**Basis:** `:multi-trace-required` (standard single-trace → `:absent-evidence`)

---

#### `:dominant-strategy-equilibrium`

**Claim:** Honest behavior was a dominant strategy in this trace — the observed outcome is consistent with honest play being optimal regardless of others' strategies.

**Single-trace proxy:**  
`invariant-violations == 0` AND `attack-successes == 0`

**What this checks:** No deviation from honest behavior was profitable *in this trace*. It does **not** verify dominance across all possible opponent strategies.

**Requires:** `:metrics/invariant-violations`, `:metrics/attack-successes`

**Applicability:**  
- `:inconclusive` when no adversarial actors present AND no invariant violations — vacuously consistent, cannot test  
- `:inconclusive` unless deviation/comparison traces are provided alongside this trace  

**Note:** If the scenario includes paired deviation traces (future feature), the check strengthens to a real dominance comparison. For now, single-trace → `:single-trace-metric-proxy`.

**Failure evidence:**  
`{:invariant-violations 1 :attack-successes 0 :basis :invariant-fired}`

**Severity:** `:hard`  
**Basis:** `:single-trace-metric-proxy`

---

#### `:nash-equilibrium`

**Claim:** No profitable unilateral deviation was observed. Consistent with Nash equilibrium in this trace.

**Single-trace proxy:**  
`attack-successes == 0` AND `invariant-violations == 0`

**What this checks:** No actor's unilateral attack succeeded in this single trace. It does **not** verify that no profitable deviation exists; only that no deviation was observed to succeed.

**Requires:** `:metrics/attack-successes`, `:metrics/invariant-violations`

**Note:** Real Nash equilibrium validation requires comparing payoffs across deviation traces. Flag `:basis :single-trace-metric-proxy` in all outputs.

**Failure evidence:**  
`{:attack-successes 1 :offending-events [:evt-7] :basis :attack-succeeded}`

**Severity:** `:hard`  
**Basis:** `:single-trace-metric-proxy`

---

#### `:subgame-perfect-equilibrium`

**Always `:inconclusive`.**

Single-trace replay cannot validate subgame perfection — it requires evaluating optimality at every decision subgame, which needs counterfactual traces. Return early:

```clojure
{:property :subgame-perfect-equilibrium
 :status   :inconclusive
 :severity :soft
 :basis    :multi-trace-required
 :observed nil
 :expected "requires deviation traces at each decision point"
 :offending []}
```

---

#### `:bayesian-nash-equilibrium`

**Always `:inconclusive`.**

Requires population/belief distributions across resolvers. Single trace cannot evaluate. Same early-return pattern as `:subgame-perfect-equilibrium`.

---

### Top-level entry point

```clojure
(defn evaluate-mechanism-properties
  "Check all declared :mechanism-properties against the terminal projection.
   Returns a map of {property-kw → result-map}."
  [properties projection])

(defn evaluate-equilibrium-concepts
  "Check all declared :equilibrium-concept values against the terminal projection.
   Returns a map of {concept-kw → result-map}."
  [concepts projection])

(defn evaluate-equilibrium
  "Top-level entry: build projection, run all declared validators.
   Called by scenario.theory/evaluate-theory when theory block contains
   :mechanism-properties or :equilibrium-concept.

   Returns:
   {:mechanism-results  {property-kw → result-map}
    :mechanism-status   :pass | :fail | :inconclusive | :not-applicable | :not-checked
    :equilibrium-results {concept-kw → result-map}
    :equilibrium-status :pass | :fail | :inconclusive | :not-applicable | :not-checked}"
  [theory result])
```

**Status roll-up rules:**
- `:fail` if any hard validator returned `:fail`  
- `:inconclusive` if no `:fail` but any `:inconclusive` or `:not-applicable`  
- `:pass` only if all validators returned `:pass`  
- `:not-checked` if no properties/concepts declared  

---

## Phase 2 — Extend `evaluate-theory`

**File:** `src/resolver_sim/scenario/theory.clj`

Call `equilibrium/evaluate-equilibrium` when `:equilibrium-concept` or `:mechanism-properties` are present in the theory block. Merge result into the returned map.

### Changes

1. Add require: `[resolver-sim.scenario.equilibrium :as equilibrium]`  
2. In `evaluate-theory`, after existing falsification logic:

```clojure
(let [eq-result (when (or (:equilibrium-concept theory) (:mechanism-properties theory))
                  (equilibrium/evaluate-equilibrium theory result))]
  (merge existing-result
         (when eq-result
           {:mechanism-results  (:mechanism-results eq-result)
            :mechanism-status   (:mechanism-status eq-result)
            :equilibrium-results (:equilibrium-results eq-result)
            :equilibrium-status  (:equilibrium-status eq-result)})))
```

3. **Backward compatibility:** `:status` and `:falsified?` remain unchanged. New keys are additive only.

4. Update field table comment (lines 90–107) to mark `:equilibrium-concept` and `:mechanism-properties` as **ACTIVE**.

---

## Phase 3 — Wire into `sim/fixtures.clj`

**File:** `src/resolver_sim/sim/fixtures.clj`

Extend the `theory-ok?` predicate to check mechanism and equilibrium status:

```clojure
(defn theory-ok?
  "A scenario's theory block is acceptable when:
   - No theory block present (:not-evaluated)
   - Claim was not falsified (:not-falsified)
   - Claim was falsified AND scenario purpose is :theory-falsification
   - :inconclusive is a soft warning, not hard failure
   - Mechanism property :fail → hard failure
   - Equilibrium concept :fail → hard failure
   - Mechanism/equilibrium :inconclusive → soft warning (pass with note)"
  [result])
```

**Severity logic:**
- `:mechanism-status :fail` → `false` (hard failure)  
- `:equilibrium-status :fail` → `false` (hard failure)  
- `:mechanism-status :inconclusive` → `true` (pass, but include in warnings)  
- `:equilibrium-status :inconclusive` → `true` (pass)  

**Strictness override (future):** Scenarios may declare `:require-conclusive? true` in their theory block to fail on `:inconclusive`. Not in scope for this implementation; note in TODO.

---

## Phase 4 — Test Scenarios

Add to `data/fixtures/` as a new suite or extend existing suites. Need **10 scenarios** covering:

| # | Type | Property | Expected result |
|---|------|----------|-----------------|
| EQ-P1 | Positive | `:budget-balance` | `:pass` — happy-path trace, all escrows released, total-held = 0 |
| EQ-P2 | Positive | `:incentive-compatibility` | `:pass` — dispute resolved, no attacks, no invariant violations |
| EQ-P3 | Positive | `:dominant-strategy-equilibrium` | `:pass` — honest resolver, no adversarial actors |
| EQ-N1 | Negative | `:budget-balance` | `:fail` — synthetic: terminal state but total-held ≠ 0 |
| EQ-N2 | Negative | `:incentive-compatibility` | `:fail` — attack-successes > 0 (base: S20 adversarial scenario) |
| EQ-N3 | Negative | `:nash-equilibrium` | `:fail` — attack succeeded in trace |
| EQ-I1 | Inconclusive | `:individual-rationality` | `:inconclusive` — property declared but no payoff-ledger present |
| EQ-I2 | Inconclusive | `:collusion-resistance` | `:inconclusive` — no coalition-net-profit metric |
| EQ-I3 | Inconclusive | `:subgame-perfect-equilibrium` | `:inconclusive` — always, single-trace |
| EQ-NA1 | Not-applicable | `:budget-balance` | `:not-applicable` — allow-open-disputes? = true |

### Scenario structure

Each scenario is a CDRS v1.1 EDN file with a `:theory` block including `:mechanism-properties` or `:equilibrium-concept`:

```clojure
{:schema-version "1.1"
 :id     :scenarios/eq-p1
 :title  "Budget Balance — Happy Path"
 :purpose :regression
 :agents [...]
 :events [...]
 :expectations {:terminal [{:path [:live-states 1 :status] :equals :released}]}
 :theory {:claim-id    :claims/budget-balance-holds
          :claim       "All escrowed funds are accounted for at trace end"
          :claim-strength :single-trace-falsification
          :assumptions [:no-open-escrows-at-end]
          :mechanism-properties [:budget-balance]
          :falsifies-if []}}  ; no falsification condition; mechanism-only check
```

### Suite registration

Register in `data/fixtures/suites/equilibrium-validation.edn`:

```clojure
{:suite/id   :suites/equilibrium-validation
 :suite/name "Trace-End Mechanism-Property Validation"
 :traces     [:scenarios/eq-p1 :scenarios/eq-p2 :scenarios/eq-p3
              :scenarios/eq-n1 :scenarios/eq-n2 :scenarios/eq-n3
              :scenarios/eq-i1 :scenarios/eq-i2 :scenarios/eq-i3
              :scenarios/eq-na1]}
```

---

## Phase 5 — TraceEquivalence Alignment

**File:** `test/foundry/TraceEquivalence.t.sol` (sew-protocol repo)

The CDRS v0.2 Solidity harness currently checks state-bucket and semantic fields per trace event. Phase 5 adds **end-state projection comparison** — both Clojure and Solidity derive the same minimal end-state shape and compare it.

### Shared end-state projection shape

Define this as the canonical bridge between Clojure `trace-end-projection` and Solidity:

```json
{
  "state-bucket": "resolved",
  "resolution-outcome": "release",
  "escalation-level": 0,
  "total-held-by-token": {"USDC": 0},
  "total-fees-by-token": {"USDC": 15},
  "pending-count": 0,
  "terminal": true,
  "participants": ["buyer", "seller", "l1resolver"],
  "mechanism-check-results": {
    "budget-balance": "pass",
    "incentive-compatibility": "pass"
  }
}
```

### Solidity changes needed

1. Add `_projectTraceEnd(TraceEnd memory expected)` helper in `TraceEquivalence.t.sol`
2. Assert projected fields against observed contract state at test end  
3. Compare `mechanism-check-results` as string assertions (pass/fail/inconclusive)  
4. Add test `test_traceEnd_BudgetBalance_Pass` and `test_traceEnd_BudgetBalance_Fail` as the minimum viable coverage  

**Note:** Full Phase 5 implementation may require adding projection fields to v0.2 fixture format and regenerating trace fixtures. This should be scoped separately. Add TODO in implementation.

---

## Phase 6 — Documentation Update

**File:** `docs/CDRS-v1.1-THEORY-SCHEMA.md`

1. Change `:equilibrium-concept` entry from `Evaluation: Reserved` to `Evaluation: ACTIVE — terminal trace proxy validation`
2. Change `:mechanism-properties` entry from `Evaluation: Reserved` to `Evaluation: ACTIVE — terminal trace proxy validation`
3. Add section: **Trace-End Mechanism-Property Validation**  
4. Add property reference table:

| Property | Required evidence | Proxy condition | Basis | Inconclusive when |
|----------|------------------|-----------------|-------|-------------------|
| `:budget-balance` | `total-held-by-token`, `terminal?` | all token balances = 0 at terminal | single-trace-terminal-proxy | non-terminal escrows remain |
| `:incentive-compatibility` | `attack-successes`, `funds-lost` | no adversarial success, no funds lost | single-trace-metric-proxy | no adversarial actors in trace |
| `:individual-rationality` | payoff-ledger (not yet tracked) | no negative payoff observed | absent-evidence | payoff-ledger not available |
| `:collusion-resistance` | `coalition-net-profit` (multi-epoch) | coalition profit ≤ 0 | multi-trace-required | single-trace (always) |
| `:dominant-strategy-equilibrium` | `invariant-violations`, `attack-successes` | no violations, no successful attacks | single-trace-metric-proxy | no adversarial actors |
| `:nash-equilibrium` | `attack-successes`, `invariant-violations` | no successful unilateral deviation | single-trace-metric-proxy | no adversarial actors |
| `:subgame-perfect-equilibrium` | deviation traces (not yet supported) | — | multi-trace-required | always inconclusive |
| `:bayesian-nash-equilibrium` | population data (not yet supported) | — | multi-epoch-required | always inconclusive |

5. Add warning box:

> **Important:** These are proxy falsification checks, not equilibrium proofs.
> A `:pass` result means the realised trace is consistent with the claimed property.
> It does not prove the property holds in general. For properties that require
> deviation traces or multi-epoch data, the result will be `:inconclusive`.

---

## Layering Rules

```
scenario/projection.clj  — may import: nothing outside clojure.core
                           must not import: sim/*, db/*, io/*, protocols/*

scenario/equilibrium.clj — may import: scenario/projection, scenario/theory (helpers only)
                           must not import: sim/*, db/*, io/*

scenario/theory.clj       — may import: scenario/equilibrium (for evaluate-equilibrium call)
                           must not import: sim/*, db/*, io/*
```

The projection layer is intentionally protocol-agnostic. It reads only the fields already present in the `world-snapshot` output and the accumulated metrics map.

---

## Files to Create/Modify

| File | Action |
|------|--------|
| `src/resolver_sim/scenario/projection.clj` | **Create** — trace-end projection |
| `src/resolver_sim/scenario/equilibrium.clj` | **Create** — validator registry |
| `src/resolver_sim/scenario/theory.clj` | **Modify** — call evaluate-equilibrium, extend output |
| `src/resolver_sim/sim/fixtures.clj` | **Modify** — extend theory-ok? |
| `data/fixtures/scenarios/eq-*.edn` | **Create** — 10 test scenarios (3 positive, 3 negative, 3 inconclusive, 1 not-applicable) |
| `data/fixtures/suites/equilibrium-validation.edn` | **Create** — suite registration |
| `docs/CDRS-v1.1-THEORY-SCHEMA.md` | **Modify** — activate fields, add proxy table |
| `test/foundry/TraceEquivalence.t.sol` (sew-protocol) | **Modify** — Phase 5 projection alignment |

---

## Implementation Order

1. `projection.clj` — standalone; no deps on other new code  
2. `equilibrium.clj` — depends on projection  
3. `theory.clj` changes — depends on equilibrium  
4. `fixtures.clj` changes — depends on theory  
5. Scenario fixtures + suite — depends on fixtures wiring to test  
6. Docs — can be done at any point after Phase 3  
7. TraceEquivalence.t.sol — separate repo; can be done after Clojure side validates  

**Run after each phase:**  
`clojure -M:run -- --invariants` (S01–S41, ~1s)  
`./scripts/test.sh`

---

## Open Questions (resolved)

1. **`:budget-balance` with open escrows?**  
   → `:not-applicable` when `allow-open-disputes? == true` or `halt-reason == :open-disputes-at-end`.

2. **`:collusion-resistance` on single trace?**  
   → Always `:inconclusive :basis :multi-trace-required`. Document as such.

3. **`:subgame-perfect-equilibrium` on single trace?**  
   → Always `:inconclusive`. Return early immediately. No need to check metrics.

4. **Should mechanism violations block suite pass?**  
   → Yes, `:hard` severity. Same as expectations failure. `:inconclusive` is a soft warning.

5. **`falsifies-if: []` — is an empty vector allowed?**  
   → Check `validate-scenario`: currently requires non-empty vector. Either relax validation for mechanism-only theory blocks, or require at least one `falsifies-if` condition. **Decision:** allow `falsifies-if` to be absent or empty when `:mechanism-properties` or `:equilibrium-concept` are declared. Update `validate-scenario` accordingly.

---

## What This Is Not

- Not a proof of protocol correctness  
- Not a full game-theoretic equilibrium verifier  
- Not a substitute for multi-epoch simulation (Phases J, AI) for equilibrium evidence  
- Not a replacement for `:falsifies-if` conditions (those remain the primary falsification mechanism)  

This is a lightweight, honest, and useful falsification layer for single-trace terminal-state consistency.
