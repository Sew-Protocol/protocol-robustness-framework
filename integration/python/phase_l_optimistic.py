"""
Phase L: Optimistic Resolution Scenarios.

Verifies the 'Socialized Security' hypothesis: the protocol remains robust
even if victims are offline, by incentivizing third-party Watchtowers
to challenge fraudulent resolutions via challenge bonds and bounties.
"""

from __future__ import annotations

from sim_api.grpc_client import SimulationClient, managed_session
from sim_api.live_agents import (
    ColludingResolverLive,
    HonestBuyerLive,
    HonestResolverLive,
)
from sim_api.live_runner import LiveRunner, RunResult


def s35_phase_l_watchtower_robustness() -> tuple[RunResult, bool]:
    """
    S35 — Phase L Watchtower Robustness.

    Setup:
      - Resolver 0 (r0) is malicious.
      - Resolver 1 (r1) is honest.
      - Watchtower (wt) is a third-party observer.
      - Buyer (victim) is OFFLINE (never appeals).
      - challenge_window_duration is active (3 days).
      - challenge_bounty_bps is set to 2000 (20% of slashed amount).

    Attack:
      - Malicious r0 misresolves in favor of the seller.
      - Buyer does nothing.
      - Watchtower (wt) challenges the resolution.

    Result:
      - Dispute escalates to r1.
      - Honest r1 reverses the decision.
      - r0 is slashed 100%.
      - Watchtower (wt) receives a bounty from the slashed stake.
    """
    agents_meta = [
        {"id": "buyer", "address": "0xbuyer", "strategy": "honest"},
        {"id": "wt", "address": "0xwatch", "strategy": "honest"},
        {"id": "r0", "address": "0xres0", "type": "malicious"},
        {"id": "r1", "address": "0xres1", "role": "resolver"},
    ]

    # Protocol params:
    # - 100% reversal slash
    # - 3 day challenge window
    # - 20% bounty to challenger
    protocol_params = {
        "reversal_slash_bps": 10000,
        "challenge_window_duration": 259200,
        "challenge_bounty_bps": 2000,
        "challenge_bond_bps": 500,  # 5% bond to challenge
        "resolver_bond_bps": 10000,
        "resolution_level_map": {0: "0xres0", 1: "0xres1", 2: "0xkleros"},
    }

    initial_stake = 1000

    with SimulationClient() as client:
        sid = "s35-optimistic"
        client.start_session(sid, agents_meta, protocol_params)

        # 1. Pre-register stakes
        client.step(
            sid,
            {
                "seq": 0,
                "time": 1000,
                "agent": "r0",
                "action": "register_stake",
                "params": {"amount": initial_stake},
            },
        )
        client.step(
            sid,
            {
                "seq": 1,
                "time": 1000,
                "agent": "r1",
                "action": "register_stake",
                "params": {"amount": initial_stake},
            },
        )

        # 2. Create Escrow (1000 tokens)
        resp = client.step(
            sid,
            {
                "seq": 2,
                "time": 1000,
                "agent": "buyer",
                "action": "create_escrow",
                "params": {"token": "USDC", "to": "0xseller", "amount": 1000},
            },
        )
        if resp is None or resp["result"] != "ok":
            print(
                f"  DEBUG: create_escrow failed with {resp.get('error') if resp else 'None'}"
            )
            print("⚠️  WARNING: Expected escrow creation to succeed")
            escrow_ok = False
            # Use default values when escrow creation fails
            wf_id = "default_wf"
            net_escrow = 1000  # default escrow amount
        else:
            escrow_ok = True
            wf_id = resp["trace_entry"]["extra"]["workflow_id"]
            net_escrow = resp["world_view"]["escrow_amounts"][str(wf_id)]

        # 3. Raise Dispute
        client.step(
            sid,
            {
                "seq": 3,
                "time": 1100,
                "agent": "buyer",
                "action": "raise_dispute",
                "params": {"workflow_id": wf_id},
            },
        )

        # 4. Malicious r0 resolves in seller's favour
        client.step(
            sid,
            {
                "seq": 4,
                "time": 1200,
                "agent": "r0",
                "action": "execute_resolution",
                "params": {"workflow_id": wf_id, "is_release": True},
            },
        )

        # 5. Watchtower (wt) Challenges (Buyer is silent)
        resp_challenge = client.step(
            sid,
            {
                "seq": 5,
                "time": 1300,
                "agent": "wt",
                "action": "challenge_resolution",
                "params": {"workflow_id": wf_id},
            },
        )
        if resp_challenge["result"] != "ok":
            print(f"  DEBUG: challenge failed with {resp_challenge.get('error')}")
            print("⚠️  WARNING: Expected challenge to succeed")
            challenge_ok = False
        else:
            challenge_ok = True

        # 6. Honest r1 reverses; auto-slash fires against r0 (100%)
        resp = client.step(
            sid,
            {
                "seq": 6,
                "time": 1400,
                "agent": "r1",
                "action": "execute_resolution",
                "params": {"workflow_id": wf_id, "is_release": False},
            },
        )

        world = resp["world_view"]
        r0_stake = world["resolver_stakes"].get("0xres0", 0)
        wt_claimable = world["claimable"].get("0xwatch", 0)

        # Verification 1: r0 was slashed 100% of net escrow (995)
        passed_slash = r0_stake == initial_stake - net_escrow

        # Verification 2: Watchtower received bounty
        # Bounty = 20% of slashed 995 = floor(199)
        expected_bounty = (net_escrow * 2000) // 10000
        passed_bounty = wt_claimable == expected_bounty

        passed = passed_slash and passed_bounty and challenge_ok and escrow_ok

        print(f"  DEBUG: r0_stake_actual={r0_stake}")
        print(f"  DEBUG: watchtower_bounty={wt_claimable}, expected={expected_bounty}")
        if not challenge_ok:
            print("⚠️  WARNING: Challenge verification failed")

        result = RunResult(
            session_id=sid,
            outcome="pass" if passed else "failed",
            steps_executed=7,
            halted_at_seq=None,
            halt_reason=None,
            trace=[],
            metrics=resp.get("metrics", {}),
            final_world_view=world,
        )

        client.destroy_session(sid)
        return result, passed


if __name__ == "__main__":
    res, ok = s35_phase_l_watchtower_robustness()
    import sys

    sys.exit(0 if ok else 1)
