# Sew Protocol — Technical Reference

## 5. Forward-Only Upgrades

> **Source**: Derived from `contracts/governance/SlowLaneQueueActivate.sol`,
> `contracts/admin/EscrowGovernanceTimelock.sol`, `contracts/core/ModuleSnapshotRegistry.sol`,
> `contracts/governance/GovGovernor.sol`, `contracts/governance/EmergencyRecoveryProposal.sol`,
> `contracts/types/EscrowTypes.sol` (`ModuleSnapshot`), and `contracts/core/BaseEscrow.sol`.

---

### 5.1 What "forward-only" means in this protocol

In most upgradeable systems, governance can push a change and, if it turns out to be wrong, simply
push a reverting change immediately after. The protocol swings back in one fast governance action.
This is the default behaviour of OZ `TimelockController` used alone.

Sew Protocol deliberately prevents this. Every protocol change follows an irreversible queue — once
a change enters the queue, the only way to undo it before it activates is to queue a *different*
value, which also incurs the same delay. There is no "cancel queued change" shortcut.
The combined effect is that the protocol can only move forward in time: once a module or parameter
is activated, retreating to the prior state costs at least another full delay period.

This is called **forward-only** upgrades.

The same principle applies at the escrow level in a stronger form: once an escrow is created, its
module configuration is frozen permanently. A new default module activated by governance does not
touch any existing escrow. Every escrow runs on the exact protocol version it was created under,
indefinitely, regardless of what happens to the protocol defaults after.

---

### 5.2 The two-layer delay structure

Protocol changes pass through two independent delay layers stacked in series:

```
Governance vote
      │
      ▼
TimelockController  ──  48-hour delay
      │                 (all operations, no exceptions)
      ▼
SlowLaneQueueActivate  ──  7-day delay
      │                    (module swaps, fee changes, resolver config, bond parameters)
      ▼
Change takes effect (for new escrows only)
```

**Layer 1 — Timelock (48h)**

`GovGovernor` is an OZ `GovernorTimelockControl`. All transactions must pass through
`TimelockController` with a minimum 48-hour delay before execution. No governance action bypasses
this layer.

**Layer 2 — Slow Lane (7 days)**

`SlowLaneQueueActivate` (and its upgradeable variant) adds a second mandatory delay for high-risk
parameter changes. The pattern has two explicit on-chain steps:

```solidity
// Step 1: queue (callable only after timelock delay)
function queueResolutionModule(address escrowContract, address module)
    external onlyRole(ROLE_TIMELOCK)
{
    _queueAddress(state.pendingResolutionModule, module);
    // Sets: pending.value = module
    //       pending.eta   = block.timestamp + 7 days
    //       pending.exists = true
}

// Step 2: activate (callable only after slow lane ETA)
function activateResolutionModule(address escrowContract)
    external onlyRole(ROLE_TIMELOCK)
{
    // Reverts if !pending.exists or block.timestamp < pending.eta
    address newModule = _activateAddress(state.pendingResolutionModule);
    // Clears pending state; applies newModule
}
```

`_queueAddress` hard-reverts on `address(0)`, so a module cannot be removed by zeroing it — it can
only be replaced by another valid address.

**Combined minimum latency for a module change to affect new escrows:**

| Step | Minimum delay |
|------|--------------|
| Governance proposal voting | configurable (typically 2–7 days) |
| TimelockController execution delay | 48 hours |
| SlowLane queue → activate | 7 days |
| **Total** | **≥ 9 days + voting period** |

Reverting to the prior module costs the same total again: there is no fast path backwards.

---

### 5.3 Escrow-level immutability: the `ModuleSnapshot`

Every escrow captures a `ModuleSnapshot` at creation time in `moduleSnapshots[workflowId]`. This
struct is written once and never updated:

```solidity
struct ModuleSnapshot {
    address resolutionModule;
    address releaseStrategy;
    address cancellationStrategy;
    address yieldGenerationModule;
    address yieldDistributionModule;
    address incentiveModule;
    uint256 yieldProtocolFeeBps;
    uint256 appealBondProtocolFeeBps;
    uint256 escrowFeeBps;
    uint256 defaultAutoReleaseDelay;
    uint256 defaultAutoCancelDelay;
    uint256 maxDisputeDuration;
    uint256 appealWindowDuration;
}
```

