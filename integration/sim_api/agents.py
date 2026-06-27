"""
agents.py — Agent strategy classes for SEW adversarial simulation.

Each agent type implements a `plan(sim_state) → list[PlannedAction]` interface.
The simulation orchestrator calls plan() at the start of each agent's lifecycle
to get the list of events that agent will emit over time.

Design:
  - Agents are STATELESS strategy objects (pure functions over sim_state).
  - All randomness flows through a seeded Random instance passed at construction.
  - Agents do NOT enforce invariants — they generate candidate actions.
    The Clojure replay engine enforces all correctness guarantees.

Agent types:
  HonestBuyer      — creates escrow, waits, releases (no dispute)
  HonestSeller     — accepts all correctly; never initiates disputes
  HonestResolver   — always rules correctly (pro-recipient when goods delivered)
  AttackingBuyer   — creates escrow then raises false dispute, tries to refund
  AttackingResolver— tries to resolve escrows it is not authorized for
  GriefingBuyer    — raises dispute then never cooperates (locks funds)
  OpportunisticBuyer — sometimes disputes, sometimes releases (random)
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Any, Protocol


# ---------------------------------------------------------------------------
# PlannedAction: what an agent intends to emit at a given simulation time
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PlannedAction:
    """
    A single action an agent intends to emit during the simulation.

    delay    — SimPy timeout (seconds after start) before emitting.
    agent_id — the agent emitting the action.
    action   — ActionName string.
    params   — action parameters (addresses resolved at planning time).
    """

    delay: float
    agent_id: str
    action: str
    params: dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# SimContext — what agents know about the world when planning
# ---------------------------------------------------------------------------


@dataclass
class SimContext:
    """
    Minimal world-view passed to agent.plan().

    Agents may only read this — they must NOT mutate it.
    All addresses must already be resolved (no agent-id lookups at runtime).
    """

    token: str
    resolver_address: str
    rng: random.Random
    protocol_params: dict[str, Any] = field(default_factory=dict)

    def next_workflow_id(self, counter: list[int]) -> int:
        """
        Predict the workflow-id for the next create_escrow in this scenario.
        counter is a mutable [int] cell shared across all agents in one scenario.
        """
        wf_id = counter[0]
        counter[0] += 1
        return wf_id


# ---------------------------------------------------------------------------
# Agent protocol
# ---------------------------------------------------------------------------


class AgentStrategy(Protocol):
    """
    Interface every agent strategy must implement.

    plan(agent_id, counterparty_address, amount, ctx, wf_counter)
      → list of PlannedActions ordered by delay (ascending).

    agent_id           — this agent's id string
    counterparty_addr  — the other party's Ethereum address
    amount             — escrow amount (wei)
    ctx                — SimContext
    wf_counter         — mutable [int] cell for workflow-id prediction
    """

    def plan(
        self,
        agent_id: str,
        counterparty_addr: str,
        amount: int,
        ctx: SimContext,
        wf_counter: list[int],
    ) -> list[PlannedAction]: ...


# ---------------------------------------------------------------------------
# Honest buyer
# Lifecycle: create_escrow → (wait) → release (happy path)
# ---------------------------------------------------------------------------


@dataclass
class HonestBuyer:
    """
    Creates an escrow and releases funds when satisfied (no dispute).
    """

    release_delay: int = 50  # seconds between creation and release

    def plan(
        self,
        agent_id: str,
        counterparty_addr: str,
        amount: int,
        ctx: SimContext,
        wf_counter: list[int],
    ) -> list[PlannedAction]:
        wf_id = ctx.next_workflow_id(wf_counter)
        return [
            PlannedAction(
                delay=0,
                agent_id=agent_id,
                action="create_escrow",
                params={
                    "token": ctx.token,
                    "to": counterparty_addr,
                    "amount": amount,
                    "custom_resolver": ctx.resolver_address,
                },
            ),
            PlannedAction(
                delay=self.release_delay,
                agent_id=agent_id,
                action="release",
                params={"workflow_id": wf_id},
            ),
        ]


# ---------------------------------------------------------------------------
# Attacking buyer
# Lifecycle: create_escrow → raise_dispute (false) → wait for auto-cancel
# Goal: lock funds or trick resolver into incorrect refund
# ---------------------------------------------------------------------------


@dataclass
class AttackingBuyer:
    """
    Creates an escrow, immediately raises a false dispute, and waits hoping
    for an incorrect resolver verdict or dispute timeout.
    """

    dispute_delay: int = 1

    def plan(
        self,
        agent_id: str,
        counterparty_addr: str,
        amount: int,
        ctx: SimContext,
        wf_counter: list[int],
    ) -> list[PlannedAction]:
        wf_id = ctx.next_workflow_id(wf_counter)
        return [
            PlannedAction(
                delay=0,
                agent_id=agent_id,
                action="create_escrow",
                params={
                    "token": ctx.token,
                    "to": counterparty_addr,
                    "amount": amount,
                    "custom_resolver": ctx.resolver_address,
                },
            ),
            PlannedAction(
                delay=self.dispute_delay,
                agent_id=agent_id,
                action="raise_dispute",
                params={"workflow_id": wf_id},
            ),
        ]


# ---------------------------------------------------------------------------
# Attacking resolver
# Tries to execute resolution on an escrow it is NOT authorized to resolve.
# The Clojure state machine will reject this — the test is that it remains a
# clean revert with no invariant violation.
# ---------------------------------------------------------------------------


@dataclass
class AttackingResolver:
    """
    Attempts to resolve escrows without being the authorized resolver.
    All such attempts must be rejected by the Clojure state machine.
    """

    def plan(
        self,
        agent_id: str,
        counterparty_addr: str,  # the real resolver address (to target correct escrow)
        amount: int,
        ctx: SimContext,
        wf_counter: list[int],
    ) -> list[PlannedAction]:
        # Attempt to resolve the most recently created escrow (predicted wf_id - 1)
        # wf_counter is NOT incremented — attacker doesn't create a new escrow
        target_wf = max(0, wf_counter[0] - 1)
        is_release = ctx.rng.choice([True, False])
        return [
            PlannedAction(
                delay=2,
                agent_id=agent_id,
                action="execute_resolution",
                params={
                    "workflow_id": target_wf,
                    "is_release": is_release,
                    "resolution_hash": "0xattacker_resolution",
                },
            ),
        ]


# ---------------------------------------------------------------------------
# Griefing buyer
# Creates escrow, disputes, never cooperates — tests dispute timeout path
# ---------------------------------------------------------------------------


@dataclass
class GriefingBuyer:
    """
    Creates an escrow and raises a dispute immediately, then does nothing.
    Tests the auto_cancel_disputed path after max_dispute_duration elapses.
    """

    def plan(
        self,
        agent_id: str,
        counterparty_addr: str,
        amount: int,
        ctx: SimContext,
        wf_counter: list[int],
    ) -> list[PlannedAction]:
        wf_id = ctx.next_workflow_id(wf_counter)
        max_dur = ctx.protocol_params.get("max_dispute_duration", 2_592_000)
        return [
            PlannedAction(
                delay=0,
                agent_id=agent_id,
                action="create_escrow",
                params={
                    "token": ctx.token,
                    "to": counterparty_addr,
                    "amount": amount,
                    "custom_resolver": ctx.resolver_address,
                },
            ),
            PlannedAction(
                delay=1,
                agent_id=agent_id,
                action="raise_dispute",
                params={"workflow_id": wf_id},
            ),
            # Anyone can trigger auto-cancel after timeout — the griefing
            # buyer doesn't need to; use a separate keeper process.
            # We record it here for single-agent scenario generation.
            PlannedAction(
                delay=max_dur + 1,
                agent_id=agent_id,
                action="auto_cancel_disputed",
                params={"workflow_id": wf_id},
            ),
        ]


# ---------------------------------------------------------------------------
# Honest resolver
# ---------------------------------------------------------------------------


@dataclass
class HonestResolver:
    """
    Executes resolution with is_release=True (correct verdict: deliver funds).
    """

    resolution_delay: int = 10

    def plan_resolution(
        self,
        agent_id: str,
        workflow_id: int,
        is_release: bool = True,
    ) -> PlannedAction:
        return PlannedAction(
            delay=self.resolution_delay,
            agent_id=agent_id,
            action="execute_resolution",
            params={
                "workflow_id": workflow_id,
                "is_release": is_release,
                "resolution_hash": f"0xhonest_{workflow_id}",
            },
        )


# ---------------------------------------------------------------------------
# Opportunistic buyer
# ---------------------------------------------------------------------------


@dataclass
class OpportunisticBuyer:
    """
    Sometimes disputes (p=dispute_prob), sometimes releases.
    Models real-world mixed behaviour.
    """

    dispute_prob: float = 0.3
    action_delay: int = 20

    def plan(
        self,
        agent_id: str,
        counterparty_addr: str,
        amount: int,
        ctx: SimContext,
        wf_counter: list[int],
    ) -> list[PlannedAction]:
        wf_id = ctx.next_workflow_id(wf_counter)
        actions = [
            PlannedAction(
                delay=0,
                agent_id=agent_id,
                action="create_escrow",
                params={
                    "token": ctx.token,
                    "to": counterparty_addr,
                    "amount": amount,
                    "custom_resolver": ctx.resolver_address,
                },
            )
        ]
        if ctx.rng.random() < self.dispute_prob:
            actions.append(
                PlannedAction(
                    delay=self.action_delay,
                    agent_id=agent_id,
                    action="raise_dispute",
                    params={"workflow_id": wf_id},
                )
            )
        else:
            actions.append(
                PlannedAction(
                    delay=self.action_delay,
                    agent_id=agent_id,
                    action="release",
                    params={"workflow_id": wf_id},
                )
            )
        return actions
