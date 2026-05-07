# Research Note v0: Structural Failure Modes in Decentralised Escrow Dispute Protocols

**Authors:** SEW Protocol Research
**Version:** v0 (pre-peer-review)
**Date:** 2026-05
**Repository:** https://github.com/Sew-Protocol/sew-simulation
**Reproducibility:** all results reproducible in ≤15 minutes from a single clone

---

## 1. Headline Claim

> **Governance rotation mid-dispute enables 100% outcome manipulation with zero invariant violations — structurally invisible to per-function audits, fuzz testing, and unit testing.**

More broadly: in a 33-scenario adversarial simulation of the SEW dispute protocol, adversarial agents succeeded in **22% of attack attempts** (9 of 41 attempts across 33 adversarial scenarios). In zero cases did the protocol enter an inconsistent state. The failures are not implementation bugs — they are **correct uses of the protocol against its own users**.

---

## 2. Model

### 2.1 What We Simulated

The SEW protocol is a decentralised escrow dispute-resolution mechanism. It defines:
- An escrow state machine (`created → funded → disputed → [released | refunded]`)
- A resolver authority model (custom resolver + Kleros L0/L1 escalation)
- A fee and accounting model
- A governance model (resolver rotation with timelock)

We simulate **multi-agent, multi-step sequences** of protocol interactions, checking 31 invariants at every state transition.

### 2.2 Simulation Architecture

The simulation runs in two modes:
1. **Deterministic invariant suite** (41 scenarios, S01–S41): fixed traces, checked in-process, no I/O. ~1 second runtime.
2. **Adversarial scenario suite** (33 scenarios, F-series): adversarial agents (Python gRPC clients) interact with a live Clojure contract model server.

The functional core (state machine, invariants, accounting) is pure Clojure with no external dependencies. Adversarial strategy generation uses Python. This separation ensures the simulation logic can be audited and replicated independently of the adversarial generation code.

### 2.3 Invariants Checked

Every state transition is checked against 31 invariants, including:

| Invariant | What it checks |
|-----------|---------------|
| `solvency` | Held token balance ≥ sum of all escrow amounts |
| `no-double-finalize` | No escrow transitions to a terminal state twice |
| `resolver-authority` | Only the authorized resolver executes resolution |
| `fee-accounting` | Fee extracted ≤ fee promised at creation |
| `state-machine-validity` | Transitions conform to the defined state machine |
| `budget-balance` | Buyer/seller receive no more than the escrow amount combined |

A violation of any invariant is a protocol fault. Zero violations occurred in any scenario in this corpus.

### 2.4 Model Boundaries

This model **does** capture:
- Multi-step, multi-agent interaction sequences
- Governance action timing relative to dispute state
- Rational resolver economic behaviour (refusal, withdrawal)
- Escalation path coverage (L0/L1/timelock)
- Same-block ordering effects

This model **does not** capture:
- Gas economics (cost is modelled in ticks, not wei)
- Network-level attacks (MEV, front-running)
- Cross-contract interactions outside the escrow/resolver system
- Oracle price manipulation (modelled separately in `oracle/`)
- Governance key compromise (treated as out-of-scope for this protocol layer)

---

## 3. Results

### 3.1 Suite-Level Statistics

| Metric | Value |
|--------|-------|
| Total scenarios | 33 adversarial + 41 deterministic |
| Invariant violations | **0** |
| Adversarial successes | 9 of 41 attack attempts = **22%** |
| Total transactions processed | 327 |
| Rejected (correct protocol reverts) | 189 (57.8%) |

### 3.2 Three Structural Failure Classes

#### Class 1 — Governance Manipulation (F3: Governance Sandwich)

**Setup:** An escrow is in `disputed` state with `0xhonest-resolver` as the authorized resolver. A governance actor (with legitimate authority) queues a resolver rotation. After the 2-tick timelock, `0xmalicious-resolver` becomes authorized. The malicious resolver resolves in the attacker's favour.

**Outcome:**
- `rotations=1`, `malicious_resolutions_succeeded=1`, `invariant_violations=0`
- Buyer's dispute, initiated against an honest resolver, resolved by a malicious one.

**Why existing tools miss this:**
- Static analysis (Slither, Mythril): rotation function is correctly access-controlled in isolation.
- Fuzz testing (Echidna, Foundry): cannot model the intent-ordered sequence `open_dispute → queue_rotation → rotation_fires → malicious_resolve`.
- Unit tests: governance rotation and dispute resolution are tested in isolation; their composition is not.

**Structural cause:** The protocol defines resolver authority as a *live* property (checked at resolution time), not a *snapshot* property (checked at dispute-open time). This is a design choice with known trade-offs, not an accident.

---

#### Class 2 — Rational Liveness Failure (F7: Profit-Threshold Strike)

**Setup:** An escrow has 100 tokens, `fee_bps=100` (fee = 1 token). A resolver with `min_profit=5` refuses to service the escrow. The dispute window expires with zero resolutions.

**Outcome:**
- `resolutions_executed=0`, `refusals=1`, `invariant_violations=0`
- Funds permanently locked. No on-chain signal.

