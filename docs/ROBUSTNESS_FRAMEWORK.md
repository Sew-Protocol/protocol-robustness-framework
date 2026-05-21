# Protocol Robustness Framework

**Adversarial Validation, Deterministic Replay, and Simulation Architecture**

---

## Overview

The Sew simulation engine is a protocol-specific adversarial testing framework
built on a protocol-agnostic kernel. Its purpose is to generate falsifiable
evidence about protocol correctness under adversarial, edge-case, and stress
conditions — not to produce qualitative claims.

The framework has three interlocking layers:

| Layer | What it does | Where it lives |
|---|---|---|
| **Deterministic replay** | Replays event sequences against the protocol kernel; checks every invariant after every transition | `contract_model/replay.clj` |
| **Adversarial simulation** | Runs Monte Carlo trials with adversarial and honest agents across a production threat envelope | `sim/adversarial.clj`, `adversaries/` |
| **Statistical phases** | Hypothesis-driven sweeps over protocol parameter space; each phase produces a pass/fail verdict | `sim/phase_*.clj`, research sub-namespaces |

All three layers are pure-functional. No I/O touches the core computation; the
shell layer (`io/`, `db/`) handles persistence. This means the kernel is
testable without a running database or filesystem.

---

## 1. Deterministic Replay Engine

### What it is

The replay engine (`resolver-sim.contract-model.replay`) is an open-world
scenario harness. It accepts a scenario map — a sequence of timed events with
agents and protocol parameters — and drives a Sew world-state forward through
each event, checking invariants between every step.

It is protocol-agnostic: all protocol-specific logic (actions, invariant
predicates, state machine) is injected through the `SimulationAdapter`
interface (`protocols/protocol.clj`).

### How a scenario is structured

```clojure
{:scenario-id     "s02-dr3-dispute-release"
 :schema-version  "1.0"
 :initial-block-time 1000
 :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                   {:id "seller"   :address "0xseller"   :strategy "honest"}
                   {:id "resolver" :address "0xresolver" :strategy "honest"}]
 :protocol-params {:resolver-fee-bps 150
                   :appeal-window-duration 0
                   :max-dispute-duration 2592000}
 :events
 [{:seq 0 :time 1000 :agent "buyer"    :action "create_escrow"
   :params {:token "USDC" :to "0xseller" :amount 5000}
   :save-id-as "wf0"}
  {:seq 1 :time 1100 :agent "buyer"    :action "dispute"
   :params {:workflow-id "wf0"}}
  {:seq 2 :time 1200 :agent "resolver" :action "resolve"
   :params {:workflow-id "wf0" :outcome :release}}]}
```

Key properties:

- **Workflow ID aliasing**: `:save-id-as` / `:workflow-id` allow referencing
  escrow IDs symbolically; the engine resolves them lazily in the event loop.
- **Deterministic time**: block time is integer-typed; `time-non-decreasing` is
  an enforced invariant.
- **Schema versioning**: schema versions `"1.0"` and `"1.1"` are supported;
  unknown versions are rejected at validation time.
- **Expected-fail scenarios**: scenarios with `:expected-fail? true` are expected
  to trigger an invariant violation. The runner treats them as passing only when
  the outcome is `:fail`. This documents known-fixed regression cases.

### What is checked after every step

The replay engine calls two classes of invariant predicates after every
successful transition:

1. **Single-world invariants** — properties that must hold on the world-state
   snapshot in isolation (e.g. solvency, fee non-negativity, status consistency).
2. **Transition invariants** — properties that must hold across two consecutive
   world states (e.g. terminal states are absorbing, time is non-decreasing,
   no action after finality).

A violation in either class immediately halts the scenario with outcome `:fail`
and records the violating invariant ID, the step index, and the world-state
diff. Expected-fail scenarios invert this: a halt is the expected outcome.

### Trace minimizer

When a scenario fails, the trace minimizer (`sim/minimizer.clj`) reduces the
event sequence to its **1-minimal subset** that still triggers the same
invariant violation. This is the smallest set of events from which no event can
be removed without suppressing the violation. Minimized traces are the
canonical artifact for bug reports and regression fixtures.

