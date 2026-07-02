"""
demo_live.py — Live gRPC simulation demo (three scenarios).

Usage:
    python python/demo_live.py

Requires the Clojure gRPC server running on localhost:50051:
    clojure -M:run -- -S
"""

import json
import textwrap
from sim_api.grpc_client import SimulationClient
from sim_api.live_agents import (
    HonestBuyerLive,
    HonestResolverLive,
    GriefingBuyerLive,
    AttackingBuyerLive,
    TimedAutomatorLive,
)
from sim_api.live_runner import LiveRunner


def run_scenario(name: str, agents_meta: list, live_agents: list, **runner_kwargs):
    print(f"\n{'═' * 56}")
    print(f"  {name}")
    print(f"{'═' * 56}")
    with SimulationClient() as client:
        runner = LiveRunner(client, agents_meta, live_agents, **runner_kwargs)
        result = runner.run(max_steps=30, max_ticks=20)

    print(f"  outcome        : {result.outcome}")
    print(f"  steps executed : {result.steps_executed}")
    if result.halted_at_seq is not None:
        print(f"  halted at seq  : {result.halted_at_seq}  ({result.halt_reason})")
    print("  metrics:")
    for k, v in result.metrics.items():
        if v:
            print(f"    {k:<36} {v}")

    if result.final_world_view:
        wv = result.final_world_view
        print(f"  final world:")
        print(f"    escrows        : {len(wv.get('escrow_transfers', {}))}")
        print(f"    live states    : {wv.get('live_states', {})}")
        print(f"    block time     : {wv.get('block_time')}")
    return result


# ---------------------------------------------------------------------------
# Scenario 1: honest buyer + honest resolver (happy path)
# ---------------------------------------------------------------------------
run_scenario(
    "Scenario 1 — Happy path: buyer creates + releases",
    agents_meta=[
        {"id": "buyer",  "address": "0xbuyer",  "strategy": "honest"},
        {"id": "seller", "address": "0xseller", "strategy": "honest"},
    ],
    live_agents=[
        HonestBuyerLive("buyer", recipient_address="0xseller", amount=5000),
    ],
)

# ---------------------------------------------------------------------------
# Scenario 2: griefing buyer raises dispute; resolver intervenes
# ---------------------------------------------------------------------------
run_scenario(
    "Scenario 2 — Griefing: buyer disputes; resolver resolves",
    agents_meta=[
        {"id": "griefer",  "address": "0xgriefer",  "strategy": "malicious", "role": "resolver"},
        {"id": "seller",   "address": "0xseller",   "strategy": "honest"},
        {"id": "resolver", "address": "0xresolver", "role": "resolver"},
    ],
    live_agents=[
        GriefingBuyerLive("griefer", recipient_address="0xseller", amount=2000),
        HonestResolverLive("resolver"),
    ],
)

# ---------------------------------------------------------------------------
# Scenario 3: attacker attempts double-release; all rejected
# ---------------------------------------------------------------------------
run_scenario(
    "Scenario 3 — Attack: double-release replay (all rejected)",
    agents_meta=[
        {"id": "attacker", "address": "0xattacker", "strategy": "malicious", "role": "resolver"},
        {"id": "seller",   "address": "0xseller",   "strategy": "honest"},
    ],
    live_agents=[
        AttackingBuyerLive("attacker", recipient_address="0xseller", amount=100),
    ],
)

print(f"\n{'═' * 56}")
print("  Done.")
print(f"{'═' * 56}\n")
