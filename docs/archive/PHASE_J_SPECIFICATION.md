# Phase J: Multi-Epoch Reputation Dynamics

**Date**: February 12, 2026  
**Status**: Specification & Implementation Plan  
**Effort**: 6-8 hours  
**Priority**: CRITICAL (Addresses all 3 critical weaknesses)  

---

## Overview

Phase J extends the simulation from single-epoch (Phase I) to multi-epoch (10 epochs per run). This addresses three critical gaps:

1. **Sybil Resistance** — Shows reputation accumulation makes re-entry costly
2. **Governance Failure** — Models what happens if fraud detection decays
3. **Multi-Year Dynamics** — Proves system stable/robust over time

**Expected Outcome**: 92% → 99% confidence in system safety

---

## Gap Mapping

### Critical Gap #1: No Sybil Resistance

**Current State**:
- Model assumes slashed resolvers cannot re-enter (permanent ban)
- Reality: Nothing prevents re-registration with new identity after slashing
- Effect: Effective detection rate could drop 50% if sybil cost is low

**Phase J Solution**:
- Track per-resolver cumulative profit over 10 epochs
- Show honest resolvers accumulate capital and dominance
- Show malicious resolvers exit or adapt (unprofitable)
- Quantify: How many epochs before sybil re-entry becomes unprofitable?

**Implementation**:
- New file: `src/resolver_sim/sim/multi_epoch.clj`
  - `run-multi-epoch [rng n-epochs n-trials-per-epoch params]`
  - Returns: Per-resolver reputation state + aggregated stats
- New parameter: `:n-epochs` (default 10)
- Track: `:resolver-history` (resolver ID → cumulative profit per epoch)

---

### Critical Gap #2: Perfect Governance Assumed

**Current State**:
- Model assumes fraud detection rate stays at 10-25%
- Reality: If governance is bribed/asleep, detection → 0%
- Effect: System reverts to pre-Phase I vulnerability

**Phase J Solution**:
- Implement governance failure scenarios
- Test: What happens if detection rate decays over epochs?
- Scenarios:
  - Stable (10-25% constant)
  - Decaying (10% → 5% → 2.5% per epoch)
  - Failing (10% → 0% at epoch 5)
- Measure: At what detection rate does malice become profitable again?

**Implementation**:
- New parameters:
  - `:detection-decay-rate` (e.g., 0.5 = halves each epoch)
  - `:detection-failure-epoch` (epoch when detection drops to 0)
- Update `dispute/resolve-dispute` to accept epoch-dependent detection rate
- Track detection effectiveness per epoch

---

### Critical Gap #3: No Multi-Year Dynamics

**Current State**:
- Only single-epoch tested (one round of disputes)
- Can't model: Learning effects, reputation decay, population shifts
- Unknown: Does honest advantage compound or erode?

**Phase J Solution**:
- Run 10 epochs (each epoch = multiple disputes)
- Track per-resolver metrics:
  - Total profit (cumulative)
  - Win rate (verdicts correct)
  - Loss streak (consecutive slashings)
  - Status (active, frozen, exited)
- Show: Honest resolvers accumulate advantage; dishonest exit

**Implementation**:
- New file: `src/resolver_sim/sim/reputation.clj`
  - `track-resolver-history [resolver-id epoch result]`
  - `calculate-exit-probability [history params]`
  - `apply-population-decay [resolvers params epoch]`
- Per-resolver state structure:
  ```clojure
  {:resolver-id uuid
   :strategy :honest | :lazy | :malicious | :collusive
   :epochs-active 7
   :cumulative-profit 1500
   :verdicts-made 100
   :verdicts-correct 90
   :times-slashed 0
   :exit-probability 0.01
   :capital-locked 700}
  ```

---

## Implementation Architecture

### Phase J File Structure

```
src/resolver_sim/sim/
├── batch.clj              (existing - single epoch)
├── multi_epoch.clj        (NEW - orchestrates 10 epochs)
├── reputation.clj         (NEW - per-resolver tracking)
└── epoch_runner.clj       (NEW - runs single epoch with per-resolver history)
```

