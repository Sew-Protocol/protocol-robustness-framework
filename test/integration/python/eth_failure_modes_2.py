"""
eth_failure_modes_2.py — Ethereum-specific failure mode scenarios S29–S33.

Each scenario models a distinct on-chain attack or failure:

  S29  f6-resolver-cartel             All escalation levels controlled by same entity
  S30  f7-profit-threshold-strike     Resolver refuses unprofitable small disputes
  S31  f8-appeal-fee-amplification    Attacker repeatedly escalates to force re-resolution
  S32  f9-subthreshold-misresolution  Fraudulent L0 resolution corrected at L1
  S33  f10-cascade-escalation-drain   Capacity-limited arbitrator overwhelmed by disputes

Usage:
    python python/eth_failure_modes_2.py

Requires the Clojure gRPC server:
    clojure -M:run -- -S
"""

from __future__ import annotations

import sys
from typing import Any

from eth_failure_modes import (
    DR3_KLEROS_APPEAL_PARAMS,
    assert_scenario,
    run_scenario,
)
from sim_api.grpc_client import SimulationClient
from sim_api.live_agents import (
    AutomateTimedActionsLive,
    CapacityLimitedArbitrator,
    ColludingResolverLive,
    EscalatingBuyerLive,
    ForkingStrategistLive,
    GriefingBuyerLive,
    HonestBuyerLive,
    HonestResolverLive,
    LiveAgent,
    ProfitThresholdResolver,
)
from sim_api.live_runner import LiveRunner, RunResult

# ---------------------------------------------------------------------------
# Param sets
# ---------------------------------------------------------------------------

# F6: All escalation levels are controlled by colluding resolvers.
# Two-level escalation with appeal window so escalation is actually possible.
CARTEL_PARAMS = {
    "resolver_fee_bps": 150,
    "resolution_module": "0xmulti",
    "escalation_resolvers": {
        "0": "0xcl0",
        "1": "0xcl1",
    },
    "appeal_window_duration": 60,
    "max_dispute_duration": 2592000,
}

# F7: Small escrow where the fee (100 * 1% = 1 token) < min_profit=5.
PROFIT_STRIKE_PARAMS = {
    "resolver_fee_bps": 100,  # 1% fee
    "appeal_window_duration": 0,
    "max_dispute_duration": 2592000,
    "dispute_resolver": "0xresolver",
}

# F8: Re-use the DR3 Kleros appeal params — escalation requires appeal window.
# Buyer escalates twice, each level resolver resolves, driving up total resolutions.
APPEAL_AMPLIFICATION_PARAMS = {
    **DR3_KLEROS_APPEAL_PARAMS,
    "escalation_resolvers": {
        "0": "0xl0",
        "1": "0xl1",
    },
}

# F9: L0 resolves fraudulently (favour_release=True), buyer escalates,
# L1 corrects (favour_release=False → refund).
MISRESOLUTION_PARAMS = {
    "resolver_fee_bps": 150,
    "resolution_module": "0xmulti",
    "escalation_resolvers": {
        "0": "0xcl0",
        "1": "0xcl1",
    },
    "appeal_window_duration": 60,
    "max_dispute_duration": 2592000,
}

# F10: Capacity-limited arbitrator (capacity=2), four disputes raised.
DRAIN_PARAMS = {
    "resolver_fee_bps": 100,
    "appeal_window_duration": 0,
    "max_dispute_duration": 2592000,
    "dispute_resolver": "0xcaplimited",
}


# ---------------------------------------------------------------------------
# S29 — F6: Resolver Cartel
# ---------------------------------------------------------------------------


