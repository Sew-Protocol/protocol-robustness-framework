# The Phase Z Discovery: A Case Study in Protocol Solvency

> "No state transition without full economic reconciliation."

This document details how the SEW Simulator identified a critical liquidity leak in the protocol's liveness logic and how that discovery led to a fundamental architectural rule for all future dispute-resolution standards.

---

## 1. The Scenario: Multi-Escrow Cascade (`phase-z-5`)

During a routine Monte Carlo sweep in **Phase Z (Legitimacy & Participation)**, the simulator encountered a "Death Spiral." Trust in the protocol plummeted, participation dropped below the security threshold (40%), and the system entered a state of permanent insolvency.

### The Trace Evidence:
The adversarial trace `trace_424_1777122159441` revealed the following sequence:
1.  **Creation**: Three high-value escrows created (225,000 USDC total).
2.  **Dispute**: The buyer raised disputes for all three simultaneously.
3.  **Liveness Failure**: The disputes remained unresolved beyond the `max-dispute-duration` (90 days).
4.  **Auto-Cancel**: The protocol's `auto_cancel_disputed` logic triggered to return funds to the buyer.

---

## 2. The Bug: The "Orphaned Liquidity" Leak

When `auto_cancel_disputed` was called, the state machine successfully transitioned the escrows to the `REFUNDED` state. However, the simulation's **Accounting Invariant** was violated.

**The mismatch:**
*   **Escrow State**: `REFUNDED` (Funds supposedly returned to buyer).
*   **Protocol State**: `total_held` remained at 225,000 USDC.
*   **Result**: The protocol "believed" it was still holding liquidity that had logically exited.

In a live environment, this would cause a **Liquidity Leak**. Users attempting to withdraw or resolvers attempting to claim fees would find the contract's real balance mismatched with its internal accounting, leading to stalled withdrawals and a catastrophic loss of trust.

---

## 3. The Fix: Atomic Reconciliation

The root cause was a **Partial Transition**. The code updated the *status* of the dispute but skipped the *economic consequences* of the timeout.

### Refactored Logic:
We refactored the lifecycle engine to enforce atomic reconciliation. Now, an `auto-cancel` transition is bundled with:
1.  **Resolver Slashing**: The assigned resolver is penalized for the timeout.
2.  **Bounty Distribution**: The slashed stake is distributed to the insurance pool and protocol.
3.  **Liquidity Clearance**: `total_held` is decremented in the same transaction as the state change.

---

## 4. The CDRS Rule

This discovery formalized a core mandate for the **Common Dispute Resolution Standard (CDRS)**:

> **"No state transition without full economic reconciliation."**

Any protocol conforming to this standard must prove that every state change is accompanied by a balance adjustment that maintains the global solvency invariant:  
`TotalValueIn == TotalValueOut + TotalValueHeld`

By using trace-equivalence and differential testing, the SEW Simulator ensures that these "hidden" accounting bugs are caught in the model before they reach the blockchain.