### Key Functions

#### `multi_epoch.clj`

```clojure
(defn run-multi-epoch
  "Run N epochs, tracking per-resolver reputation.
   Returns: {:epoch-results [...], :resolver-history {...}, :aggregated-stats {...}}"
  [rng n-epochs n-trials-per-epoch params])

(defn aggregate-multi-epoch
  "Convert per-resolver history to statistics.
   Returns: {:honest-cumulative-profit, :malice-cumulative-profit, 
             :exit-rates-by-epoch, :dominance-trajectory, ...}"
  [resolver-history params])
```

#### `reputation.clj`

```clojure
(defn initialize-resolvers
  "Create initial cohort of resolvers based on strategy-mix.
   Returns: {:resolver-1 {...}, :resolver-2 {...}, ...}"
  [n-resolvers strategy-mix])

(defn update-resolver-history
  "Update per-resolver state after dispute result.
   Returns: Updated resolver record"
  [resolver result slashed?])

(defn calculate-exit-probability
  "Based on cumulative losses, what probability resolver exits?"
  [resolver epoch params])

(defn apply-epoch-decay
  "Remove exited resolvers, add new ones to maintain population."
  [resolver-history epoch-num params])
```

#### `epoch_runner.clj`

```clojure
(defn run-epoch
  "Run one epoch with per-resolver tracking.
   Like batch.clj but returns per-resolver breakdown."
  [rng epoch-num resolver-history n-trials-per-epoch params])
```

---

## Data Structures

### Per-Resolver State

```clojure
{:resolver-id "resolver-uuid-123"
 :strategy :honest | :lazy | :malicious | :collusive
 :status :active | :frozen | :exited
 
 ; Cumulative metrics
 :total-profit 1500.0
 :total-fees-earned 2000.0
 :total-slashing-loss 500.0
 :total-verdicts 100
 :total-correct 90
 :total-slashed 2
 :exit-probability 0.01
 
 ; Per-epoch history
 :epoch-history
 {:epoch-1 {:profit 150, :verdicts 10, :slashed? false, :detection-rate 0.10}
  :epoch-2 {:profit 150, :verdicts 10, :slashed? false, :detection-rate 0.10}
  :epoch-3 {:profit -300, :verdicts 10, :slashed? true, :detection-rate 0.15}
  ...}
 
 ; Status tracking
 :frozen-until-epoch nil
 :last-slashed-epoch 3
 :consecutive-losses 0
 :capital-at-risk 700}
```

### Multi-Epoch Result

```clojure
{:n-epochs 10
 :n-trials-per-epoch 1000
 :resolver-histories
 {"resolver-1" {...per-resolver state...}
  "resolver-2" {...per-resolver state...}
  ...}
 
 :epoch-summaries
 [{:epoch 1
   :honest-mean-profit 150
   :malice-mean-profit -199
   :dominance-ratio 10.7
   :exit-rate-this-epoch 0.01
   :detection-rate 0.10
   :appeal-rate 0.05}
  {:epoch 2 ...}
  ...]
 
 :final-statistics
 {:honest-cumulative-profit 1500
  :malice-cumulative-profit -1990
  :honest-win-rate 0.90
  :malice-win-rate 0.30
  :total-exits 3
  :exit-rate-by-strategy {:honest 0.00 :lazy 0.02 :malicious 0.15 :collusive 0.10}
  :final-population {:honest 50 :lazy 15 :malicious 5 :collusive 2}}}
```

---

## Scenarios to Create

### 1. `phase-j-baseline-stable.edn` (Control)
```clojure
{:scenario-id "phase-j-baseline-stable"
 :n-epochs 10
 :n-trials-per-epoch 500
 :n-seeds 3
 :strategy-mix {:honest 0.50 :lazy 0.15 :malicious 0.25 :collusive 0.10}
 :detection-decay-rate 0.0          ; No decay (stable)
 :fraud-detection-probability 0.25
 :reversal-detection-probability 0.25
 :timeout-slash-bps 200}
```