```clojure
;; Produce the 1-minimal failing trace for a target invariant
(minimizer/minimize scenario :solvency)
```

The minimizer is protocol-agnostic; it accepts any `DisputeProtocol`
implementation via the registry.

---

## 2. Invariant Catalogue

36 canonical invariant IDs are enforced across the Sew v1 model. They are
organized into six groups:

### Accounting invariants (enforced after every step)

| ID | Meaning |
|---|---|
| `:solvency` | `total-held[t] = sum(live afa[t])` and `total-held[t] ≤ token-balance[t]` |
| `:fees-non-negative` | Accumulated fee pool is never negative |
| `:fees-monotone` | Fee pool never decreases |
| `:held-non-negative` | Per-escrow held amount is never negative |
| `:conservation-of-funds` | Funds in + funds created = funds out + funds held |
| `:fee-cap` | Collected fees do not exceed the fee-cap configured in params |
| `:finalization-accounting-correct` | Claimable credits exactly match what was finalized |
| `:token-tax-reconciliation` | Tax-adjusted token flows reconcile across all escrows |
| `:single-resolution-payout-consistent` | A single-path resolution pays out exactly one side |

### State machine invariants

| ID | Meaning |
|---|---|
| `:terminal-states-unchanged` | `RELEASED`, `REFUNDED`, `RESOLVED` are absorbing; no transition out |
| `:all-status-combinations-valid` | `SenderStatus` × `RecipientStatus` combination is a valid enum pair |
| `:pending-settlement-consistent` | If `pendingSettlement.exists`, escrow is in `DISPUTED` state |
| `:dispute-resolution-path` | Dispute resolves only through authorized, sequenced resolution path |
| `:escalation-level-monotonic` | Escalation level is non-decreasing within a dispute |
| `:no-withdrawal-during-dispute` | `withdrawEscrow` cannot be called while escrow is in `DISPUTED` state |
| `:no-auto-fraud-execute` | Automated execution cannot fire on an escrow flagged for fraud |

### Time invariants

| ID | Meaning |
|---|---|
| `:time-non-decreasing` | Block time strictly does not go backwards between events |
| `:time-no-action-after-finality` | No event can be processed against a finalized escrow |
| `:time-lock-integrity` | Time-lock protected operations cannot execute before unlock timestamp |
| `:dispute-timestamp-consistent` | Dispute start timestamp is set exactly once, at dispute initiation |

### Bond and slashing invariants

| ID | Meaning |
|---|---|
| `:bond-liquidity` | Bond posted is held in contract and accessible for slashing |
| `:bond-slash-bounded` | Slash amount ≤ posted bond for the workflow |
| `:appeal-bond-conserved` | Appeal bond is conserved across the appeal lifecycle |
| `:appeal-bond-custody-consistent` | Appeal bond custody state matches the current appeal phase |
| `:slash-status-consistent` | Slash status flags are consistent with resolver freeze state |
| `:slash-distribution-consistent` | Slash proceeds distributed to insurance and treasury sum correctly |
| `:slash-epoch-cap-respected` | Slashes per epoch do not exceed the configured epoch cap |
| `:reversal-slash-disabled` | Reversal slash path is disabled (config-gated) |
| `:fraud-slash-executions-accounted` | Every fraud-detected slash is recorded in the slash ledger |

### Resolver invariants

| ID | Meaning |
|---|---|
| `:resolver-capacity` | Resolver's active dispute count does not exceed capacity |
| `:resolver-not-frozen-on-assign` | A frozen resolver cannot be assigned new disputes |
| `:resolver-bond-mix-valid` | Resolver bond mix (own + delegated) satisfies minimum coverage rules |
| `:senior-coverage-not-exceeded` | Senior resolver coverage exposure does not exceed declared limit |
| `:dispute-level-bounded` | Escalation level cannot exceed the module's maximum round |

### Yield invariants

| ID | Meaning |
|---|---|
| `:yield-position-consistency` | Yield module position is consistent with escrow held state |
| `:yield-exposure` | Aggregate yield exposure does not exceed the yield exposure cap |

---

## 3. Scenario Suite