Every dispute, resolution, escalation, bond calculation, and fee collection uses the values from
this snapshot — not the current global defaults. A governance action that activates a new
resolution module for new escrows has no effect on any escrow whose snapshot is already written.

This is the strongest form of forward-only: **existing escrows are not upgradeable at all.**

#### What the snapshot freezes

| Parameter | Consequence of freezing |
|-----------|------------------------|
| `resolutionModule` | The dispute resolution contract cannot be replaced mid-dispute |
| `incentiveModule` | Bond distribution rules are fixed at creation |
| `escrowFeeBps` | The fee rate cannot be raised on funds already in escrow |
| `appealBondProtocolFeeBps` | Protocol cut of appeal bonds cannot change after creation |
| `maxDisputeDuration` | The liveness timeout cannot be shortened or lengthened post-creation |
| `appealWindowDuration` | The time allowed to appeal a resolution is fixed |
| `yieldProtocolFeeBps` | Yield fee cannot be altered for deployed positions |

A user who creates an escrow with module X and dispute timeout of 14 days will always have those
terms, even if governance later switches to module Y with a 7-day timeout for all new escrows.

---

### 5.4 What cannot be rolled back

The following list describes what the contracts make structurally impossible to reverse quickly:

**Module addresses.** Once a new module is activated and starts receiving snapshot writes,
retreating to the old module requires a full new queue/activate cycle (minimum 9 days). There is
no `cancelPending()` function.

**Dispute outcomes.** Once a dispute transitions to `RELEASED` or `REFUNDED` (terminal states in
`EscrowState`), no governance action can reverse it. There is no `undoResolution` function. The
state machine has no transitions out of terminal states.

**Resolution decisions during appeal window.** Once `releaseAsDisputeResolver` or
`cancelAsDisputeResolver` records a `PendingSettlement`, it cannot be cancelled by governance.
Only the losing party can cancel it — by exercising their right to escalate before the
`appealDeadline`.

**Escalation path.** Each call to `escalateDispute` increments the round counter in the DRM.
There is no `deescalate` function. The dispute moves forward through rounds only; it cannot
retreat to a prior resolver.

**Bond deposits.** Once an appeal bond is deposited into the incentive module, it is held until
finality logic distributes it. Governance cannot seize or cancel individual bond deposits.

**Kleros rulings.** Once `KlerosArbitrableProxy.rule()` records a ruling, `dispute.resolved` is
set to `true`. The ruling is permanent. There is no `retractRuling` function, and
`KlerosArbitrableProxy.canEscalate` always returns false, making Kleros the terminal stage.

---

### 5.5 Emergency path — what it is and what it is not

`EmergencyRecoveryProposal` provides a governance-controlled recovery mechanism for use when the
system is paused. It is explicitly *not* a rollback mechanism:

```solidity
// Safety check: only allow recovery proposals when paused
if (!escrowVault.paused()) {
    revert SystemNotPaused(address(escrowVault));
}
```

It is available only when the guardian has paused the vault (an extreme incident response
measure). Even then, it requires:

1. A governance vote.
2. A 2-day execution delay from approval.
3. Execution of a pre-approved recovery template (`EMERGENCY_UNWIND_AAVE`,
   `WITHDRAW_PAUSED_ESCROWS`, `RESET_YIELD_MODULES`, `UPDATE_GUARDIAN_ADDRESS`).

None of these templates can reverse a completed escrow settlement, alter a snapshot, or undo a
Kleros ruling. Emergency recovery is about fund safety in a compromised yield module, not about
reverting protocol-level governance decisions.

The guardian itself is also governance-controlled: `UPDATE_GUARDIAN_ADDRESS` requires a full
governance vote, so the guardian cannot be unilaterally replaced by a single actor.

---

### 5.6 Benefits

#### For users

**Predictable escrow terms.** A user who reads the module addresses and parameters at escrow
creation time knows precisely what rules apply for the lifetime of their escrow. They do not need
to monitor governance proposals; a governance upgrade cannot change the rules under which their
funds are held.

**No surprise fee changes.** `escrowFeeBps`, `yieldProtocolFeeBps`, and
`appealBondProtocolFeeBps` are all snapshotted. A governance action that raises any fee only
applies to new escrows. Users with open escrows are unaffected.