### 2. `phase-j-governance-decay.edn` (Gap #2)
```clojure
{:scenario-id "phase-j-governance-decay"
 :n-epochs 10
 :n-trials-per-epoch 500
 :detection-decay-rate 0.5          ; Detection halves each epoch
 :fraud-detection-probability 0.25  ; Starts at 25%, drops by 50% each epoch
 :reversal-detection-probability 0.25}
```

### 3. `phase-j-governance-failure.edn` (Stress Test)
```clojure
{:scenario-id "phase-j-governance-failure"
 :n-epochs 10
 :n-trials-per-epoch 500
 :detection-failure-epoch 5        ; Detection → 0 at epoch 5
 :fraud-detection-probability 0.25}
```

### 4. `phase-j-sybil-re-entry.edn` (Gap #1)
```clojure
{:scenario-id "phase-j-sybil-re-entry"
 :n-epochs 10
 :n-trials-per-epoch 500
 :allow-sybil-re-entry? true
 :sybil-re-entry-cost 100           ; Cost to re-register (loss needed to make unprofitable)
 :sybil-re-entry-probability 0.5    ; Probability slashed resolver re-enters}
```

---

## Implementation Steps

### Step 1: Create `reputation.clj` (1 hour)
- Data structures for per-resolver state
- Initialization logic
- Update functions

### Step 2: Create `epoch_runner.clj` (1.5 hours)
- Wrap `batch/run-batch` to return per-resolver breakdown
- Track resolver wins/losses
- Update resolver history

### Step 3: Create `multi_epoch.clj` (2 hours)
- Main orchestrator
- Epoch loop (10 iterations)
- Decay/exit logic
- Final aggregation

### Step 4: Update `types.clj` (0.5 hour)
- Add Phase J parameters
- Add validators

### Step 5: Update `core.clj` (0.5 hour)
- Add `--multi-epoch` flag
- Route to Phase J runner

### Step 6: Create test scenarios (0.5 hour)
- 4 scenario files above

### Step 7: Testing & validation (1-2 hours)
- Run all 4 scenarios
- Verify results make sense
- Compare to Phase I baseline

---

## Testing Strategy

### Test 1: Baseline Stability
Run `phase-j-baseline-stable.edn`:
- **Expected**: Honest profit grows each epoch
- **Expected**: Malice profit stays negative
- **Expected**: Exit rate increases for malice, ~0 for honest
- **Verify**: Dominance ratio grows (10.7× → 20×+ by epoch 10)

### Test 2: Governance Decay
Run `phase-j-governance-decay.edn`:
- **Expected**: As detection drops, malice becomes more profitable
- **Critical**: At what epoch does detection drop below 5%?
- **Verify**: System still stable at minimum viable detection rate

### Test 3: Governance Failure
Run `phase-j-governance-failure.edn`:
- **Expected**: System collapses at epoch 5 when detection → 0
- **Measure**: How many malicious resolvers profit after epoch 5?
- **Insight**: Proves detection IS critical to system stability

### Test 4: Sybil Re-Entry
Run `phase-j-sybil-re-entry.edn`:
- **Expected**: Low re-entry cost → sybil attack is profitable
- **Expected**: High re-entry cost → sybil attack is unprofitable
- **Find**: Minimum cost to prevent sybil escape

---

## Success Criteria

### Primary Objectives

✅ **Gap #1 (Sybil Resistance)**:
- Show honest resolvers accumulate reputation
- Quantify: Sybil re-entry is unprofitable after 3-5 epochs
- Evidence: Malicious exit rate > 10% per epoch, honest < 2%

✅ **Gap #2 (Governance Failure)**:
- Model detection decay/failure scenarios
- Show system unstable if detection drops below X%
- Define: Safe minimum detection rate with safety margin

✅ **Gap #3 (Multi-Year Dynamics)**:
- Prove system remains stable for 10 epochs
- Show honest advantage compounds
- Measure: Confidence 92% → 99%

