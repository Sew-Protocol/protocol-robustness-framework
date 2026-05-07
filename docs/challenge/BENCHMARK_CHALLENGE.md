# SEW Dispute Protocol — "Break This Mechanism" Benchmark Challenge

**Repository:** [Sew-Protocol/sew-simulation](https://github.com/Sew-Protocol/sew-simulation)
**Protocol version:** SEW v0.1 (ethereum branch)
**Challenge status:** Open

> This is a standing challenge. There is no deadline. All valid submissions are logged.

---

## Background

The SEW protocol is a decentralised escrow dispute-resolution mechanism. It has been simulated against **41 deterministic invariant scenarios** (S01–S41, Clojure in-process suite) and **33 adversarial scenarios** (S24–S35, Python gRPC suite), with 31 protocol invariants checked at every state transition.

**Headline result:** 22% adversarial success rate across the corpus. Zero invariant violations. All attacks succeed through *correct* use of the protocol against itself — not implementation bugs.

The challenge: find what we missed.

---

## Three Tasks

You may attempt any or all. Tasks are independent.

---

### Task A — Find a Profitable Attack

**Goal:** Construct a trace in which an adversarial agent extracts value from the protocol that exceeds the cost of the attack.

**Profitability criterion:**
```
net_gain = funds_extracted - (gas_cost_equivalent + opportunity_cost)
profitable := net_gain > 0
```

**Starting points:**
- Look at the adversarial profitability surface: `bb adv:sweep` generates `results/profitability-surfaces/*/surface.csv`
- Existing partial attack traces: `data/fixtures/traces/governance-decay-exploit.trace.json`, `data/fixtures/traces/s08-state-machine-attack-gauntlet.trace.json`
- Adversarial agent code: `python/adversarial_agent.py`, `python/resolver_withdrawal_adversarial.py`

**Submission format:**
```json
{
  "task": "A",
  "trace_file": "path/to/your.trace.json",
  "claimed_gain": 42.0,
  "attack_description": "one paragraph",
  "reproduce_command": "bb trace:compare --baseline ... --candidate ..."
}
```

**Scoring:**

| Score | Criterion |
|-------|-----------|
| 1 pt | Trace runs without error against the live Clojure server |
| 2 pts | `net_gain > 0` under the stated assumptions |
| 3 pts | `net_gain > 0` after we stress-test with a ±20% parameter sweep |
| Bonus | Attack generalises to a class of parameters (not just one point) |

---

### Task B — Find an Invariant Violation

**Goal:** Cause the protocol to enter a state that violates one of the **31 invariants** defined in `src/resolver_sim/protocols/sew/invariants.clj`.

The invariant suite is the protocol's formal specification. A violation means the state machine is broken — not just attacked.

**Invariant catalogue:** See `src/resolver_sim/protocols/sew/invariants.clj` and `docs/testing/TEST_SUITE.md`.

Key invariant classes:
- `solvency-invariant` — held tokens ≥ sum of all escrow amounts
- `no-double-finalize` — no escrow transitions to a terminal state twice
- `resolver-authority` — only the authorized resolver may execute resolution
- `fee-accounting` — fee extracted ≤ fee promised at creation

**How to check invariants:**
```bash
# Run the deterministic suite (S01–S41, ~1s, no server needed)
clojure -M:run -- --invariants

# Run against your own trace via the gRPC server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 8
cd python
python invariant_suite.py --scenario YOUR_SCENARIO --json results/my-violation.json
```

**Submission format:**
```json
{
  "task": "B",
  "invariant_id": "solvency-invariant",
  "trace_file": "path/to/your.trace.json",
  "violation_description": "one paragraph",
  "reproduce_command": "python invariant_suite.py ..."
}
```

**Scoring:**

| Score | Criterion |
|-------|-----------|
| 3 pts | Invariant violation confirmed in simulation output |
| 5 pts | Violation is reproducible from a fresh clone with a single command |
| Bonus | Violation represents a class of inputs (parametric, not a single trace) |
| Bonus | Violation is novel (not in the existing 41-scenario corpus) |

---

### Task C — Beat Baseline Dispute Latency or Cost

**Goal:** Propose a mechanism change (resolver module configuration, fee parameter, escalation rule) that measurably reduces dispute resolution latency or cost without increasing adversarial success rate.

**Baseline numbers (from suite run, git `31ba27b`):**

| Metric | Baseline |
|--------|----------|
| Mean resolution latency | measured in simulation ticks from `raise_dispute` to `execute_resolution` |
| Mean fee extracted | `fee_bps=100` on 100-token escrow = 1 token |
| Adversarial success rate | 22% (9 of 41 attack attempts across 33 adversarial scenarios) |

**Instructions:**
1. Modify `data/params/` to change protocol parameters, OR
2. Implement a new resolver module in `protocols/sew/` and add a fixture trace, OR
3. Propose a rule change with supporting scenario traces.

Run the full suite before and after to compare:
```bash
clojure -M:run -- --invariants   # must still pass 41/41
bb adv:sweep                      # adversarial success rate must not increase
```

**Submission format:**
```json
{
  "task": "C",
  "change_description": "one paragraph",
  "before_latency": 120,
  "after_latency": 85,
  "before_adv_success_rate": 0.22,
  "after_adv_success_rate": 0.20,
  "reproduce_command": "clojure -M:run -- ..."
}
```

**Scoring:**

| Score | Criterion |
|-------|-----------|
| 1 pt | Mechanism change is implemented and the suite still passes |
| 2 pts | Latency or cost reduction ≥ 10% vs baseline |
| 3 pts | Latency or cost reduction ≥ 25% vs baseline |
| Bonus | Adversarial success rate also decreases |
| Bonus | Change is fully backward-compatible (no trace format changes) |

---

## How to Submit

Open a GitHub issue with:
- Title: `[Challenge] Task A/B/C — <one-line description>`
- The JSON submission block above
- The trace file or parameter file attached or linked

We will:
1. Clone the submission, run the reproduce command, verify the result.
2. Update this file with confirmed results.
3. Credit the submission in the repository.

---

## Setup (≤10 minutes)

```bash
# 1. Clone
git clone https://github.com/Sew-Protocol/sew-simulation
cd sew-simulation

# 2. Start the Clojure gRPC server (Tasks A and B via Python)
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 10

# 3. Verify — run the deterministic invariant suite (no server needed)
clojure -M:run -- --invariants
# Expected: 41/41 pass

# 4. Verify — run a sample adversarial scenario
cd python
python invariant_suite.py --scenario F3
# Expected: scenario passes, attack metrics logged
```

Requirements: JDK 11+, Clojure CLI, Python 3.10+, Babashka.

---

## Known Limitations (Accepted Attack Surface)

These are *known* and documented — submissions in these areas are lower value:

- Governance key compromise (modelled but not mitigated — out of scope for this protocol layer)
- Oracle price manipulation (modelled in `oracle/` — a separate challenge)
- Gas-cost ordering in same-block transactions (see `same-block-ordering.trace.json`)

The highest-value submissions target structural composability failures we have not yet identified.

---

## Confirmed Submissions

*None yet — be the first.*

---

## Questions

Open a GitHub Discussion or tag `@Sew-Protocol` on the issue.
