"""
simulation.py — SimPy-based discrete-event multi-agent simulation.

Each agent runs as a SimPy process that emits PlannedActions at specific times.
The simulation collects events in seq order and exports them as a Scenario.

Design:
  - One SimPy process per agent lifecycle.
  - A shared event queue accumulates (simpy_time, PlannedAction) tuples.
  - After all processes complete, the queue is sorted by time → seq assigned.
  - The simulation does NOT execute state machine logic — it only generates events.
    All correctness guarantees come from the Clojure replay engine.

Typical usage:
  rng = random.Random(seed)
  sim = SEWSimulation(env, agents, ctx, rng)
  env.run(until=sim.run_until)
  scenario = sim.to_scenario(seed=seed)
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Any

import simpy

from .agents import (
    AgentStrategy,
    AttackingBuyer,
    AttackingResolver,
    GriefingBuyer,
    HonestBuyer,
    HonestResolver,
    OpportunisticBuyer,
    PlannedAction,
    SimContext,
)
from .schema import Agent, AgentType, Event, ProtocolParams, Scenario


# ---------------------------------------------------------------------------
# Simulation run result
# ---------------------------------------------------------------------------


@dataclass
class SimulationRun:
    """Output of a completed SimPy run — a list of raw events ready for export."""

    raw_events: list[tuple[float, PlannedAction]]  # (simpy_time, action)

    def to_events(self, initial_block_time: int) -> list[Event]:
        """
        Convert raw events to typed Event objects.
        Events are sorted by simpy_time then by insertion order (stable).
        Block-time = initial_block_time + int(simpy_time).
        """
        sorted_raw = sorted(self.raw_events, key=lambda x: x[0])
        return [
            Event(
                seq=i,
                time=initial_block_time + int(sim_time),
                agent=pa.agent_id,
                action=pa.action,  # type: ignore[arg-type]
                params=pa.params,
            )
            for i, (sim_time, pa) in enumerate(sorted_raw)
        ]


# ---------------------------------------------------------------------------
# Core simulation class
# ---------------------------------------------------------------------------


class SEWSimulation:
    """
    SimPy-based SEW protocol simulation.

    Orchestrates multiple agent processes, collecting their planned actions
    into a time-ordered event sequence.

    Parameters
    ----------
    env         — SimPy Environment
    agent_defs  — list of (Agent, AgentStrategy, counterparty_addr, amount) tuples
    ctx         — SimContext shared by all agents
    resolver    — HonestResolver instance for post-dispute resolution steps
    """

    def __init__(
        self,
        env: simpy.Environment,
        agent_defs: list[tuple[Agent, AgentStrategy, str, int]],
        ctx: SimContext,
        resolver: HonestResolver | None = None,
    ) -> None:
        self.env = env
        self.agent_defs = agent_defs
        self.ctx = ctx
        self.resolver = resolver or HonestResolver()

        self._raw_events: list[tuple[float, PlannedAction]] = []
        self._wf_counter: list[int] = [0]  # shared workflow-id predictor
        self._resolver_queue: list[tuple[float, int, bool]] = []  # (time, wf_id, is_release)

    def _emit(self, simpy_time: float, pa: PlannedAction) -> None:
        self._raw_events.append((simpy_time, pa))

    def _agent_process(
        self,
        agent: Agent,
        strategy: AgentStrategy,
        counterparty_addr: str,
        amount: int,
    ):
        """SimPy generator: execute one agent's planned actions."""
        actions = strategy.plan(
            agent_id=agent.id,
            counterparty_addr=counterparty_addr,
            amount=amount,
            ctx=self.ctx,
            wf_counter=self._wf_counter,
        )

        # Sort by delay so we process in chronological order
        actions = sorted(actions, key=lambda a: a.delay)
        current_delay = 0.0

        for pa in actions:
            wait = pa.delay - current_delay
            if wait > 0:
                yield self.env.timeout(wait)
            current_delay = pa.delay

            self._emit(self.env.now, pa)

            # If this is a raise_dispute, schedule resolver response
            if pa.action == "raise_dispute" and self.resolver is not None:
                wf_id = pa.params.get("workflow_id", 0)
                resolution_time = self.env.now + self.resolver.resolution_delay
                self._resolver_queue.append((resolution_time, wf_id, True))

    def _resolver_process(self, resolver_agent: Agent):
        """SimPy generator: resolver responds to queued disputes."""
        # Wait a bit then check queue
        while True:
            if self._resolver_queue:
                target_time, wf_id, is_release = self._resolver_queue.pop(0)
                wait = max(0, target_time - self.env.now)
                if wait > 0:
                    yield self.env.timeout(wait)
                pa = self.resolver.plan_resolution(
                    agent_id=resolver_agent.id,
                    workflow_id=wf_id,
                    is_release=is_release,
                )
                self._emit(self.env.now, pa)
            else:
                # No disputes yet — check again in 1 time unit
                yield self.env.timeout(1)
                if self.env.now > 10_000:  # safety exit
                    break

    def run(
        self,
        resolver_agent: Agent | None = None,
        until: float = 5_000.0,
    ) -> SimulationRun:
        """
        Launch all agent processes and run the simulation.

        resolver_agent — if provided, a dedicated resolver process is started.
        until          — max simulation time (seconds).

        Returns a SimulationRun with collected raw events.
        """
        # Start agent processes
        for agent, strategy, counterparty_addr, amount in self.agent_defs:
            self.env.process(
                self._agent_process(agent, strategy, counterparty_addr, amount)
            )

        # Start resolver process if there is a resolver agent
        if resolver_agent is not None:
            self.env.process(self._resolver_process(resolver_agent))

        self.env.run(until=until)
        return SimulationRun(raw_events=list(self._raw_events))