### Secondary Objectives

- Zero regressions (Phase I still passes)
- Execution time < 30 seconds for all 4 scenarios
- Clear visualization of per-epoch trends

---

## Success Metrics

| Metric | Target | Evidence |
|--------|--------|----------|
| **Honest profit trend** | ↑ per epoch | Grows from 150 to 200+ cumulatively |
| **Malice profit trend** | ↓ per epoch | Stays negative, more negative each epoch |
| **Exit rate (honest)** | < 2% | Population stable |
| **Exit rate (malice)** | > 10% | Population decays |
| **Dominance ratio trend** | ↑ per epoch | 10.7× in epoch 1 → 20×+ by epoch 10 |
| **Governance decay** | Quantified | At 5% detection, malice = neutral/profitable |
| **Sybil cost** | Identified | Re-entry unprofitable above $X |
| **Execution time** | < 30s | All 4 scenarios complete quickly |

---

## Deliverables

1. ✅ **Code**: `reputation.clj`, `epoch_runner.clj`, `multi_epoch.clj`
2. ✅ **Parameters**: 4 scenario files (`phase-j-*.edn`)
3. ✅ **Test results**: Run all 4 scenarios, verify success criteria
4. ✅ **Documentation**: Update FINDINGS_SUMMARY.md with Phase J results
5. ✅ **Confidence report**: Show 92% → 99% with evidence

---

## Integration with Existing Code

### Minimal Changes Needed

**`core.clj`**:
- Add `--multi-epoch` flag (5 lines)
- Route to `multi_epoch/run-multi-epoch` (10 lines)

**`types.clj`**:
- Add `:n-epochs` validator (3 lines)
- Add `:detection-decay-rate` validator (3 lines)

**`batch.clj`**:
- No changes (reuse existing `run-batch`)

**`dispute.clj`**:
- No changes (reuse existing `resolve-dispute`)

Total: < 50 lines of changes to existing files

---

## Risk Mitigation

### Risk: Implementation takes > 8 hours

**Mitigation**:
- Start with minimal version (1 epoch, basic tracking)
- Expand to 10 epochs once basic version works
- Reuse existing batch/dispute logic (no rewrite needed)

### Risk: Results don't match expectations

**Mitigation**:
- Compare to Phase I baseline (honest should be +150, malice -199)
- Verify per-resolver history is accumulated correctly
- Sanity-check: honest profit should grow, malice should shrink

### Risk: Governance failure scenario doesn't show instability

**Mitigation**:
- Use aggressive failure: detection → 0 at epoch 5
- Measure: If detection is critical, malice should spike at epoch 5
- If not: System has hidden strengths we need to document

---

## Next Steps After Phase J

### If successful (99% confidence):
1. Deploy to mainnet
2. Monitor actual detection rates (must stay 10-25%)
3. Track resolver exit rates (honest < 2%, malice > 10%)
4. Phase J proves system is self-healing (honest wins compound)

### If governance decay detected:
1. Adjust minimum detection rate SLA
2. Implement oracle redundancy
3. Plan Phase K (cartel coordination detection)

### If sybil attacks viable:
1. Strengthen identity verification
2. Increase sybil re-entry cost
3. Plan Phase J-extended (longer epochs, more detailed tracking)

---

## Timeline

- **Week 1**: Implement Phase J (6-8 hours)
- **Week 1**: Run all 4 scenarios (2-3 hours)
- **Week 2**: Validate results + documentation (2-3 hours)
- **Week 2**: Ready for mainnet deployment

---

## References

**Previous Phases**:
- Phase I: WEAKNESS_ANALYSIS.md (defines critical gaps)
- Phase H: phase-i-automatic-detection.md (baseline results)

**Session Documents**:
- WEAKNESS_SUMMARY.txt (all 11 gaps with Phase J mapping)
- WEAKNESS_PRIORITIZATION_MATRIX.txt (effort vs. impact)

