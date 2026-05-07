# Adversarial Simulation Evidence — Summary

**Repository:** SEW Dispute Resolution Simulator
**Suite version:** git `31ba27b` (ethereum branch)
**Run date:** 2026-04-15
**Result:** 33/33 scenarios passed · 0 invariant violations

---

## What This Evidence Shows

This repository contains a set of reproducible adversarial simulations demonstrating failure modes in Ethereum dispute resolution systems. Each scenario is deterministic, runs against a live contract state machine, and is verified against 31 protocol invariants at every state transition.

These are not theoretical vulnerabilities — they are emergent failures that occur under realistic multi-agent conditions that existing security tools cannot detect.

---

## Suite-Level Statistics

| Metric | Value |
|--------|-------|
| Total transactions processed | 327 |
| Rejected (correct reverts) | 189 (57.8%) |
| Escrows created | 43 |
| Total escrow volume simulated | 112,701 tokens |
| Disputes triggered | 32 |
| Resolutions executed | 27 |
| Attack attempts logged | 41 |
| Attack successes | 9 (22.0%) |
| **Invariant violations** | **0** |

Attack successes (22%) represent cases where an adversarial agent achieved its objective (e.g., disputed a pending settlement, succeeded in rotating a resolver mid-dispute). Zero invariant violations means the protocol did not enter a corrupt state in any of these cases — the failures are structural design issues, not implementation bugs.

---

## Highlighted Failure Modes

### 🏛 Governance Failure — Resolver Rotation Mid-Dispute

**Scenario:** F3 — Governance Sandwich

A governance actor with legitimate authority rotates the authorized resolver after a dispute opens but before it resolves. The replacement resolver is malicious and resolves the in-flight dispute in the attacker's favour. Each individual action — governance rotation and resolution — is fully authorized by the protocol.

**Measured outcome:**
- `rotations=1` — governance rotation executed mid-dispute
- `malicious_resolutions_succeeded=1` — malicious resolver successfully altered the outcome
- `invariant_violations=0` — protocol state remained consistent; this is not a bug

**Impact:** Retroactive outcome manipulation — an escrow party receives an outcome they could not have anticipated at contract creation, via a governance action that appears routine.

**Risk:** Fund extraction without violating any contract rule. Invisible to per-function audits.

[Full scenario →](detailed/F3-governance-sandwich.md) · [Raw JSON →](../../results/evidence/f3-run.json)

---

### 💸 Economic Failure — Rational Resolver Withdrawal

**Scenario:** F7 — Profit-Threshold Strike

A resolver with a minimum profit threshold refuses to service an escrow where the protocol fee is below its cost floor. The escrow has 100 tokens, `fee_bps=100` (fee = 1 token), but the resolver requires `min_profit=5`. The resolver logs the refusal off-chain and takes no action. The dispute window expires with zero resolutions.

**Measured outcome:**
- `resolutions_executed=0` — no resolution in the run window
- `refusals=1` — resolver explicitly refused to act
- `invariant_violations=0` — no protocol error raised

**Impact:** Funds permanently locked in a dispute. No on-chain signal. Users have no recourse without governance intervention.

**Risk:** Any escrow below a resolver's cost floor is silently unserviced. An attacker can create many small disputed escrows to exhaust a resolver's attention without triggering any detectable fault.

[Full scenario →](detailed/F7-profit-threshold-strike.md) · [Raw JSON →](../../results/evidence/f7-run.json)

---

### 🌊 Liveness Failure — Capacity-Limited Arbitrator

**Scenario:** F10 — Cascade Escalation Drain

Four buyers simultaneously raise disputes against a single arbitrator capped at two resolutions. The arbitrator processes the first two and stops. Escrows 2 and 3 remain permanently in `disputed` state.

**Measured outcome:**
- `disputes=4` — all four disputes raised
- `arbitrator.resolutions=2` — arbitrator processed first two only
- `still_disputed=2` — two escrows permanently unresolved
- `invariant_violations=0` — no protocol rule violated

**Impact:** Permanent fund lockup for 50% of affected escrows. No on-chain error.

**Risk:** A coordinated dispute flood overwhelms a single-arbitrator system at negligible cost. The `resolve()` function is callable and valid — the failure only manifests under concurrent load.

[Full scenario →](detailed/F10-cascade-escalation.md) · [Raw JSON →](../../results/evidence/f10-run.json)

---

## Why Existing Tools Miss These

| Tool | Why it misses these failures |
|------|----------------------------|
| Slither, Mythril | Analyse individual functions; cannot model actor sequences |
| Echidna, Foundry fuzz | Generate random inputs to single functions; no multi-actor intent |
| Unit tests | Test one ordering; miss concurrent and time-ordered interactions |
| Manual audit | Per-function analysis; emergent multi-step interactions require simulation |

All three highlighted failures share the same root cause: **each individual transaction is valid and authorized**. The failure is in the sequence, not the function.

---

## All Scenarios Passing

```
33/33 scenarios passed  (0.6s total)
  Invariant violations: 0
  git: 31ba27b (ethereum) · 2026-04-15T17:40:32Z
```

See [full-suite-run.json](../../results/evidence/full-suite-run.json) for complete machine-readable output.
