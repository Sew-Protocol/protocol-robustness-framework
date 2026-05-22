# CDRS v1.1 Theory Schema Documentation

## Overview

   The `:theory` block in CDRS v1.1 scenarios expresses a falsifiable claim about the protocol and specifies conditions that would refute it.

**Schema version:** 1.1  
**Evaluator:** `resolver-sim.scenario.theory/evaluate-theory`  
**Validator:** `resolver-sim.contract-model.replay/validate-scenario`  

---

## Field Inventory

### Mandatory Fields (Parsed & Evaluated)

These fields are required when a `:theory` block is present. All are parsed and actively used by the evaluator.

#### `:claim-id` → keyword

Unique identifier for this claim.

- **Type:** keyword
- **Required:** yes (when `:theory` present)
- **Example:** `:claims/collusion-negative-ev`
- **Evaluation:** Included in evidence output; used as claim identifier in results

#### `:assumptions` → vector of keywords

Vector of assumption identifiers that this claim depends on.

- **Type:** `[keyword ...]` (may be empty `[]`)
- **Required:** yes (when `:theory` present)
- **Example:** `[:slashing-enforced :fraud-detection-rate-at-least-0.25 :positive-identity-reset-cost]`
- **Evaluation:** Recorded in output; not programmatically enforced (used for documentation and future constraint checking)
- **Design:** Assumptions make the scope of the claim explicit — if any assumption is violated, the claim's failure may not refute the protocol design.

#### `:falsifies-if` → vector of metric conditions

Non-empty vector of conditions; the claim is falsified if **any** condition is met.

- **Type:** `[{:metric kw :op kw :value num} ...]` (non-empty)
- **Required:** yes (when `:theory` present)
- **Example:**
  ```clojure
  [{:metric :coalition/net-profit :op > :value 0}
   {:metric :attack-successes :op > :value 0}]
  ```
- **Operators:** `:=`, `:<`, `:>`, `:<=`, `:>=`, `:not=`
- **Evaluation:** For each condition, the evaluator:
  1. Retrieves the metric value from the replay result
  2. Applies the operator (numeric-aware)
  3. If **any** condition is true, the claim is `:falsified`
  4. If **all** are false, the claim is `:not-falsified`
  5. If **all** metrics are nil (untracked), the claim is `:inconclusive`

---

### Metadata Fields (Recorded, Not Evaluated)

These fields are part of the schema and are passed through in output, but do not drive evaluation logic. They exist for documentation and future extensions.

#### `:claim` → string (optional)

Human-readable description of the claim.

- **Type:** string
- **Required:** no
- **Example:** `"Honest buyers can successfully release funds to sellers."`
- **Evaluation:** Recorded in output; not used by evaluator

#### `:game-class` → keyword (optional)

Classification of the game-theoretic model.

- **Type:** keyword
- **Required:** no
- **Examples:** `:repeated-stochastic-game`, `:sealed-bid-auction`, `:all-pay-auction`
- **Evaluation:** Reserved for future game-theoretic analysis; not used by current evaluator
- **Design note:** This field drives equilibrium proxy validation — see `:equilibrium-concept` below.

#### `:equilibrium-concept` → vector of keywords (optional)

Expected equilibrium concept(s) for the mechanism.