# ---------------------------------------------------------------------------
# Factory: build common scenario configurations
# ---------------------------------------------------------------------------


def build_honest_trade(
    seed: int,
    amount: int = 10_000,
    token: str = "0xUSDC",
    release_delay: int = 50,
) -> Scenario:
    """Single honest buyer + seller + resolver. No disputes. Happy path."""
    rng = random.Random(seed)
    env = simpy.Environment()

    alice = Agent(id="alice", type=AgentType.honest, address="0xAlice")
    bob = Agent(id="bob", type=AgentType.honest, address="0xBob")
    resolver = Agent(id="resolver", type=AgentType.honest, address="0xResolver")
    pp = ProtocolParams()

    ctx = SimContext(
        token=token,
        resolver_address=resolver.address,
        rng=rng,
        protocol_params=pp.model_dump(),
    )

    strategy = HonestBuyer(release_delay=release_delay)
    sim = SEWSimulation(
        env=env,
        agent_defs=[(alice, strategy, bob.address, amount)],
        ctx=ctx,
    )
    run = sim.run(resolver_agent=resolver, until=release_delay + 100)
    events = run.to_events(initial_block_time=1000)

    return Scenario(
        seed=seed,
        agents=[alice, bob, resolver],
        protocol_params=pp,
        initial_block_time=1000,
        events=events,
    )


def build_dispute_scenario(
    seed: int,
    amount: int = 10_000,
    token: str = "0xUSDC",
    attacker_ratio: float = 0.5,
) -> Scenario:
    """
    Mixed honest + attacker buyers against an honest resolver.

    attacker_ratio controls what fraction of buyer agents are attackers.
    """
    rng = random.Random(seed)
    env = simpy.Environment()

    buyer_a = Agent(id="buyer_a", type=AgentType.attacker, address="0xBuyerA")
    buyer_b = Agent(id="buyer_b", type=AgentType.honest, address="0xBuyerB")
    seller = Agent(id="seller", type=AgentType.honest, address="0xSeller")
    resolver_agent = Agent(id="resolver", type=AgentType.honest, address="0xResolver")
    pp = ProtocolParams()

    ctx = SimContext(
        token=token,
        resolver_address=resolver_agent.address,
        rng=rng,
        protocol_params=pp.model_dump(),
    )

    attacker_strategy = AttackingBuyer(dispute_delay=1)
    honest_strategy = HonestBuyer(release_delay=50)
    honest_resolver = HonestResolver(resolution_delay=10)

    sim = SEWSimulation(
        env=env,
        agent_defs=[
            (buyer_a, attacker_strategy, seller.address, amount),
            (buyer_b, honest_strategy, seller.address, amount + rng.randint(0, 500)),
        ],
        ctx=ctx,
        resolver=honest_resolver,
    )
    run = sim.run(resolver_agent=resolver_agent, until=200)
    events = run.to_events(initial_block_time=1000)

    return Scenario(
        seed=seed,
        agents=[buyer_a, buyer_b, seller, resolver_agent],
        protocol_params=pp,
        initial_block_time=1000,
        events=events,
    )


def build_griefing_scenario(
    seed: int,
    amount: int = 5_000,
    token: str = "0xUSDC",
    max_dispute_duration: int = 500,
) -> Scenario:
    """
    Griefing buyer raises dispute then abandons — tests auto-cancel timeout path.
    """
    rng = random.Random(seed)
    env = simpy.Environment()

    griefing_buyer = Agent(id="griefing", type=AgentType.attacker, address="0xGriefer")
    seller = Agent(id="seller", type=AgentType.honest, address="0xSeller")
    resolver_agent = Agent(id="resolver", type=AgentType.honest, address="0xResolver")
    pp = ProtocolParams(max_dispute_duration=max_dispute_duration)

    ctx = SimContext(
        token=token,
        resolver_address=resolver_agent.address,
        rng=rng,
        protocol_params=pp.model_dump(),
    )

    strategy = GriefingBuyer()
    sim = SEWSimulation(
        env=env,
        agent_defs=[(griefing_buyer, strategy, seller.address, amount)],
        ctx=ctx,
        resolver=None,  # resolver never acts — griefing scenario
    )
    run = sim.run(resolver_agent=None, until=max_dispute_duration + 100)
    events = run.to_events(initial_block_time=1000)

    return Scenario(
        seed=seed,
        agents=[griefing_buyer, seller, resolver_agent],
        protocol_params=pp,
        initial_block_time=1000,
        events=events,
    )