### Suite composition

The canonical suite (`invariant_scenarios.clj`) currently contains **48+
named scenarios** spanning four types:

| Type | Count | Purpose |
|---|---|---|
| `:baseline` | 11 | Happy-path flows; confirm basic protocol operations work correctly |
| `:edge-case` | 14 | Boundary conditions; confirm guards and error paths fire correctly |
| `:stress` | 6 | Multi-escrow or multi-resolver conditions; confirm accounting holds under load |
| `:adversarial` | 18+ | Active adversary agents; confirm incentive alignment holds under attack |

### Baseline scenarios (S01–S23 selected)

| Scenario | Coverage |
|---|---|
| `s01-baseline-happy-path` | Create → release; no dispute |
| `s02-dr3-dispute-release` | Dispute raised; resolver releases to buyer |
| `s03-dr3-dispute-refund` | Dispute raised; resolver refunds to seller |
| `s04-dispute-timeout-autocancel` | Dispute opened; resolver non-responsive; timeout auto-cancels |
| `s05-pending-settlement-execute` | Split settlement proposed and executed |
| `s06-mutual-cancel` | Mutual cancel without dispute |
| `s13-pending-settlement-refund` | Pending settlement refunded |
| `s16-ieo-create-release` | Instant-escrow-only flow (no resolver) |
| `s17-ieo-dispute-no-resolver-timeout` | IEO dispute times out without resolver |
| `s18-dr3-kleros-l0-resolves` | Kleros L0 round resolves without escalation |
| `s46-reorg-idempotence` | Re-submitted event is idempotent (reorg safety) |

### Edge-case scenarios

| Scenario | What it validates |
|---|---|
| `s07-unauthorized-resolver-rejected` | Only the authorized resolver may call `resolve` |
| `s08-state-machine-attack-gauntlet` | 12+ invalid transitions attempted; all rejected |
| `s10-double-finalize-rejected` | Calling finalize twice on the same escrow is rejected |
| `s11-zero-fee-edge-case` | Zero resolver fee does not break accounting |
| `s12a/s12b-snapshot-isolation` | Fee-param change after escrow creation does not apply retroactively |
| `s14-dr3-module-authorized` | Module-authorized resolution path accepted |
| `s15-dr3-module-unauthorized-rejected` | Unauthorized module resolution rejected |
| `s19–s23` (Kleros edge cases) | Escalation guards, max-level cap, pending-settlement clearing |
| `s66-cooldown-boundary-reorg` | Cooldown window boundary is correctly enforced across reorg |

### Stress scenarios

| Scenario | What it validates |
|---|---|
| `s09-multi-escrow-solvency` | Solvency invariant holds across concurrent escrows |
| `s24-resolver-stake-depletion-cascade` | Stake depletion cascades across 3 slashing events |
| `s38-dr3-bond-mix-valid` | Mixed bond (own + senior delegated) validates correctly |
| `s39-dr3-senior-coverage-delegation` | Senior coverage limit respected under delegation |
| `s40-dr3-freeze-post-slash` | Resolver correctly frozen after slash; no new assignments |
| `s41-dr3-reversal-slash-disabled` | Reversal slash does not fire when config gate is closed |

### Adversarial scenarios

Adversarial scenarios involve agents with explicit adversary types and declared
attack traits. The registry currently covers three adversary classes:

#### `:profit-maximizer`

Attempts to extract value from escrow or resolver infrastructure through
misuse of protocol mechanics.

| Scenario | Traits | What is being probed |
|---|---|---|
| `s25-profit-maximizer-slash-lifecycle` | `:multi-step :capital-efficient` | Profit via deliberate slash cascade |
| `s34-profit-maximizer-unchallenged-slash` | `:capital-efficient` | Slash without appeal response |
| `s35-profit-maximizer-governance-wins-appeal` | `:multi-step :high-capital` | Appeal won via governance lever |
| `s36-profit-maximizer-pre-window-execute-rejected` | `:multi-step :adaptive` | Pre-window execution rejected |
| `s37-profit-maximizer-two-resolver-split-outcomes` | `:stealthy :capital-efficient` | Conflicting outcomes across two resolvers |
| `s45-flash-loan-stake-inflation` | `:flash-loan :stake-inflation` | Stake inflation via flash loan |
| `s67-reentrancy-callback` | `:reentrancy :callback` | Reentrancy via callback |

