"""
property_tests.py — Property-based invariant tests using Hypothesis.

Each property asserts that invariant_violations == 0 across its entire
parameter space.  Invalid transitions are expected to be rejected with
an error result; they must never produce an invariant violation.

Properties
----------
 P1  create + release solvency          ∀ (amount, fee_bps)
 P2  DR3 dispute → resolve (release)    ∀ (amount, fee_bps)
 P3  DR3 dispute → resolve (refund)     ∀ (amount, fee_bps)
 P4  fee arithmetic boundaries          ∀ (amount, fee_bps ∈ [0, 10000])
 P5  random action sequences            ∀ sequences of up to 8 actions
 P6  concurrent escrow solvency         ∀ N ∈ [2, 4] escrows, mixed outcomes

Usage:
    python -m pytest python/property_tests.py -v
    python -m pytest python/property_tests.py -v --hypothesis-seed=0
    python -m pytest python/property_tests.py -v -x          # stop on first failure
    python -m pytest python/property_tests.py::test_fee_arithmetic -v  # single property

Requires the Clojure gRPC server:
    clojure -M:run -- -S
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from hypothesis import HealthCheck, given, note, settings
from hypothesis import strategies as st

from sim_api.grpc_client import SimulationClient, managed_session


# ---------------------------------------------------------------------------
# Fixed agent roster (registered with every session)
# ---------------------------------------------------------------------------

AGENTS = [
    {"id": "buyer",    "address": "0xbuyer",    "strategy": "honest"},
    {"id": "buyer2",   "address": "0xbuyer2",   "strategy": "honest"},
    {"id": "buyer3",   "address": "0xbuyer3",   "strategy": "honest"},
    {"id": "seller",   "address": "0xseller",   "strategy": "honest"},
    {"id": "resolver", "address": "0xresolver", "role": "resolver"},
    {"id": "attacker", "address": "0xattacker", "strategy": "malicious", "role": "resolver"},
    {"id": "keeper",   "address": "0xkeeper",   "role": "keeper"},
]


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

valid_amount   = st.integers(min_value=1,     max_value=10_000_000)
valid_fee_bps  = st.integers(min_value=0,     max_value=500)
any_fee_bps    = st.integers(min_value=0,     max_value=10_000)
is_release     = st.booleans()
workflow_id    = st.integers(min_value=0,     max_value=7)


@st.composite
def action_sequence(draw, max_steps: int = 8) -> list[dict]:
    """Random sequence of (agent, action, params) that may contain invalid transitions.

    Includes both plausibly valid actions (create → dispute → resolve) and
    clearly invalid ones (resolve-before-dispute, double-dispute, wrong caller).
    Every action must result in invariant_violations == 0 regardless of whether
    Clojure accepts or rejects it.
    """
    return draw(st.lists(
        st.one_of(
            # create_escrow — may or may not include a resolver
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["buyer", "buyer2", "buyer3", "attacker"]),
                "action": st.just("create_escrow"),
                "params": st.fixed_dictionaries({
                    "token":           st.just("USDC"),
                    "to":              st.just("0xseller"),
                    "amount":          valid_amount,
                    "custom_resolver": st.one_of(st.none(), st.just("0xresolver")),
                }),
            }),
            # release — valid caller is buyer; attacker tries wrong caller
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["buyer", "buyer2", "buyer3", "attacker"]),
                "action": st.just("release"),
                "params": st.fixed_dictionaries({"workflow_id": workflow_id}),
            }),
            # raise_dispute
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["buyer", "buyer2", "buyer3", "attacker"]),
                "action": st.just("raise_dispute"),
                "params": st.fixed_dictionaries({"workflow_id": workflow_id}),
            }),
            # execute_resolution — authorized (resolver) and unauthorized (attacker)
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["resolver", "attacker"]),
                "action": st.just("execute_resolution"),
                "params": st.fixed_dictionaries({
                    "workflow_id":      workflow_id,
                    "is_release":       is_release,
                    "resolution_hash":  st.just("0xhash"),
                }),
            }),
            # sender_cancel
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["buyer", "buyer2", "attacker"]),
                "action": st.just("sender_cancel"),
                "params": st.fixed_dictionaries({"workflow_id": workflow_id}),
            }),
            # recipient_cancel — attacker pretends to be seller
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["seller", "attacker"]),
                "action": st.just("recipient_cancel"),
                "params": st.fixed_dictionaries({"workflow_id": workflow_id}),
            }),
            # auto_cancel_disputed — anyone can trigger keeper role
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["keeper", "attacker"]),
                "action": st.just("auto_cancel_disputed"),
                "params": st.fixed_dictionaries({"workflow_id": workflow_id}),
            }),
            # execute_pending_settlement
            st.fixed_dictionaries({
                "agent":  st.sampled_from(["keeper", "attacker"]),
                "action": st.just("execute_pending_settlement"),
                "params": st.fixed_dictionaries({"workflow_id": workflow_id}),
            }),
        ),
        min_size=1,
        max_size=max_steps,
    ))


# ---------------------------------------------------------------------------
# Thin step runner — bypasses LiveRunner round-robin; executes in exact order
# ---------------------------------------------------------------------------


def _clean_params(params: dict) -> dict:
    """Remove None values (Clojure treats absent keys differently from None)."""
    return {k: v for k, v in params.items() if v is not None}


def run_steps(
    client: SimulationClient,
    steps: list[dict],
    fee_bps: int = 50,
    time_step: int = 60,
    initial_block_time: int = 1000,
) -> dict[str, Any]:
    """Execute an ordered sequence of steps against a fresh session.

    Returns:
        violations  — count of invariant_violated results
        halted      — True if Clojure halted the session
        steps_run   — number of steps executed before halt (or total)
        results     — list of "ok" / "error" / "invariant_violated" per step
    """
    sid = str(uuid.uuid4())
    violations = 0
    results: list[str] = []

    with managed_session(client, AGENTS, {"resolver_fee_bps": fee_bps}, initial_block_time, sid) as sid:
        block_time = initial_block_time
        for i, step in enumerate(steps):
            block_time += time_step
            event = {
                "seq":    i,
                "time":   block_time,
                "agent":  step["agent"],
                "action": step["action"],
                "params": _clean_params(step.get("params", {})),
            }
            resp  = client.step(sid, event)
            entry = resp.get("trace_entry") or {}
            r     = entry.get("result", "unknown")
            results.append(r)

            if r == "invariant_violated":
                violations += 1

            if resp.get("halted"):
                return {"violations": violations, "halted": True,
                        "steps_run": i + 1, "results": results}

    return {"violations": violations, "halted": False,
            "steps_run": len(steps), "results": results}


# ---------------------------------------------------------------------------
# Pytest fixture — single SimulationClient for the whole test session
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def sim_client():
    with SimulationClient() as c:
        yield c


# ---------------------------------------------------------------------------
# P1 — create + release solvency  ∀ (amount, fee_bps)
# ---------------------------------------------------------------------------


@settings(max_examples=50, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(amount=valid_amount, fee_bps=valid_fee_bps)
def test_create_release_solvency(sim_client, amount: int, fee_bps: int) -> None:
    """create_escrow → release must never produce invariant violations."""
    steps = [
        {"agent": "buyer",  "action": "create_escrow",
         "params": {"token": "USDC", "to": "0xseller", "amount": amount}},
        {"agent": "buyer",  "action": "release",
         "params": {"workflow_id": 0}},
    ]
    r = run_steps(sim_client, steps, fee_bps=fee_bps)
    note(f"amount={amount} fee_bps={fee_bps} → results={r['results']}")
    assert r["violations"] == 0


# ---------------------------------------------------------------------------
# P2 — DR3 full path (release)  ∀ (amount, fee_bps)
# ---------------------------------------------------------------------------


@settings(max_examples=50, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(amount=valid_amount, fee_bps=valid_fee_bps)
def test_dr3_release_path(sim_client, amount: int, fee_bps: int) -> None:
    """create → dispute → authorized resolve (release) must never violate invariants."""
    steps = [
        {"agent": "buyer",    "action": "create_escrow",
         "params": {"token": "USDC", "to": "0xseller", "amount": amount,
                    "custom_resolver": "0xresolver"}},
        {"agent": "buyer",    "action": "raise_dispute",
         "params": {"workflow_id": 0}},
        {"agent": "resolver", "action": "execute_resolution",
         "params": {"workflow_id": 0, "is_release": True, "resolution_hash": "0xh"}},
    ]
    r = run_steps(sim_client, steps, fee_bps=fee_bps)
    note(f"amount={amount} fee_bps={fee_bps} → results={r['results']}")
    assert r["violations"] == 0


# ---------------------------------------------------------------------------
# P3 — DR3 full path (refund)  ∀ (amount, fee_bps)
# ---------------------------------------------------------------------------


@settings(max_examples=50, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(amount=valid_amount, fee_bps=valid_fee_bps)
def test_dr3_refund_path(sim_client, amount: int, fee_bps: int) -> None:
    """create → dispute → authorized resolve (refund) must never violate invariants."""
    steps = [
        {"agent": "buyer",    "action": "create_escrow",
         "params": {"token": "USDC", "to": "0xseller", "amount": amount,
                    "custom_resolver": "0xresolver"}},
        {"agent": "buyer",    "action": "raise_dispute",
         "params": {"workflow_id": 0}},
        {"agent": "resolver", "action": "execute_resolution",
         "params": {"workflow_id": 0, "is_release": False, "resolution_hash": "0xh"}},
    ]
    r = run_steps(sim_client, steps, fee_bps=fee_bps)
    note(f"amount={amount} fee_bps={fee_bps} → results={r['results']}")
    assert r["violations"] == 0


# ---------------------------------------------------------------------------
# P4 — fee arithmetic across the full bps range  ∀ (amount, fee_bps ∈ [0, 10000])
#
# Clojure may legitimately reject create_escrow when fee > amount (amount-out-of-safe-range
# or fee exceeds principal), but it must not produce invariant violations regardless.
# ---------------------------------------------------------------------------


@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(amount=valid_amount, fee_bps=any_fee_bps)
def test_fee_arithmetic(sim_client, amount: int, fee_bps: int) -> None:
    """No fee/amount combination should produce invariant violations."""
    steps = [
        {"agent": "buyer", "action": "create_escrow",
         "params": {"token": "USDC", "to": "0xseller", "amount": amount}},
    ]
    r = run_steps(sim_client, steps, fee_bps=fee_bps)
    note(f"amount={amount} fee_bps={fee_bps} → results={r['results']}")
    # May be rejected (fee > amount, etc.) but MUST NOT violate invariants
    assert r["violations"] == 0


# ---------------------------------------------------------------------------
# P5 — random action sequences  ∀ sequences up to 8 steps
#
# The key fuzzing property: no sequence of gRPC calls — including illegal
# combinations — should produce invariant_violations > 0.  Invalid actions
# must return "error", never "invariant_violated".
# ---------------------------------------------------------------------------


@settings(max_examples=200, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(steps=action_sequence(max_steps=8), fee_bps=valid_fee_bps)
def test_random_action_sequence(sim_client, steps: list[dict], fee_bps: int) -> None:
    """∀ random action sequences: invariant_violations == 0."""
    r = run_steps(sim_client, steps, fee_bps=fee_bps)
    note(f"fee_bps={fee_bps} steps={[(s['agent'], s['action']) for s in steps]}")
    note(f"results={r['results']}")
    assert r["violations"] == 0


# ---------------------------------------------------------------------------
# P6 — concurrent escrow solvency  N concurrent escrows, mixed outcomes
#
# Creates N escrows from different buyers, then applies mixed finalisation:
# first buyer releases normally, remaining buyers dispute + resolver resolves.
# Solvency invariant must hold throughout.
# ---------------------------------------------------------------------------


@settings(max_examples=30, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    amounts=st.lists(valid_amount, min_size=2, max_size=3),
    fee_bps=valid_fee_bps,
)
def test_concurrent_escrow_solvency(
    sim_client, amounts: list[int], fee_bps: int
) -> None:
    """N concurrent escrows with mixed outcomes must never violate solvency."""
    buyers = ["buyer", "buyer2", "buyer3"][:len(amounts)]
    steps: list[dict] = []

    # Create all escrows (buyer 0 plain, rest with custom_resolver)
    for i, (buyer, amount) in enumerate(zip(buyers, amounts)):
        params: dict = {"token": "USDC", "to": "0xseller", "amount": amount}
        if i > 0:
            params["custom_resolver"] = "0xresolver"
        steps.append({"agent": buyer, "action": "create_escrow", "params": params})

    # buyer 0 releases wf=0
    steps.append({"agent": "buyer", "action": "release", "params": {"workflow_id": 0}})

    # remaining buyers dispute their escrows
    for wf_id in range(1, len(amounts)):
        steps.append({"agent": buyers[wf_id], "action": "raise_dispute",
                      "params": {"workflow_id": wf_id}})

    # resolver resolves all disputes (alternating release/refund)
    for wf_id in range(1, len(amounts)):
        steps.append({"agent": "resolver", "action": "execute_resolution",
                      "params": {"workflow_id": wf_id,
                                 "is_release": (wf_id % 2 == 1),
                                 "resolution_hash": "0xhash"}})

    r = run_steps(sim_client, steps, fee_bps=fee_bps)
    note(f"amounts={amounts} fee_bps={fee_bps} → results={r['results']}")
    assert r["violations"] == 0
