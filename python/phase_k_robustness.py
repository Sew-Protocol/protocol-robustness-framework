"""
Phase K: Detection-Independent Robustness Scenarios.

Verifies the hypothesis that protocol robustness can be maintained with
zero fraud detection probability by relying on Tiered Authority (Bonding)
and Auto-Slashing on Reversal.
"""

from __future__ import annotations

from sim_api.grpc_client import SimulationClient
from sim_api.live_runner import RunResult


def s34_phase_k_auto_slash_robustness() -> tuple[RunResult, bool]:
    """
    S34 — Phase K Auto-Slash Robustness.
    
    Setup:
      - Resolver 0 (r0) is malicious.
      - Resolver 1 (r1) is honest (Senior tier).
      - Fraud detection probability is set to 0.
      - Reversal slashing is active and set to 10000 bps (100%).
      - Initial stakes are exactly matching the escrow amount (1:1 ratio).

    Attack:
      - Malicious r0 misresolves in favor of the seller (collusion).
      - Buyer appeals to Level 1.
      - Honest r1 reverses the decision.
    
    Result:
      - Malicious r0 is slashed automatically upon r1's resolution.
      - Slashed amount (100%) covers the full gain of the fraud.
      - Attack is net-negative or net-zero, making it economically non-viable.
    """
    agents_meta = [
        {"id": "buyer",    "address": "0xbuyer",    "strategy": "honest"},
        {"id": "r0",       "address": "0xres0",     "type": "malicious"},
        {"id": "r1",       "address": "0xres1",     "role": "resolver"},
    ]
    
    # Protocol params: 0% fraud detection, 100% reversal slash
    # This ensures Cost of Attack >= Gain of Attack
    protocol_params = {
        "fraud_detection_probability": 0.0,
        "reversal_slash_bps": 10000,
        "appeal_bond_amount": 100,
        "resolver_bond_bps": 1000,
        "appeal_window_duration": 259200,
        "resolution_module": "0xkleros-proxy",
        "escalation_resolvers": {"0": "0xres0", "1": "0xres1", "2": "0xkleros"},
    }

    initial_stake = 1000

    with SimulationClient() as client:
        sid = "s34-auto-slash"
        client.start_session(sid, agents_meta, protocol_params)

        # 1. Pre-register stakes
        client.step(sid, {"seq": 0, "time": 1000, "agent": "r0",
                          "action": "register_stake", "params": {"amount": initial_stake}})
        client.step(sid, {"seq": 1, "time": 1000, "agent": "r1",
                          "action": "register_stake", "params": {"amount": initial_stake}})

        # 2. Create Escrow (1000 tokens)
        resp = client.step(sid, {"seq": 2, "time": 1000, "agent": "buyer",
                                 "action": "create_escrow",
                                 "params": {"token": "USDC", "to": "0xseller", "amount": 1000}})
        assert resp["result"] == "ok"
        wf_id = resp["trace_entry"]["extra"]["workflow_id"]
        net_escrow = resp["world_view"]["escrow_amounts"][str(wf_id)]

        # 3. Raise Dispute
        client.step(sid, {"seq": 3, "time": 1100, "agent": "buyer",
                          "action": "raise_dispute", "params": {"workflow_id": wf_id}})

        # 4. Malicious r0 resolves in seller's favour
        client.step(sid, {"seq": 4, "time": 1200, "agent": "r0",
                          "action": "execute_resolution",
                          "params": {"workflow_id": wf_id, "is_release": True}})

        # 5. Buyer appeals
        client.step(sid, {"seq": 5, "time": 1300, "agent": "buyer",
                          "action": "escalate_dispute",
                          "params": {"workflow_id": wf_id}})

        # 6. Honest r1 reverses; auto-slash fires against r0 (100%)
        resp = client.step(sid, {"seq": 6, "time": 1400, "agent": "r1",
                                 "action": "execute_resolution",
                                 "params": {"workflow_id": wf_id, "is_release": False}})

        world = resp["world_view"]
        r0_stake = world["resolver_stakes"].get("0xres0", 0)
        
        # Verification 1: r0 was slashed 100% of net escrow (995)
        # 1000 - 995 = 5
        expected_stake = initial_stake - net_escrow
        passed_slash = (r0_stake == expected_stake)
        
        # Verification 2: Economic Robustness
        # Attack Gain = 995 (released to seller/attacker)
        # Attack Loss = 995 (slashed from bond)
        # Net profit = 0.
        passed_robust = (r0_stake + net_escrow <= initial_stake)

        passed = passed_slash and passed_robust

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
    # Test execution if run directly
    res, ok = s34_phase_k_auto_slash_robustness()
    print(f"Outcome: {res.outcome}, Stake: {res.final_world_view['resolver_stakes']['0xres0']}")
    import sys
    sys.exit(0 if ok else 1)