**Protection against rushed or malicious governance.** A governance key compromise or a
whale-driven governance attack cannot immediately alter the resolution module for in-flight
disputes. At minimum 9 days must pass before any such change affects new escrows, giving the
community time to detect and respond.

#### For auditors and security reviewers

**Bounded blast radius.** A bug in module V2 cannot retroactively affect escrows that were created
under module V1. The impact of a compromised or buggy module is bounded to the set of escrows
created after its activation.

**Deterministic state transitions.** Because an escrow's effective configuration is frozen at
creation, its entire lifecycle can be reproduced deterministically from the creation transaction
alone. There are no mid-lifecycle configuration reads that might produce different results
depending on when a transaction is replayed.

**Auditable governance trail.** Every change produces two on-chain events: one for the queue step
(containing the proposed value and ETA) and one for the activate step. An auditor can reconstruct
the complete history of every parameter and module address, with timestamps, from event logs alone.

**No admin override of individual escrows.** There is no function in `BaseEscrow` that allows an
admin to alter the `moduleSnapshots[workflowId]` mapping for an existing escrow. The only way to
influence an existing escrow's behaviour after creation is through the resolution process defined
in its snapshotted module — which is itself a forward-only, auditable process.

#### For protocol development

**Staged rollouts are safe by default.** A new module version can be activated as the default for
new escrows while all existing escrows continue running on prior versions. Both versions coexist.
There is no need for a migration or re-enrollment step.

**Protocol version history is automatically preserved.** Because snapshots reference module
addresses directly, old module versions remain deployed and in use for as long as any escrow
references them. There is no accidental breakage of existing escrows when deploying module V2.

**Incentive to get modules right before activation.** The minimum 9-day window between proposing
and activating a module change creates a strong incentive to thoroughly audit and test any change
before it enters the queue. The cost of a mistake is measured not just in code but in time: fixing
a bug in an activated module requires deploying a new module and waiting another 9 days.

---

### 5.7 Tradeoffs and limitations

The forward-only model is not cost-free.

**Critical bug response is slow.** If a bug is discovered in the current default resolution
module, the minimum fix latency is ~9 days (plus voting). During that window, new escrows continue
to be created under the buggy module unless the guardian pauses new escrow creation. This is the
intended tradeoff: users get protection from fast governance attacks at the cost of slow
governance response to bugs.

**Existing escrows cannot be migrated.** A user whose escrow was created under a module with a
known vulnerability cannot be moved to a fixed module. Their escrow will run to conclusion under
the original module. This is by design: forced migration would itself be an unacceptable governance
power over user funds.

**Debt to prior module versions.** Old module versions remain active (and must remain deployed)
for as long as any escrow references them. The protocol accumulates deployed module versions over
time. This is a storage and maintenance consideration, not a security risk, but auditors reviewing
the full system must understand which escrows reference which module versions.

**Parameter drift between cohorts.** Different escrow cohorts may have meaningfully different
parameters (fee rates, appeal windows, dispute timeouts). This is correct by design but
complicates aggregate analysis and fee projection.

---

### 5.8 Reference: delays summary

| Mechanism | Delay | Where enforced |
|-----------|-------|----------------|
| Governor TimelockController | 48 hours | `GovGovernor` / `TimelockController` |
| Slow lane queue → activate (module swaps, fees) | 7 days | `SlowLaneQueueActivate.SLOW_DELAY` |
| Slow lane queue → activate (DRM escalation config) | 7 days | `DRMAdminFacet`, `SLOW_DELAY` |
| Slow lane queue → activate (DRM cost curve) | 7 days | `DRMAdminFacet`, `SLOW_DELAY` |
| Bond token registry queue → activate | 7 days | `BondTokenRegistry.SLOW_DELAY` |
| Insurance pool parameter queue → activate | 7 days | `InsurancePoolVault.SLOW_DELAY` |
| Emergency recovery: approval → execution | 2 days | `EmergencyRecoveryProposal` |
| Multi-L2 module coordinator activation | 48 hours minimum | `MultiL2ModuleCoordinator.MIN_ACTIVATION_DELAY` |
| Escrow module snapshot | Permanent (creation-time freeze) | `BaseEscrow.moduleSnapshots` |
