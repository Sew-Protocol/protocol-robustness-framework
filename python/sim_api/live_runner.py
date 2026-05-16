"""
Phase 2: live adversarial streaming simulation runner.

Drives a round-robin event loop where each agent decides its next action
based on the current world_view snapshot from Clojure.  Events are sent
one at a time via gRPC Step; Clojure executes and enforces all invariants.

The runner owns the seq counter and block-time advancement.  It never
holds or computes world state — it only reads the world_view snapshot
returned by each step.

Usage::

    from sim_api.grpc_client import SimulationClient, managed_session
    from sim_api.live_agents import HonestBuyerLive, HonestResolverLive
    from sim_api.live_runner import LiveRunner

    agents_meta = [
        {"id": "buyer",    "address": "0xbuyer",    "strategy": "honest"},
        {"id": "resolver", "address": "0xresolver", "role": "resolver", "strategy": "honest"},
    ]
    buyer    = HonestBuyerLive("buyer", "0xseller")
    resolver = HonestResolverLive("resolver")

    with SimulationClient() as client:
        runner = LiveRunner(client, agents_meta, [buyer, resolver])
        result = runner.run(session_id="run-1", max_steps=20)
        print(result["outcome"], result["metrics"])
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Optional

from sim_api.grpc_client import SimulationClient, managed_session
from sim_api.live_agents import LiveAgent


# ---------------------------------------------------------------------------
# Result type
# ---------------------------------------------------------------------------


@dataclass
class RunResult:
    session_id: str
    outcome: str          # "pass" | "halted" | "error"
    steps_executed: int
    halted_at_seq: Optional[int]
    halt_reason: Optional[str]
    trace: list[dict]
    metrics: dict[str, Any]
    final_world_view: Optional[dict]


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


class LiveRunner:
    """
    Round-robin live streaming runner.

    Agents are iterated in order each tick.  An agent that returns None from
    decide() is skipped for that tick.  Block-time advances by time_step_secs
    per tick regardless of how many agents acted.

    Parameters
    ----------
    client          SimulationClient instance (must be connected).
    agents_meta     List of agent dicts for StartSession (id/address/type).
    live_agents     Ordered list of LiveAgent instances (must match agents_meta ids).
    protocol_params Optional protocol parameter overrides.
    initial_block_time  Starting block timestamp.
    time_step_secs  Seconds to advance per tick (default: 60).
    """

    def __init__(
        self,
        client: SimulationClient,
        agents_meta: list[dict],
        live_agents: list[LiveAgent],
        protocol_params: dict | None = None,
        initial_block_time: int = 1000,
        time_step_secs: int = 60,
    ):
        self._client = client
        self._agents_meta = agents_meta
        self._live_agents = live_agents
        self._protocol_params = protocol_params or {}
        self._initial_block_time = initial_block_time
        self._time_step = time_step_secs

    def run(
        self,
        session_id: str | None = None,
        max_steps: int = 100,
        max_ticks: int = 50,
    ) -> RunResult:
        """
        Run the simulation.  Returns a RunResult regardless of outcome.

        max_steps  — hard limit on total events sent (across all agents and ticks)
        max_ticks  — hard limit on rounds through the agent list
        """
        import uuid
        sid = session_id or str(uuid.uuid4())

        with managed_session(
            self._client,
            self._agents_meta,
            self._protocol_params,
            self._initial_block_time,
            session_id=sid,
        ):
            return self._run_loop(sid, max_steps, max_ticks)

    def _run_loop(
        self, sid: str, max_steps: int, max_ticks: int
    ) -> RunResult:
        seq        = 0
        block_time = self._initial_block_time
        trace: list[dict] = []
        metrics = _zero_metrics()
        world_view: dict | None = None

        for _tick in range(max_ticks):
            if seq >= max_steps:
                break

            block_time += self._time_step

            for agent in self._live_agents:
                if seq >= max_steps:
                    break

                action = agent.decide(world_view, seq, block_time)
                if action is None:
                    continue

                event = {
                    "seq":    seq,
                    "time":   block_time,
                    "agent":  agent.agent_id,
                    "action": action["action"],
                    "params": action.get("params", {}),
                }

                resp = self._client.step(sid, event)
                agent.update_from_response(resp)

                entry = resp.get("trace_entry") or {}
                trace.append(entry)
                world_view = resp.get("world_view")
                _accum_metrics(metrics, action, entry, agent)

                seq += 1

                if resp.get("halted"):
                    halt_reason = resp.get("error") or _format_violations(
                        (resp.get("trace_entry") or {}).get("violations")
                    )
                    return RunResult(
                        session_id=sid,
                        outcome="halted",
                        steps_executed=seq,
                        halted_at_seq=seq - 1,
                        halt_reason=halt_reason,
                        trace=trace,
                        metrics=metrics,
                        final_world_view=world_view,
                    )

        return RunResult(
            session_id=sid,
            outcome="pass",
            steps_executed=seq,
            halted_at_seq=None,
            halt_reason=None,
            trace=trace,
            metrics=metrics,
            final_world_view=world_view,
        )


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------


def _zero_metrics() -> dict[str, Any]:
    return {
        "total_escrows":               0,
        "total_volume":                0,
        "disputes_triggered":          0,
        "resolutions_executed":        0,
        "pending_settlements_executed": 0,
        "attack_attempts":             0,
        "attack_successes":            0,
        "reverts":                     0,
        "invariant_violations":        0,
    }


def _accum_metrics(
    metrics: dict,
    action: dict,
    entry: dict,
    agent: LiveAgent,
) -> None:
    action_name = action.get("action", "")
    result      = entry.get("result", "")
    accepted    = result == "ok"
    is_attacker = getattr(agent, "_attack_count", None) is not None  # duck-type

    if accepted and action_name == "create_escrow":
        metrics["total_escrows"] += 1
        metrics["total_volume"] += action.get("params", {}).get("amount", 0)
    if accepted and action_name == "raise_dispute":
        metrics["disputes_triggered"] += 1
    if accepted and action_name == "execute_resolution":
        metrics["resolutions_executed"] += 1
    if accepted and action_name == "execute_pending_settlement":
        metrics["pending_settlements_executed"] += 1
    if is_attacker:
        metrics["attack_attempts"] += 1
        if accepted:
            metrics["attack_successes"] += 1
    if not accepted:
        metrics["reverts"] += 1
    if result == "invariant_violated":
        metrics["invariant_violations"] += 1


def _format_violations(violations: dict | None) -> str:
    """Format Clojure invariant violations map into a readable string.

    violations is a map of invariant-name → {:holds? bool :violations [...]}
    Keys with holds?=false are the violated invariants.
    """
    if not violations:
        return "invariant_violation"
    failed = [
        name
        for name, v in violations.items()
        if isinstance(v, dict) and not v.get("holds?", True)
    ]
    if not failed:
        return "invariant_violation"
    return "invariant_violated: " + ", ".join(failed)