- **Type:** `[keyword ...]`
- **Required:** no
- **Examples:** `[:subgame-perfect-equilibrium]`, `[:dominant-strategy-equilibrium :bayesian-nash-equilibrium]`
- **Evaluation:** **ACTIVE** — evaluated by `scenario/equilibrium.clj` as terminal-state proxy checks. See [Equilibrium Proxy Validation](#equilibrium-proxy-validation) below.

#### `:equilibrium-claim-tier` → keyword (optional)

Declares the minimum evidence tier expected for equilibrium-concept claims.

- **Type:** keyword
- **Required:** no (defaults to `:proxy`)
- **Allowed values:** `:proxy`, `:deviation-tested`, `:population-tested`
- **Evaluation:** **ACTIVE** — consumed by `scenario/equilibrium.clj` to enforce evidence-policy gating for selected concepts.
  - For `:deviation-tested` / `:population-tested`, concepts `:dominant-strategy-equilibrium` and `:nash-equilibrium` require `projection[:deviation-bundle :meets-minimum?] = true`; otherwise result is `:inconclusive` with basis `:multi-trace-required`.

#### `:mechanism-properties` → vector of keywords (optional)

Properties the mechanism is claimed to satisfy.

- **Type:** `[keyword ...]`
- **Required:** no
- **Examples:** `[:incentive-compatibility :budget-balance :individual-rationality]`
- **Evaluation:** **ACTIVE** — evaluated by `scenario/equilibrium.clj` as terminal-state proxy checks. See [Equilibrium Proxy Validation](#equilibrium-proxy-validation) below.

#### `:threat-model` → vector of objects (optional)

Descriptions of threat actors and capabilities assumed or excluded.

- **Type:** `[{:actor kw :capability str :bounded-by [kw ...]} ...]`
- **Required:** no
- **Example:**
  ```clojure
  [{:actor :collusive-resolver-ring
    :capability "Can coordinate across multiple identities"
    :bounded-by [:slashing-detection :identity-reset-cost]}]
  ```
- **Evaluation:** Recorded for documentation; not used by current evaluator

---

## Field Dependencies by Purpose

### `:purpose :regression`

Regression scenarios test basic protocol correctness and should not include a `:theory` block.

**Valid structure:**
```clojure
{:schema-version "1.1"
 :id :scenarios/s01
 :title "Happy Path"
 :purpose :regression
 :agents [...]
 :events [...]
 :expectations {:terminal [...] :metrics [...]}}
```

**Validation:** No `:theory` block required or expected.

---

### `:purpose :adversarial-robustness`

Adversarial scenarios test robustness against attack strategies. They may use `:theory` OR comprehensive `:expectations`.

**Valid structure 1 — with theory:**
```clojure
{:schema-version "1.1"
 :id :scenarios/s20
 :title "Same-Block Ordering Attack"
 :purpose :adversarial-robustness
 :agents [...]
 :events [...]
 :expectations {:terminal [...] :metrics [...]}
 :theory {:claim-id :claims/atomic-lifecycle
          :assumptions [:transactions-sequential-in-block]
          :falsifies-if [{:metric :double-settlements :op > :value 0}]}}
```

**Valid structure 2 — with comprehensive expectations:**
```clojure
{:schema-version "1.1"
 :id :scenarios/s21
 :title "Ordering Edge Case"
 :purpose :adversarial-robustness
 :agents [...]
 :events [...]
 :expectations {:invariants [:conservation-of-funds]
                :terminal [{:path [:live-states 0 :status] :equals :released}]
                :metrics [{:name :reverts :op := :value 0}]}}
```

**Validation:** At least one of `:theory` or non-trivial `:expectations` is required.

---

### `:purpose :theory-falsification`

Theory-falsification scenarios are designed as exploit replays or edge case demonstrations. They **must** include a `:theory` block and are expected to falsify claims (which is a **success** outcome for these scenarios).

**Valid structure:**
```clojure
{:schema-version "1.1"
 :id :scenarios/s30
 :title "Collusive Resolver Exploit"
 :purpose :theory-falsification
 :threat-tags [:collusive-resolvers :sybil-reentry]
 :agents [...]
 :events [...]
 :expectations {:metrics [{:name :attack-successes :op >= :value 1}]}
 :theory {:claim-id :claims/collusion-negative-ev
          :game-class :repeated-stochastic-game
          :mechanism-properties [:collusion-resistance]
          :assumptions [:slashing-enforced
                        :fraud-detection-rate-at-least-0.25
                        :positive-identity-reset-cost]
          :falsifies-if [{:metric :coalition/net-profit :op > :value 0}]}
 :payoff-model {:tracked [:coalition/net-profit :honest/relative-equity]
                :costs {:slashing true :gas true}}}
```

**Validation:**
- `:theory` block **required**
- `:claim-id` required and validated
- `:assumptions` required (may be empty)
- `:falsifies-if` non-empty

**Expected outcome:** `✓ Theory: Claim falsified` (this is **correct** for `:theory-falsification` purpose)

---

## Theory Evaluation Result Format

The evaluator returns:

```clojure
{:status    kw                ;; :not-evaluated | :not-falsified | :falsified | :inconclusive
 :falsified? bool             ;; = (= status :falsified)
 :evidence  [evidence-maps]}  ;; only populated when :falsified
```

### Status Values

| Status | Meaning | Triggers |
|---|---|---|
| `:not-evaluated` | No `:theory` block provided | Input: `nil` theory |
| `:not-falsified` | Claim held; no falsification condition triggered | All `:falsifies-if` conditions = false |
| `:falsified` | At least one falsification condition was met | Any `:falsifies-if` condition = true |
| `:inconclusive` | Cannot evaluate; none of the required metrics were tracked | All `:falsifies-if` metrics = nil |

### Evidence Map

When a claim is falsified, each triggered condition is recorded:

```clojure
{:metric   kw         ;; from :falsifies-if/:metric
 :op       kw         ;; from :falsifies-if/:op
 :value    num        ;; from :falsifies-if/:value (expected threshold)
 :actual   num|nil}   ;; actual metric value from replay
```

---

## Numeric Comparison Rules

All metric comparisons are **numeric-aware**:

- If both sides are numbers (Long, Integer, Double), use `==` (JVM numeric equality)
- Otherwise use `=` (structural equality)

Examples:
```clojure
;; These all evaluate to true
(evaluate-metric-op :=  1    1.0)     ;; == for numbers
(evaluate-metric-op :=  1L   1)       ;; == for numbers
(evaluate-metric-op :>  1000 999)     ;; > for numbers

;; These evaluate correctly
(evaluate-metric-op :=  :released :released)  ;; = for keywords
(evaluate-metric-op :!= "foo"      "bar")     ;; = for strings
```

---

## Assumption Vocabulary

Assumptions are typically written as keywords in kebab-case. Common assumption families:

### Detection Assumptions
- `:fraud-detection-rate-at-least-N`
- `:slashing-detection-probability-above-N`
- `:oversight-is-active`

### Protocol Enforcement Assumptions
- `:slashing-enforced`
- `:identity-reset-cost-positive`
- `:timelock-integrity`

### Game-Theoretic Assumptions
- `:bounded-adversary-capital`
- `:rational-actors`
- `:no-collusion-enforcement`

### Environment Assumptions
- `:mempool-is-fair`
- `:transactions-sequential-in-block`
- `:token-is-standard-erc20`

---

## Future Extensions

The following fields are reserved for future use and should be accepted but not evaluated:

1. **Per-invariant result maps** — When the replay engine tracks individual invariant pass/fail status:
   ```clojure
   :invariant-results {:conservation-of-funds :pass
                       :atomic-settlement    :fail}
   ```

2. **Equilibrium convergence analysis** — When multi-epoch results are available:
   ```clojure
   :convergence {:check :dominant-strategy-eq
                 :tolerance 0.05}
   ```

3. **Constraint satisfaction** — For assumption-driven evaluation:
   ```clojure
   :constraint-checks
   [{:assumption :slashing-enforced
     :check "slashing-rate >= 0.1"
     :constraint-satisfied? true}]
   ```

---

## Validation Examples

### ✓ Valid: Minimal regression (no theory)
```clojure
{:schema-version "1.1"
 :id :scenarios/s01
 :title "Happy Path"
 :purpose :regression
 :agents [{:id "buyer" :address "0x1"}]
 :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"}]
 :expectations {:terminal [{:path [:status] :equals :released}]}}
```

**Result:** ✓ PASS — Regression scenarios do not require theory.

### ✓ Valid: Theory-falsification with complete metadata
```clojure
{:schema-version "1.1"
 :id :scenarios/exploit-001
 :title "Collusion Ring Profit Test"
 :purpose :theory-falsification
 :agents [...]
 :events [...]
 :theory {:claim-id :claims/collusion-negative-ev
          :claim "Collusive resolvers cannot make positive profit"
          :game-class :repeated-stochastic-game
          :equilibrium-concept [:subgame-perfect-equilibrium]
          :mechanism-properties [:collusion-resistance]
          :threat-model [{:actor :resolver-ring :capability "coordinate identities"}]
          :assumptions [:slashing-enforced :fraud-detection-rate-at-least-0.25]
          :falsifies-if [{:metric :coalition/net-profit :op > :value 0}]}
 :expectations {:metrics [{:name :coalition/net-profit :op >= :value 0}]}}
```

**Result:** ✓ PASS — All mandatory fields present, metadata complete.

### ✗ Invalid: Theory-falsification without theory block
```clojure
{:schema-version "1.1"
 :id :scenarios/bad-001
 :purpose :theory-falsification
 :agents [...]
 :events [...]
 :expectations {...}}
```

**Error:** `{:error :theory-required :detail "purpose :theory-falsification requires a :theory block"}`

### ✗ Invalid: Theory block missing :claim-id
```clojure
{:schema-version "1.1"
 :id :scenarios/bad-002
 :purpose :theory-falsification
 :theory {:assumptions [] :falsifies-if [{:metric :x :op > :value 0}]}}
```

**Error:** `{:error :theory-missing-claim-id :detail ":theory must include a :claim-id"}`

### ✗ Invalid: Empty falsifies-if
```clojure
{:schema-version "1.1"
 :purpose :theory-falsification
 :theory {:claim-id :c/x :assumptions [] :falsifies-if []}}
```

**Error:** `{:error :theory-missing-falsifies-if :detail ":theory must include a non-empty :falsifies-if vector"}`

---

## Metric Registry

> **Scope: deterministic trace metrics only.**
> This registry covers metrics the replay engine can compute from a single scenario execution.
> Population-level metrics (e.g. `:coalition/net-profit`, `:malice-mean-profit`, `:dominance-ratio`)
> belong in a separate future registry — `resolver-sim.sim.multi-epoch/known-metrics`.
> Do not mix them. A scenario referencing a population metric in `falsifies-if` would produce
> silent `:inconclusive` results because the replay engine can never compute it.

All metrics usable in `:expectations/:metrics` and `:theory/:falsifies-if` must be declared in `replay/known-metrics`.

### Live metrics (computed during replay)

| Metric | Description |
|---|---|
| `:total-escrows` | Count of accepted `create_escrow` calls |
| `:total-volume` | Sum of `:amount` params for accepted `create_escrow` |
| `:disputes-triggered` | Count of accepted `raise_dispute` calls |
| `:resolutions-executed` | Count of accepted `execute_resolution` calls |
| `:pending-settlements-executed` | Count of accepted `execute_pending_settlement` calls |
| `:attack-attempts` | Adversarial events (per-event `adversarial?` or agent `type: "attacker"`) |
| `:attack-successes` | Adversarial events that were accepted |
| `:rejected-attacks` | Adversarial events that were rejected |
| `:reverts` | All rejected events (blunt aggregate; use `rejected-attacks` for adversarial scope) |
| `:invariant-violations` | Aggregate count of invariant failures |
| `:double-settlements` | Accepted settlement when a prior resolution already executed |
| `:invalid-state-transitions` | Rejected events with a state-related error code (not auth/param) |
| `:funds-lost` | Decrease in `total-held` caused by accepted adversarial actions (summed across tokens) |

**`:funds-lost` semantics:** Measured as `max(0, total-held-before − total-held-after)` for each accepted adversarial event. Does not fire for honest events. For `same-block-ordering` (all events honest) this is always 0. For `governance-decay-exploit` (attacker agent) this equals the escrow's `amount-after-fee` at the point funds leave the system.

### Non-numeric internal metrics

| Key | Description |
|---|---|
| `:invariant-results` | Map of `{inv-kw :fail}` for named invariant failures. Not evaluatable via metric operators; used only by `evaluate-invariants`. |

---

## Implementation Reference

### Parser Location
- **Validator:** `resolver-sim.contract-model.replay/validate-scenario`
- **Evaluator:** `resolver-sim.scenario.theory/evaluate-theory`

### Active Field Handlers
| Field | Handler | Code Location |
|---|---|---|
| `:falsifies-if` | `evaluate-theory` | theory.clj:102–111 |
| `:claim-id` | evidence builder | theory.clj:103 |
| `:assumptions` | passed through | theory.clj (not parsed) |

### Ignored Fields (Accepted but not evaluated)
| Field | Reason |
|---|---|
| `:claim` | documentation only |
| `:game-class` | reserved for future game-theoretic analysis |
| `:threat-model` | documentation only |

### Active Validation Fields
| Field | Evaluator | Result key | Status values |
|---|---|---|---|
| `:mechanism-properties` | `scenario/equilibrium.clj` | `:mechanism-results`, `:mechanism-status` | `:pass` / `:fail` / `:inconclusive` / `:not-applicable` |
| `:equilibrium-concept` | `scenario/equilibrium.clj` | `:equilibrium-results`, `:equilibrium-status` | `:pass` / `:fail` / `:inconclusive` / `:not-applicable` |

---

## Equilibrium Proxy Validation

These fields activate **trace-end mechanism-property validation**: a lightweight falsification layer that checks whether realised terminal outcomes are consistent with claimed economic properties.

> **Claim strength note:** These are *single-trace proxy checks*, not full game-theoretic proofs.
> A `:pass` means "the realised trace is consistent with the claimed property." It does not prove equilibrium holds in all strategy profiles. Results are labelled with `:basis` values such as `:single-trace-metric-proxy`, `:single-trace-terminal-proxy`, `:multi-trace-required`, or `:multi-epoch-required`.

### Mechanism Properties

| Property | Proxy condition | `:inconclusive` when | Severity |
|---|---|---|---|
| `:budget-balance` | `total-held[token] == 0` for all tokens, all escrows terminal | non-terminal escrows remain | hard |
| `:incentive-compatibility` | `attack-successes == 0` and `funds-lost == 0` | no adversarial attempts in trace | hard |
| `:sybil-resistance` | `attack-successes == 0` (identity-agnostic proxy) | no adversarial attempts in trace | hard |

### Equilibrium Concepts

| Concept | Proxy condition | `:inconclusive` when | Severity |
|---|---|---|---|
| `:dominant-strategy-equilibrium` | `invariant-violations == 0` and `attack-successes == 0` | no adversarial events in trace | hard |
| `:nash-equilibrium` | `attack-successes == 0` and `invariant-violations == 0` | no attack metrics | hard |
| `:subgame-perfect-equilibrium` | — | **always inconclusive** for single-trace | soft |
| `:bayesian-nash-equilibrium` | — | **always inconclusive** for single-trace | soft |

### Status Semantics

| Status | Meaning | Suite behaviour |
|---|---|---|
| `:pass` | Trace terminal state consistent with claimed property | passes |
| `:fail` | Observed violation of claimed property | **hard failure** |
| `:inconclusive` | Required evidence absent; cannot falsify or confirm | soft warning |
| `:not-applicable` | Property does not apply to this trace (e.g. no escrows) | passes |
| `:not-checked` | No properties declared in theory block | passes |

### evaluate-theory Output Keys

When `:mechanism-properties` or `:equilibrium-concept` are declared, `evaluate-theory` returns:

```clojure
 {:evidence-schema-version "1.0"
  :equilibrium-claim-tier :proxy | :deviation-tested | :population-tested
  :mechanism-results  {<property-kw> {:property kw :status kw :severity kw :observed ... :expected ... :basis ...}}
 :mechanism-status   :pass | :fail | :inconclusive | :not-applicable | :not-checked
 :equilibrium-results {<concept-kw> {:property kw :status kw ...}}
 :equilibrium-status  :pass | :fail | :inconclusive | :not-applicable | :not-checked}
```

---

## Testing

**Unit test coverage:**
- ✓ Theory evaluation with all four status values
- ✓ Evidence collection for falsified claims
- ✓ Numeric equality with mixed JVM types
- ✓ Missing metric handling (inconclusive)

**Integration test coverage:**
- ✓ Canonical scenario: s01 (no theory)
- ✓ Canonical scenario: same-block-ordering (`:not-falsified`; all three metrics = 0)
- ✓ Canonical scenario: governance-decay-exploit (`:falsified`; `funds-lost = 9950`)

**See:** `test/resolver_sim/core_tests.clj` (test 10, 18 assertions)