def s29_f6_resolver_cartel() -> tuple[RunResult, Any]:
    """
    F6: All escalation levels are controlled by the same colluding entity.

    Setup:
      - DR3 with two escalation levels; both resolvers share the cartel address.
      - Buyer escalates after L0 resolution.

    Attack:
      - ColludingResolverLive at 0xcl0 and 0xcl1 both vote to release funds.
      - Even after escalation, the same cartel controls the outcome.

    Expected outcome:
      - No invariant violations (protocol is internally consistent).
      - Final state is 'released' (cartel succeeds; seller wins fraudulently).
      - Both colluding resolvers each execute at least one resolution.
    """
    buyer = EscalatingBuyerLive(
        "buyer", recipient_address="0xseller", amount=5000, max_escalations=1
    )
    cl0 = ColludingResolverLive("cl0", favour_release=True)
    cl1 = ColludingResolverLive("cl1", favour_release=True)
    keeper = AutomateTimedActionsLive("keeper")

    agents_meta = [
        {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
        {"id": "seller", "address": "0xseller", "strategy": "honest"},
        {"id": "cl0", "address": "0xcl0", "role": "resolver"},
        {"id": "cl1", "address": "0xcl1", "role": "resolver"},
        {"id": "keeper", "address": "0xkeeper", "role": "keeper"},
    ]
    live_agents = [buyer, cl0, cl1, keeper]

    result = run_scenario(
        "S29 f6-resolver-cartel",
        agents_meta,
        live_agents,
        protocol_params=CARTEL_PARAMS,
        max_steps=80,
        max_ticks=40,
    )

    final = (result.final_world_view.get("live_states") or {}).get("0", "")
    extra = f"final_state={final} cl0.resolutions={cl0.resolutions} cl1.resolutions={cl1.resolutions}"
    ok = assert_scenario("S29  f6-resolver-cartel", result, extra=extra)

    # Cartel succeeds: both levels resolve, protocol delivers outcome without violations.
    cartel_ok = True
    if cl0.resolutions < 1:
        print(f"⚠️  WARNING: Expected L0 cartel resolver to act; got {cl0.resolutions}")
        cartel_ok = False
    if cl1.resolutions < 1:
        print(f"⚠️  WARNING: Expected L1 cartel resolver to act; got {cl1.resolutions}")
        cartel_ok = False

    # Combine scenario success with cartel behavior check
    final_ok = ok and cartel_ok
    return result, final_ok


# ---------------------------------------------------------------------------
# S30 — F7: Profit-Threshold Strike
# ---------------------------------------------------------------------------


def s30_f7_profit_threshold_strike() -> tuple[RunResult, Any]:
    """
    F7: Rational resolver refuses to act on unprofitable disputes.

    Setup:
      - Single escrow of 100 tokens; fee_bps=100 → fee=1 token.
      - ProfitThresholdResolver min_profit=5 → refuses (fee < min_profit).

    Attack:
      - Buyer raises dispute; resolver silently withholds service.

    Expected outcome:
      - No resolution ever executes (resolutions_executed == 0).
      - Resolver records at least 1 refusal.
      - Dispute remains open until max_dispute_duration forces timeout
        (automate_timed_actions resolves it as a timeout — or it stays open).
    """
    buyer = GriefingBuyerLive("buyer", recipient_address="0xseller", amount=100)
    resolver = ProfitThresholdResolver(
        "resolver",
        fee_bps=100,
        min_profit_abs=5,
    )

    agents_meta = [
        {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
        {"id": "seller", "address": "0xseller", "strategy": "honest"},
        {"id": "resolver", "address": "0xresolver", "role": "resolver"},
    ]
    live_agents = [buyer, resolver]

    result = run_scenario(
        "S30 f7-profit-threshold-strike",
        agents_meta,
        live_agents,
        protocol_params=PROFIT_STRIKE_PARAMS,
        max_steps=20,
        max_ticks=10,
    )

    resolutions = result.metrics.get("resolutions_executed", 0)
    extra = f"resolutions_executed={resolutions} refusals={resolver.refusals}"
    ok = assert_scenario("S30  f7-profit-threshold-strike", result, extra=extra)

    # Resolver refuses; dispute stays open — no resolution during the run window.
    profit_ok = True
    if resolver.refusals < 1:
        print(f"⚠️  WARNING: Expected ≥1 refusal; got {resolver.refusals}")
        profit_ok = False
    if resolutions != 0:
        print(f"⚠️  WARNING: Expected 0 resolutions_executed; got {resolutions}")
        profit_ok = False

    final_ok = ok and profit_ok
    return result, final_ok


# ---------------------------------------------------------------------------
# S31 — F8: Appeal Fee Amplification
# ---------------------------------------------------------------------------


def s31_f8_appeal_fee_amplification() -> tuple[RunResult, Any]:
    """
    F8: Attacker (buyer) repeatedly escalates to force the protocol to pay
    multiple resolution fees across multiple escalation levels.

    Setup:
      - DR3 two-level escalation; honest resolvers at each level.
      - Buyer escalates after each level resolves.

    Attack:
      - Buyer escalates to L1 after L0 resolves; total fee bill = L0 + L1.

    Expected outcome:
      - At least 2 resolutions executed (one per escalation level).
      - No invariant violations.
    """
    buyer = EscalatingBuyerLive(
        "buyer", recipient_address="0xseller", amount=5000, max_escalations=1
    )
    l0 = HonestResolverLive("l0")
    l1 = HonestResolverLive("l1")
    keeper = AutomateTimedActionsLive("keeper")

    agents_meta = [
        {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
        {"id": "seller", "address": "0xseller", "strategy": "honest"},
        {"id": "l0", "address": "0xl0", "role": "resolver"},
        {"id": "l1", "address": "0xl1", "role": "resolver"},
        {"id": "keeper", "address": "0xkeeper", "role": "keeper"},
    ]
    live_agents = [buyer, l0, l1, keeper]

    result = run_scenario(
        "S31 f8-appeal-fee-amplification",
        agents_meta,
        live_agents,
        protocol_params=APPEAL_AMPLIFICATION_PARAMS,
        max_steps=80,
        max_ticks=40,
    )

    resolutions = result.metrics.get("resolutions_executed", 0)
    extra = f"resolutions_executed={resolutions}"
    ok = assert_scenario("S31  f8-appeal-fee-amplification", result, extra=extra)

    # At least 2 resolutions: L0 then L1 after buyer escalates.
    amplification_ok = True
    if resolutions < 2:
        print(f"⚠️  WARNING: Expected ≥2 resolutions; got {resolutions}")
        amplification_ok = False

    final_ok = ok and amplification_ok
    return result, final_ok


# ---------------------------------------------------------------------------
# S32 — F9: Sub-Threshold Misresolution
# ---------------------------------------------------------------------------


def s32_f9_subthreshold_misresolution() -> tuple[RunResult, Any]:
    """
    F9: L0 resolver issues a fraudulent resolution (favour_release=True when
    outcome should be a refund).  Buyer escalates; L1 corrects to refund.

    Setup:
      - Two-level DR3; L0 is colluding (favour_release=True), L1 is honest
        (favour_release=False → refund).
      - Buyer escalates after seeing the wrong L0 outcome.

    Attack:
      - L0 misresolves in seller's favour; buyer pays escalation cost.
      - L1 corrects outcome; seller's fraudulent resolution is overridden.

    Expected outcome:
      - cl0.resolutions >= 1 (fraudulent L0 resolution fired).
      - cl1.resolutions >= 1 (L1 correction fired).
      - Final state is 'refunded'.
    """
    buyer = EscalatingBuyerLive(
        "buyer", recipient_address="0xseller", amount=5000, max_escalations=1
    )
    cl0 = ColludingResolverLive(
        "cl0", favour_release=True
    )  # fraudulent: releases to seller
    cl1 = ColludingResolverLive(
        "cl1", favour_release=False
    )  # corrects: refunds to buyer
    keeper = AutomateTimedActionsLive("keeper")

    agents_meta = [
        {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
        {"id": "seller", "address": "0xseller", "strategy": "honest"},
        {"id": "cl0", "address": "0xcl0", "role": "resolver"},
        {"id": "cl1", "address": "0xcl1", "role": "resolver"},
        {"id": "keeper", "address": "0xkeeper", "role": "keeper"},
    ]
    live_agents = [buyer, cl0, cl1, keeper]

    result = run_scenario(
        "S32 f9-subthreshold-misresolution",
        agents_meta,
        live_agents,
        protocol_params=MISRESOLUTION_PARAMS,
        max_steps=80,
        max_ticks=40,
    )

    final = (result.final_world_view.get("live_states") or {}).get("0", "")
    extra = (
        f"final_state={final}"
        f" cl0.resolutions={cl0.resolutions}"
        f" cl1.resolutions={cl1.resolutions}"
    )
    ok = assert_scenario("S32  f9-subthreshold-misresolution", result, extra=extra)

    misresolution_ok = True
    if cl0.resolutions < 1:
        print(f"⚠️  WARNING: Expected L0 fraudulent resolution; got {cl0.resolutions}")
        misresolution_ok = False
    if cl1.resolutions < 1:
        print(f"⚠️  WARNING: Expected L1 correction resolution; got {cl1.resolutions}")
        misresolution_ok = False

    final_ok = ok and misresolution_ok
    return result, final_ok


# ---------------------------------------------------------------------------
# S33 — F10: Cascade Escalation Drain
# ---------------------------------------------------------------------------


def s33_f10_cascade_escalation_drain() -> tuple[RunResult, Any]:
    """
    F10: More disputes are created than the capacity-limited arbitrator can handle.

    Setup:
      - 4 buyers each raise a dispute against 1 seller (seller address is shared
        so escrow IDs are 0,1,2,3).
      - CapacityLimitedArbitrator can resolve at most 2 disputes.

    Attack:
      - All 4 buyers raise disputes simultaneously.
      - Arbitrator resolves first 2 then stops.
      - Remaining 2 disputes are permanently unresolved.

    Expected outcome:
      - disputes_triggered == 4.
      - arbitrator.resolutions == 2 (capacity exhausted).
      - At least 2 escrows remain in 'disputed' state.
    """
    buyers = [
        GriefingBuyerLive(f"buyer{i}", recipient_address="0xseller", amount=500)
        for i in range(1, 5)
    ]
    arbitrator = CapacityLimitedArbitrator("arbitrator", capacity=2)

    agents_meta = (
        [
            {"id": f"buyer{i}", "address": f"0xbuyer{i}", "strategy": "honest"}
            for i in range(1, 5)
        ]
        + [{"id": "seller", "address": "0xseller", "strategy": "honest"}]
        + [{"id": "arbitrator", "address": "0xcaplimited", "role": "resolver"}]
    )
    live_agents = [*buyers, arbitrator]

    result = run_scenario(
        "S33 f10-cascade-escalation-drain",
        agents_meta,
        live_agents,
        protocol_params=DRAIN_PARAMS,
        max_steps=60,
        max_ticks=30,
    )

    disputes = result.metrics.get("disputes_triggered", 0)
    live = result.final_world_view.get("live_states") or {}
    still_disputed = sum(1 for s in live.values() if s == "disputed")

    extra = (
        f"disputes={disputes}"
        f" arbitrator.resolutions={arbitrator.resolutions}"
        f" still_disputed={still_disputed}"
    )
    ok = assert_scenario("S33  f10-cascade-escalation-drain", result, extra=extra)

    cascade_ok = True
    if disputes != 4:
        print(f"⚠️  WARNING: Expected 4 disputes; got {disputes}")
        cascade_ok = False
    if arbitrator.resolutions != 2:
        print(
            f"⚠️  WARNING: Expected arbitrator capacity=2 used; got {arbitrator.resolutions}"
        )
        cascade_ok = False
    if still_disputed < 2:
        print(f"⚠️  WARNING: Expected ≥2 escrows still disputed; got {still_disputed}")
        cascade_ok = False

    final_ok = ok and cascade_ok
    return result, final_ok


def s34_f11_reorg_race_condition() -> tuple[RunResult, bool]:
    """
    S34 — F11 — Forking strategist (Re-org Race).

    Attacker attempts to exploit the non-finality window.
    """
    attacker = ForkingStrategistLive("attacker")
    seller = HonestBuyerLive("seller", recipient_address="0xattacker", amount=1000)

    agents_meta = [
        {"id": "attacker", "address": "0xattacker", "strategy": "honest"},
        {"id": "seller", "address": "0xseller", "strategy": "honest"},
    ]
    live_agents = [attacker, seller]

    # Seller will try to release. Attacker will try to cancel.
    result = run_scenario(
        "S34 f11-reorg-race-condition",
        agents_meta,
        live_agents,
        max_steps=10,
        max_ticks=5,
    )

    ok = assert_scenario("S34  f11-reorg-race-condition", result)
    return result, ok


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

SCENARIOS_2 = [
    ("S29  f6-resolver-cartel", s29_f6_resolver_cartel),
    ("S30  f7-profit-threshold-strike", s30_f7_profit_threshold_strike),
    ("S31  f8-appeal-fee-amplification", s31_f8_appeal_fee_amplification),
    ("S32  f9-subthreshold-misresolution", s32_f9_subthreshold_misresolution),
    ("S33  f10-cascade-escalation-drain", s33_f10_cascade_escalation_drain),
    ("S34  f11-reorg-race-condition", s34_f11_reorg_race_condition),
]


def main() -> None:
    print(f"\n{'═' * 72}")
    print("  SEW Ethereum Failure Modes — F6–F10")
    print(f"{'═' * 72}")
    results = []
    for name, fn in SCENARIOS_2:
        try:
            _, ok = fn()
            results.append((name, ok))
        except Exception as exc:
            print(f"  ✗ FAIL  {name}: {exc}")
            results.append((name, False))

    passed = sum(1 for _, ok in results if ok)
    total = len(results)
    print(f"\n{'═' * 72}")
    print(f"  {passed}/{total} scenarios passed")
    if passed < total:
        failed = [n for n, ok in results if not ok]
        print(f"  FAILED: {', '.join(failed)}")
    print(f"{'═' * 72}\n")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