#### `:forking-strategist`

Attempts to exploit escalation mechanics by forcing the dispute into
unfavorable rounds for the opposing party.

| Scenario | Traits | What is being probed |
|---|---|---|
| `s26-forking-strategist-l1-reversal` | `:multi-step :adaptive` | L1 escalation reversal |
| `s27-forking-strategist-l2-fork` | `:multi-step :adaptive` | L2 fork strategy |
| `s28-forking-strategist-late-escalation-rejected` | `:multi-step` | Late escalation outside window rejected |
| `s29-forking-strategist-seller-escalates` | `:multi-step :reactive` | Seller-initiated escalation path |
| `s30-forking-strategist-double-loss` | `:multi-step :adaptive` | Strategy that incurs two losses |
| `s31-forking-strategist-all-levels-confirm` | `:multi-step :high-capital` | All levels confirm original outcome |
| `s32-forking-strategist-premature-settlement-rejected` | `:multi-step` | Settlement during open appeal rejected |
| `s33-forking-strategist-two-escrow-fork-isolation` | `:multi-step :capital-efficient` | Fork isolation across two escrows |

#### `:colluder`

Models coordinated multi-agent attacks involving bribery or collusion.

| Scenario | Traits | What is being probed |
|---|---|---|
| `s42-resolver-buyer-bribery-loop` | `:multi-agent :bribery` | Resolver-buyer bribe loop |

---

## 4. Monte Carlo Simulation

### Architecture

The Monte Carlo layer (`sim/batch.clj`, `sim/sweep.clj`) runs independent
trials across a parameter space. Each trial instantiates an honest agent and
a malicious agent with the same protocol parameters, simulates the dispute
process stochastically, and records expected value (EV) for each strategy.

The primary question: **is honest behavior the dominant strategy under the
production threat envelope?**

The dominance condition is:

```
EV(honest) / EV(malicious) > 1.0
```

A ratio above 1.0 means honest resolving is more profitable than malicious
resolving. Pass threshold for most hypotheses is ≥ 80% of trials across a
sweep satisfying this condition.

### Production Threat Envelope (PTE)

The adversarial search uses a defined parameter envelope:

```clojure
(def pte-v1
  {:resolver-fee-bps           [100 300]   ; 1–3% resolver fee
   :resolver-bond-bps          [500 1500]  ; 5–15% bond requirement
   :slash-multiplier           [1.0 3.0]   ; 1–3× slash on bond
   :slashing-detection-probability [0.03 0.30]}) ; 3–30% fraud detection rate
```

The hill-climbing adversarial search (`adversarial/hill-climb`) finds the
parameter combination within the PTE that maximises malicious EV, and reports
whether honest behavior is still dominant at that worst-case point.

### Simulation phases

Each phase tests a specific hypothesis. All phases are pure functions; I/O is
confined to the runner in `core/phases.clj`.

#### Economic and incentive phases

| Phase | Hypothesis | Trials | Key finding |
|---|---|---|---|
| **G** — Slashing delays | Slash delay does not invert the honest/malicious EV ratio | 5,000 | Passes; ratio stable across delay ranges |
| **H** — Bond mechanics | Bond sizing creates correct incentive gradients | 5,000 + 2D sweep | Passes; higher bond increases dominance ratio |
| **I** — Automatic detection | Detection mechanisms (fraud, timeout, reversal) increase malicious cost | 5,000 | Passes; all 3 detection paths effective |
| **N** — Appeal outcomes | Even at 50% appeal success, waterfall maintains surplus | 5,000 | Passes; 3,300%+ surplus at 50% appeal rate |
| **O** — Market exit cascade | Resolver pool depletion does not destabilize remaining resolvers | 40,000 | Passes; graceful degradation |

#### Multi-epoch and governance phases