**Structural cause:** The protocol does not enforce a minimum fee floor or penalise resolver inaction within the dispute window. Rational resolvers will refuse low-margin disputes at scale.

**Attack variant:** An adversary can create many small disputed escrows below a resolver's cost floor, exhausting the resolver's attention without triggering any detectable fault. Cost to attacker: dispute initiation gas. Cost to victims: permanent fund lock.

---

#### Class 3 — Capacity-Limited Arbitrator Drain (F10: Cascade Escalation)

**Setup:** Four simultaneous disputes are raised against a single arbitrator with `max_capacity=2`. The arbitrator processes the first two. Escrows 3 and 4 remain in `disputed` state indefinitely.

**Outcome:**
- `resolutions_executed=2`, `stuck_escrows=2`, `invariant_violations=0`

**Structural cause:** Capacity limits are enforced but there is no requeue or fallback path for disputes that exceed capacity. The protocol is live for individual disputes but not at scale under a capacity-constrained resolver.

---

### 3.3 What the Invariants Tell Us

The zero-violation result is the most important finding for protocol designers:

> **The protocol does not break. It is used correctly against its users.**

This means the threat model for this protocol class cannot be "find a bug." It must be "find a sequence of valid actions that produces an outcome no party intended at contract creation." Traditional security tooling is not designed for this threat model.

---

## 4. Strongest Single Result

**Governance Sandwich (F3)** is the strongest result because:

1. It is reproducible from a single fixture trace in under 1 minute.
2. It requires **no bug** — every action is authorized.
3. It passes all 31 invariants.
4. It is structurally invisible to the three most common smart contract security tools.
5. It generalises to any protocol where resolver authority is a live property.

**Exact reproduction command:**
```bash
git clone https://github.com/Sew-Protocol/sew-simulation
cd sew-simulation
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 10
cd python
python invariant_suite.py --scenario F3 --json results/f3-reproduction.json
```

Expected output: `malicious_resolutions_succeeded=1, invariant_violations=0`

---

## 5. Known Limitations

1. **Tick-based time, not block-based.** The model approximates Ethereum block timing with integer ticks. Gas economics and MEV are not modelled.

2. **Closed-world adversary set.** Adversaries are modelled as Python agents with defined strategies. Novel attack strategies not in the agent library are not covered.

3. **No cross-contract composition.** The escrow system is simulated in isolation. Real deployments may interact with DeFi protocols (flash loans, AMMs) in ways not modelled here.

4. **Governance key model is simplified.** We assume governance authority is held by a single actor. Multi-sig governance, DAO voting, and timelock councils are not modelled.

5. **Liveness proofs are incomplete.** We show liveness failures; we do not prove liveness bounds (e.g., "the protocol resolves all disputes within N blocks under assumptions X").

---

## 6. Open Questions

These are the highest-value open questions for follow-up research:

**Q1 — Snapshot authority:** Does committing resolver identity at `raise_dispute` time (rather than `execute_resolution` time) eliminate the governance sandwich without introducing new failures? What are the trade-offs for legitimate resolver upgrades?

**Q2 — Minimum fee enforcement:** What is the minimum fee floor that eliminates rational liveness failures, and what is the market equilibrium for resolver participation under this floor?

**Q3 — Capacity routing:** Can a resolver registry with capacity signalling route disputes to available resolvers? What are the trust assumptions for the registry?

**Q4 — Composability attacks:** Do flash-loan or re-entrancy patterns enable profitable attacks against this protocol class that are invisible in single-contract simulation?

**Q5 — Mechanism equivalence:** Is the SEW mechanism strategically equivalent to any well-studied mechanism in the mechanism design literature (Kleros, Optimistic Oracle, UMA)? If so, do known results from those systems transfer?

---

## 7. Concrete Asks

If you read this note, we have three asks:

1. **Replicate F3.** Run the reproduction command above. Tell us if you get a different result.

2. **Stress assumption Q1.** Implement snapshot-authority in a fork. Run the suite. Does it eliminate the F3 class? Does it break anything else?

3. **Submit a counterexample.** If you believe the protocol *cannot* be successfully attacked under a different parameter regime, construct the trace and show us. See the [Benchmark Challenge](challenge/BENCHMARK_CHALLENGE.md).

---

## Appendix: Scenario Corpus Summary

| Class | Count | Scenarios |
|-------|-------|-----------|
| Baseline / happy path | 3 | s01, s16, s09 |
| Dispute mechanics | 8 | s02–s05, s13, s17, s22, s23 |
| Resolver authority | 4 | s07, s14, s15, governance-decay-exploit |
| Kleros escalation | 5 | s18–s21, s19 |
| State-machine attack | 1 | s08 |
| Equilibrium / game theory | 10 | eq-v1–v10 |
| SPE analysis | 5 | spe-v1–v5 |
| Edge cases | 5 | s10, s11, s12a, s12b, fee-on-transfer-leak |
| Regression | 3 | padded-timelock-failure, same-block-ordering, timelock-regression |

Full trace corpus: `data/fixtures/traces/`
Full invariant scenario definitions: `src/resolver_sim/protocols/sew/invariant_scenarios.clj`