| Phase | Hypothesis | Trials | Key finding |
|---|---|---|---|
| **J** — Multi-epoch reputation | Reputation decay over 10 epochs does not invert incentives | 50,000 | Passes; EMA convergence stable |
| **L** — Waterfall cascade | Simultaneous multi-resolver slashes do not drain insurance pool | 5,000 | Passes; 10%/30%/cascade/simultaneous tested |
| **M** — Governance response time | Resolver freezing eliminates governance response-time sensitivity | 50,000 | **Passes; with freeze, response time is irrelevant** |

#### Research-phase adversarial simulations

Beyond the canonical phases, a library of research simulations probes specific
attack hypotheses. Each is a falsifiable test with a defined pass criterion.

**Adversarial economics:**

| Simulation | What it probes |
|---|---|
| `falsification-lite` / `falsification-revised` | Can an adversary falsify evidence to swing a dispute outcome? |
| `evidence-fog` | Does evidence cost asymmetry create exploitable fog-of-war for adversaries? |
| `legitimacy-loop` | Can a resolver maintain legitimacy score while defecting in selected disputes? |
| `trust-floor` | Is there a trust-score floor below which resolver is barred regardless of bond? |
| `fair-slashing` | Does the slashing model correctly distinguish malicious vs negligent failure? |
| `epoch-solvency` | Is per-epoch solvency maintained under maximum adversarial pressure? |
| `ema-convergence` | Does the EMA reputation signal converge correctly under manipulation? |
| `equity-divergence` | Can equity divergence be induced by coordinated defection? |
| `escalation-trap` | Can an adversary force unnecessary escalation to drain appeal bonds? |
| `collusion-ring` | Does a coordinated resolver ring break detection thresholds? |
| `advanced-vulnerability` | Known vulnerability patterns from literature (not Sew-specific) |
| `liveness-participation` | Can a liveness attacker block resolution without being detected? |
| `adaptive-attacker` | An attacker that adapts strategy based on protocol response |

**Governance:**

| Simulation | What it probes |
|---|---|
| `adversary` (governance) | Can a governance adversary capture protocol parameters? |
| `effort-rewards` | Do effort-proportional rewards maintain resolver quality? |
| `capture-drift` | Does governance capture drift over multi-epoch runs? |
| `bandwidth-floor` | Is there a minimum governance participation floor for security? |

**Economic:**

| Simulation | What it probes |
|---|---|
| `market-exit` | Does market exit cascade cause resolver pool collapse? |
| `belief-cascades` | Can belief cascades in dispute outcome shift equilibrium? |
| `dispute-clustering` | Does dispute clustering stress the waterfall more than uniform distribution? |
| `burst-concurrency` | Does burst concurrent dispute volume break accounting under stress? |

### Ring attacker model

The `RingAttacker` adversary (`adversaries/ring_attacker.clj`) models a
coordinated ring of *N* resolvers that rotate disputes among members to
suppress per-member fraud detection probability:

```
effective-detection(member) = base-detection / ring-size
detection-avoidance-rate    = 1 − (1 / ring-size)
```

The ring earns the same gross profit as an individual attacker, but each
member stays below the detection threshold longer. This model is the
foundation of the Phase AH/AI multi-epoch ring evasion tests.

---

## 5. Fixture Suite and Golden Reports

The fixture suite (`sim/fixtures.clj`) loads scenarios from `data/fixtures/`
as EDN or JSON and runs them through the replay engine. The suite runner
supports three named suites:

| Suite | Coverage |
|---|---|
| `:suites/all-invariants` | All S01–S41+ named invariant scenarios |
| `:suites/equilibrium-validation` | Equilibrium property scenarios |
| `:suites/spe-validation` | Subgame-perfect equilibrium validation scenarios |

Golden reports (`generate-golden-report`) record the expected pass/fail
outcome for each fixture, allowing regression detection: if a scenario
transitions from pass to fail (or vice versa), the suite fails.

Trace minimization is integrated into the suite: failing scenarios are
automatically minimized before recording.

---

## 6. Layering and Purity Constraints

The framework's correctness depends on strict layer isolation. Violations are
treated as bugs.

```
protocols/sew/*        ← Domain logic (pure)
  invariants.clj         36 invariant predicates
  invariant_scenarios.clj  48+ named scenarios
  invariant_runner.clj     In-process suite runner

contract_model/replay.clj  ← Protocol-agnostic kernel (pure)
  - imports: protocols/protocol only
  - no db/*, no io/*

sim/*                  ← Monte Carlo and research phases (pure)
  - imports: contract_model/*, protocols/*, stochastic/*, adversaries/*
  - no db/*, no io/*

adversaries/*          ← Adversary strategies (pure)
  - imports: stochastic/* only

db/*                   ← XTDB persistence (shell; side-effectful)
io/*                   ← File I/O (shell; side-effectful)
core.clj               ← CLI dispatch (wires everything)
```

The key invariant: **the functional core is fully testable without a running
XTDB instance or filesystem.** This means every scenario, every Monte Carlo
trial, and every adversarial simulation can be reproduced in a clean JVM
process with a fixed seed and no external dependencies.

---

## 7. Running the Framework

### Full canonical test suite

```bash
./scripts/test.sh all
```

This runs: unit tests → generator regressions → cross-layer contract checks →
deterministic invariant suite (S01–S41) → fixture suites.

### Invariant suite only (fast, ~1s, no gRPC required)

```bash
clojure -M:run -- --invariants
```

### Fixture suite runner

```clojure
(require '[resolver-sim.sim.fixtures :as f])
(f/run-suite :suites/all-invariants)
```

### Monte Carlo phase (example)

```bash
clojure -M:run -- -p data/params/phase-h-2d-realistic.edn -s
```

### Adversarial hill-climb

```clojure
(require '[resolver-sim.sim.adversarial :as adv]
         '[resolver-sim.stochastic.rng  :as rng])
(adv/hill-climb (rng/make-rng 42) adv/pte-v1 200)
```

### Trace minimization

```clojure
(require '[resolver-sim.sim.minimizer :as m])
(m/minimize failing-scenario :solvency)
```

---

## 8. Evidence Strength and Known Gaps

### What the framework currently covers

- ✅ 36 invariants enforced after every step of every deterministic replay
- ✅ 48+ named scenarios across baseline, edge-case, stress, and adversarial types
- ✅ 147,500+ Monte Carlo trials across 8 statistical phases
- ✅ 18+ adversarial research simulations with falsifiable hypotheses
- ✅ Ring attacker, bribery, collusion, flash-loan, reentrancy, and escalation
  trap adversary models
- ✅ Multi-epoch reputation decay, governance capture, and waterfall cascade

### Known gaps (not yet covered)

| Gap | Risk level | Notes |
|---|---|---|
| Formal verification (Halmos / Echidna) | Medium | Invariant predicates are designed to be portable to Foundry invariant tests; not yet wired |
| Cross-module yield failure interaction | Medium | Yield dual-failure edge case modelled analytically; no deterministic scenario |
| Kleros round-2 final-appeal path | Low–Medium | S18–S23 cover L0/L1 Kleros paths; L2 final path (`MAX_ROUND`) not yet a named scenario |
| Governance collusion across multiple epochs | Medium | `capture-drift` research simulation exists; no deterministic invariant scenario |
| Token rebase / fee-on-transfer tokens | Medium | `token-tax-reconciliation` invariant defined; no stress scenario with non-standard token |
| Concurrent dispute flood (on-chain gas) | Out of scope | `burst-concurrency` covers accounting; gas model is not simulated |

---

## Related Documents

- [`docs/testing/TEST_SUITE.md`](testing/TEST_SUITE.md) — canonical test matrix and run commands
- [`docs/testing/RUNNING_TESTS.md`](testing/RUNNING_TESTS.md) — test entrypoints and current baseline
- [`docs/evidence/RESEARCHER_EVIDENCE_PACK.md`](evidence/RESEARCHER_EVIDENCE_PACK.md) — condensed evidence for external reviewers
- [`docs/security-model.md`](security-model.md) — threat model and security assumptions
- [`docs/CDRS-v1.1-THEORY-SCHEMA.md`](CDRS-v1.1-THEORY-SCHEMA.md) — CDRS theory schema (scenario classification)
